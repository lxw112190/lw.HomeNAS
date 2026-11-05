#include "core.hpp"
#include <algorithm>
#include <set>

namespace nas {
namespace {
bool folderId(const std::string& id){return id=="default"||(id.size()==48&&id.find_first_not_of("0123456789abcdef")==std::string::npos);}
const std::string folderFields="SELECT f.id,f.name,f.description,f.owner_id,f.created,f.updated,(SELECT display_name FROM users WHERE id=f.owner_id) AS owner_name,(SELECT count(*) FROM folder_items i JOIN assets a ON a.id=i.asset_id AND a.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=a.id) WHERE i.folder_id=f.id) AS item_count FROM folders f ";
}
void Store::initializeFolders(){
    db.transaction([&]{
        bool column=false;for(const auto& row:db.query("PRAGMA table_info(uploads)"))if(row["name"]=="folder_id")column=true;
        if(!column)db.exec("ALTER TABLE uploads ADD COLUMN folder_id TEXT NOT NULL DEFAULT 'default'");
        db.exec("CREATE TABLE IF NOT EXISTS folders(id TEXT PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',owner_id TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,updated INTEGER NOT NULL);");
        db.exec("CREATE UNIQUE INDEX IF NOT EXISTS folder_owner_name ON folders(owner_id,name COLLATE NOCASE) WHERE deleted=0;");
        db.exec("CREATE TABLE IF NOT EXISTS folder_items(folder_id TEXT NOT NULL REFERENCES folders(id),asset_id TEXT NOT NULL REFERENCES assets(id),PRIMARY KEY(folder_id,asset_id)); CREATE INDEX IF NOT EXISTS folder_asset ON folder_items(asset_id,folder_id);");
        auto now=std::to_string(millis());db.exec("INSERT OR IGNORE INTO folders(id,name,owner_id,created,updated) VALUES('default','默认文件夹','',?,?)",{now,now});
        if(setting("folder_schema").empty()){db.exec("INSERT OR IGNORE INTO folder_items(folder_id,asset_id) SELECT 'default',id FROM assets WHERE state='READY'");setting("folder_schema","1");}
    });
}
json Store::folder(const std::string& id,const Identity& who,bool write){
    if(!folderId(id))throw Error(400,"INVALID_FOLDER","文件夹标识无效");
    auto rows=db.query(folderFields+"WHERE f.id=? AND f.deleted=0",{id});if(rows.empty())throw Error(404,"FOLDER_NOT_FOUND","文件夹不存在或已删除");
    auto row=rows[0];bool writable=who.canUpload()&&(id=="default"||who.admin||row["owner_id"]==who.userId);
    row["is_default"]=id=="default";row["can_write"]=writable;row["can_edit"]=writable&&id!="default";
    if(write&&!writable)throw Error(403,"FOLDER_OWNER_REQUIRED","只能写入默认文件夹或自己创建的文件夹");return row;
}
std::string Store::resolveFolder(const std::string& id,const Identity& who){
    if(!folderId(id))throw Error(400,"INVALID_FOLDER","文件夹标识无效");
    auto rows=db.query("SELECT deleted FROM folders WHERE id=?",{id});if(rows.empty())throw Error(404,"FOLDER_NOT_FOUND","请重新选择备份文件夹");
    auto effective=rows[0]["deleted"]=="1"?std::string("default"):id;folder(effective,who,true);return effective;
}
void Store::attachFolder(const std::string& requested,const std::string& assetId){
    auto rows=db.query("SELECT id FROM folders WHERE id=? AND deleted=0",{requested});auto destination=rows.empty()?std::string("default"):requested;
    db.exec("INSERT OR IGNORE INTO folder_items(folder_id,asset_id) VALUES(?,?)",{destination,assetId});
}
json Store::folders(const std::string& before,const Identity& who){
    if(!before.empty()&&!folderId(before))throw Error(400,"INVALID_CURSOR","文件夹游标无效");
    Params args;std::string where="WHERE deleted=0";if(!before.empty()){where+=" AND id<>'default'";if(before!="default"){where+=" AND id<?";args.push_back(before);}}
    auto ids=db.query("SELECT id FROM folders "+where+" ORDER BY (id='default') DESC,id DESC LIMIT 49",args);std::string next;if(ids.size()>48){ids.erase(ids.end()-1);next=ids.back()["id"];}
    json items=json::array();for(auto row:ids)items.push_back(folder(row["id"],who));return {{"items",items},{"next",next}};
}
json Store::folderItems(const std::string& id,const std::string& before,const std::string& type,const std::string& search,const Identity& who){
    auto info=folder(id,who);if(search.size()>256)throw Error(400,"SEARCH_TOO_LONG","搜索文字过长");
    if(!before.empty()&&(before.size()!=48||before.find_first_not_of("0123456789abcdef")!=std::string::npos))throw Error(400,"INVALID_CURSOR","文件分页游标无效");
    Params args{id};std::string sql="SELECT a.id,a.sha256,a.size,a.name,a.mime,a.category,a.created,a.modified,a.preview FROM folder_items i JOIN assets a ON a.id=i.asset_id WHERE i.folder_id=? AND a.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=a.id)";
    if(!before.empty()){sql+=" AND a.id<?";args.push_back(before);}if(type=="image"||type=="video"||type=="file"){sql+=" AND a.category=?";args.push_back(type);}if(!search.empty()){sql+=" AND a.name LIKE ?";args.push_back("%"+search+"%");}
    auto rows=db.query(sql+" ORDER BY a.id DESC LIMIT 49",args);std::string next;if(rows.size()>48){rows.erase(rows.end()-1);next=rows.back()["id"];}assetPermissions(rows,who);
    return {{"folder",info},{"items",rows},{"next",next}};
}
void Store::folderMembership(json& items,const std::string& folderId){
    Params args{folderId};std::string slots;for(auto row:items){if(!slots.empty())slots+=",";slots+="?";args.push_back(row["asset_id"]);}
    std::set<std::string> present;if(!slots.empty())for(auto row:db.query("SELECT asset_id FROM folder_items WHERE folder_id=? AND asset_id IN ("+slots+")",args))present.insert(row["asset_id"]);
    for(auto& row:items)row["in_folder"]=row["state"]=="READY"&&present.count(row["asset_id"].get<std::string>())!=0;
}
json Store::changeFolder(const std::string& id,const std::string& action,const json& input,const Identity& who){
    if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能修改文件夹");std::string resultId=id;
    db.transaction([&]{
        auto now=std::to_string(millis());
        if(id.empty()||action=="edit"){
            if(!id.empty()){folder(id,who,true);if(id=="default")throw Error(403,"DEFAULT_FOLDER","默认文件夹不能改名");}
            auto name=text(input,"name",160),description=text(input,"description",1000,true);
            if(name=="默认文件夹"||name.find_first_not_of(" \t")==std::string::npos||name.front()==' '||name.back()==' '||name=="."||name==".."||name.find_first_of("/\\")!=std::string::npos||std::any_of(name.begin(),name.end(),[](unsigned char c){return c<32||c==127;}))throw Error(400,"INVALID_NAME","请填写有效文件夹名称；默认文件夹名称由系统保留");
            auto owner=id.empty()?who.userId:folder(id,who)["owner_id"].get<std::string>();
            if(!db.query("SELECT id FROM folders WHERE owner_id=? AND name=? COLLATE NOCASE AND deleted=0 AND id<>?",{owner,name,id}).empty())throw Error(409,"FOLDER_NAME_EXISTS","你已创建同名文件夹");
            if(id.empty()){resultId=randomHex();db.exec("INSERT INTO folders(id,name,description,owner_id,created,updated) VALUES(?,?,?,?,?,?)",{resultId,name,description,who.userId,now,now});}
            else db.exec("UPDATE folders SET name=?,description=?,updated=? WHERE id=?",{name,description,now,id});return;
        }
        folder(id,who,true);
        if(action=="delete"){
            if(id=="default")throw Error(403,"DEFAULT_FOLDER","默认文件夹不能删除");
            db.exec("INSERT OR IGNORE INTO folder_items(folder_id,asset_id) SELECT 'default',asset_id FROM folder_items WHERE folder_id=?",{id});db.exec("DELETE FROM folder_items WHERE folder_id=?",{id});db.exec("UPDATE folders SET deleted=1,updated=? WHERE id=?",{now,id});return;
        }
        if(!input.contains("asset_ids")||!input["asset_ids"].is_array()||input["asset_ids"].empty()||input["asset_ids"].size()>100)throw Error(400,"INVALID_BATCH","每次选择1至100个文件");
        std::set<std::string> ids;for(auto value:input["asset_ids"]){if(!value.is_string())throw Error(400,"INVALID_ID","文件标识无效");auto assetId=value.get<std::string>();if(assetId.size()!=48||assetId.find_first_not_of("0123456789abcdef")!=std::string::npos||!ids.insert(assetId).second)throw Error(400,"INVALID_ID","文件标识无效或重复");auto row=asset(assetId);std::error_code ec;if(!fs::is_regular_file(originalPath(row),ec)||fs::file_size(originalPath(row),ec)!=number(row,"size"))throw Error(409,"ORIGINAL_MISSING","原件需要恢复检查");}
        auto target=action=="move"?text(input,"target_id",48):std::string("default");if(action=="move")folder(target,who,true);
        if(action!="add"&&action!="remove"&&action!="move")throw Error(400,"INVALID_ACTION","不支持的文件夹操作");
        for(auto assetId:ids){
            if(action=="add"){attachFolder(id,assetId);continue;}
            if(db.query("SELECT asset_id FROM folder_items WHERE folder_id=? AND asset_id=?",{id,assetId}).empty())throw Error(409,"FOLDER_ITEM_CHANGED","文件已移出此文件夹，请刷新");
            if(id=="default"&&!who.admin){auto rows=json::array({asset(assetId)});assetPermissions(rows,who);if(!rows[0]["can_rename"].get<bool>())throw Error(403,"ASSET_OWNER_REQUIRED","普通用户只能从默认文件夹移动自己上传过的文件");}
            if(id!=target){attachFolder(target,assetId);db.exec("DELETE FROM folder_items WHERE folder_id=? AND asset_id=?",{id,assetId});}
        }
    });return action=="delete"?json{{"deleted",true}}:folder(resultId,who);
}
}
