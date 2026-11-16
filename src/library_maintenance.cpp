#include "core.hpp"
#include <windows.h>
#include <mbedtls/sha256.h>
#include <algorithm>

namespace nas {
namespace {
void administrator(const Identity& who){if(!who.admin)throw Error(403,"ADMIN_REQUIRED","资料库维护仅限管理员");}
Identity systemIdentity(){return {"system","system","自动维护","admin",true};}
bool safeOriginal(const fs::path& path,const fs::path& root){
    auto p=fs::absolute(path).lexically_normal(),r=fs::absolute(root).lexically_normal();
    auto a=p.begin();for(auto b=r.begin();b!=r.end();++b,++a)if(a==p.end()||_wcsicmp(a->c_str(),b->c_str()))return false;
    if(a==p.end())return false;
    for(auto part=p;!part.empty();part=part.parent_path()){
        auto flags=GetFileAttributesW(part.c_str());if(flags!=INVALID_FILE_ATTRIBUTES&&(flags&FILE_ATTRIBUTE_REPARSE_POINT))return false;
        if(part==part.root_path())break;
    }return true;
}
}
void Store::initializeMaintenance(){
    db.exec("CREATE TABLE IF NOT EXISTS operation_history(seq INTEGER PRIMARY KEY AUTOINCREMENT,created INTEGER NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,object_id TEXT NOT NULL,detail TEXT NOT NULL); CREATE TABLE IF NOT EXISTS health_items(asset_id TEXT PRIMARY KEY,path TEXT NOT NULL,size TEXT NOT NULL,sha256 TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'PENDING',issue TEXT NOT NULL DEFAULT '',checked_at TEXT NOT NULL DEFAULT '0'); CREATE INDEX IF NOT EXISTS health_pending ON health_items(state,asset_id);");
    auto h=setting("health_run"),b=setting("backup_schedule");
    healthState_=h.empty()?json{{"state","IDLE"}}:json::parse(h);
    if(healthState_.value("state","")=="RUNNING"){healthState_["state"]="INTERRUPTED";setting("health_run",healthState_.dump());}
    backupSchedule_=b.empty()?json{{"enabled",false},{"interval_hours",24},{"keep",7},{"last_success","0"},{"next_run","0"},{"last_error",""}}:json::parse(b);
}
void Store::audit(const Identity& who,const std::string& action,const std::string& object,const std::string& detail){
    db.exec("INSERT INTO operation_history(created,actor,action,object_id,detail) VALUES(?,?,?,?,?)",{std::to_string(millis()),who.userId,action,object,detail});
    db.exec("DELETE FROM operation_history WHERE seq <= (SELECT COALESCE(max(seq),0)-10000 FROM operation_history)");
}
json Store::relocationImpact(const std::string& id,const json& input,const Identity& who){
    auto parent=text(input,"parent_id",48,true);json result;
    db.transaction([&]{
        auto source=folder(id,who);if(id=="default"||!source["can_manage"].get<bool>())throw Error(403,"FOLDER_MANAGE_REQUIRED","需要当前目录管理权限");
        if(!parent.empty()&&!folder(parent,who)["can_manage"].get<bool>())throw Error(403,"FOLDER_MANAGE_REQUIRED","需要目标目录管理权限");
        if(!db.query("SELECT 1 FROM folder_tree WHERE folder_id=? AND ancestor_id=?",{parent,id}).empty())throw Error(400,"FOLDER_CYCLE","不能移动到下级目录");
        if(!db.query("SELECT id FROM folders WHERE deleted=0 AND parent_id=? AND name=? COLLATE NOCASE AND id<>? AND (?<>'' OR owner_id=?)",{parent,source["name"],id,parent,source["owner_id"]}).empty())throw Error(409,"FOLDER_NAME_EXISTS","目标存在同名目录");
        auto people=db.query("SELECT id,username,display_name,role FROM users WHERE enabled=1 ORDER BY id LIMIT 201");bool truncated=people.size()>200;if(truncated)people.erase(people.end()-1);
        auto counts=[&](const json& person){Identity recipient{person["id"],person["username"],person["display_name"],person["role"],person["role"]=="admin"};auto level=folderAccessSql("f.id",recipient);return db.query("SELECT COALESCE(sum(("+level+")>=1),0) AS readable,COALESCE(sum(("+level+")>=2),0) AS writable,COALESCE(sum(("+level+")>=3),0) AS manageable FROM folders f JOIN folder_tree t ON t.folder_id=f.id WHERE t.ancestor_id=? AND f.deleted=0",{id})[0];};
        std::vector<json> before;for(const auto& person:people)before.push_back(counts(person));
        auto total=db.query("SELECT count(*) AS n FROM folder_tree WHERE ancestor_id=?",{id})[0]["n"];
        db.exec("SAVEPOINT relocation_preview");db.exec("UPDATE folders SET parent_id=?,policy=CASE WHEN ?='' AND policy='inherit' THEN 'private' ELSE policy END WHERE id=?",{parent,parent,id});rebuildFolderTree();
        json changes=json::array();for(size_t i=0;i<people.size();++i){auto after=counts(people[i]);if(after!=before[i])changes.push_back({{"name",people[i]["display_name"]},{"before",before[i]},{"after",after}});}
        db.exec("ROLLBACK TO relocation_preview; RELEASE relocation_preview");result={{"folders",total},{"changes",changes},{"truncated",truncated},{"note","仅为当前权限预览，移动时将重新校验"}};
    });return result;
}
json Store::auditPage(const std::string& before,const Identity& who){
    administrator(who);if(!before.empty()&&(before.size()>18||before.find_first_not_of("0123456789")!=std::string::npos))throw Error(400,"INVALID_CURSOR","历史游标无效");
    auto rows=db.query("SELECT h.*,COALESCE(u.display_name,CASE WHEN h.actor='system' THEN '自动维护' ELSE '已删除账号' END) AS actor_name FROM operation_history h LEFT JOIN users u ON u.id=h.actor WHERE h.seq<CAST(? AS INTEGER) ORDER BY h.seq DESC LIMIT 51",{before.empty()?"9223372036854775807":before});std::string next;if(rows.size()>50){rows.erase(rows.end()-1);next=rows.back()["seq"];}return {{"items",rows},{"next",next},{"retention",10000}};
}
json Store::libraryMaintenance(const Identity& who){
    administrator(who);std::lock_guard<std::mutex> lock(maintenanceStateMutex_);
    auto counts=db.query("SELECT count(*) AS total,COALESCE(sum(state='OK'),0) AS good,COALESCE(sum(state='ISSUE'),0) AS issues,COALESCE(sum(state='SKIPPED'),0) AS skipped,COALESCE(sum(state='PENDING'),0) AS pending FROM health_items")[0];
    return {{"health",healthState_},{"counts",counts},{"schedule",backupSchedule_}};
}
json Store::healthResults(const std::string& before,const Identity& who){
    administrator(who);if(!before.empty()&&(before.size()!=48||before.find_first_not_of("0123456789abcdef")!=std::string::npos))throw Error(400,"INVALID_CURSOR","检查结果游标无效");
    auto rows=db.query("SELECT h.asset_id,h.state,h.issue,h.checked_at,COALESCE(a.name,'原记录已移除') AS name FROM health_items h LEFT JOIN assets a ON a.id=h.asset_id WHERE h.state='ISSUE' AND h.asset_id>? ORDER BY h.asset_id LIMIT 101",{before});std::string next;if(rows.size()>100){rows.erase(rows.end()-1);next=rows.back()["asset_id"];}return {{"items",rows},{"next",next}};
}
json Store::healthAction(const std::string& action,const json& input,const Identity& who){
    administrator(who);{
        std::lock_guard<std::mutex> lock(maintenanceStateMutex_);auto state=healthState_.value("state","IDLE");
        if(action=="start"){
            if(state=="RUNNING"||state=="PAUSED"||state=="INTERRUPTED")throw Error(409,"HEALTH_ACTIVE","请继续或取消现有检查");
            auto rate=input.contains("rate_mib")?number(input,"rate_mib"):32;if(rate<1||rate>256)throw Error(400,"INVALID_RATE","检查速度为1至256 MiB/s");
            ++healthEpoch_;db.transaction([&]{db.exec("DELETE FROM health_items; INSERT INTO health_items(asset_id,path,size,sha256) SELECT id,path,CAST(size AS TEXT),sha256 FROM assets WHERE state IN ('READY','MISSING');");
                auto check=db.query("PRAGMA quick_check");bool good=check.size()==1&&check[0].begin().value()=="ok"&&db.query("PRAGMA foreign_key_check").empty();
                healthState_={{"state","RUNNING"},{"started",std::to_string(millis())},{"finished","0"},{"rate_mib",rate},{"database",good?"ok":"needs_attention"}};setting("health_run",healthState_.dump());audit(who,"health.start","",std::to_string(rate));});
        }else if(action=="pause"&&state=="RUNNING"){++healthEpoch_;healthState_["state"]="PAUSED";}
        else if(action=="resume"&&(state=="PAUSED"||state=="INTERRUPTED")){++healthEpoch_;healthState_["state"]="RUNNING";}
        else if(action=="cancel"&&(state=="RUNNING"||state=="PAUSED"||state=="INTERRUPTED")){++healthEpoch_;healthState_["state"]="CANCELLED";}
        else throw Error(409,"HEALTH_STATE","检查状态已变化，请刷新");
        setting("health_run",healthState_.dump());
    }return libraryMaintenance(who);
}
json Store::backupSchedule(const json& input,const Identity& who){
    administrator(who);auto hours=number(input,"interval_hours");if(hours<1||hours>720||!input.contains("enabled")||!input["enabled"].is_boolean())throw Error(400,"INVALID_SCHEDULE","快照间隔为1至720小时");
    auto keep=input.contains("keep")?number(input,"keep"):7;if(keep<1||keep>100)throw Error(400,"INVALID_RETENTION","自动快照保留1至100份");
    std::lock_guard<std::mutex> lock(maintenanceStateMutex_);backupSchedule_["enabled"]=input["enabled"];backupSchedule_["interval_hours"]=hours;backupSchedule_["keep"]=keep;backupSchedule_["next_run"]=std::to_string(millis()+hours*3600000);backupSchedule_["last_error"]="";
    db.transaction([&]{setting("backup_schedule",backupSchedule_.dump());audit(who,"backup.schedule","",input["enabled"].get<bool>()?std::to_string(hours):"disabled");});return backupSchedule_;
}
void Store::checkHealthItem(const json& item,unsigned epoch,unsigned rate){
    auto id=item["asset_id"].get<std::string>();auto mutex=sessionMutex("asset:"+id);std::lock_guard<std::mutex> guard(*mutex);
    if(stopping||epoch!=healthEpoch_)return;
    std::string state="OK",issue;
    auto rows=db.query("SELECT * FROM assets WHERE id=? AND state IN ('READY','MISSING')",{id});
    if(rows.empty()||rows[0]["path"]!=item["path"]||rows[0]["sha256"]!=item["sha256"]){state="SKIPPED";}
    else try{
        auto path=config.data/fs::u8path(item["path"].get<std::string>());std::error_code ec;
        if(!safeOriginal(path,config.data/"storage")){issue="UNSAFE_PATH";}
        else if(!fs::is_regular_file(path,ec)||ec){issue="ORIGINAL_MISSING";}
        else if(fs::file_size(path)!=number(item,"size")){issue="SIZE_MISMATCH";}
        else {
            File file(path,false);mbedtls_sha256_context context;mbedtls_sha256_init(&context);
            struct Digest {mbedtls_sha256_context* p;~Digest(){mbedtls_sha256_free(p);}} digest{&context};mbedtls_sha256_starts(&context,0);
            std::vector<char> buffer(1024*1024);uint64_t offset=0;auto started=millis();
            while(offset<number(item,"size")){
                if(stopping||epoch!=healthEpoch_)return;auto n=file.read(offset,buffer.data(),static_cast<size_t>(std::min<uint64_t>(buffer.size(),number(item,"size")-offset)));if(!n)throw std::runtime_error("read");
                mbedtls_sha256_update(&context,reinterpret_cast<unsigned char*>(buffer.data()),n);offset+=n;
                const auto due=started+offset*1000/(rate*1024ULL*1024);
                while(millis()<due){if(stopping||epoch!=healthEpoch_)return;std::this_thread::sleep_for(std::chrono::milliseconds(10));}
            }
            unsigned char bytes[32];mbedtls_sha256_finish(&context,bytes);std::string hash;const char* hex="0123456789abcdef";for(auto b:bytes){hash+=hex[b>>4];hash+=hex[b&15];}if(hash!=item["sha256"])issue="HASH_MISMATCH";
        }
        if(issue.empty()&&db.query("SELECT 1 FROM folder_items i JOIN folders f ON f.id=i.folder_id WHERE i.asset_id=? AND f.deleted=0 LIMIT 1",{id}).empty())issue="FOLDER_LINK_MISSING";
        if(issue.empty()&&rows[0]["preview"]=="READY"&&!fs::is_regular_file(config.data/"cache"/(id+".jpg")))issue="THUMBNAIL_MISSING";
    }catch(...){issue="READ_FAILED";}
    std::lock_guard<std::mutex> lock(maintenanceStateMutex_);
    if(stopping||epoch!=healthEpoch_)return;if(!issue.empty())state="ISSUE";
    db.exec("UPDATE health_items SET state=?,issue=?,checked_at=? WHERE asset_id=?",{state,issue,std::to_string(millis()),id});
}
void Store::scheduleWorker(){
    while(!stopping){
        try{
            bool due;unsigned keep;
            {std::lock_guard<std::mutex> lock(maintenanceStateMutex_);due=backupSchedule_.value("enabled",false)&&millis()>=number(backupSchedule_,"next_run");keep=backupSchedule_.value("keep",7u);}
            if(due){
                std::string error;try{metadataBackup(systemIdentity());pruneScheduledBackups(keep);}catch(...){error="SNAPSHOT_FAILED";}
                std::lock_guard<std::mutex> lock(maintenanceStateMutex_);if(error.empty())backupSchedule_["last_success"]=std::to_string(millis());backupSchedule_["last_error"]=error;
                backupSchedule_["next_run"]=std::to_string(millis()+(error.empty()?number(backupSchedule_,"interval_hours")*3600000:300000));setting("backup_schedule",backupSchedule_.dump());
            }
        }catch(...){/* A maintenance failure must not terminate the server. */}
        for(int i=0;i<10&&!stopping;++i)std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
}
void Store::maintenanceWorker(){
    while(!stopping){
        try{
            bool run=false;unsigned epoch=0,rate=32;
            {std::lock_guard<std::mutex> lock(maintenanceStateMutex_);run=healthState_.value("state","")=="RUNNING";epoch=healthEpoch_;rate=healthState_.value("rate_mib",32u);
            }
            if(run){auto rows=db.query("SELECT * FROM health_items WHERE state='PENDING' ORDER BY asset_id LIMIT 1");if(!rows.empty()){checkHealthItem(rows[0],epoch,rate);continue;}
                std::lock_guard<std::mutex> lock(maintenanceStateMutex_);if(epoch==healthEpoch_&&healthState_.value("state","")=="RUNNING"){healthState_["state"]="COMPLETED";healthState_["finished"]=std::to_string(millis());setting("health_run",healthState_.dump());audit(systemIdentity(),"health.completed","");}}
        }catch(...){std::lock_guard<std::mutex> lock(maintenanceStateMutex_);if(healthState_.value("state","")=="RUNNING"){healthState_["state"]="INTERRUPTED";healthState_["error"]="CHECK_FAILED";try{setting("health_run",healthState_.dump());}catch(...){}}}
        for(int i=0;i<10&&!stopping;++i)std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
}
}
