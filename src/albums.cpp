#include "core.hpp"
#include <set>

namespace nas {
namespace {
bool albumId(const std::string& id){return id.size()==48&&id.find_first_not_of("0123456789abcdef")==std::string::npos;}
void writer(const Identity& who){if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能修改图集");}
const std::string albumFields="SELECT a.id,a.name,a.description,a.owner_id,a.created,a.updated,a.cover_id,(SELECT count(*) FROM album_items i JOIN assets s ON s.id=i.asset_id AND s.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=s.id) WHERE i.album_id=a.id) AS item_count FROM albums a ";
}
json Store::album(const std::string& id,const Identity& who,bool edit){
    if(edit)writer(who);
    auto rows=db.query(albumFields+"WHERE a.id=?",{id});
    if(rows.empty())throw Error(404,"ALBUM_NOT_FOUND","图集不存在或已删除");
    auto row=rows[0];bool allowed=who.canUpload()&&(who.admin||row["owner_id"]==who.userId);
    if(edit&&!allowed)throw Error(403,"ALBUM_OWNER_REQUIRED","只能管理自己创建的图集");
    row["can_edit"]=allowed;
    auto covers=db.query("SELECT s.id,s.name,s.category,s.preview FROM assets s JOIN album_items i ON i.asset_id=s.id WHERE i.album_id=? AND s.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=s.id) ORDER BY (s.id=?) DESC,i.asset_id DESC LIMIT 1",{id,row["cover_id"]});
    row["cover"]=covers.empty()?json(nullptr):covers[0];return row;
}
json Store::albums(const std::string& before,const Identity& who){
    if(!before.empty()&&!albumId(before))throw Error(400,"INVALID_CURSOR","图集分页游标无效");
    auto ids=db.query("SELECT id FROM albums WHERE id<? ORDER BY id DESC LIMIT 49",{before.empty()?std::string(49,'f'):before});
    std::string next;if(ids.size()>48){ids.erase(ids.end()-1);next=ids.back()["id"];}
    json rows=json::array();for(auto& id:ids)rows.push_back(album(id["id"],who));return {{"items",rows},{"next",next}};
}
json Store::albumItems(const std::string& id,const std::string& before,const std::string& type,const std::string& search,const Identity& who){
    auto info=album(id,who);if(search.size()>256)throw Error(400,"SEARCH_TOO_LONG","搜索文字过长");
    if(!before.empty()&&!albumId(before))throw Error(400,"INVALID_CURSOR","图集分页游标无效");
    Params params{id};std::string sql="SELECT s.id,s.sha256,s.size,s.name,s.mime,s.category,s.created,s.modified,s.preview FROM album_items i JOIN assets s ON s.id=i.asset_id WHERE i.album_id=? AND s.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=s.id)";
    if(!before.empty()){sql+=" AND i.asset_id<?";params.push_back(before);}
    if(type=="image"||type=="video"||type=="file"){sql+=" AND s.category=?";params.push_back(type);}
    if(!search.empty()){sql+=" AND s.name LIKE ?";params.push_back("%"+search+"%");}
    auto rows=db.query(sql+" ORDER BY i.asset_id DESC LIMIT 49",params);std::string next;
    if(rows.size()>48){rows.erase(rows.end()-1);next=rows.back()["id"];}
    assetPermissions(rows,who);return {{"album",info},{"items",rows},{"next",next}};
}
json Store::changeAlbum(const std::string& id,const std::string& action,const json& input,const Identity& who){
    writer(who);std::string resultId=id;
    db.transaction([&]{
        const auto now=std::to_string(millis());
        if(id.empty()){
            auto name=text(input,"name",160),description=text(input,"description",1000,true);
            if(name.find_first_not_of(" \t\r\n")==std::string::npos)throw Error(400,"INVALID_NAME","请填写图集名称");
            resultId=randomHex();db.exec("INSERT INTO albums(id,name,description,owner_id,created,updated) VALUES(?,?,?,?,?,?)",{resultId,name,description,who.userId,now,now});return;
        }
        album(id,who,true);
        if(action=="delete"){db.exec("DELETE FROM albums WHERE id=?",{id});return;}
        if(action=="edit"){
            auto name=text(input,"name",160),description=text(input,"description",1000,true);
            if(name.find_first_not_of(" \t\r\n")==std::string::npos)throw Error(400,"INVALID_NAME","请填写图集名称");
            db.exec("UPDATE albums SET name=?,description=?,updated=? WHERE id=?",{name,description,now,id});
        }else if(action=="cover"){
            auto assetId=text(input,"asset_id",48);
            if(db.query("SELECT i.asset_id FROM album_items i JOIN assets s ON s.id=i.asset_id AND s.state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=s.id) WHERE i.album_id=? AND i.asset_id=?",{id,assetId}).empty())throw Error(400,"NOT_ALBUM_MEMBER","请从图集内选择封面");
            db.exec("UPDATE albums SET cover_id=?,updated=? WHERE id=?",{assetId,now,id});
        }else if(action=="add"||action=="remove"){
            if(!input.contains("asset_ids")||!input["asset_ids"].is_array()||input["asset_ids"].empty()||input["asset_ids"].size()>100)throw Error(400,"INVALID_BATCH","每次选择1至100个文件");
            std::set<std::string> ids;
            for(auto& value:input["asset_ids"]){if(!value.is_string()||!albumId(value.get<std::string>()))throw Error(400,"INVALID_ID","文件标识无效");ids.insert(value.get<std::string>());}
            for(auto& assetId:ids){
                if(action=="add"){auto row=asset(assetId);if(row["category"]!="image"&&row["category"]!="video")throw Error(400,"MEDIA_REQUIRED","图集只接收照片和视频");db.exec("INSERT OR IGNORE INTO album_items(album_id,asset_id) VALUES(?,?)",{id,assetId});}
                else db.exec("DELETE FROM album_items WHERE album_id=? AND asset_id=?",{id,assetId});
            }
            db.exec("UPDATE albums SET updated=?,cover_id=CASE WHEN EXISTS(SELECT 1 FROM album_items WHERE album_id=? AND asset_id=cover_id) THEN cover_id ELSE '' END WHERE id=?",{now,id,id});
        }else throw Error(400,"INVALID_ACTION","不支持的图集操作");
    });
    return action=="delete"?json{{"ok",true}}:album(resultId,who);
}
}
