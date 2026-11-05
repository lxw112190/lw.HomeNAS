#include "core.hpp"
#include <algorithm>
#include <cctype>
#include <set>

namespace nas {
void Store::assetPermissions(json& rows,const Identity& who){
    std::set<std::string> owned;
    if(who.canUpload()&&!who.admin&&!rows.empty()){
        Params args{who.userId};std::string slots;
        for(const auto& row:rows){if(!slots.empty())slots+=",";slots+="?";args.push_back(row["id"]);}
        for(const auto& row:db.query("SELECT DISTINCT asset_id FROM uploads WHERE user_id=? AND state='READY' AND asset_id IN ("+slots+")",args))owned.insert(row["asset_id"]);
    }
    for(auto& row:rows)row["can_rename"]=who.canUpload()&&(who.admin||owned.count(row["id"].get<std::string>())!=0);
}
json Store::renameAsset(const std::string& id,const json& input,const Identity& who){
    if(!who.canUpload())throw Error(403,"READ_ONLY","只读账号不能重命名文件");
    auto name=text(input,"name",240),previous=text(input,"old_name",512);
    if(name=="."||name==".."||name.front()==' '||name.back()==' '||name.back()=='.'||name.find_first_of("<>:\"/\\|?*")!=std::string::npos||std::any_of(name.begin(),name.end(),[](unsigned char c){return c<32||c==127;}))throw Error(400,"INVALID_FILENAME","文件名含不支持的字符，或以空格、句点结尾");
    auto stem=name.substr(0,name.find('.'));while(!stem.empty()&&stem.back()==' ')stem.pop_back();
    std::transform(stem.begin(),stem.end(),stem.begin(),[](unsigned char c){return static_cast<char>(std::toupper(c));});
    if(stem=="CON"||stem=="PRN"||stem=="AUX"||stem=="NUL"||(stem.size()==4&&(stem.substr(0,3)=="COM"||stem.substr(0,3)=="LPT")&&stem[3]>='1'&&stem[3]<='9'))throw Error(400,"INVALID_FILENAME","该文件名为系统保留名称");
    auto suffix=[](const std::string& s){auto dot=s.find_last_of('.');return dot==std::string::npos||dot==0?std::string{}:s.substr(dot);};
    json result;
    db.transaction([&]{
        auto rows=json::array({asset(id)});assetPermissions(rows,who);auto row=rows[0];
        if(!row["can_rename"].get<bool>())throw Error(403,"ASSET_OWNER_REQUIRED","普通用户只能重命名自己已上传的文件");
        if(row["name"]!=previous)throw Error(409,"NAME_CHANGED","文件名称已被修改，请刷新后重试");
        if(suffix(name)!=suffix(row["name"]))throw Error(400,"EXTENSION_CHANGED","重命名时请保留原扩展名");
        db.exec("UPDATE assets SET name=? WHERE id=?",{name,id});row["name"]=name;row.erase("path");result=row;
    });
    return result;
}
}
