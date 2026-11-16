#include "page_jump.hpp"
#include "core.hpp"
#include <algorithm>
#include <set>

namespace nas {
namespace {
bool folderId(const std::string& id){return id=="default"||(id.size()==48&&id.find_first_not_of("0123456789abcdef")==std::string::npos);}
const std::string folderFields="SELECT f.id,f.name,f.description,f.owner_id,f.created,f.updated,f.parent_id,f.policy,(SELECT display_name FROM users WHERE id=f.owner_id) AS owner_name FROM folders f ";
}
void Store::initializeFolders(){
    db.transaction([&]{
        bool uploadFolderColumn=false;for(const auto& row:db.query("PRAGMA table_info(uploads)"))if(row["name"]=="folder_id")uploadFolderColumn=true;
        if(!uploadFolderColumn)db.exec("ALTER TABLE uploads ADD COLUMN folder_id TEXT NOT NULL DEFAULT 'default'");
        db.exec("CREATE TABLE IF NOT EXISTS folders(id TEXT PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',owner_id TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,updated INTEGER NOT NULL);");
        db.exec("CREATE TABLE IF NOT EXISTS folder_items(folder_id TEXT NOT NULL REFERENCES folders(id),asset_id TEXT NOT NULL REFERENCES assets(id),PRIMARY KEY(folder_id,asset_id)); CREATE INDEX IF NOT EXISTS folder_asset ON folder_items(asset_id,folder_id);");
        for(const auto& column:std::vector<std::pair<std::string,std::string>>{{"parent_id","TEXT NOT NULL DEFAULT ''"},{"policy","TEXT NOT NULL DEFAULT 'legacy'"}}){bool found=false;for(const auto& row:db.query("PRAGMA table_info(folders)"))if(row["name"]==column.first)found=true;if(!found)db.exec("ALTER TABLE folders ADD COLUMN "+column.first+" "+column.second);}
        db.exec("DROP INDEX IF EXISTS folder_owner_name; CREATE UNIQUE INDEX IF NOT EXISTS folder_sibling_name ON folders(parent_id,owner_id,name COLLATE NOCASE) WHERE deleted=0; CREATE UNIQUE INDEX IF NOT EXISTS folder_child_name ON folders(parent_id,name COLLATE NOCASE) WHERE deleted=0 AND parent_id<>''; CREATE INDEX IF NOT EXISTS folder_parent ON folders(parent_id,id) WHERE deleted=0; CREATE TABLE IF NOT EXISTS folder_acl(folder_id TEXT NOT NULL REFERENCES folders(id),user_id TEXT NOT NULL REFERENCES users(id),level INTEGER NOT NULL CHECK(level BETWEEN 1 AND 3),PRIMARY KEY(folder_id,user_id)); CREATE TABLE IF NOT EXISTS folder_tree(folder_id TEXT NOT NULL,ancestor_id TEXT NOT NULL,depth INTEGER NOT NULL,PRIMARY KEY(folder_id,ancestor_id));");
        auto now=std::to_string(millis());db.exec("INSERT OR IGNORE INTO folders(id,name,owner_id,created,updated) VALUES('default','默认文件夹','',?,?)",{now,now});
        if(setting("folder_schema").empty()){db.exec("INSERT OR IGNORE INTO folder_items(folder_id,asset_id) SELECT 'default',id FROM assets WHERE state='READY'");setting("folder_schema","1");}
        rebuildFolderTree();
    });
}
json Store::folder(const std::string& id,const Identity& who,bool write){
    if(!folderId(id))throw Error(400,"INVALID_FOLDER","文件夹标识无效");
    auto rows=db.query(folderFields+"WHERE f.id=? AND f.deleted=0",{id});if(rows.empty()){if(!db.query("SELECT id FROM folders WHERE id=? AND deleted=1",{id}).empty())throw Error(403,"FOLDER_UNAVAILABLE","目标目录已删除，请重新选择");throw Error(404,"FOLDER_NOT_FOUND","文件夹不存在");}
    auto row=rows[0];int level=std::stoi(db.query("SELECT "+folderAccessSql("'"+id+"'",who)+" AS level")[0]["level"].get<std::string>());if(level<1)throw Error(403,"FOLDER_ACCESS_DENIED","无权访问此目录，请重新选择；不会改存默认目录");bool writable=level>=2;row["access_level"]=level;
    auto ancestors=db.query("SELECT f.id,f.name FROM folder_tree t JOIN folders f ON f.id=t.ancestor_id WHERE t.folder_id=? ORDER BY t.depth DESC",{id});row["breadcrumbs"]=ancestors;row["depth"]=ancestors.size();std::string path;for(const auto& a:ancestors){if(!path.empty())path+=" / ";path+=a["name"].get<std::string>();}row["full_path"]=path;row["can_manage"]=level>=3;row["stats_scope"]="direct";
    row["is_default"]=id=="default";row["can_write"]=writable;row["can_edit"]=level>=3&&id!="default";
    if(write&&!writable)throw Error(403,"FOLDER_OWNER_REQUIRED","需要目标目录的可整理或管理权限");
    // Upload permission checks must not recount a large destination folder per file.
    if(!write){row["child_count"]=db.query("SELECT count(*) AS n FROM folders f WHERE f.parent_id=? AND f.deleted=0 AND "+folderAccessSql("f.id",who)+">=1",{id})[0]["n"];auto stats=db.query("SELECT count(*) AS item_count,COALESCE(sum(a.size),0) AS total_bytes,COALESCE(sum(a.category='image'),0) AS photo_count,COALESCE(sum(a.category='video'),0) AS video_count FROM folder_items i JOIN assets a ON a.id=i.asset_id AND a.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=a.id) WHERE i.folder_id=? AND "+assetAccessSql("a",who)+"",{id})[0];for(auto it=stats.begin();it!=stats.end();++it)row[it.key()]=it.value();}return row;
}
std::string Store::resolveFolder(const std::string& id,const Identity& who){
    if(!folderId(id))throw Error(400,"INVALID_FOLDER","文件夹标识无效");
    auto rows=db.query("SELECT deleted FROM folders WHERE id=?",{id});if(rows.empty())throw Error(404,"FOLDER_NOT_FOUND","请重新选择备份文件夹");
    if(rows[0]["deleted"]=="1")throw Error(409,"FOLDER_UNAVAILABLE","目标目录已删除，请重新选择；不会自动改存共享目录");folder(id,who,true);return id;
}
void Store::attachFolder(const std::string& requested,const std::string& assetId){
    auto rows=db.query("SELECT id FROM folders WHERE id=? AND deleted=0",{requested});if(rows.empty())throw Error(409,"FOLDER_UNAVAILABLE","目标目录已删除，进度保留");auto destination=requested;
    db.exec("INSERT OR IGNORE INTO folder_items(folder_id,asset_id) VALUES(?,?)",{destination,assetId});
}
json Store::folders(const std::string& before,const Identity& who,const std::string& parent){
    if(!before.empty()&&!isPageJump(before)&&!folderId(before))throw Error(400,"INVALID_CURSOR","文件夹游标无效");
    Params args;std::string where="WHERE deleted=0 AND "+folderAccessSql("folders.id",who)+">=1";if(parent!="*"){if(!parent.empty())folder(parent,who);where+=" AND parent_id=?";args.push_back(parent);}if(!before.empty()&&!isPageJump(before)){where+=" AND id<>'default'";if(before!="default"){where+=" AND id<?";args.push_back(before);}}
    PageJump jump(before,db,"SELECT id FROM folders "+where,args,48);auto ids=db.query("SELECT id FROM folders "+where+" ORDER BY (id='default') DESC,id DESC LIMIT 49"+jump.offset,args);std::string next;if(ids.size()>48){ids.erase(ids.end()-1);next=ids.back()["id"];}
    json items=json::array();for(auto row:ids)items.push_back(folder(row["id"],who));return jump.finish({{"items",items},{"next",next},{"folder_acl_version",1},{"parent_id",parent}});
}
json Store::folderItems(const std::string& id,const std::string& before,const std::string& type,const std::string& search,const Identity& who,const std::string& sort){
    auto ordered=fileSort(sort,"a.",before);
    auto info=folder(id,who);if(search.size()>256)throw Error(400,"SEARCH_TOO_LONG","搜索文字过长");
    if(!before.empty()&&!isPageJump(before)&&(before.size()!=48||before.find_first_not_of("0123456789abcdef")!=std::string::npos))throw Error(400,"INVALID_CURSOR","文件分页游标无效");
    Params args{id};std::string sql="SELECT a.id,a.sha256,a.size,a.name,a.mime,a.category,a.created,a.modified,a.preview";
    if(search.empty())sql+=" FROM folder_items i JOIN assets a ON a.id=i.asset_id WHERE i.folder_id=?";
    else sql+=" FROM assets a WHERE EXISTS(SELECT 1 FROM folder_items i JOIN folder_tree ft ON ft.folder_id=i.folder_id WHERE i.asset_id=a.id AND ft.ancestor_id=?)";
    sql+=" AND a.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=a.id)";
    sql+=" AND "+assetAccessSql("a",who);if(!before.empty()&&!isPageJump(before)){sql+=" AND a.id<?";args.push_back(before);}if(type=="image"||type=="video"||type=="file"){sql+=" AND a.category=?";args.push_back(type);}if(!search.empty()){sql+=" AND a.name LIKE ? ESCAPE '\\'";args.push_back(literalSearch(search));}
    PageJump jump(!sort.empty()&&before.empty()?"@page:0":before,db,sql,args,48);auto rows=db.query(sql+" ORDER BY "+(ordered.empty()?"a.id DESC":ordered)+" LIMIT 49"+jump.offset,args);std::string next;if(rows.size()>48){rows.erase(rows.end()-1);next=sort.empty()?rows.back()["id"].get<std::string>():"@page:"+std::to_string(jump.index+1);}assetPermissions(rows,who);
    if(!search.empty())for(auto& row:rows){auto sources=db.query("SELECT i.folder_id FROM folder_items i JOIN folder_tree ft ON ft.folder_id=i.folder_id WHERE i.asset_id=? AND ft.ancestor_id=? ORDER BY ft.depth,i.folder_id LIMIT 1",{row["id"],id});if(!sources.empty())row["source_folder"]=sources[0]["folder_id"];}
    return jump.finish({{"folder",info},{"items",rows},{"next",next},{"children",folders("",who,id)},{"folder_acl_version",1}});
}
void Store::folderMembership(json& items,const std::string& folderId){
    Params args{folderId};std::string slots;for(auto row:items){if(!slots.empty())slots+=",";slots+="?";args.push_back(row["asset_id"]);}
    std::set<std::string> present;if(!slots.empty())for(auto row:db.query("SELECT asset_id FROM folder_items WHERE folder_id=? AND asset_id IN ("+slots+")",args))present.insert(row["asset_id"]);
    for(auto& row:items)row["in_folder"]=row["state"]=="READY"&&present.count(row["asset_id"].get<std::string>())!=0;
}
json Store::folderMoveOptions(const json& input,const Identity& who){
    if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能移动文件");
    if(!input.contains("asset_ids")||!input["asset_ids"].is_array()||input["asset_ids"].empty()||input["asset_ids"].size()>1000)throw Error(400,"INVALID_BATCH","每次选择1至1000个文件");
    std::set<std::string> unique;Params ids;std::string slots;
    for(const auto& value:input["asset_ids"]){
        if(!value.is_string())throw Error(400,"INVALID_ID","文件标识无效");auto id=value.get<std::string>();
        if(id.size()!=48||id.find_first_not_of("0123456789abcdef")!=std::string::npos||!unique.insert(id).second)throw Error(400,"INVALID_ID","文件标识无效或重复");
        if(!slots.empty())slots+=",";slots+="?";ids.push_back(id);
    }
    json result;
    db.transaction([&]{
        auto assets=db.query("SELECT id FROM assets a WHERE a.id IN ("+slots+") AND a.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=a.id) AND "+assetAccessSql("a",who),ids);
        if(assets.size()!=ids.size())throw Error(409,"SELECTION_CHANGED","部分所选文件已删除或不可用，请刷新后重新选择");
        assetPermissions(assets,who);bool defaultAllowed=true;for(const auto& row:assets)if(!row["can_rename"].get<bool>())defaultAllowed=false;
        auto common=db.query("SELECT i.folder_id FROM folder_items i JOIN folders f ON f.id=i.folder_id AND f.deleted=0 WHERE i.asset_id IN ("+slots+") GROUP BY i.folder_id HAVING COUNT(*)="+std::to_string(ids.size())+" ORDER BY (i.folder_id='default') DESC,i.folder_id",ids);
        auto items=json::array();for(const auto& row:common){auto info=folder(row["folder_id"],who);if(info["can_write"].get<bool>()&&(info["id"]!="default"||who.admin||defaultAllowed))items.push_back(info);}
        result={{"items",items},{"selected_count",ids.size()}};
    });return result;
}
json Store::changeFolder(const std::string& id,const std::string& action,const json& input,const Identity& who){
    if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能修改文件夹");std::string resultId=id;
    db.transaction([&]{auto operation=[&]{
        auto now=std::to_string(millis());
        if(id.empty()||action=="edit"){
            if(!id.empty()){if(!folder(id,who)["can_manage"].get<bool>())throw Error(403,"FOLDER_MANAGE_REQUIRED","需要目录管理权限");if(id=="default")throw Error(403,"DEFAULT_FOLDER","默认文件夹不能改名");}
            auto name=text(input,"name",160),description=text(input,"description",1000,true);
            if(name=="默认文件夹"||name.find_first_not_of(" \t")==std::string::npos||name.front()==' '||name.back()==' '||name=="."||name==".."||name.find_first_of("/\\")!=std::string::npos||std::any_of(name.begin(),name.end(),[](unsigned char c){return c<32||c==127;}))throw Error(400,"INVALID_NAME","请填写有效文件夹名称；默认文件夹名称由系统保留");
            auto parent=id.empty()?text(input,"parent_id",48,true):folder(id,who)["parent_id"].get<std::string>();if(!parent.empty()){if(!folderId(parent))throw Error(400,"INVALID_FOLDER","父目录无效");auto parentInfo=folder(parent,who);if(!parentInfo["can_manage"].get<bool>())throw Error(403,"FOLDER_MANAGE_REQUIRED","需要父目录管理权限");if(number(parentInfo,"depth")>=16)throw Error(400,"FOLDER_DEPTH","最多支持16层目录");}
            auto owner=id.empty()?who.userId:folder(id,who)["owner_id"].get<std::string>();
            if(!db.query("SELECT id FROM folders WHERE parent_id=? AND name=? COLLATE NOCASE AND deleted=0 AND id<>? AND (?<>'' OR owner_id=?)",{parent,name,id,parent,owner}).empty())throw Error(409,"FOLDER_NAME_EXISTS","你已创建同名文件夹");
            if(id.empty()){resultId=randomHex();db.exec("INSERT INTO folders(id,name,description,owner_id,created,updated) VALUES(?,?,?,?,?,?)",{resultId,name,description,who.userId,now,now});db.exec("UPDATE folders SET parent_id=?,policy=? WHERE id=?",{parent,parent.empty()?"private":"inherit",resultId});rebuildFolderTree();}
            else db.exec("UPDATE folders SET name=?,description=?,updated=? WHERE id=?",{name,description,now,id});return;
        }
        folder(id,who,true);
        if(action=="relocate"){
            if(id=="default"||!folder(id,who)["can_manage"].get<bool>())throw Error(403,"FOLDER_MANAGE_REQUIRED","需要目录管理权限");auto parent=text(input,"parent_id",48,true);if(!parent.empty()&&(!folderId(parent)||!folder(parent,who)["can_manage"].get<bool>()))throw Error(403,"FOLDER_MANAGE_REQUIRED","需要目标父目录管理权限");if(!db.query("SELECT folder_id FROM folder_tree WHERE folder_id=? AND ancestor_id=?",{parent,id}).empty())throw Error(400,"FOLDER_CYCLE","不能移入自身或下级目录");if(input.value("confirm_access_change",false)!=true)throw Error(409,"ACCESS_CONFIRM_REQUIRED","移动可能改变整棵目录的可见范围，请确认");if(!db.query("SELECT id FROM folders WHERE parent_id=? AND name=? COLLATE NOCASE AND deleted=0 AND id<>? AND (?<>'' OR owner_id=?)",{parent,folder(id,who)["name"],id,parent,folder(id,who)["owner_id"]}).empty())throw Error(409,"FOLDER_NAME_EXISTS","目标位置已有同名目录");db.exec("UPDATE folders SET parent_id=?,policy=CASE WHEN ?='' AND policy='inherit' THEN 'private' ELSE policy END WHERE id=?",{parent,parent,id});rebuildFolderTree();return;
        }
        if(action=="delete"){
            if(id=="default")throw Error(403,"DEFAULT_FOLDER","默认文件夹不能删除");
            if(!folder(id,who)["can_manage"].get<bool>())throw Error(403,"FOLDER_MANAGE_REQUIRED","需要目录管理权限");if(!db.query("SELECT id FROM folders WHERE parent_id=? AND deleted=0",{id}).empty()||!db.query("SELECT asset_id FROM folder_items WHERE folder_id=?",{id}).empty())throw Error(409,"FOLDER_NOT_EMPTY","请先移走文件（含回收站关联）和子目录，删除仅限空目录");
            db.exec("INSERT OR IGNORE INTO folder_items(folder_id,asset_id) SELECT 'default',asset_id FROM folder_items WHERE folder_id=?",{id});db.exec("DELETE FROM folder_items WHERE folder_id=?",{id});db.exec("UPDATE folders SET deleted=1,updated=? WHERE id=?",{now,id});return;
        }
        if(!input.contains("asset_ids")||!input["asset_ids"].is_array()||input["asset_ids"].empty()||input["asset_ids"].size()>100)throw Error(400,"INVALID_BATCH","每次选择1至100个文件");
        std::set<std::string> ids;for(auto value:input["asset_ids"]){if(!value.is_string())throw Error(400,"INVALID_ID","文件标识无效");auto assetId=value.get<std::string>();if(assetId.size()!=48||assetId.find_first_not_of("0123456789abcdef")!=std::string::npos||!ids.insert(assetId).second)throw Error(400,"INVALID_ID","文件标识无效或重复");requireAssetAccess(assetId,who,2);auto writableRows=json::array({asset(assetId)});assetPermissions(writableRows,who);if(!writableRows[0]["can_rename"].get<bool>())throw Error(403,"ASSET_OWNER_REQUIRED","需要所选文件的整理权限");auto row=asset(assetId);std::error_code ec;if(!fs::is_regular_file(originalPath(row),ec)||fs::file_size(originalPath(row),ec)!=number(row,"size"))throw Error(409,"ORIGINAL_MISSING","原件需要恢复检查");}
        auto target=action=="move"?text(input,"target_id",48):std::string("default");if(action=="move")folder(target,who,true);
        if(action!="add"&&action!="remove"&&action!="move")throw Error(400,"INVALID_ACTION","不支持的文件夹操作");
        for(auto assetId:ids){
            if(action=="add"){if(!input.value("confirm_access_change",false))throw Error(409,"ACCESS_CONFIRM_REQUIRED","添加引用可能收紧文件可见范围，请确认");attachFolder(id,assetId);continue;}
            if(db.query("SELECT asset_id FROM folder_items WHERE folder_id=? AND asset_id=?",{id,assetId}).empty())throw Error(409,"FOLDER_ITEM_CHANGED","文件已移出此文件夹，请刷新");
            if(id=="default"&&!who.admin){auto rows=json::array({asset(assetId)});assetPermissions(rows,who);if(!rows[0]["can_rename"].get<bool>())throw Error(403,"ASSET_OWNER_REQUIRED","普通用户只能从默认文件夹移动自己上传过的文件");}
            if(id!=target){if(!input.value("confirm_access_change",false))throw Error(409,"ACCESS_CONFIRM_REQUIRED","移动或添加可能改变可见范围，请确认");attachFolder(target,assetId);db.exec("DELETE FROM folder_items WHERE folder_id=? AND asset_id=?",{id,assetId});}
        }
    };operation();audit(who,"folder."+action,resultId,input.contains("asset_ids")?std::to_string(input["asset_ids"].size()):"");});return action=="delete"?json{{"deleted",true}}:folder(resultId,who);
}
}

