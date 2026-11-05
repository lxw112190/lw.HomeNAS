#include "core.hpp"
#include <set>

namespace nas {
json Store::recycle(const json& input,const Identity& who,bool restore){
    if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能删除或恢复文件");
    if(!input.contains("asset_ids")||!input["asset_ids"].is_array()||input["asset_ids"].empty()||input["asset_ids"].size()>100)throw Error(400,"INVALID_BATCH","每次选择1至100个文件");
    std::set<std::string> ids;
    for(auto id:input["asset_ids"]){if(!id.is_string())throw Error(400,"INVALID_ID","文件标识无效");auto s=id.get<std::string>();if(s.size()!=48||s.find_first_not_of("0123456789abcdef")!=std::string::npos||!ids.insert(s).second)throw Error(400,"INVALID_ID","文件标识无效或重复");}
    db.transaction([&]{for(const auto& id:ids){
        auto rows=db.query("SELECT * FROM assets WHERE id=? AND state IN ('READY','MISSING')",{id});if(rows.empty())throw Error(404,"ASSET_NOT_FOUND","文件不存在或原件需要恢复检查");
        assetPermissions(rows,who);if(!rows[0]["can_rename"].get<bool>())throw Error(403,"ASSET_OWNER_REQUIRED","普通用户只能删除自己上传过的文件");
        if(rows[0]["state"]!="READY")throw Error(409,"ORIGINAL_MISSING","原件需要恢复检查，仍保留回收站记录");
        auto trash=db.query("SELECT deleted_by FROM asset_trash WHERE asset_id=?",{id});
        if(restore){
            if(trash.empty())throw Error(409,"TRASH_CHANGED","文件已恢复，请刷新");
            if(!who.admin&&trash[0]["deleted_by"]!=who.userId)throw Error(403,"TRASH_OWNER_REQUIRED","只能恢复自己删除的文件");
            std::error_code ec;auto path=originalPath(rows[0]);if(!fs::is_regular_file(path,ec)||fs::file_size(path,ec)!=number(rows[0],"size")||ec)throw Error(409,"ORIGINAL_MISSING","原件需要恢复检查，仍保留回收站记录");
            db.exec("DELETE FROM asset_trash WHERE asset_id=?",{id});
            if(db.query("SELECT asset_id FROM folder_items i JOIN folders f ON f.id=i.folder_id AND f.deleted=0 WHERE asset_id=?",{id}).empty())attachFolder("default",id);
        }else{
            if(!trash.empty())throw Error(409,"TRASH_CHANGED","文件已在回收站，请刷新");
            db.exec("INSERT INTO asset_trash(asset_id,deleted_at,deleted_by) VALUES(?,?,?)",{id,std::to_string(millis()),who.userId});
        }
    }});return {{"count",ids.size()},{"restored",restore}};
}
json Store::recycled(const std::string& before,const std::string& type,const std::string& search,const Identity& who){
    if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能管理回收站");
    if(search.size()>256)throw Error(400,"SEARCH_TOO_LONG","搜索文字过长");
    Params params;std::string where=" WHERE a.state IN ('READY','MISSING')";
    if(!who.admin){where+=" AND t.deleted_by=?";params.push_back(who.userId);}
    if(type=="image"||type=="video"||type=="file"){where+=" AND a.category=?";params.push_back(type);}
    if(!search.empty()){where+=" AND a.name LIKE ?";params.push_back("%"+search+"%");}
    if(!before.empty()){
        auto pos=before.find(':');auto time=before.substr(0,pos),id=pos==std::string::npos?"":before.substr(pos+1);
        if(time.empty()||time.size()>19||time.find_first_not_of("0123456789")!=std::string::npos||id.size()!=48||id.find_first_not_of("0123456789abcdef")!=std::string::npos||number(json{{"time",time}},"time")>static_cast<uint64_t>(INT64_MAX))throw Error(400,"INVALID_CURSOR","回收站分页游标无效");
        where+=" AND (t.deleted_at,a.id)<(CAST(? AS INTEGER),?)";params.insert(params.end(),{time,id});
    }
    auto rows=db.query("SELECT a.id,a.name,a.size,a.category,a.mime,a.modified,a.created,a.sha256,a.preview,t.deleted_at FROM asset_trash t JOIN assets a ON a.id=t.asset_id"+where+" ORDER BY t.deleted_at DESC,a.id DESC LIMIT 49",params);
    std::string next;if(rows.size()>48){rows.erase(rows.end()-1);next=rows.back()["deleted_at"].get<std::string>()+":"+rows.back()["id"].get<std::string>();}
    return {{"items",rows},{"next",next}};
}
}
