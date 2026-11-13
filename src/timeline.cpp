#include "core.hpp"
#include <ctime>
#include <cstdio>
#include <regex>
#include <set>
namespace nas {
void Store::initializeTimeline(){
    std::set<std::string> columns;for(const auto& row:db.query("PRAGMA table_info(assets)"))columns.insert(row["name"]);
    for(auto pair:{std::pair<const char*,const char*>{"taken","INTEGER NOT NULL DEFAULT 0"},{"taken_text","TEXT NOT NULL DEFAULT ''"},{"taken_zone","TEXT NOT NULL DEFAULT ''"},{"metadata_scanned","INTEGER NOT NULL DEFAULT 0"}})
        if(!columns.count(pair.first))db.exec(std::string("ALTER TABLE assets ADD COLUMN ")+pair.first+" "+pair.second);
    db.exec("UPDATE assets SET metadata_scanned=1 WHERE category<>'image' AND metadata_scanned=0");
    db.exec(std::string("CREATE INDEX IF NOT EXISTS asset_capture_time ON assets(")+TimelineClock+" DESC,id DESC) WHERE state='READY'; CREATE INDEX IF NOT EXISTS asset_category_capture_time ON assets(category,"+TimelineClock+" DESC,id DESC) WHERE state='READY'; CREATE INDEX IF NOT EXISTS asset_metadata_pending ON assets(id) WHERE state='READY' AND category='image' AND metadata_scanned=0;");
}
void Store::updateCaptureTime(const json& row){
    uint64_t time=0;std::string raw,zone;
    try{
        auto metadata=imageMetadata(originalPath(row));auto exif=metadata["exif"];
        if(exif.contains("taken_at")&&exif["taken_at"].is_string()){
            raw=exif["taken_at"];std::smatch match;
            if(std::regex_match(raw,match,std::regex("([0-9]{4}):([0-9]{2}):([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})"))){
                int y=std::stoi(match[1]),m=std::stoi(match[2]),d=std::stoi(match[3]),h=std::stoi(match[4]),n=std::stoi(match[5]),s=std::stoi(match[6]);
                std::tm tm{};tm.tm_year=y-1900;tm.tm_mon=m-1;tm.tm_mday=d;tm.tm_hour=h;tm.tm_min=n;tm.tm_sec=s;tm.tm_isdst=-1;
                if(y>=1970&&y<=2100&&m>=1&&m<=12&&d>=1&&d<=31&&h<24&&n<60&&s<60){
                    auto utc=_mkgmtime64(&tm);std::tm checked{};_gmtime64_s(&checked,&utc);
                    if(utc>=0&&checked.tm_year==y-1900&&checked.tm_mon==m-1&&checked.tm_mday==d){
                        zone=exif.value("utc_offset",std::string{});std::smatch offset;
                        if(std::regex_match(zone,offset,std::regex("([+-])([0-9]{2}):([0-9]{2})"))&&std::stoi(offset[2])<=14&&std::stoi(offset[3])<60&&(std::stoi(offset[2])<14||std::stoi(offset[3])==0)){
                            auto minutes=(std::stoi(offset[2])*60+std::stoi(offset[3]))*(offset[1]=="+"?1:-1);utc-=minutes*60;
                        }else{zone="local";tm.tm_isdst=-1;utc=_mktime64(&tm);}
                        if(utc>0)time=static_cast<uint64_t>(utc)*1000;
                    }
                }
            }
        }
    }catch(...){/* Missing/unsupported EXIF falls back to file time. */}
    if(!time){raw.clear();zone.clear();}
    db.exec("UPDATE assets SET taken=?,taken_text=?,taken_zone=?,metadata_scanned=1 WHERE id=? AND state='READY'",{std::to_string(time),raw,zone,row["id"]});
}
void Store::metadataWorker(){
    while(!stopping){
        try{auto rows=db.query("SELECT * FROM assets WHERE state='READY' AND category='image' AND metadata_scanned=0 ORDER BY id LIMIT 1");
            if(!rows.empty()){auto mutex=sessionMutex("asset:"+rows[0]["id"].get<std::string>());std::lock_guard<std::mutex> guard(*mutex);if(!db.query("SELECT id FROM assets WHERE id=? AND state='READY' AND metadata_scanned=0",{rows[0]["id"]}).empty())updateCaptureTime(rows[0]);continue;}
        }catch(...){if(stopping)return;}
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
}
json Store::calendar(const std::string& type,const std::string& search,int offset){
    if(offset < -840||offset>840||search.size()>256)throw Error(400,"INVALID_CALENDAR","日期参数无效");
    Params args{std::to_string(offset)+" minutes"};std::string sql="SELECT strftime('%Y-%m',"+std::string(TimelineClock)+"/1000,'unixepoch',?) AS month,count(*) AS count FROM assets WHERE state='READY' AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=assets.id)";
    if(type=="image"||type=="video"||type=="file"){sql+=" AND category=?";args.push_back(type);}if(!search.empty()){sql+=" AND name LIKE ?";args.push_back("%"+search+"%");}
    return {{"items",db.query(sql+" GROUP BY month ORDER BY month DESC LIMIT 2000",args)},{"remaining",db.query("SELECT count(*) AS count FROM assets WHERE state='READY' AND category='image' AND metadata_scanned=0")[0]["count"]},{"offset",offset},{"timeline_version",2}};
}
}
