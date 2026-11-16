#pragma once
#include "core.hpp"
namespace nas {
inline bool isPageJump(const std::string& before){return before=="@last"||before.rfind("@page:",0)==0;}
inline std::string fileSort(const std::string& sort,const std::string& alias,const std::string& before){
    if(sort.empty())return "";
    if(!before.empty()&&!isPageJump(before))throw Error(400,"INVALID_CURSOR","排序已改变，请从首页重新加载");
    static const std::map<std::string,std::string> fields={{"name_asc","name COLLATE NOCASE ASC"},{"name_desc","name COLLATE NOCASE DESC"},{"modified_desc","modified DESC"},{"size_desc","size DESC"},{"size_asc","size ASC"}};
    auto it=fields.find(sort);if(it==fields.end())throw Error(400,"INVALID_SORT","排序方式无效");return alias+it->second+","+alias+"id DESC";
}
struct PageJump {
    long long index=-1;std::string offset;
    PageJump(const std::string& before,Database& db,const std::string& sql,const Params& args,unsigned size){
        if(!isPageJump(before))return;
        uint64_t requested=0;
        if(before!="@last"){auto value=before.substr(6);if(value.empty()||value.size()>9||value.find_first_not_of("0123456789")!=std::string::npos)throw Error(400,"INVALID_CURSOR","分页位置无效");requested=std::stoull(value);}
        auto total=number(db.query("SELECT count(*) AS n FROM ("+sql+")",args)[0],"n"),last=total?(total-1)/size:0;
        index=static_cast<long long>(before=="@last"?last:std::min(requested,last));offset=" OFFSET "+std::to_string(index*size);
    }
    json finish(json result)const{if(index>=0)result["page_index"]=index;return result;}
};
}
