#include "core.hpp"
#include <set>

namespace nas {
void generateThumbnail(const fs::path& input,const fs::path& output);
fs::path Store::recycledThumbnail(const std::string& id,const Identity& who){
    auto mutex=sessionMutex("asset:"+id);std::lock_guard<std::mutex> guard(*mutex);auto row=recycledAsset(id,who);auto path=config.data/"cache"/(id+".jpg");
    if(!fs::is_regular_file(path)&&row["category"]=="image"&&fs::is_regular_file(originalPath(row))){try{generateThumbnail(originalPath(row),path);db.exec("UPDATE assets SET preview='READY' WHERE id=? AND state='READY'",{id});}catch(...){throw Error(404,"PREVIEW_UNAVAILABLE","原件无法生成缩略图");}}
    if(!fs::is_regular_file(path))throw Error(404,"PREVIEW_UNAVAILABLE","暂无缩略图");return path;
}
json Store::recycledAsset(const std::string& id,const Identity& who){
    if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能查看回收站");
    auto rows=db.query("SELECT a.*,t.deleted_by FROM assets a JOIN asset_trash t ON t.asset_id=a.id WHERE a.id=? AND a.state IN ('READY','MISSING')",{id});
    if(rows.empty()||(!who.admin&&rows[0]["deleted_by"]!=who.userId))throw Error(404,"TRASH_NOT_FOUND","回收站文件不存在或无权访问");return rows[0];
}
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
    Params params;std::string where=" WHERE a.state IN ('READY','MISSING','PURGING')";
    if(!who.admin){where+=" AND t.deleted_by=?";params.push_back(who.userId);}
    if(type=="image"||type=="video"||type=="file"){where+=" AND a.category=?";params.push_back(type);}
    if(!search.empty()){where+=" AND a.name LIKE ?";params.push_back("%"+search+"%");}
    if(!before.empty()){
        auto pos=before.find(':');auto time=before.substr(0,pos),id=pos==std::string::npos?"":before.substr(pos+1);
        if(time.empty()||time.size()>19||time.find_first_not_of("0123456789")!=std::string::npos||id.size()!=48||id.find_first_not_of("0123456789abcdef")!=std::string::npos||number(json{{"time",time}},"time")>static_cast<uint64_t>(INT64_MAX))throw Error(400,"INVALID_CURSOR","回收站分页游标无效");
        where+=" AND (t.deleted_at,a.id)<(CAST(? AS INTEGER),?)";params.insert(params.end(),{time,id});
    }
    auto rows=db.query("SELECT a.id,a.name,a.size,a.category,a.mime,a.modified,a.created,a.sha256,a.preview,a.state,t.deleted_at FROM asset_trash t JOIN assets a ON a.id=t.asset_id"+where+" ORDER BY t.deleted_at DESC,a.id DESC LIMIT 49",params);
    for(auto& row:rows){row["can_purge"]=who.admin;row["can_restore"]=row["state"]=="READY";row["purging"]=row["state"]=="PURGING";}
    std::string next;if(rows.size()>48){rows.erase(rows.end()-1);next=rows.back()["deleted_at"].get<std::string>()+":"+rows.back()["id"].get<std::string>();}
    return {{"items",rows},{"next",next}};
}
json Store::purgeAsset(const std::string& id,const json& input,const Identity& who){
    if(!who.admin)throw Error(403,"ADMIN_REQUIRED","彻底删除仅限管理员");
    if(text(input,"confirm",16)!="DELETE")throw Error(400,"CONFIRM_REQUIRED","请确认永久删除");
    auto deletedAt=text(input,"deleted_at",19);if(deletedAt.empty()||deletedAt.find_first_not_of("0123456789")!=std::string::npos)throw Error(400,"INVALID_CONFIRMATION","请刷新回收站后重新确认");
    auto mutex=sessionMutex("asset:"+id);std::lock_guard<std::mutex> guard(*mutex);
    db.transaction([&]{
        auto rows=db.query("SELECT a.state,t.deleted_at FROM assets a JOIN asset_trash t ON t.asset_id=a.id WHERE a.id=?",{id});
        if(rows.empty())throw Error(409,"TRASH_CHANGED","文件已恢复或删除，请刷新回收站");
        if(rows[0]["deleted_at"]!=deletedAt)throw Error(409,"TRASH_CHANGED","回收站记录已改变，请重新确认");
        if(rows[0]["state"]!="READY"&&rows[0]["state"]!="MISSING"&&rows[0]["state"]!="PURGING")throw Error(409,"ASSET_BUSY","文件正在处理，请稍后重试");
        if(!db.query("SELECT id FROM uploads WHERE asset_id=? AND state IN ('VERIFYING','COMMITTING')",{id}).empty())throw Error(409,"ASSET_BUSY","文件正在入库，请稍后重试");
        db.exec("UPDATE assets SET state='PURGING' WHERE id=?",{id});
    });
    finishPurge(id);return {{"id",id},{"purged",true}};
}
void Store::finishPurge(const std::string& id){
    auto rows=db.query("SELECT * FROM assets WHERE id=? AND state='PURGING' AND EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=assets.id)",{id});if(rows.empty())return;
    auto row=rows[0];
    // Reject corrupt paths and cross-asset aliases before touching either file.
    if(!db.query("SELECT id FROM assets WHERE path=? AND id<>?",{row["path"],id}).empty())throw Error(409,"SHARED_STORAGE_PATH","存储路径被其他文件引用，未执行物理删除");
    auto safePath=[&](const fs::path& base,const fs::path& path){
        std::error_code ec;auto root=fs::weakly_canonical(base,ec);if(ec)throw Error(409,"PURGE_PATH_INVALID","无法核对删除路径");auto target=fs::weakly_canonical(path,ec);if(ec)throw Error(409,"PURGE_PATH_INVALID","无法核对删除路径");auto relative=target.lexically_relative(root);
        if(relative.empty()||relative=="."||relative.is_absolute()||relative!=path.lexically_normal().lexically_relative(base.lexically_normal()))throw Error(409,"PURGE_PATH_INVALID","删除路径异常或包含路径别名");for(auto& component:relative)if(component=="..")throw Error(409,"PURGE_PATH_INVALID","删除路径超出存储目录");return target;
    };
    auto original=safePath(config.data/"storage/originals",originalPath(row));auto cache=safePath(config.data/"cache",config.data/"cache"/(id+".jpg"));
    auto removeFile=[&](const fs::path& path){std::error_code ec;if(fs::is_directory(path,ec))throw Error(409,"PURGE_PATH_INVALID","文件路径异常，未递归删除");ec.clear();fs::remove(path,ec);if(ec)throw Error(409,"PURGE_PENDING","文件被占用或无权限，删除意图已保存，请关闭预览后继续清理");};
    removeFile(cache);removeFile(original);
    db.transaction([&]{
        db.exec("UPDATE albums SET cover_id='' WHERE cover_id=?",{id});db.exec("DELETE FROM album_items WHERE asset_id=?",{id});db.exec("DELETE FROM folder_items WHERE asset_id=?",{id});db.exec("DELETE FROM sources WHERE asset_id=?",{id});
        db.exec("UPDATE uploads SET state='ERROR',asset_id='',error='ORIGINAL_PURGED',updated=? WHERE asset_id=?",{std::to_string(millis()),id});
        db.exec("DELETE FROM asset_trash WHERE asset_id=?",{id});db.exec("DELETE FROM assets WHERE id=? AND state='PURGING'",{id});
    });
}
}
