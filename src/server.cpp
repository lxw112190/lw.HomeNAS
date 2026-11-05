#include "core.hpp"
#include "service.hpp"
#include "httplib.h"
#include <csignal>
#include <iostream>
#include <set>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <ctime>
#include <windows.h>
#include <wincrypt.h>

namespace nas {
namespace {
httplib::Server* httpServer=nullptr;
httplib::Server* tlsServer=nullptr;
Store* runningStore=nullptr;
std::mutex lifecycleMutex;
std::atomic<bool> stopRequested{false};
struct RequestLog {
    fs::path folder;std::mutex mutex;
    explicit RequestLog(const fs::path& data):folder(data/"logs"){}
    static std::string route(const httplib::Request& request){
        const auto& path=request.path;
        static const std::set<std::string> known={"/api/v1/server","/api/v1/login","/api/v1/logout","/api/v1/me","/api/v1/overview","/api/v1/users","/api/v1/password","/api/v1/uploads","/api/v1/tasks","/api/v1/assets","/api/v1/sources/status","/api/v1/certificate"};
        if(known.count(path))return path;
        for(const std::string prefix:{"/api/v1/uploads/","/api/v1/assets/","/api/v1/users/"})if(path.rfind(prefix,0)==0){auto rest=path.substr(prefix.size());auto slash=rest.find('/');auto id=rest.substr(0,slash),suffix=slash==std::string::npos?std::string{}:rest.substr(slash);
            if((id=="administrator"||(id.size()==48&&id.find_first_not_of("0123456789abcdef")==std::string::npos))&&(suffix.empty()||suffix=="/data"||suffix=="/complete"||suffix=="/cancel"||suffix=="/original"||suffix=="/thumbnail"))return path;
        }
        return "[other-route]";
    }
    void write(const std::string& detail) noexcept {
        try{std::lock_guard<std::mutex> guard(mutex);fs::create_directories(folder);auto active=folder/"requests.log";
            if(fs::exists(active)&&fs::file_size(active)>2*1024*1024){std::error_code error;fs::remove(folder/"requests.4.log",error);for(int i=3;i>=1;--i){auto old=folder/("requests."+std::to_string(i)+".log");if(fs::exists(old))fs::rename(old,folder/("requests."+std::to_string(i+1)+".log"));}fs::rename(active,folder/"requests.1.log");}
            auto now=millis();std::time_t seconds=static_cast<std::time_t>(now/1000);std::tm tm{};gmtime_s(&tm,&seconds);char stamp[32];std::strftime(stamp,sizeof(stamp),"%Y-%m-%dT%H:%M:%S",&tm);
            auto message=detail.substr(0,1600);for(auto& ch:message)if(ch=='\r'||ch=='\n'||ch=='\t')ch=' ';
            std::ofstream out(active,std::ios::app|std::ios::binary);out<<stamp<<'.'<<std::setfill('0')<<std::setw(3)<<(now%1000)<<"Z "<<message<<'\n';
        }catch(...){/* Diagnostics must not affect backup state. */}
    }
    void response(const httplib::Request& request,const httplib::Response& response){
        if(request.path.rfind("/api/",0)!=0)return;
        auto trace=request.get_header_value("X-NAS-Trace");if(trace.size()>64||trace.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")!=std::string::npos)trace="invalid";
        auto method=request.method=="GET"||request.method=="POST"||request.method=="PUT"?request.method:"OTHER";
        write("HTTP client="+request.remote_addr+" trace="+trace+" method="+method+" route="+route(request)+" status="+std::to_string(response.status));
    }
};
BOOL WINAPI stopConsole(DWORD event) {
    if(event!=CTRL_C_EVENT&&event!=CTRL_BREAK_EVENT&&event!=CTRL_CLOSE_EVENT&&event!=CTRL_SHUTDOWN_EVENT)return FALSE;
    requestServerStop();return TRUE;
}
void reply(httplib::Response& response,const json& data,int status=200) { response.status=status;response.set_content(data.dump(),"application/json; charset=utf-8");response.set_header("Cache-Control","no-store"); }
std::string token(const httplib::Request& request) {
    auto auth=request.get_header_value("Authorization");if(auth.rfind("Bearer ",0)==0)return auth.substr(7);
    auto cookie=request.get_header_value("Cookie");if(cookie.size()>8192)return {};
    size_t start=0;while(start<cookie.size()){auto end=cookie.find(';',start);auto part=cookie.substr(start,end==std::string::npos?end:end-start);auto begin=part.find_first_not_of(' ');if(begin!=std::string::npos)part.erase(0,begin);if(part.rfind("nas_session=",0)==0)return part.substr(12);if(end==std::string::npos)break;start=end+1;}return {};
}
json body(const httplib::Request& request) {
    if(request.body.size()>16384)throw Error(413,"JSON_TOO_LARGE","请求信息过长");
    if(request.get_header_value("Content-Type").rfind("application/json",0)!=0)throw Error(415,"JSON_REQUIRED","此接口需要 JSON 请求");
    try{auto value=json::parse(request.body);if(!value.is_object())throw std::runtime_error("object expected");return value;}catch(...){throw Error(400,"INVALID_JSON","JSON 请求格式不正确");}
}
void admin(const Identity& identity){if(!identity.admin)throw Error(403,"ADMIN_REQUIRED","此操作需要电脑管理员权限");}
std::string encode(const std::string& value) {
    const char* digits="0123456789ABCDEF";std::string out;
    for(unsigned char c:value){if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.')out+=static_cast<char>(c);else{out+='%';out+=digits[c>>4];out+=digits[c&15];}}return out;
}
void serveFile(const httplib::Request& request,httplib::Response& response,const fs::path& path,const std::string& type,const std::string& name,bool attachment) {
    if(request.ranges.size()>1)throw Error(416,"SINGLE_RANGE_REQUIRED","仅支持单段范围请求");
    auto file=std::make_shared<File>(path,false);auto size=file->size();
    response.set_header("Accept-Ranges","bytes");response.set_header("Cache-Control","private, no-store");
    if(!name.empty())response.set_header("Content-Disposition",std::string(attachment?"attachment":"inline")+"; filename=\"download\"; filename*=UTF-8''"+encode(name));
    response.set_content_provider(static_cast<size_t>(size),type,[file](size_t offset,size_t length,httplib::DataSink& sink){
        try{std::vector<char> bytes(std::min<size_t>(length,128*1024));auto count=file->read(offset,bytes.data(),bytes.size());return count && sink.write(bytes.data(),count);}catch(...){return false;}
    });
}
struct LoginLimiter {
    std::mutex mutex;std::map<std::string,std::pair<unsigned,uint64_t>> failures;
    void check(const std::string& ip){std::lock_guard<std::mutex> lock(mutex);auto& item=failures[ip];if(millis()-item.second>60000)item={0,millis()};if(item.first>=8)throw Error(429,"TRY_LATER","尝试过于频繁，请一分钟后再试");}
    void failed(const std::string& ip){std::lock_guard<std::mutex> lock(mutex);if(failures.size()>2048)failures.clear();++failures[ip].first;}
};
#include "maintenance.hpp"
void routes(httplib::Server& server,Store& store,bool https,bool insecureLan,const std::shared_ptr<RequestLog>& logs) {
    auto limiter=std::make_shared<LoginLimiter>();
    server.new_task_queue=[] {return new httplib::ThreadPool(8,16,64);};
    server.set_payload_max_length(static_cast<size_t>(128ULL*1024*1024));
    server.set_read_timeout(30,0);server.set_write_timeout(30,0);server.set_keep_alive_timeout(2);
    server.set_logger([logs](const auto& request,const auto& response){logs->response(request,response);});
    server.set_exception_handler([logs](const httplib::Request& request,httplib::Response& response,std::exception_ptr exception){
        try{if(exception)std::rethrow_exception(exception);}catch(const Error& error){logs->write("API error route="+RequestLog::route(request)+" code="+error.code+" status="+std::to_string(error.status));reply(response,{{"code",error.code},{"message",error.what()}},error.status);}
        catch(const std::exception& error){logs->write("API internal error route="+RequestLog::route(request)+" reason="+error.what());reply(response,{{"code","INTERNAL_ERROR"},{"message","操作失败，请检查磁盘空间与目录权限；任务状态已保留"}},500);}
    });
    server.set_pre_routing_handler([&store,https](const httplib::Request& request,httplib::Response& response){
        response.set_header("X-Content-Type-Options","nosniff");response.set_header("Referrer-Policy","no-referrer");response.set_header("X-Frame-Options","DENY");
        response.set_header("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        auto origin=request.get_header_value("Origin"),host=request.get_header_value("Host");
        auto allowed=ipv4Addresses();bool hostOK=store.config.allowExternalHosts;
        for(const auto& ip:allowed)if(host==ip+":"+std::to_string(https?store.config.httpsPort:store.config.httpPort))hostOK=true;
        if(host=="localhost:"+std::to_string(https?store.config.httpsPort:store.config.httpPort))hostOK=true;
        if(!hostOK){reply(response,{{"code","HOST_REJECTED"},{"message","请使用电脑显示的地址；公网测试可在配置中设置 allow_external_hosts 为 true 后重启"}},400);return httplib::Server::HandlerResponse::Handled;}
        if(!origin.empty()&&origin!=(std::string(https?"https://":"http://")+host)){
            reply(response,{{"code","ORIGIN_REJECTED"},{"message","不接受来自其他网站的请求"}},403);return httplib::Server::HandlerResponse::Handled;
        }
        if(request.path.rfind("/api/",0)==0 && request.path!="/api/v1/server" && request.path!="/api/v1/login" && request.path!="/api/v1/client/latest" && request.path.rfind("/api/v1/client/packages/",0)!=0){
            try{store.authenticate(token(request));}catch(const Error& e){reply(response,{{"code",e.code},{"message",e.what()}},e.status);return httplib::Server::HandlerResponse::Handled;}
        }
        return httplib::Server::HandlerResponse::Unhandled;
    });
    server.set_error_handler([](const httplib::Request& request,httplib::Response& response){if(request.path.rfind("/api/",0)==0&&response.body.empty())reply(response,{{"code","HTTP_ERROR"},{"message","请求未能完成"}},response.status);});
    server.Get("/api/v1/server",[&store,insecureLan,https](const auto&,auto& response){reply(response,{{"name",store.config.name},{"server_id",store.serverId},{"version",Version},{"https",https},{"insecure_lan",!https&&insecureLan},{"allow_external_hosts",store.config.allowExternalHosts},{"backup_folders",true},{"automatic_backup",false},{"member_mode","shared_family_accounts"},{"authentication","username_password"}});});
    auto session=[https](httplib::Response& response,const json& data){
        response.set_header("Set-Cookie","nas_session="+data["token"].template get<std::string>()+"; Path=/; HttpOnly; SameSite=Strict; Max-Age="+(data["admin"].template get<bool>()?"86400":"604800")+(https?"; Secure":""));
    };
    server.Post("/api/v1/login",[&store,limiter,session](const auto& request,auto& response){
        limiter->check(request.remote_addr);auto input=body(request);
        try{auto data=store.login(input);session(response,data);if(input.value("client",std::string{})!="native")data.erase("token");reply(response,data);}catch(const Error&){limiter->failed(request.remote_addr);throw;}
    });
    server.Get("/api/v1/me",[&store](const auto& request,auto& response){auto who=store.authenticate(token(request));reply(response,{{"user_id",who.userId},{"username",who.username},{"display_name",who.displayName},{"role",who.role},{"admin",who.admin},{"can_upload",who.canUpload()}});});
    server.Post("/api/v1/logout",[&store,https](const auto& request,auto& response){body(request);auto t=token(request);store.db.exec("DELETE FROM credentials WHERE hash=?",{sha256(t.data(),t.size())});response.set_header("Set-Cookie",std::string("nas_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")+(https?"; Secure":""));reply(response,{{"ok",true}});});
    server.Get("/api/v1/overview",[&store](const auto& request,auto& response){reply(response,store.overview(store.authenticate(token(request))));});
    server.Get("/api/v1/users",[&store](const auto& request,auto& response){reply(response,store.users(store.authenticate(token(request))));});
    server.Post("/api/v1/users",[&store](const auto& request,auto& response){reply(response,store.createUser(body(request),store.authenticate(token(request))),201);});
    server.Post("/api/v1/users/([a-f0-9]{48}|administrator)",[&store](const auto& request,auto& response){reply(response,store.updateUser(request.matches[1],body(request),store.authenticate(token(request))));});
    server.Post("/api/v1/password",[&store](const auto& request,auto& response){store.changePassword(body(request),store.authenticate(token(request)));reply(response,{{"ok",true}});});
    server.Post("/api/v1/sources/status",[&store](const auto& request,auto& response){reply(response,store.sourceStatus(body(request),store.authenticate(token(request))));});
    server.Post("/api/v1/uploads",[&store](const auto& request,auto& response){reply(response,store.createUpload(body(request),store.authenticate(token(request))),201);});
    server.Get("/api/v1/uploads/([a-f0-9]{48})",[&store](const auto& request,auto& response){reply(response,store.upload(request.matches[1],store.authenticate(token(request))));});
    server.Put("/api/v1/uploads/([a-f0-9]{48})/data",[&store](const httplib::Request& request,httplib::Response& response,const httplib::ContentReader& reader){
        if(store.activeUploads.fetch_add(1)>=4){store.activeUploads.fetch_sub(1);throw Error(429,"UPLOAD_LIMIT","上传槽位已满，请稍后继续");}
        struct Release{std::atomic<unsigned>& counter;~Release(){counter.fetch_sub(1);}}release{store.activeUploads};
        auto header=json{{"offset",request.get_header_value("X-Upload-Offset")},{"size",request.get_header_value("Content-Length")}};
        reply(response,store.writeChunk(request.matches[1],store.authenticate(token(request)),number(header,"offset"),number(header,"size"),request.get_header_value("X-Chunk-SHA256"),[&](auto receiver){return reader(receiver);}));
    });
    server.Post("/api/v1/uploads/([a-f0-9]{48})/complete",[&store](const auto& request,auto& response){body(request);reply(response,store.complete(request.matches[1],store.authenticate(token(request))),202);});
    server.Post("/api/v1/uploads/([a-f0-9]{48})/cancel",[&store](const auto& request,auto& response){body(request);reply(response,store.cancel(request.matches[1],store.authenticate(token(request))));});
    server.Get("/api/v1/tasks",[&store](const auto& request,auto& response){reply(response,store.tasks(store.authenticate(token(request))));});
    server.Get("/api/v1/tasks/page",[&store](const auto& request,auto& response){reply(response,store.taskPage(store.authenticate(token(request)),request.get_param_value("before"),request.get_param_value("filter")));});
    server.Get("/api/v1/albums",[&store](const auto& request,auto& response){reply(response,store.albums(request.get_param_value("before"),store.authenticate(token(request))));});
    server.Get("/api/v1/folders",[&store](const auto& request,auto& response){reply(response,store.folders(request.get_param_value("before"),store.authenticate(token(request))));});
    server.Post("/api/v1/folders",[&store](const auto& request,auto& response){reply(response,store.changeFolder("","create",body(request),store.authenticate(token(request))),201);});
    server.Get("/api/v1/folders/(default|[a-f0-9]{48})",[&store](const auto& request,auto& response){reply(response,store.folder(request.matches[1],store.authenticate(token(request))));});
    server.Get("/api/v1/folders/(default|[a-f0-9]{48})/items",[&store](const auto& request,auto& response){reply(response,store.folderItems(request.matches[1],request.get_param_value("before"),request.get_param_value("type"),request.get_param_value("q"),store.authenticate(token(request))));});
    server.Post("/api/v1/folders/(default|[a-f0-9]{48})/(edit|delete|add|remove|move)",[&store](const auto& request,auto& response){reply(response,store.changeFolder(request.matches[1],request.matches[2],body(request),store.authenticate(token(request))));});
    server.Post("/api/v1/albums",[&store](const auto& request,auto& response){reply(response,store.changeAlbum("","create",body(request),store.authenticate(token(request))),201);});
    server.Get("/api/v1/albums/([a-f0-9]{48})/items",[&store](const auto& request,auto& response){reply(response,store.albumItems(request.matches[1],request.get_param_value("before"),request.get_param_value("type"),request.get_param_value("q"),store.authenticate(token(request))));});
    server.Post("/api/v1/albums/([a-f0-9]{48})/(edit|delete|add|remove|cover)",[&store](const auto& request,auto& response){reply(response,store.changeAlbum(request.matches[1],request.matches[2],body(request),store.authenticate(token(request))));});
    server.Get("/api/v1/trash",[&store](const auto& request,auto& response){reply(response,store.recycled(request.get_param_value("before"),request.get_param_value("type"),request.get_param_value("q"),store.authenticate(token(request))));});
    server.Post("/api/v1/assets/trash",[&store](const auto& request,auto& response){reply(response,store.recycle(body(request),store.authenticate(token(request)),false));});
    server.Post("/api/v1/assets/restore",[&store](const auto& request,auto& response){reply(response,store.recycle(body(request),store.authenticate(token(request)),true));});
    server.Get("/api/v1/assets/([a-f0-9]{48})/folders",[&store](const auto& request,auto& response){store.asset(request.matches[1]);auto who=store.authenticate(token(request));json items=json::array();for(auto row:store.db.query("SELECT f.id FROM folder_items i JOIN folders f ON f.id=i.folder_id WHERE i.asset_id=? AND f.deleted=0",{request.matches[1]}))items.push_back(store.folder(row["id"],who));reply(response,{{"items",items},{"next",""}});});
    server.Post("/api/v1/assets/([a-f0-9]{48})/rename",[&store](const auto& request,auto& response){reply(response,store.renameAsset(request.matches[1],body(request),store.authenticate(token(request))));});
    server.Get("/api/v1/assets",[&store](const auto& request,auto& response){auto search=request.get_param_value("q");if(search.size()>256)throw Error(400,"SEARCH_TOO_LONG","搜索文字过长");auto result=store.assets(request.get_param_value("before"),request.get_param_value("type"),search,request.has_param("limit")?static_cast<int>(std::min<uint64_t>(number(json{{"limit",request.get_param_value("limit")}},"limit"),101)):60,request.has_param("order")?request.get_param_value("order"):"created");store.assetPermissions(result["items"],store.authenticate(token(request)));reply(response,result);});
    server.Get("/api/v1/assets/([a-f0-9]{48})/original",[&store](const auto& request,auto& response){auto row=store.asset(request.matches[1]);auto type=row["mime"].template get<std::string>();serveFile(request,response,store.originalPath(row),type,row["name"],request.get_param_value("download")=="1"||type=="application/octet-stream");});
    server.Get("/api/v1/assets/([a-f0-9]{48})/metadata",[&store](const auto& request,auto& response){auto row=store.asset(request.matches[1]);json details={{"id",row["id"]},{"name",row["name"]},{"mime",row["mime"]},{"size",row["size"]},{"sha256",row["sha256"]},{"modified",row["modified"]},{"created",row["created"]},{"image",nullptr}};if(row["category"]=="image"){try{details["image"]=imageMetadata(store.originalPath(row));}catch(...){details["image"]={{"available",false},{"exif",json::object()}};}}reply(response,details);});
    server.Get("/api/v1/assets/([a-f0-9]{48})/thumbnail",[&store](const auto& request,auto& response){auto row=store.asset(request.matches[1]);if(row["preview"]!="READY")throw Error(404,"PREVIEW_UNAVAILABLE","缩略图尚未生成或该格式暂不支持预览");serveFile(request,response,store.config.data/"cache"/(row["id"].template get<std::string>()+".jpg"),"image/jpeg","",false);});
    server.Get("/api/v1/certificate",[&store](const auto& request,auto& response){admin(store.authenticate(token(request)));serveFile(request,response,store.config.data/"server-cert.pem","application/x-pem-file","lw.HomeNAS.crt",true);});
    maintenanceRoutes(server,store);
    for(const auto& name:{"index.html","boot.js","app.js","library-collections.js","photo-viewer.js","style.css","sha256.js","hash-worker.js","views/login.html","views/home.html","views/library.html","views/transfers.html","views/users.html","views/settings.html","views/dialogs.html"}){
        const std::string file=name;auto path=store.config.web/fs::u8path(file);auto route=file=="index.html"?"/":"/"+file;
        server.Get(route,[path,file](const auto&,auto& response){auto type=file.size()>=5&&file.substr(file.size()-5)==".html"?"text/html; charset=utf-8":file=="style.css"?"text/css; charset=utf-8":"text/javascript; charset=utf-8";response.set_content(readText(path,2*1024*1024),type);response.set_header("Cache-Control","no-cache");});
    }
}
}
void requestServerStop() {
    stopRequested=true;std::lock_guard<std::mutex> guard(lifecycleMutex);
    if(runningStore)runningStore->stopping=true;
    if(httpServer)httpServer->stop();if(tlsServer)tlsServer->stop();
}
int runServer(Config config,std::optional<bool> insecureLanOverride,const std::function<void()>& ready) {
    const bool insecureLan=insecureLanOverride.value_or(config.allowInsecureLan);
    extern void initializeTlsThreads(); initializeTlsThreads();
    fs::create_directories(config.data);
    auto lockPath=config.data/"instance.lock";
    HANDLE instance=CreateFileW(lockPath.c_str(),GENERIC_READ|GENERIC_WRITE,0,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(instance==INVALID_HANDLE_VALUE)throw std::runtime_error("This data directory is already in use, or is not writable");
    struct Close{HANDLE h;~Close(){CloseHandle(h);}}close{instance};
    ensureCertificate(config.data);Store store(config);
    auto cert=readText(config.data/"server-cert.pem"),key=readText(config.data/"server-key.pem");
    httplib::SSLServer::PemMemory memory{};memory.cert_pem=cert.c_str();memory.cert_pem_len=cert.size();memory.key_pem=key.c_str();memory.key_pem_len=key.size();
    httplib::SSLServer tls(memory);httplib::Server http;
    if(!tls.is_valid())throw std::runtime_error("TLS initialization failed");
    auto logs=std::make_shared<RequestLog>(config.data);routes(http,store,false,insecureLan,logs);routes(tls,store,true,insecureLan,logs);
    if(!http.bind_to_port(insecureLan?"0.0.0.0":"127.0.0.1",config.httpPort))throw std::runtime_error("Cannot bind HTTP management port");
    if(!tls.bind_to_port(config.host,config.httpsPort))throw std::runtime_error("Cannot bind HTTPS port");
    {std::lock_guard<std::mutex> guard(lifecycleMutex);if(stopRequested)return 0;httpServer=&http;tlsServer=&tls;runningStore=&store;}
    if(!ready)SetConsoleCtrlHandler(stopConsole,TRUE);
    std::ostringstream banner;banner<<"lw.HomeNAS "<<Version<<"\n电脑管理：http://127.0.0.1:"<<config.httpPort<<"/\n";
    for(const auto& ip:ipv4Addresses())if(ip!="127.0.0.1"){
        banner<<"局域网 HTTPS：https://"<<ip<<":"<<config.httpsPort<<"/\n";
        if(insecureLan)banner<<"局域网 HTTP：http://"<<ip<<":"<<config.httpPort<<"/\n";
    }
    banner<<"数据目录："<<config.data.u8string()<<"\n证书 SHA256："<<certificateFingerprint(config.data/"server-cert.pem")<<"\n";
    banner<<"默认管理员用户名：admin\n";
    if(fs::exists(config.data/"initial-admin-password.txt")){
        if(!ready)banner<<"首次管理员密码："<<readText(config.data/"initial-admin-password.txt")<<"登录后请修改密码。\n";
        else banner<<"首次管理员密码位于数据目录的 initial-admin-password.txt，不写入服务日志。\n";
    }
    if(insecureLan)banner<<"当前已开启局域网 HTTP，传输未加密；HTTPS 同时保留。\n";
    if(config.allowExternalHosts)banner<<"公网地址测试已开启：接受公网IP或域名Host，不限本机网卡地址；账号登录仍需用户名和密码。\n";
    banner<<"按 Ctrl+C 停止服务；关闭浏览器不影响服务。\n";consoleText(banner.str());
    std::thread local([&]{http.listen_after_bind();});
    if(ready)ready();
    bool result=tls.listen_after_bind();http.stop();if(local.joinable())local.join();
    if(!ready)SetConsoleCtrlHandler(stopConsole,FALSE);
    {std::lock_guard<std::mutex> guard(lifecycleMutex);httpServer=nullptr;tlsServer=nullptr;runningStore=nullptr;}
    return result||stopRequested?0:1;
}
}