namespace nas {
void Store::rebuildFolderTree(){
 db.exec("DELETE FROM folder_tree; WITH RECURSIVE chain(folder_id,ancestor_id,depth) AS (SELECT id,id,0 FROM folders WHERE deleted=0 UNION ALL SELECT c.folder_id,f.parent_id,c.depth+1 FROM chain c JOIN folders f ON f.id=c.ancestor_id WHERE f.parent_id<>'' AND c.depth<16) INSERT INTO folder_tree SELECT folder_id,ancestor_id,depth FROM chain;");
 if(!db.query("SELECT f.id FROM folders f WHERE f.deleted=0 AND NOT EXISTS(SELECT 1 FROM folder_tree t JOIN folders a ON a.id=t.ancestor_id WHERE t.folder_id=f.id AND a.parent_id='' AND a.deleted=0 AND t.depth<16)").empty())throw Error(400,"FOLDER_DEPTH","目录包含循环、无效父目录或超过16层");
}
std::string Store::folderAccessSql(const std::string& folder,const Identity& who){
 if(who.admin)return "3";auto user=who.userId;if(user!="administrator"&&(user.size()!=48||user.find_first_not_of("0123456789abcdef")!=std::string::npos))throw Error(401,"AUTH_REQUIRED","账号无效");
 auto level="COALESCE((SELECT min(CASE WHEN p.deleted<>0 THEN 0 WHEN p.owner_id='"+user+"' THEN 3 WHEN p.policy='inherit' AND p.parent_id<>'' THEN 3 WHEN p.policy='legacy' THEN CASE WHEN p.id='default' THEN 2 ELSE 1 END WHEN p.policy='public' THEN 1 ELSE COALESCE((SELECT g.level FROM folder_acl g WHERE g.folder_id=p.id AND g.user_id='"+user+"'),0) END) FROM folder_tree t JOIN folders p ON p.id=t.ancestor_id WHERE t.folder_id="+folder+"),0)";
 return who.canUpload()?level:"min(1,"+level+")";
}
std::string Store::assetAccessSql(const std::string& alias,const Identity& who,int level){
 if(who.admin)return "1";return "(EXISTS(SELECT 1 FROM folder_items af JOIN folders ff ON ff.id=af.folder_id AND ff.deleted=0 WHERE af.asset_id="+alias+".id) AND NOT EXISTS(SELECT 1 FROM folder_items ax WHERE ax.asset_id="+alias+".id AND "+folderAccessSql("ax.folder_id",who)+"<"+std::to_string(level)+"))";
}
void Store::requireAssetAccess(const std::string& id,const Identity& who,int level){if(db.query("SELECT a.id FROM assets a WHERE a.id=? AND "+assetAccessSql("a",who,level),{id}).empty())throw Error(404,"ASSET_NOT_FOUND","文件不存在或无权访问");}
Identity Store::taskIdentity(const json& task){auto users=db.query("SELECT id,username,display_name,role FROM users WHERE id=? AND enabled=1",{task["user_id"]});if(users.empty())throw Error(403,"FOLDER_PERMISSION_CHANGED","上传账号已停用，数据保留");auto r=users[0];Identity who{r["id"],r["username"],r["display_name"],r["role"],r["role"]=="admin"};resolveFolder(task["folder_id"],who);return who;}
json Store::folderPeople(const Identity& who){if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能设置权限");return {{"items",db.query("SELECT id,username,display_name,role FROM users WHERE enabled=1 ORDER BY username")}};}
json Store::folderPolicy(const std::string& id,const json* input,const Identity& who){
 auto info=folder(id,who);if(!info["can_manage"].get<bool>())throw Error(403,"FOLDER_MANAGE_REQUIRED","需要目录管理权限");
 if(input){db.transaction([&]{
  info=folder(id,who);if(!info["can_manage"].get<bool>()||id=="default")throw Error(403,"FOLDER_MANAGE_REQUIRED","默认目录保留兼容权限，不能设置私有");
  auto policy=text(*input,"policy",16);if(policy!="inherit"&&policy!="private"&&policy!="restricted"&&policy!="public")throw Error(400,"INVALID_POLICY","权限模式无效");if(policy=="inherit"&&info["parent_id"]=="")throw Error(400,"INVALID_POLICY","一级目录没有可继承的父目录");if(!input->value("confirm_access_change",false))throw Error(409,"ACCESS_CONFIRM_REQUIRED","权限变化影响后代目录及旧文件引用，请确认");
  auto grants=input->value("grants",json::array());if(!grants.is_array()||grants.size()>200)throw Error(400,"INVALID_GRANTS","每个目录最多授权200个账号");db.exec("DELETE FROM folder_acl WHERE folder_id=?",{id});std::set<std::string> seen;
  if(policy=="restricted")for(const auto& g:grants){auto user=text(g,"user_id",48);auto level=number(g,"level");if(level<1||level>3||!seen.insert(user).second||db.query("SELECT id FROM users WHERE id=? AND enabled=1",{user}).empty())throw Error(400,"INVALID_GRANTS","授权账号或权限无效");if(info["parent_id"]!=""){auto p=db.query("SELECT username,display_name,role FROM users WHERE id=?",{user})[0];Identity recipient{user,p["username"],p["display_name"],p["role"],p["role"]=="admin"};auto cap=std::stoi(db.query("SELECT "+folderAccessSql("'"+info["parent_id"].get<std::string>()+"'",recipient)+" AS n")[0]["n"].get<std::string>());if(level>static_cast<uint64_t>(cap))throw Error(400,"PARENT_PERMISSION_LIMIT","子目录授权不能超过父目录，请先调整父目录");}db.exec("INSERT INTO folder_acl(folder_id,user_id,level) VALUES(?,?,?)",{id,user,std::to_string(level)});}
  db.exec("UPDATE folders SET policy=?,updated=? WHERE id=?",{policy,std::to_string(millis()),id});if(number(db.query("SELECT "+folderAccessSql("'"+id+"'",who)+" AS level")[0],"level")<3)throw Error(409,"SELF_ACCESS_REQUIRED","请保留自己的管理权限，或由所有者/管理员调整");
 audit(who,"folder.permissions",id);});info=folder(id,who);}
 info["grants"]=db.query("SELECT g.user_id,g.level,u.display_name FROM folder_acl g JOIN users u ON u.id=g.user_id WHERE g.folder_id=?",{id});info["people"]=folderPeople(who)["items"];return info;
}
}
