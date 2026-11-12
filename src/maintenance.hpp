std::string decodeEnvelope(const std::string& text){DWORD size=0;if(!CryptStringToBinaryA(text.c_str(),static_cast<DWORD>(text.size()),CRYPT_STRING_BASE64,nullptr,&size,nullptr,nullptr)||size>12000)throw Error(400,"INVALID_ENVELOPE","资源清单编码无效");std::string decoded(size,'\0');if(!CryptStringToBinaryA(text.c_str(),static_cast<DWORD>(text.size()),CRYPT_STRING_BASE64,reinterpret_cast<BYTE*>(decoded.data()),&size,nullptr,nullptr))throw Error(400,"INVALID_ENVELOPE","资源清单编码无效");decoded.resize(size);return decoded;}
// Included inside server.cpp's anonymous namespace after the common HTTP helpers.
std::mutex maintenanceMutex;
fs::path maintenanceRoot(Store& store){auto root=store.config.data/"maintenance";fs::create_directories(root/"packages");fs::create_directories(root/"reports");return root;}
void replaceClientJson(const fs::path& target,const json& value){
    auto temp=target.parent_path()/(randomHex()+".json.tmp");
    try{writeText(temp,value.dump());if(!MoveFileExW(temp.c_str(),target.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Cannot atomically publish client metadata");}
    catch(...){std::error_code e;fs::remove(temp,e);throw;}
}
uint64_t clientReleaseNumber(const json& value,bool apk){return apk?number(value,"versionCode"):number(json::parse(decodeEnvelope(text(value,"payload",12000))),"revision");}
void importBundledClient(Store& store,const fs::path& source,bool apk){
    const std::string kind=apk?"apk":"ui";auto manifest=source/(kind+".json");if(!fs::exists(manifest))return;
    auto input=json::parse(readText(manifest,65536));auto metadata=apk?input:json::parse(decodeEnvelope(text(input,"payload",12000)));
    auto code=clientReleaseNumber(input,apk);auto hash=text(metadata,"sha256",64);auto size=number(metadata,"size");
    if(!code||code>2147483647||hash.size()!=64||hash.find_first_not_of("0123456789abcdef")!=std::string::npos||!size||size>(apk?128ULL:20ULL)*1024*1024)throw std::runtime_error("Invalid bundled client metadata");
    if(apk){text(input,"versionName",64);text(input,"notes",4096,true);}else{text(input,"signature",2048);auto native=number(metadata,"minNative");if(!native||native>12)throw std::runtime_error("Unsupported bundled UI protocol");}
    auto root=maintenanceRoot(store),catalog=root/(kind+".json"),withdrawn=root/(kind+"-withdrawn.json");
    if(fs::exists(withdrawn)&&code<=number(json::parse(readText(withdrawn,65536)),"version")){consoleText("Bundled "+kind+": retained administrator withdrawal.\n");return;}
    bool sameVersion=false;
    if(fs::exists(catalog)){
        auto current=json::parse(readText(catalog,65536));auto currentCode=clientReleaseNumber(current,apk);
        if(currentCode>code){consoleText("Bundled "+kind+": retained newer published version.\n");return;}
        if(currentCode==code){auto currentMeta=apk?current:json::parse(decodeEnvelope(text(current,"payload",12000)));if(text(currentMeta,"sha256",64)!=hash)throw std::runtime_error("Same client version has different content");sameVersion=true;}
    }
    const auto extension=apk?".apk":".zip";auto file=source/(hash+extension);
    // Only the fixed release directory is trusted, never symlinked files outside it.
    if(fs::weakly_canonical(file).parent_path()!=fs::weakly_canonical(source)||!fs::is_regular_file(file)||fs::file_size(file)!=size||hashFile(file)!=hash)throw std::runtime_error("Bundled client file missing or checksum/size mismatch");
    auto target=root/"packages"/(hash+extension);
    if(!fs::exists(target)){
        auto temporary=root/"packages"/(randomHex()+".part");
        try{fs::copy_file(file,temporary);if(fs::file_size(temporary)!=size||hashFile(temporary)!=hash)throw std::runtime_error("Bundled client changed while copying");if(!MoveFileExW(temporary.c_str(),target.c_str(),MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Cannot install bundled client file");}
        catch(...){std::error_code e;fs::remove(temporary,e);throw;}
    }else if(fs::file_size(target)!=size||hashFile(target)!=hash)throw std::runtime_error("Published client file checksum mismatch");
    if(sameVersion){consoleText("Bundled "+kind+": already available; skipped import.\n");return;}
    replaceClientJson(catalog,input);consoleText("Bundled "+kind+": automatically published version "+std::to_string(code)+".\n");
}
void importBundledClients(Store& store){
    auto source=executableDirectory()/"client-release";std::error_code ec;bool found=fs::exists(source,ec);if(ec){consoleText("Cannot inspect bundled clients; NAS startup continues.\n",true);return;}if(!found)return;
    for(bool apk:{true,false})try{importBundledClient(store,source,apk);}catch(const std::exception& e){consoleText(std::string("Bundled ")+(apk?"apk":"ui")+" not published: "+e.what()+"; NAS startup continues.\n",true);}
}
json clientCatalog(Store& store){
    std::lock_guard<std::mutex> lock(maintenanceMutex);auto root=maintenanceRoot(store);json result={{"apk",nullptr},{"ui",nullptr}};
    for(const auto& kind:{"apk","ui"}){auto path=root/(std::string(kind)+".json"),marker=root/(std::string(kind)+"-withdrawn.json");if(fs::exists(path)){auto value=json::parse(readText(path,65536));if(!fs::exists(marker)||clientReleaseNumber(value,std::string(kind)=="apk")>number(json::parse(readText(marker,65536)),"version"))result[kind]=value;}}return result;
}
void maintenanceRoutes(httplib::Server& server,Store& store){
    server.Get("/api/v1/client/latest",[&store](const auto&,auto& response){reply(response,clientCatalog(store));});
    server.Get("/api/v1/client/packages/([a-f0-9]{64})\\.(apk|zip)",[&store](const auto& request,auto& response){
        auto name=request.matches[1].str()+"."+request.matches[2].str();auto path=maintenanceRoot(store)/"packages"/name;
        if(!fs::is_regular_file(path))throw Error(404,"PACKAGE_MISSING","更新文件尚未发布");
        serveFile(request,response,path,request.matches[2]=="apk"?"application/vnd.android.package-archive":"application/zip",name,true);
    });
    server.Post("/api/v1/admin/client/(apk|ui)/blob",[&store](const httplib::Request& request,httplib::Response& response,const httplib::ContentReader& reader){
        admin(store.authenticate(token(request)));auto root=maintenanceRoot(store);auto temporary=root/"packages"/(randomHex()+".part");
        struct Cleanup{fs::path path;~Cleanup(){std::error_code e;fs::remove(path,e);}}cleanup{temporary};
        const uint64_t limit=request.matches[1]=="apk"?128ULL*1024*1024:20ULL*1024*1024;uint64_t count=0;
        {File output(temporary,true,true);bool ok=reader([&](const char* data,size_t size){if(size>limit-count)return false;output.write(count,data,size);count+=size;return true;});if(!ok||!count)throw Error(413,"PACKAGE_SIZE","文件为空或超过更新包大小限制");output.flush();}
        auto hash=hashFile(temporary);auto extension=request.matches[1]=="apk"?".apk":".zip";auto dest=root/"packages"/(hash+extension);
        {std::lock_guard<std::mutex> lock(maintenanceMutex);if(!fs::exists(dest))fs::rename(temporary,dest);}
        reply(response,{{"sha256",hash},{"size",std::to_string(count)}});
    });
    server.Post("/api/v1/admin/client/(apk|ui)/publish",[&store](const auto& request,auto& response){
        admin(store.authenticate(token(request)));auto input=body(request);bool apk=request.matches[1]=="apk";auto root=maintenanceRoot(store);std::string hash;
        if(apk){hash=text(input,"sha256",64);auto code=number(input,"versionCode");if(!code||code>2147483647)throw Error(400,"INVALID_VERSION","versionCode无效");text(input,"versionName",64);text(input,"notes",4096,true);}
        else{auto envelope=input;auto encoded=text(envelope,"payload",12000);text(envelope,"signature",2048);auto decoded=decodeEnvelope(encoded);auto manifest=json::parse(decoded);hash=text(manifest,"sha256",64);if(number(manifest,"revision")==0||number(manifest,"minNative")==0||number(manifest,"minNative")>12)throw Error(400,"INVALID_VERSION","资源包协议或版本不支持");}
        if(hash.size()!=64||hash.find_first_not_of("0123456789abcdef")!=std::string::npos)throw Error(400,"INVALID_HASH","更新包摘要无效");
        auto file=root/"packages"/(hash+(apk?".apk":".zip"));if(!fs::exists(file))throw Error(404,"PACKAGE_MISSING","先上传对应安装包或资源包");
        if(apk)input["size"]=std::to_string(fs::file_size(file));
        else{auto manifest=json::parse(decodeEnvelope(input["payload"].template get<std::string>()));if(number(manifest,"size")!=fs::file_size(file))throw Error(400,"PACKAGE_SIZE","资源包大小不匹配");}
        std::lock_guard<std::mutex> lock(maintenanceMutex);auto target=root/(apk?"apk.json":"ui.json");auto temp=root/(randomHex()+".json.tmp");writeText(temp,input.dump());
        if(!MoveFileExW(temp.c_str(),target.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)){std::error_code e;fs::remove(temp,e);throw Error(500,"PUBLISH_FAILED","发布失败");}fs::remove(root/(apk?"apk-withdrawn.json":"ui-withdrawn.json"));reply(response,{{"published",true}});
    });
    server.Delete("/api/v1/admin/client/(apk|ui)",[&store](const auto& request,auto& response){
        admin(store.authenticate(token(request)));std::lock_guard<std::mutex> lock(maintenanceMutex);auto kind=request.matches[1].str();auto root=maintenanceRoot(store);auto catalog=root/(kind+".json"),marker=root/(kind+"-withdrawn.json");
        uint64_t version=0;if(fs::exists(marker))version=number(json::parse(readText(marker,65536)),"version");
        if(fs::exists(catalog))version=std::max(version,clientReleaseNumber(json::parse(readText(catalog,65536)),kind=="apk"));
        replaceClientJson(marker,{{"version",version}});fs::remove(catalog);reply(response,{{"withdrawn",true}});
    });
    server.Post("/api/v1/diagnostics",[&store](const httplib::Request& request,httplib::Response& response,const httplib::ContentReader& reader){
        auto who=store.authenticate(token(request));auto root=maintenanceRoot(store);auto id=randomHex();auto temporary=root/"reports"/(id+".part");uint64_t count=0;
        struct Cleanup{fs::path path;~Cleanup(){std::error_code e;fs::remove(path,e);}}cleanup{temporary};
        {File output(temporary,true,true);bool ok=reader([&](const char* data,size_t size){if(size>16ULL*1024*1024-count)return false;output.write(count,data,size);count+=size;return true;});if(!ok||!count)throw Error(413,"REPORT_SIZE","诊断包为空或超过16MiB");output.flush();}
        auto note=request.get_header_value("X-NAS-Note-Base64");if(note.size()>2048)throw Error(400,"NOTE_SIZE","问题描述过长");
        json info={{"description",note.empty()?std::string{}:decodeEnvelope(note)},{"id",id},{"user",who.username},{"created",std::to_string(millis())},{"bytes",std::to_string(count)}};
        fs::rename(temporary,root/"reports"/(id+".zip"));writeText(root/"reports"/(id+".json"),info.dump());reply(response,info,201);
    });
    server.Get("/api/v1/admin/diagnostics",[&store](const auto& request,auto& response){admin(store.authenticate(token(request)));auto root=maintenanceRoot(store)/"reports";json items=json::array();for(const auto& file:fs::directory_iterator(root)){if(items.size()>=500)break;if(file.path().extension()==".json")items.push_back(json::parse(readText(file.path(),65536)));}reply(response,items);});
    server.Get("/api/v1/admin/diagnostics/([a-f0-9]{48})",[&store](const auto& request,auto& response){admin(store.authenticate(token(request)));auto path=maintenanceRoot(store)/"reports"/(request.matches[1].str()+".zip");if(!fs::exists(path))throw Error(404,"REPORT_MISSING","诊断报告不存在");serveFile(request,response,path,"application/zip","diagnostics.zip",true);});
    server.Delete("/api/v1/admin/diagnostics/([a-f0-9]{48})",[&store](const auto& request,auto& response){admin(store.authenticate(token(request)));auto root=maintenanceRoot(store)/"reports";fs::remove(root/(request.matches[1].str()+".zip"));fs::remove(root/(request.matches[1].str()+".json"));reply(response,{{"deleted",true}});});
}
