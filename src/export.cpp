#include "export.hpp"
#include <array>
#include <set>
#include <ctime>

namespace nas {
namespace {
void word(std::string& out,uint64_t n,int bytes){for(int i=0;i<bytes;++i)out.push_back(static_cast<char>((n>>(8*i))&255));}
struct Entry{std::shared_ptr<File> file;std::string name;uint64_t size,offset=0;uint32_t crc=0;uint16_t time=0,date=33;};
uint32_t crc32(uint32_t crc,const char* data,size_t count){
    static const auto table=[] {std::array<uint32_t,256> t{};for(uint32_t i=0;i<256;++i){auto x=i;for(int k=0;k<8;++k)x=(x>>1)^((x&1)?0xedb88320U:0);t[i]=x;}return t;}();
    for(size_t i=0;i<count;++i)crc=table[(crc^static_cast<unsigned char>(data[i]))&255]^(crc>>8);return crc;
}
}
void exportArchive(Store& store,httplib::Response& response,const json& input){
    if(!input.contains("asset_ids")||!input["asset_ids"].is_array()||input["asset_ids"].empty()||input["asset_ids"].size()>1000)throw Error(400,"INVALID_BATCH","每次导出1至1000项");
    auto entries=std::make_shared<std::vector<Entry>>();std::set<std::string> seen;
    for(const auto& value:input["asset_ids"]){
        if(!value.is_string())throw Error(400,"INVALID_ID","文件标识无效");auto id=value.get<std::string>();if(id.size()!=48||id.find_first_not_of("0123456789abcdef")!=std::string::npos||!seen.insert(id).second)throw Error(400,"INVALID_ID","文件标识无效或重复");
        auto row=store.asset(id);auto file=std::make_shared<File>(store.originalPath(row),false);if(file->size()!=number(row,"size"))throw Error(409,"ORIGINAL_CHANGED","原件已变化，请刷新资料库");
        auto name=row["name"].get<std::string>();for(auto& c:name)if(static_cast<unsigned char>(c)<32||std::string("/\\:*?\"<>|").find(c)!=std::string::npos)c='_';
        while(!name.empty()&&(name.back()=='.'||name.back()==' '))name.pop_back();if(name.empty())name="file";
        entries->push_back({file,std::to_string(entries->size()+1)+"_"+name,file->size()});
        auto ms=number(row,"modified");if(!ms)ms=number(row,"created");std::time_t seconds=static_cast<std::time_t>(ms/1000);std::tm tm{};
        if(localtime_s(&tm,&seconds)==0&&tm.tm_year>=80&&tm.tm_year<=207){auto& e=entries->back();e.time=static_cast<uint16_t>((tm.tm_hour<<11)|(tm.tm_min<<5)|(tm.tm_sec/2));e.date=static_cast<uint16_t>(((tm.tm_year-80)<<9)|((tm.tm_mon+1)<<5)|tm.tm_mday);}
    }
    response.set_header("Content-Disposition","attachment; filename=\"HomeNAS-export.zip\"");response.set_header("Cache-Control","private, no-store");
    response.set_chunked_content_provider("application/zip",[entries](size_t,httplib::DataSink& sink){
        uint64_t position=0;auto write=[&](const char* data,size_t size){if(!sink.is_writable()||!sink.write(data,size))return false;position+=size;return true;};auto emit=[&](const std::string& s){return write(s.data(),s.size());};std::array<char,65536> buffer{};
        try{
            for(auto& e:*entries){e.offset=position;std::string h;word(h,0x04034b50,4);word(h,45,2);word(h,0x808,2);word(h,0,2);word(h,e.time,2);word(h,e.date,2);word(h,0,4);word(h,0xffffffff,4);word(h,0xffffffff,4);word(h,e.name.size(),2);word(h,20,2);h+=e.name;word(h,1,2);word(h,16,2);word(h,e.size,8);word(h,e.size,8);if(!emit(h))return false;
                uint64_t at=0;uint32_t crc=0xffffffff;while(at<e.size){auto n=e.file->read(at,buffer.data(),static_cast<size_t>(std::min<uint64_t>(buffer.size(),e.size-at)));if(!n)return false;crc=crc32(crc,buffer.data(),n);if(!write(buffer.data(),n))return false;at+=n;}e.crc=crc^0xffffffff;
                h.clear();word(h,0x08074b50,4);word(h,e.crc,4);word(h,e.size,8);word(h,e.size,8);if(!emit(h))return false;
            }
            auto directory=position;
            for(const auto& e:*entries){std::string h;word(h,0x02014b50,4);word(h,45,2);word(h,45,2);word(h,0x808,2);word(h,0,2);word(h,e.time,2);word(h,e.date,2);word(h,e.crc,4);word(h,0xffffffff,4);word(h,0xffffffff,4);word(h,e.name.size(),2);word(h,28,2);word(h,0,2);word(h,0,2);word(h,0,2);word(h,0,4);word(h,0xffffffff,4);h+=e.name;word(h,1,2);word(h,24,2);word(h,e.size,8);word(h,e.size,8);word(h,e.offset,8);if(!emit(h))return false;}
            auto directorySize=position-directory,zip64=position;std::string h;word(h,0x06064b50,4);word(h,44,8);word(h,45,2);word(h,45,2);word(h,0,4);word(h,0,4);word(h,entries->size(),8);word(h,entries->size(),8);word(h,directorySize,8);word(h,directory,8);word(h,0x07064b50,4);word(h,0,4);word(h,zip64,8);word(h,1,4);word(h,0x06054b50,4);word(h,0,2);word(h,0,2);word(h,0xffff,2);word(h,0xffff,2);word(h,0xffffffff,4);word(h,0xffffffff,4);word(h,0,2);if(!emit(h))return false;sink.done();return true;
        }catch(...){return false;}
    });
}
}
