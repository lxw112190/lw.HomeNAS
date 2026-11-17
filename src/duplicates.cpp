#include "core.hpp"
#include "page_jump.hpp"
#include <set>

namespace nas {
namespace {
std::string checkedId(const json& input,const std::string& key){
    auto id=text(input,key.c_str(),48);if(id.size()!=48||id.find_first_not_of("0123456789abcdef")!=std::string::npos)throw Error(400,"INVALID_ID","文件标识无效");return id;
}
}
std::string Store::duplicateWhere(const json& input,const Identity& who,Params& args){
    auto folderId=text(input,"folder_id",48,true),type=text(input,"type",8,true);
    if(type.empty())type="image";
    if(type!="all"&&type!="image"&&type!="video"&&type!="file")throw Error(400,"INVALID_TYPE","文件类型无效");
    std::string where=" FROM assets a WHERE a.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=a.id) AND "+assetAccessSql("a",who);
    if(!folderId.empty()){folder(folderId,who);where+=" AND EXISTS(SELECT 1 FROM folder_items fi JOIN folder_tree ft ON ft.folder_id=fi.folder_id WHERE fi.asset_id=a.id AND ft.ancestor_id=?)";args.push_back(folderId);}
    if(type!="all"){where+=" AND a.category=?";args.push_back(type);}return where;
}
json Store::duplicateReferences(const std::string& id,const Identity& who){
    auto folders=db.query("SELECT f.id,f.name,f.policy,f.updated FROM folder_items i JOIN folders f ON f.id=i.folder_id AND f.deleted=0 WHERE i.asset_id=? ORDER BY f.id LIMIT 17",{id});
    bool truncated=folders.size()>16;if(truncated)folders.erase(folders.end()-1);
    for(auto& f:folders){auto info=folder(f["id"],who);f["full_path"]=info["full_path"];}
    auto albums=db.query("SELECT album_id FROM album_items WHERE asset_id=? ORDER BY album_id LIMIT 17",{id});
    if(albums.size()>16){truncated=true;albums.erase(albums.end()-1);}
    // No hidden album names or memberships are returned. The opaque stamp detects changed references.
    json fingerprint={{"folders",folders},{"albums",albums},{"truncated",truncated}};
    auto serialized=fingerprint.dump();return {{"folders",folders},{"has_album_references",!albums.empty()},{"truncated",truncated},{"stamp",sha256(serialized.data(),serialized.size())}};
}
json Store::duplicates(const json& input,const Identity& who,bool members){
    Params args;auto where=duplicateWhere(input,who,args),before=text(input,"before",32,true);
    if(!isPageJump(before)&&!before.empty())throw Error(400,"INVALID_CURSOR","分页游标无效");
    if(!members){
        auto sql="SELECT a.sha256,a.size,count(*) AS copies,min(a.created) AS earliest"+where+" GROUP BY a.sha256,a.size HAVING count(*)>1";
        PageJump jump(before.empty()?"@page:0":before,db,sql,args,12);
        auto rows=db.query(sql+" ORDER BY a.sha256,a.size LIMIT 13"+jump.offset,args);bool more=rows.size()>12;if(more)rows.erase(rows.end()-1);
        return jump.finish({{"items",rows},{"next",more?"@page:"+std::to_string(jump.index+1):""}});
    }
    auto hash=text(input,"sha256",64);if(hash.size()!=64||hash.find_first_not_of("0123456789abcdef")!=std::string::npos)throw Error(400,"INVALID_HASH","摘要无效");auto size=number(input,"size");
    where+=" AND a.sha256=? AND a.size=?";args.push_back(hash);args.push_back(std::to_string(size));
    auto sql="SELECT a.id,a.name,a.size,a.sha256,a.created,a.modified,a.preview,a.category,a.mime"+where;
    PageJump jump(before.empty()?"@page:0":before,db,sql,args,48);auto rows=db.query(sql+" ORDER BY a.created,a.id LIMIT 49"+jump.offset,args);bool more=rows.size()>48;if(more)rows.erase(rows.end()-1);
    assetPermissions(rows,who);for(auto& row:rows)row["references"]=duplicateReferences(row["id"],who);
    auto oldest=db.query("SELECT a.id"+where+" ORDER BY a.created,a.id LIMIT 1",args);
    return jump.finish({{"items",rows},{"recommended_id",oldest.empty()?json(""):oldest[0]["id"]},{"next",more?"@page:"+std::to_string(jump.index+1):""}});
}
json Store::cleanDuplicates(const json& input,const Identity& who){
    if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能清理重复文件");
    if(!input.value("confirm_references",false))throw Error(400,"CONFIRM_REQUIRED","请确认目录权限及图集影响");
    auto keep=checkedId(input,"keep_id");
    if(!input.contains("remove")||!input["remove"].is_array()||input["remove"].empty()||input["remove"].size()>20)throw Error(400,"INVALID_BATCH","每次清理1至20份副本");
    std::set<std::string> ids{keep};std::map<std::string,std::string> stamps;
    stamps[keep]=text(input,"keep_stamp",64);
    for(const auto& item:input["remove"]){auto id=checkedId(item,"id");if(!ids.insert(id).second)throw Error(400,"INVALID_BATCH","保留文件不能被清理，副本不能重复");stamps[id]=text(item,"stamp",64);}
    std::vector<std::shared_ptr<std::mutex>> mutexes;std::vector<std::unique_lock<std::mutex>> locks;
    for(const auto& id:ids){mutexes.push_back(sessionMutex("asset:"+id));locks.emplace_back(*mutexes.back());}
    auto read=[&](const std::string& id){Params args;auto where=duplicateWhere(input,who,args);where+=" AND a.id=?";args.push_back(id);auto rows=db.query("SELECT a.*"+where,args);if(rows.empty())throw Error(409,"DUPLICATES_CHANGED","文件状态、范围或权限已改变，请刷新");assetPermissions(rows,who);if(id!=keep&&!rows[0]["can_rename"].get<bool>())throw Error(403,"ASSET_OWNER_REQUIRED","没有副本的整理权限");return rows[0];};
    auto kept=read(keep);std::map<std::string,json> checked;
    for(const auto& id:ids){auto row=read(id);if(row["sha256"]!=kept["sha256"]||row["size"]!=kept["size"])throw Error(409,"NOT_DUPLICATES","所选文件内容不相同");
        auto refs=duplicateReferences(id,who);if(refs["truncated"].get<bool>()||refs["stamp"]!=stamps[id])throw Error(409,"REFERENCES_CHANGED","目录或图集关联已改变或过多，请重新检查");
        try{auto path=originalPath(row);if(!fs::is_regular_file(path)||fs::file_size(path)!=number(row,"size")||hashFile(path,&stopping)!=row["sha256"])throw Error(409,"ORIGINAL_CHANGED","原件缺失或校验异常，未清理任何副本");}catch(const Error&){throw;}catch(...){throw Error(409,"ORIGINAL_CHANGED","无法校验原件，未清理任何副本");}checked[id]=row;
    }
    db.transaction([&]{for(const auto& id:ids){auto row=read(id),refs=duplicateReferences(id,who);if(row["path"]!=checked[id]["path"]||row["size"]!=kept["size"]||row["sha256"]!=kept["sha256"]||refs["stamp"]!=stamps[id])throw Error(409,"DUPLICATES_CHANGED","资料已改变，未清理任何副本");}
        for(const auto& id:ids)if(id!=keep)db.exec("INSERT INTO asset_trash(asset_id,deleted_at,deleted_by) VALUES(?,?,?)",{id,std::to_string(millis()),who.userId});audit(who,"asset.duplicates_trash",keep,std::to_string(ids.size()-1));
    });return {{"count",ids.size()-1},{"keep_id",keep},{"space_released",false}};
}
}
