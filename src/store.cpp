#include "core.hpp"
#include "sqlite3.h"
#include <algorithm>
#include <ctime>
#include <set>
#include <mbedtls/sha256.h>

namespace nas {
void generateThumbnail(const fs::path& input, const fs::path& output);
namespace {
bool videoToolAvailable(const Config& config){std::error_code ec;return !config.ffmpeg.empty()&&fs::is_regular_file(config.ffmpeg,ec);}
void sqlCheck(int code, sqlite3* db) { if (code != SQLITE_OK && code != SQLITE_DONE && code != SQLITE_ROW) throw std::runtime_error(std::string("SQLite: ") + sqlite3_errmsg(db)); }
struct Statement {
    sqlite3_stmt* value = nullptr;
    ~Statement() { if (value) sqlite3_finalize(value); }
};
bool validHash(const std::string& hash) { return hash.size() == 64 && hash.find_first_not_of("0123456789abcdef") == std::string::npos; }
std::string normalizedHash(const json& j) {
    auto result = text(j, "sha256", 64);
    std::transform(result.begin(), result.end(), result.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    if (!validHash(result)) throw Error(400, "INVALID_HASH", "SHA256 必须为64位十六进制摘要");
    return result;
}
std::string extension(const std::string& name) {
    auto ext = fs::u8path(name).extension().u8string();
    if (ext.size() > 12 || ext.empty() || ext.substr(1).find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") != std::string::npos) return ".bin";
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); }); return ext;
}
std::string mime(const std::string& name) {
    static const std::map<std::string, std::string> known = {{".jpg","image/jpeg"},{".jpeg","image/jpeg"},{".png","image/png"},{".gif","image/gif"},{".webp","image/webp"},
        {".mp4","video/mp4"},{".webm","video/webm"},{".mov","video/quicktime"},{".m4v","video/mp4"},{".mkv","video/x-matroska"},{".avi","video/x-msvideo"},{".3gp","video/3gpp"},{".mpeg","video/mpeg"},{".mpg","video/mpeg"},{".ts","video/mp2t"},{".mts","video/mp2t"},{".m2ts","video/mp2t"},{".mp3","audio/mpeg"},{".wav","audio/wav"},{".ogg","audio/ogg"}};
    auto it = known.find(extension(name)); return it == known.end() ? "application/octet-stream" : it->second;
}
std::string category(const std::string& name) {
    auto ext = extension(name); auto m = mime(name);
    if (m.rfind("image/",0)==0 || ext==".heic" || ext==".heif" || ext==".raw" || ext==".dng") return "image";
    if (m.rfind("video/",0)==0 || ext==".mkv" || ext==".avi") return "video";
    return "file";
}
json cleanTask(json task) { task.erase("source_key"); return task; }
const std::string TaskListFields="SELECT u.id,u.user_id,u.device_id,u.name,u.size,u.offset,u.state,u.asset_id,u.error,u.created,u.updated,u.folder_id,COALESCE(f.id,'default') AS effective_folder_id,COALESCE(f.name,'默认文件夹') AS folder_name FROM uploads u LEFT JOIN folders f ON f.id=u.folder_id AND f.deleted=0 ";
void taskFolderFlags(json& rows){for(auto& row:rows)row["folder_defaulted"]=row["folder_id"]!=row["effective_folder_id"];}
void requireAdmin(const Identity& who) { if(!who.admin)throw Error(403,"ADMIN_REQUIRED","此操作需要管理员权限"); }
void requireUpload(const Identity& who) { if(!who.canUpload())throw Error(403,"READ_ONLY","当前账号为只读，不能上传或更改备份任务"); }
std::string userName(const json& input) {
    auto value=text(input,"username",32);
    if(value.size()<3||value.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-")!=std::string::npos)
        throw Error(400,"INVALID_USERNAME","用户名需为3至32位英文字母、数字、下划线、点或短横线");
    std::transform(value.begin(),value.end(),value.begin(),[](unsigned char c){return static_cast<char>(std::tolower(c));});return value;
}
std::string userRole(const json& input) {
    auto value=text(input,"role",16);
    if(value!="admin"&&value!="member"&&value!="viewer")throw Error(400,"INVALID_ROLE","角色只能是管理员、普通用户或只读用户");return value;
}
std::string newPassword(const json& input,const char* field) {auto value=text(input,field,256);if(passwordCharacters(value)<6)throw Error(400,"WEAK_PASSWORD","密码至少6个字符");return value;}
const std::string UserFields="SELECT u.id,u.username,u.display_name,u.role,u.enabled,u.created,d.last_seen FROM users u JOIN devices d ON d.id=u.id ";
}
Database::Database(const fs::path& file) {
    fs::create_directories(file.parent_path());
    sqlCheck(sqlite3_open_v2(file.u8string().c_str(), &db_, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nullptr), db_);
    sqlite3_busy_timeout(db_, 5000);
    exec("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;");
    auto version = query("PRAGMA user_version");
    if (!version.empty() && std::stoll(version[0]["user_version"].get<std::string>()) > 2) throw std::runtime_error("Database was created by a newer version");
}
Database::~Database() { if (db_) { sqlite3_wal_checkpoint_v2(db_, nullptr, SQLITE_CHECKPOINT_TRUNCATE, nullptr, nullptr); sqlite3_close(db_); } }
void Database::exec(const std::string& sql, const Params& values) {
    std::lock_guard<std::recursive_mutex> guard(mutex_);
    if (values.empty()) { sqlCheck(sqlite3_exec(db_, sql.c_str(), nullptr, nullptr, nullptr), db_); return; }
    Statement statement; sqlCheck(sqlite3_prepare_v2(db_, sql.c_str(), -1, &statement.value, nullptr), db_);
    for (size_t i=0;i<values.size();++i) sqlCheck(sqlite3_bind_text(statement.value, static_cast<int>(i+1), values[i].c_str(), static_cast<int>(values[i].size()), SQLITE_TRANSIENT),db_);
    sqlCheck(sqlite3_step(statement.value), db_);
}
json Database::query(const std::string& sql, const Params& values) {
    std::lock_guard<std::recursive_mutex> guard(mutex_); Statement statement;
    sqlCheck(sqlite3_prepare_v2(db_, sql.c_str(), -1, &statement.value, nullptr), db_);
    for (size_t i=0;i<values.size();++i) sqlCheck(sqlite3_bind_text(statement.value, static_cast<int>(i+1), values[i].c_str(), static_cast<int>(values[i].size()), SQLITE_TRANSIENT),db_);
    json result = json::array(); int code;
    while ((code=sqlite3_step(statement.value))==SQLITE_ROW) {
        json row=json::object(); for(int i=0;i<sqlite3_column_count(statement.value);++i) { const auto* v=sqlite3_column_text(statement.value,i); row[sqlite3_column_name(statement.value,i)]=v?reinterpret_cast<const char*>(v):""; } result.push_back(std::move(row));
    }
    sqlCheck(code,db_); return result;
}
void Database::transaction(const std::function<void()>& action) {
    std::lock_guard<std::recursive_mutex> guard(mutex_); exec("BEGIN IMMEDIATE");
    try { action(); exec("COMMIT"); } catch (...) { try { exec("ROLLBACK"); } catch (...) {} throw; }
}

Store::Store(Config value,bool startBackground) : config(std::move(value)), db(config.data/"catalog.db") {
    fs::create_directories(config.data/"storage/uploads"); fs::create_directories(config.data/"storage/originals"); fs::create_directories(config.data/"cache");
    db.exec(R"SQL(
      CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,admin INTEGER NOT NULL DEFAULT 0,revoked INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,last_seen INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS credentials(hash TEXT PRIMARY KEY,device_id TEXT NOT NULL REFERENCES devices(id),expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS assets(id TEXT PRIMARY KEY,sha256 TEXT NOT NULL,size INTEGER NOT NULL,path TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,category TEXT NOT NULL,created INTEGER NOT NULL,modified INTEGER NOT NULL,state TEXT NOT NULL,preview TEXT NOT NULL DEFAULT 'PENDING');
      CREATE UNIQUE INDEX IF NOT EXISTS ready_digest ON assets(sha256,size) WHERE state='READY';
      CREATE INDEX IF NOT EXISTS asset_timeline ON assets(created DESC,id DESC);
      CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY,client_task_id TEXT NOT NULL,device_id TEXT NOT NULL REFERENCES devices(id),name TEXT NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL,offset INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL,asset_id TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT '',source_key TEXT NOT NULL,album TEXT NOT NULL DEFAULT '',modified INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,updated INTEGER NOT NULL,UNIQUE(device_id,client_task_id));
      CREATE TABLE IF NOT EXISTS sources(device_id TEXT NOT NULL,source_key TEXT NOT NULL,asset_id TEXT NOT NULL REFERENCES assets(id),name TEXT NOT NULL,album TEXT NOT NULL,modified INTEGER NOT NULL,PRIMARY KEY(device_id,source_key,asset_id));
    )SQL");
    if (setting("server_id").empty()) {
        auto password = config.bootstrapAdminPassword.empty()?randomHex(8):config.bootstrapAdminPassword, salt=randomHex(16); auto now=std::to_string(millis());
        writeText(config.data/"initial-admin-password.txt",password+"\r\n");
        db.transaction([&] { setting("server_id",randomHex()); setting("admin_salt",salt); setting("admin_hash",passwordHash(password,salt)); db.exec("INSERT INTO devices(id,name,admin,created,last_seen) VALUES('administrator','电脑管理员',1,?,?)",{now,now}); });
    }
    db.exec("CREATE TABLE IF NOT EXISTS asset_trash(asset_id TEXT PRIMARY KEY REFERENCES assets(id),deleted_at INTEGER NOT NULL,deleted_by TEXT NOT NULL); CREATE INDEX IF NOT EXISTS trash_time ON asset_trash(deleted_at DESC,asset_id DESC);");
    migrateAccounts();
    initializeFolders();
    db.exec("CREATE INDEX IF NOT EXISTS upload_ready_asset_owner ON uploads(user_id,asset_id) WHERE state='READY'");
    db.exec(R"SQL(
      CREATE TABLE IF NOT EXISTS albums(id TEXT PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',owner_id TEXT NOT NULL REFERENCES users(id),created INTEGER NOT NULL,updated INTEGER NOT NULL,cover_id TEXT NOT NULL DEFAULT '');
      CREATE TABLE IF NOT EXISTS album_items(album_id TEXT NOT NULL REFERENCES albums(id) ON DELETE CASCADE,asset_id TEXT NOT NULL REFERENCES assets(id),PRIMARY KEY(album_id,asset_id));
    )SQL");
    db.exec("CREATE INDEX IF NOT EXISTS asset_pending_video ON assets(created,id) WHERE state='READY' AND category='video' AND preview='PENDING';");
    db.exec("CREATE INDEX IF NOT EXISTS asset_ready_timeline ON assets(created DESC,id DESC) WHERE state='READY'; CREATE INDEX IF NOT EXISTS asset_category_timeline ON assets(state,category,created DESC,id DESC); CREATE INDEX IF NOT EXISTS upload_user_timeline ON uploads(user_id,created DESC,id DESC); CREATE INDEX IF NOT EXISTS upload_timeline ON uploads(created DESC,id DESC);");db.exec("CREATE INDEX IF NOT EXISTS asset_file_time ON assets((CASE WHEN modified>0 THEN modified ELSE created END) DESC,id DESC) WHERE state='READY'; CREATE INDEX IF NOT EXISTS asset_category_file_time ON assets(category,(CASE WHEN modified>0 THEN modified ELSE created END) DESC,id DESC) WHERE state='READY';");initializeTimeline();serverId=setting("server_id");if(startBackground){recover(); worker_=std::thread([this]{worker();});videoWorker_=std::thread([this]{videoWorker();});metadataWorker_=std::thread([this]{metadataWorker();});}
}
Store::~Store() { stopping=true; queueCondition_.notify_all();videoCondition_.notify_all(); if(worker_.joinable()) worker_.join();if(videoWorker_.joinable())videoWorker_.join();if(metadataWorker_.joinable())metadataWorker_.join(); }
std::string Store::setting(const std::string& key) { auto rows=db.query("SELECT value FROM settings WHERE key=?",{key}); return rows.empty()?"":rows[0]["value"].get<std::string>(); }
void Store::setting(const std::string& key,const std::string& value) { db.exec("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",{key,value}); }
void Store::migrateAccounts() {
    auto version=number(db.query("PRAGMA user_version")[0],"user_version");if(version>=2)return;
    db.transaction([&]{
        db.exec("CREATE TABLE users(id TEXT PRIMARY KEY REFERENCES devices(id),username TEXT NOT NULL COLLATE NOCASE UNIQUE,display_name TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('admin','member','viewer')),enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),salt TEXT NOT NULL,password_hash TEXT NOT NULL,created INTEGER NOT NULL)");
        db.exec("INSERT INTO users(id,username,display_name,role,enabled,salt,password_hash,created) VALUES('administrator','admin','管理员','admin',1,?,?,?)",{setting("admin_salt"),setting("admin_hash"),std::to_string(millis())});
        // Preserve old task IDs, offsets and source-device provenance. Old tasks belong to admin.
        db.exec("ALTER TABLE uploads ADD COLUMN user_id TEXT NOT NULL DEFAULT 'administrator'");
        db.exec("CREATE INDEX uploads_user_task ON uploads(user_id,client_task_id)");
        db.exec("DELETE FROM credentials; UPDATE devices SET revoked=1 WHERE id!='administrator'; DELETE FROM settings WHERE key IN ('pair_hash','pair_expires','admin_salt','admin_hash'); PRAGMA user_version=2;");
    });
}
std::string Store::credential(const std::string& id,uint64_t expires) {
    auto token=randomHex(32);db.exec("DELETE FROM credentials WHERE expires>0 AND expires<?",{std::to_string(millis())});
    db.exec("INSERT INTO credentials(hash,device_id,expires) VALUES(?,?,?)",{sha256(token.data(),token.size()),id,std::to_string(expires)});return token;
}
json Store::login(const json& input) {
    auto username=text(input,"username",64),password=text(input,"password",256);
    auto rows=db.query("SELECT * FROM users WHERE username=? COLLATE NOCASE",{username});
    auto salt=rows.empty()?serverId:rows[0]["salt"].get<std::string>();auto hash=passwordHash(password,salt);
    if(rows.empty()||rows[0]["enabled"]!="1"||!secureEqual(hash,rows[0]["password_hash"]))throw Error(401,"LOGIN_FAILED","用户名或密码不正确，或账号已被禁用");
    auto row=rows[0];bool isAdmin=row["role"]=="admin";auto expires=millis()+(isAdmin?86400000ULL:604800000ULL);
    return {{"token",credential(row["id"],expires)},{"user_id",row["id"]},{"username",row["username"]},{"display_name",row["display_name"]},{"role",row["role"]},{"admin",isAdmin},{"can_upload",row["role"]!="viewer"},{"expires",std::to_string(expires)}};
}
Identity Store::authenticate(const std::string& token) {
    if(token.size()!=64)throw Error(401,"AUTH_REQUIRED","请使用用户名和密码登录");
    auto rows=db.query("SELECT u.id,u.username,u.display_name,u.role FROM credentials c JOIN users u ON u.id=c.device_id WHERE c.hash=? AND u.enabled=1 AND c.expires>?",{sha256(token.data(),token.size()),std::to_string(millis())});
    if(rows.empty())throw Error(401,"AUTH_REQUIRED","登录已失效，或账号权限已调整，请重新登录");
    auto row=rows[0];auto id=row["id"].get<std::string>();db.exec("UPDATE devices SET last_seen=? WHERE id=? AND last_seen<?",{std::to_string(millis()),id,std::to_string(millis()-60000)});
    return {id,row["username"],row["display_name"],row["role"],row["role"]=="admin"};
}
json Store::users(const Identity& caller) {requireAdmin(caller);return db.query(UserFields+"ORDER BY u.created,u.username");}
json Store::createUser(const json& input,const Identity& caller) {
    requireAdmin(caller);auto username=userName(input),name=text(input,"display_name",96),role=userRole(input),password=newPassword(input,"password");
    auto salt=randomHex(16),hash=passwordHash(password,salt),id=randomHex(),now=std::to_string(millis());
    db.transaction([&]{
        if(!db.query("SELECT id FROM users WHERE username=? COLLATE NOCASE",{username}).empty())throw Error(409,"USERNAME_EXISTS","用户名已存在");
        db.exec("INSERT INTO devices(id,name,created,last_seen) VALUES(?,?,?,?)",{id,name,now,"0"});
        db.exec("INSERT INTO users(id,username,display_name,role,enabled,salt,password_hash,created) VALUES(?,?,?,?,1,?,?,?)",{id,username,name,role,salt,hash,now});
    });return db.query(UserFields+"WHERE u.id=?",{id})[0];
}
json Store::updateUser(const std::string& id,const json& input,const Identity& caller) {
    requireAdmin(caller);for(auto it=input.begin();it!=input.end();++it)if(it.key()!="display_name"&&it.key()!="role"&&it.key()!="enabled"&&it.key()!="new_password")throw Error(400,"INVALID_USER_FIELD","不支持的账号修改字段");
    std::string salt,hash;
    if(input.contains("new_password")){auto password=newPassword(input,"new_password");salt=randomHex(16);hash=passwordHash(password,salt);}
    bool invalidate=false;
    db.transaction([&]{
        auto rows=db.query("SELECT * FROM users WHERE id=?",{id});if(rows.empty())throw Error(404,"USER_NOT_FOUND","账号不存在");auto row=rows[0];
        auto name=input.contains("display_name")?text(input,"display_name",96):row["display_name"].get<std::string>();
        auto role=input.contains("role")?userRole(input):row["role"].get<std::string>();
        bool enabled=row["enabled"]=="1";
        if(input.contains("enabled")){if(!input["enabled"].is_boolean())throw Error(400,"INVALID_ENABLED","启用状态必须为布尔值");enabled=input["enabled"].get<bool>();}
        if(row["role"]=="admin"&&row["enabled"]=="1"&&(!enabled||role!="admin")&&number(db.query("SELECT COUNT(*) AS n FROM users WHERE enabled=1 AND role='admin'")[0],"n")<=1)
            throw Error(409,"LAST_ADMIN","至少保留一个启用的管理员账号");
        invalidate=row["role"]!=role || (row["enabled"]=="1")!=enabled || !hash.empty();
        db.exec("UPDATE users SET display_name=?,role=?,enabled=? WHERE id=?",{name,role,enabled?"1":"0",id});
        db.exec("UPDATE devices SET name=?,revoked=? WHERE id=?",{name,enabled?"0":"1",id});
        if(!hash.empty())db.exec("UPDATE users SET salt=?,password_hash=? WHERE id=?",{salt,hash,id});
        if(invalidate)db.exec("DELETE FROM credentials WHERE device_id=?",{id});
    });
    if(id=="administrator"&&!hash.empty()){std::error_code e;fs::remove(config.data/"initial-admin-password.txt",e);}
    auto result=db.query(UserFields+"WHERE u.id=?",{id})[0];result["sessions_revoked"]=invalidate;return result;
}
void Store::changePassword(const json& input,const Identity& caller) {
    auto old=text(input,"old_password",256),next=newPassword(input,"new_password");auto rows=db.query("SELECT salt,password_hash FROM users WHERE id=?",{caller.userId});
    if(rows.empty()||!secureEqual(passwordHash(old,rows[0]["salt"]),rows[0]["password_hash"]))throw Error(403,"LOGIN_FAILED","当前密码不正确");
    auto salt=randomHex(16),hash=passwordHash(next,salt),previous=rows[0]["password_hash"].get<std::string>();
    db.transaction([&]{
        auto current=db.query("SELECT password_hash FROM users WHERE id=? AND enabled=1",{caller.userId});if(current.empty()||current[0]["password_hash"]!=previous)throw Error(409,"ACCOUNT_CHANGED","账号信息已改变，请重新登录");
        db.exec("UPDATE users SET salt=?,password_hash=? WHERE id=?",{salt,hash,caller.userId});db.exec("DELETE FROM credentials WHERE device_id=?",{caller.userId});
    });
    if(caller.userId=="administrator"){std::error_code e;fs::remove(config.data/"initial-admin-password.txt",e);}
}
std::shared_ptr<std::mutex> Store::sessionMutex(const std::string& id) {
    std::lock_guard<std::mutex> lock(sessionsMutex_);
    if(sessions_.size()>512) for(auto it=sessions_.begin();it!=sessions_.end();) { if(it->second.expired())it=sessions_.erase(it);else++it; }
    auto result=sessions_[id].lock();if(!result){result=std::make_shared<std::mutex>();sessions_[id]=result;}return result;
}
fs::path Store::part(const std::string& id) const { return config.data/"storage/uploads"/(id+".part"); }
fs::path Store::originalPath(const json& row) const { return config.data/fs::u8path(row["path"].get<std::string>()); }
json Store::taskRow(const std::string& id) {
    if(id.size()!=48 || id.find_first_not_of("0123456789abcdef")!=std::string::npos)throw Error(404,"UPLOAD_NOT_FOUND","上传任务不存在");
    auto rows=db.query("SELECT * FROM uploads WHERE id=?",{id});if(rows.empty())throw Error(404,"UPLOAD_NOT_FOUND","上传任务不存在");auto row=rows[0];auto folders=db.query("SELECT id,name,deleted FROM folders WHERE id=?",{row["folder_id"]});bool fallback=folders.empty()||folders[0]["deleted"]=="1";row["effective_folder_id"]=fallback?json("default"):row["folder_id"];row["folder_name"]=fallback?json("默认文件夹"):folders[0]["name"];row["folder_defaulted"]=fallback;return row;
}
json Store::upload(const std::string& id,const Identity& who) {
    auto task=taskRow(id);if(!who.admin&&task["user_id"]!=who.userId)throw Error(403,"USER_MISMATCH","不能访问其他账号的上传任务");return cleanTask(task);
}
json Store::createUpload(const json& input,const Identity& who) {
    requireUpload(who);
    auto hash=normalizedHash(input),name=text(input,"name",512),client=text(input,"client_task_id",128),source=text(input,"source_key",1024);
    auto album=text(input,"album",256,true);auto size=number(input,"size"),modified=input.contains("modified")?number(input,"modified"):millis();
    if(size>config.maxFile || modified>static_cast<uint64_t>(INT64_MAX))throw Error(413,"FILE_TOO_LARGE","文件超过当前配置的大小上限");
    auto requested=input.contains("folder_id")?text(input,"folder_id",48):std::string("default");
    json result;
    db.transaction([&]{
        resolveFolder(requested,who);
        auto existing=db.query("SELECT * FROM uploads WHERE user_id=? AND client_task_id=? ORDER BY offset DESC",{who.userId,client});
        if(!existing.empty()) {
            auto row=existing[0];if(row["sha256"]!=hash || number(row,"size")!=size || row["folder_id"]!=requested)throw Error(409,"TASK_ID_CONFLICT","同一个任务ID不能用于不同文件");
            if(row["state"]=="READY"){
                auto originals=db.query("SELECT * FROM assets WHERE id=? AND state='READY'",{row["asset_id"]});std::error_code ec;
                if(originals.empty() || !fs::is_regular_file(originalPath(originals[0]),ec) || fs::file_size(originalPath(originals[0]),ec)!=size){
                    db.exec("UPDATE assets SET state='MISSING' WHERE id=? AND state='READY'",{row["asset_id"]});
                    db.exec("UPDATE uploads SET state='ERROR',error='ORIGINAL_MISSING' WHERE id=?",{row["id"]});
                    row=taskRow(row["id"]);
                }
            }
            result=cleanTask(taskRow(row["id"]));return;
        }
        auto assets=db.query("SELECT * FROM assets WHERE sha256=? AND size=? AND state='READY'",{hash,std::to_string(size)});
        std::string assetId;
        if(!assets.empty()) {
            auto path=originalPath(assets[0]);std::error_code error;
            if(fs::is_regular_file(path,error)&&fs::file_size(path,error)==size) assetId=assets[0]["id"].get<std::string>();
            else db.exec("UPDATE assets SET state='MISSING' WHERE id=? AND state='READY'",{assets[0]["id"].get<std::string>()});
        }
        if(assetId.empty()) {
            if(!db.query("SELECT t.asset_id FROM asset_trash t JOIN assets a ON a.id=t.asset_id WHERE a.sha256=? AND a.size=?",{hash,std::to_string(size)}).empty())throw Error(409,"ASSET_TRASHED","该文件在回收站且原件需要检查，请在电脑处理后再备份");
            auto reserved=db.query("SELECT COALESCE(SUM(size-offset),0) AS bytes FROM uploads WHERE state IN ('UPLOADING','VERIFYING','COMMITTING')");auto remaining=number(reserved[0],"bytes");
            auto available=fs::space(config.data).available;
            if(available<config.minFree || remaining>available-config.minFree || size>available-config.minFree-remaining)throw Error(507,"INSUFFICIENT_SPACE","电脑存储空间不足，已考虑未完成任务的容量预留");
        }
        auto id=randomHex(),now=std::to_string(millis());
        if(assetId.empty()){File f(part(id),true,true);f.truncate(0);f.flush();}
        db.exec("INSERT INTO uploads(id,client_task_id,device_id,user_id,name,sha256,size,offset,state,asset_id,source_key,album,modified,created,updated,folder_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            {id,client,who.userId,who.userId,name,hash,std::to_string(size),assetId.empty()?"0":std::to_string(size),assetId.empty()?"UPLOADING":"READY",assetId,source,album,std::to_string(modified),now,now,requested});
        if(!assetId.empty())db.exec("INSERT OR IGNORE INTO sources(device_id,source_key,asset_id,name,album,modified) VALUES(?,?,?,?,?,?)",{who.userId,source,assetId,name,album,std::to_string(modified)});
        if(!assetId.empty())attachFolder(requested,assetId);
        result=cleanTask(taskRow(id));
    });return result;
}
json Store::writeChunk(const std::string& id,const Identity& who,uint64_t offset,uint64_t size,const std::string& digest,
    const std::function<bool(const std::function<bool(const char*,size_t)>&)>& reader) {
    requireUpload(who);
    auto mutex=sessionMutex(id);std::unique_lock<std::mutex> lock(*mutex,std::try_to_lock);
    if(!lock.owns_lock())throw Error(409,"UPLOAD_BUSY","该文件正在处理，请查询进度后重试");
    auto task=upload(id,who);auto confirmed=number(task,"offset"),total=number(task,"size");
    if(task["state"]!="UPLOADING")throw Error(409,"UPLOAD_STATE","该任务当前不能接收数据");
    if(offset!=confirmed)throw Error(409,"OFFSET_MISMATCH","续传偏移不一致，请重新查询服务器进度");
    if(!size || size>config.chunkBytes || size>total-confirmed || !validHash(digest))throw Error(400,"INVALID_CHUNK","数据块长度或摘要无效");
    File file(part(id),true);if(file.size()!=confirmed)throw Error(409,"PART_SIZE_MISMATCH","临时文件状态异常，请重启服务恢复核对");
    mbedtls_sha256_context context;mbedtls_sha256_init(&context);mbedtls_sha256_starts(&context,0);uint64_t received=0;
    try {
        bool ok=reader([&](const char* data,size_t length){
            if(stopping||length>size-received)return false;
            file.write(confirmed+received,data,length);if(mbedtls_sha256_update(&context,reinterpret_cast<const unsigned char*>(data),length)!=0)throw std::runtime_error("Block hashing failed");received+=length;return true;
        });
        if(!ok || received!=size)throw Error(400,"INCOMPLETE_CHUNK","本块未完整收到，确认进度未前移");
        unsigned char bytes[32];if(mbedtls_sha256_finish(&context,bytes)!=0)throw std::runtime_error("Block hashing failed");
        static const char* digits="0123456789abcdef";std::string actual;for(auto b:bytes){actual+=digits[b>>4];actual+=digits[b&15];}
        if(!secureEqual(actual,digest))throw Error(422,"CHUNK_HASH_MISMATCH","数据块校验失败，请重传本块");
        file.flush();db.exec("UPDATE uploads SET offset=?,updated=?,error='' WHERE id=?",{std::to_string(confirmed+received),std::to_string(millis()),id});
        mbedtls_sha256_free(&context);return upload(id,who);
    }catch(...){
        mbedtls_sha256_free(&context);
        try{file.truncate(confirmed);file.flush();}catch(...){db.exec("UPDATE uploads SET state='ERROR',error='PART_ROLLBACK_FAILED' WHERE id=?",{id});}
        throw;
    }
}
void Store::enqueue(const std::string& id) { {std::lock_guard<std::mutex> lock(queueMutex_);queue_.push(id);}queueCondition_.notify_one(); }
json Store::complete(const std::string& id,const Identity& who) {
    requireUpload(who);
    auto mutex=sessionMutex(id);std::lock_guard<std::mutex> lock(*mutex);auto task=upload(id,who);
    if(task["state"]=="READY" || task["state"]=="VERIFYING" || task["state"]=="COMMITTING")return task;
    if(task["state"]!="UPLOADING" || number(task,"offset")!=number(task,"size"))throw Error(409,"UPLOAD_INCOMPLETE","文件尚未完整上传");
    db.exec("UPDATE uploads SET state='VERIFYING',error='',updated=? WHERE id=?",{std::to_string(millis()),id});enqueue(id);return upload(id,who);
}
json Store::cancel(const std::string& id,const Identity& who) {
    requireUpload(who);
    auto mutex=sessionMutex(id);std::unique_lock<std::mutex> lock(*mutex,std::try_to_lock);
    if(!lock.owns_lock())throw Error(409,"UPLOAD_BUSY","文件正在处理，请先暂停上传后重试");
    auto task=upload(id,who);
    if(task["state"]!="UPLOADING"&&task["state"]!="CANCELLED")throw Error(409,"UPLOAD_STATE","仅能取消尚未入库的上传任务");
    db.exec("UPDATE uploads SET state='CANCELLED',updated=? WHERE id=?",{std::to_string(millis()),id});
    std::error_code ec;fs::remove(part(id),ec);
    return upload(id,who);
}
void Store::finishCommit(const json& task,const json& assetRow) {
    db.transaction([&]{
        auto id=task["id"].get<std::string>(),assetId=assetRow["id"].get<std::string>();
        if(db.query("SELECT id FROM assets WHERE id=? AND state<>'PURGING'",{assetId}).empty())throw Error(409,"ASSET_PURGING","文件正在彻底删除，未将备份标记为成功");
        db.exec("UPDATE assets SET state='READY' WHERE id=?",{assetId});
        db.exec("UPDATE uploads SET state='READY',asset_id=?,offset=size,error='',updated=? WHERE id=?",{assetId,std::to_string(millis()),id});
        db.exec("INSERT OR IGNORE INTO sources(device_id,source_key,asset_id,name,album,modified) VALUES(?,?,?,?,?,?)",{task["device_id"],task["source_key"],assetId,task["name"],task["album"],task["modified"]});
        attachFolder(task["folder_id"],assetId);
    });
}
void Store::process(const std::string& id) {
    auto mutex=sessionMutex(id);std::lock_guard<std::mutex> guard(*mutex);auto task=taskRow(id);auto size=number(task,"size");json row;
    if(task["state"]=="COMMITTING") {
        auto rows=db.query("SELECT * FROM assets WHERE id=?",{task["asset_id"]});if(rows.empty())throw Error(500,"COMMIT_RECORD_MISSING","入库记录缺失");row=rows[0];
        auto target=originalPath(row),source=fs::exists(target)?target:part(id);
        if(!fs::exists(source)||fs::file_size(source)!=size||hashFile(source,&stopping)!=task["sha256"])throw Error(422,"RECOVERY_HASH_MISMATCH","恢复核对失败，文件已保留等待检查");
        if(source!=target){fs::create_directories(target.parent_path());fs::rename(source,target);}finishCommit(task,row);
    } else {
        if(task["state"]!="VERIFYING")return;
        if(fs::file_size(part(id))!=size || hashFile(part(id),&stopping)!=task["sha256"])throw Error(422,"FILE_HASH_MISMATCH","完整文件摘要不一致，未标记为备份成功");
        auto duplicates=db.query("SELECT * FROM assets WHERE sha256=? AND size=? AND state='READY'",{task["sha256"],task["size"]});
        if(!duplicates.empty() && fs::exists(originalPath(duplicates[0])) && fs::file_size(originalPath(duplicates[0]))==size) {
            row=duplicates[0];finishCommit(task,row);std::error_code error;fs::remove(part(id),error);return;
        }
        if(!duplicates.empty())db.exec("UPDATE assets SET state='MISSING' WHERE id=? AND state='READY'",{duplicates[0]["id"]});
        auto assetId=randomHex();std::time_t t=static_cast<std::time_t>(number(task,"created")/1000);std::tm tm{};gmtime_s(&tm,&t);char folder[16];std::strftime(folder,sizeof(folder),"%Y/%m",&tm);
        auto relative=fs::path("storage/originals")/folder/(assetId+extension(task["name"]));
        db.transaction([&]{
            db.exec("INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state) VALUES(?,?,?,?,?,?,?,?,?,'COMMITTING')",{assetId,task["sha256"],task["size"],relative.generic_u8string(),task["name"],mime(task["name"]),category(task["name"]),task["created"],task["modified"]});
            db.exec("UPDATE uploads SET state='COMMITTING',asset_id=? WHERE id=?",{assetId,id});
        });
        row=db.query("SELECT * FROM assets WHERE id=?",{assetId})[0];auto target=originalPath(row);fs::create_directories(target.parent_path());fs::rename(part(id),target);finishCommit(task,row);
    }
    makePreview(row);
}
void Store::makePreview(const json& row) {
    auto mutex=sessionMutex("asset:"+row["id"].get<std::string>());std::lock_guard<std::mutex> guard(*mutex);
    if(db.query("SELECT id FROM assets WHERE id=? AND state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=assets.id)",{row["id"]}).empty())return;
    if(row["category"]=="image") {
        updateCaptureTime(row);
        try{generateThumbnail(originalPath(row),config.data/"cache"/(row["id"].get<std::string>()+".jpg"));db.exec("UPDATE assets SET preview='READY' WHERE id=?",{row["id"]});}
        catch(...){db.exec("UPDATE assets SET preview='UNAVAILABLE' WHERE id=?",{row["id"]});}
    } else if(row["category"]!="video"||!videoToolAvailable(config))db.exec("UPDATE assets SET preview='UNAVAILABLE' WHERE id=?",{row["id"]});
}
void Store::videoWorker() {
    while(!stopping){
        try {
            auto rows=db.query("SELECT * FROM assets WHERE state='READY' AND category='video' AND preview='PENDING' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=assets.id) ORDER BY created,id LIMIT 1");
            if(!rows.empty()){
                auto mutex=sessionMutex("asset:"+rows[0]["id"].get<std::string>());std::lock_guard<std::mutex> guard(*mutex);
                if(db.query("SELECT id FROM assets WHERE id=? AND state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=assets.id)",{rows[0]["id"]}).empty())continue;
                auto row=rows[0];try{generateVideoThumbnail(config.ffmpeg,originalPath(row),config.data/"cache"/(row["id"].get<std::string>()+".jpg"),stopping);db.exec("UPDATE assets SET preview='READY' WHERE id=?",{row["id"]});}
                catch(...){if(stopping)return;db.exec("UPDATE assets SET preview='UNAVAILABLE' WHERE id=?",{row["id"]});}continue;
            }
        }catch(...){if(stopping)return;}
        std::unique_lock<std::mutex> lock(queueMutex_);videoCondition_.wait_for(lock,std::chrono::milliseconds(500));
    }
}
void Store::worker() {
    for(;;){
        std::string id;{std::unique_lock<std::mutex> lock(queueMutex_);queueCondition_.wait(lock,[&]{return stopping||!queue_.empty();});if(stopping)return;id=queue_.front();queue_.pop();}
        try{if(id.rfind("preview:",0)==0)makePreview(asset(id.substr(8)));else process(id);}catch(const Error& e){if(stopping)return;try{db.exec("UPDATE uploads SET state='ERROR',error=?,updated=? WHERE id=?",{e.code,std::to_string(millis()),id});}catch(...){stopping=true;return;}}
        catch(const std::exception&){if(stopping)return;try{db.exec("UPDATE uploads SET state='ERROR',error='PROCESS_FAILED',updated=? WHERE id=?",{std::to_string(millis()),id});}catch(...) {stopping=true;return;}}
    }
}
void Store::recover() {
    for(auto& row:db.query("SELECT id FROM assets WHERE state='PURGING'")){try{finishPurge(row["id"]);}catch(...){/* Retain the durable intent for an administrator retry. */}}
    for(auto& row:db.query("SELECT id,name FROM assets WHERE state='READY' AND category='video' AND mime='application/octet-stream'")){auto type=mime(row["name"]);if(type!="application/octet-stream")db.exec("UPDATE assets SET mime=? WHERE id=?",{type,row["id"]});}
    if(videoToolAvailable(config))db.exec("UPDATE assets SET preview='PENDING' WHERE state='READY' AND category='video' AND preview='UNAVAILABLE'");
    for(auto& task:db.query("SELECT id FROM uploads WHERE state='CANCELLED'")){std::error_code ec;fs::remove(part(task["id"]),ec);}
    for(auto& task:db.query("SELECT * FROM uploads WHERE state IN ('UPLOADING','VERIFYING','COMMITTING')")) {
        auto id=task["id"].get<std::string>();
        if(task["state"]=="COMMITTING"){enqueue(id);continue;}
        try{
            File f(part(id),true);auto offset=number(task,"offset");if(f.size()<offset)throw std::runtime_error("Lost acknowledged bytes");if(f.size()>offset){f.truncate(offset);f.flush();}
            if(task["state"]=="VERIFYING")enqueue(id);
        }catch(...){db.exec("UPDATE uploads SET state='ERROR',error='RECOVERY_PART_MISSING_OR_SHORT' WHERE id=?",{id});}
    }
    for(auto& row:db.query("SELECT id,path,size,preview FROM assets WHERE state='READY'")){
        std::error_code e;auto path=originalPath(row);if(!fs::is_regular_file(path,e)||fs::file_size(path,e)!=number(row,"size"))db.exec("UPDATE assets SET state='MISSING' WHERE id=? AND state='READY'",{row["id"]});
        else if(row["preview"]=="PENDING")enqueue("preview:"+row["id"].get<std::string>());
    }
}
json Store::asset(const std::string& id) {
    auto rows=db.query("SELECT * FROM assets WHERE id=? AND state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=assets.id)",{id});if(rows.empty())throw Error(404,"ASSET_NOT_FOUND","文件不存在或原件需要恢复检查");return rows[0];
}
json Store::assets(const std::string& before,const std::string& type,const std::string& search,int limit,const std::string& order,const std::string& from,const std::string& to) {
    if(limit<1||limit>100)throw Error(400,"INVALID_LIMIT","每页数量需1至100");
    if(order!="created"&&order!="timeline")throw Error(400,"INVALID_ORDER","排序方式无效");
    const bool timeline=order=="timeline",filtered=type=="image"||type=="video"||type=="file";
    const std::string clock=timeline?TimelineClock:"created";
    Params params;std::string sql="SELECT id,sha256,size,name,mime,category,created,modified,preview,taken,taken_text,taken_zone,"+std::string(TimelineClock)+" AS timeline_time FROM assets INDEXED BY "+std::string(timeline?(filtered?"asset_category_capture_time":"asset_capture_time"):(filtered?"asset_category_timeline":"asset_ready_timeline"))+" WHERE state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=assets.id)";
    if(!before.empty()){
        auto colon=before.find(':');if(colon==std::string::npos)throw Error(400,"INVALID_CURSOR","分页游标无效");auto time=before.substr(0,colon),id=before.substr(colon+1);
        if(time.empty()||time.size()>19||time.find_first_not_of("0123456789")!=std::string::npos||id.size()!=48||id.find_first_not_of("0123456789abcdef")!=std::string::npos)throw Error(400,"INVALID_CURSOR","分页游标无效");
        if(number(json{{"cursor_time",time}},"cursor_time")>static_cast<uint64_t>(INT64_MAX))throw Error(400,"INVALID_CURSOR","分页时间超出范围");
        sql+=" AND ("+clock+",id)<(CAST(? AS INTEGER),?)";params.insert(params.end(),{time,id});
    }
    if(type=="image"||type=="video"||type=="file"){sql+=" AND category=?";params.push_back(type);}
    if(!from.empty()||!to.empty()){
        auto valid=[](const std::string& value){return !value.empty()&&value.size()<=19&&value.find_first_not_of("0123456789")==std::string::npos&&number(json{{"v",value}},"v")<=static_cast<uint64_t>(INT64_MAX);};
        if(!valid(from)||!valid(to)||number(json{{"v",from}},"v")>=number(json{{"v",to}},"v"))throw Error(400,"INVALID_TIME_RANGE","日期范围无效");
        sql+=" AND "+clock+">=CAST(? AS INTEGER) AND "+clock+"<CAST(? AS INTEGER)";params.insert(params.end(),{from,to});
    }
    if(!search.empty()){sql+=" AND name LIKE ?";params.push_back("%"+search+"%");}
    sql+=" ORDER BY "+clock+" DESC,id DESC LIMIT "+std::to_string(limit+1);auto rows=db.query(sql,params);std::string next;
    if(rows.size()>static_cast<size_t>(limit)){rows.erase(rows.end()-1);}
    else limit=0;
    if(limit>0){auto last=rows.back();next=last[timeline?"timeline_time":"created"].get<std::string>()+":"+last["id"].get<std::string>();}
    for(auto& row:rows)row["time_source"]=number(row,"taken")?"exif":number(row,"modified")?"file":"import";json result={{"items",rows},{"next",next},{"order",order},{"timeline_version",2}};if(!from.empty()){result["from"]=from;result["to"]=to;}return result;
}
json Store::sourceStatus(const json& input,const Identity& who) {
    if(!input.contains("task_ids")||!input["task_ids"].is_array()||input["task_ids"].empty()||input["task_ids"].size()>100)throw Error(400,"INVALID_BATCH","每次核对1至100项");
    Params params{who.userId};std::string slots;std::set<std::string> unique;
    for(const auto& value:input["task_ids"]){if(!value.is_string())throw Error(400,"INVALID_ID","任务标识无效");auto id=value.get<std::string>();if(id.size()!=48||id.find_first_not_of("0123456789abcdef")!=std::string::npos||!unique.insert(id).second)throw Error(400,"INVALID_ID","任务标识无效或重复");if(!slots.empty())slots+=",";slots+="?";params.push_back(id);}
    auto rows=db.query("SELECT u.id,u.state,u.size,u.modified,u.sha256,u.asset_id,a.path,a.state AS asset_state,a.size AS original_size,(SELECT count(*) FROM asset_trash t WHERE t.asset_id=a.id) AS trashed FROM uploads u LEFT JOIN assets a ON a.id=u.asset_id WHERE u.user_id=? AND u.id IN ("+slots+")",params);
    json items=json::array();for(auto row:rows){std::string state=row["state"];
        if(state=="READY"){std::error_code error;bool present=row["asset_state"]=="READY"&&!row["path"].is_null();if(present){auto file=config.data/fs::u8path(row["path"].get<std::string>());present=fs::is_regular_file(file,error)&&!error&&fs::file_size(file,error)==number(row,"size")&&!error;}if(!present)state="MISSING";}
        if(row["trashed"]=="1")state="TRASHED";
        items.push_back({{"id",row["id"]},{"state",state},{"size",row["size"]},{"modified",row["modified"]},{"sha256",row["sha256"]},{"asset_id",row["asset_id"]}});
    }if(input.contains("folder_id"))folderMembership(items,resolveFolder(text(input,"folder_id",48),who));return {{"items",items}};
}
json Store::tasks(const Identity& who) {
    auto rows=db.query(TaskListFields+(who.admin?"":"WHERE u.user_id=? ")+"ORDER BY u.updated DESC,u.id DESC LIMIT 100",who.admin?Params{}:Params{who.userId});taskFolderFlags(rows);return rows;
}
json Store::taskPage(const Identity& who,const std::string& before,const std::string& filter){
    Params values;std::string sql=TaskListFields+"WHERE 1=1";
    if(!who.admin){sql+=" AND u.user_id=?";values.push_back(who.userId);}
    if(filter=="active")sql+=" AND u.state IN ('UPLOADING','VERIFYING','COMMITTING')";
    else if(filter=="failed")sql+=" AND u.state IN ('ERROR','CANCELLED')";
    else if(filter=="ready")sql+=" AND u.state='READY'";
    else if(!filter.empty()&&filter!="all")throw Error(400,"INVALID_FILTER","任务筛选无效");
    if(!before.empty()){
        auto colon=before.find(':');auto time=before.substr(0,colon),id=colon==std::string::npos?std::string{}:before.substr(colon+1);
        if(time.empty()||time.size()>19||time.find_first_not_of("0123456789")!=std::string::npos||id.size()!=48||id.find_first_not_of("0123456789abcdef")!=std::string::npos)throw Error(400,"INVALID_CURSOR","分页游标无效");
        sql+=" AND (u.created,u.id)<(?,?)";values.insert(values.end(),{time,id});
    }
    auto rows=db.query(sql+" ORDER BY u.created DESC,u.id DESC LIMIT 51",values);taskFolderFlags(rows);bool more=rows.size()>50;if(more)rows.erase(rows.end()-1);
    std::string next;if(more)next=rows.back()["created"].get<std::string>()+":"+rows.back()["id"].get<std::string>();return {{"items",rows},{"next",next}};
}
json Store::overview(const Identity& caller){
    auto counts=db.query("SELECT COUNT(*) AS files,COALESCE(SUM(size),0) AS bytes,COALESCE(SUM(category='image'),0) AS images,COALESCE(SUM(category='video'),0) AS videos FROM assets WHERE state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=assets.id)")[0];
    auto active=db.query("SELECT COUNT(*) AS active,COALESCE(SUM(size-offset),0) AS reserved FROM uploads WHERE state IN ('UPLOADING','VERIFYING','COMMITTING')")[0];
    auto trash=db.query("SELECT COUNT(*) AS files,COALESCE(SUM(a.size),0) AS bytes FROM asset_trash t JOIN assets a ON a.id=t.asset_id")[0];
    auto space=fs::space(config.data);
    json result={{"library",counts},{"trash",trash},{"transfers",active},{"capacity",std::to_string(space.capacity)},{"available",std::to_string(space.available)},{"chunk_bytes",std::to_string(config.chunkBytes)},
        {"data_dir",config.data.u8string()},{"addresses",ipv4Addresses()},{"http_port",config.httpPort},{"https_port",config.httpsPort},{"certificate_sha256",certificateFingerprint(config.data/"server-cert.pem")}};
    if(!caller.admin)result.erase("data_dir");return result;
}
}
