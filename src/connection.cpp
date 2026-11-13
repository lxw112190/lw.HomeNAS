#include "connection.hpp"
#include "../third_party/qrcodegen/qrcodegen.hpp"
#include <set>
#include <regex>
namespace nas {namespace {
std::vector<std::string> addresses(const httplib::Request& request,const Config& config,bool http,bool https){
    std::set<std::string> known;std::vector<std::string> out;auto add=[&](const std::string& value){if(known.insert(value).second)out.push_back(value);};
    auto host=request.get_header_value("Host");if(std::regex_match(host,std::regex("[A-Za-z0-9.-]+:[0-9]{1,5}"))&&host.rfind("127.",0)!=0&&host.rfind("localhost:",0)!=0){add(std::string(https?"https://":"http://")+host);}
    for(const auto& ip:ipv4Addresses())if(ip!="127.0.0.1"){if(http)add("http://"+ip+":"+std::to_string(config.httpPort));if(config.host=="0.0.0.0"||config.host=="::"||config.host==ip)add("https://"+ip+":"+std::to_string(config.httpsPort));}return out;
}
}
void connectionRoutes(httplib::Server& server,Store& store,bool insecureLan,bool https){
    server.Get("/api/v1/connection",[&store,insecureLan,https](const auto& request,auto& response){
        json choices=json::array();for(const auto& url:addresses(request,store.config,insecureLan,https))choices.push_back({{"endpoint",url},{"http",url.rfind("http:",0)==0}});
        response.set_content(json{{"items",choices},{"name",store.config.name},{"server_id",store.serverId},{"credentials_in_qr",false}}.dump(),"application/json; charset=utf-8");response.set_header("Cache-Control","no-store");
    });
    server.Get("/api/v1/connection/qr",[&store,insecureLan,https](const auto& request,auto& response){
        auto endpoint=request.get_param_value("endpoint");auto choices=addresses(request,store.config,insecureLan,https);if(std::find(choices.begin(),choices.end(),endpoint)==choices.end())throw Error(400,"INVALID_CONNECTION","请选择本服务显示的连接地址");
        auto qr=qrcodegen::QrCode::encodeText(endpoint.c_str(),qrcodegen::QrCode::Ecc::MEDIUM);auto size=qr.getSize();std::string svg="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "+std::to_string(size+8)+" "+std::to_string(size+8)+"\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/><path fill=\"black\" d=\"";
        for(int y=0;y<size;++y)for(int x=0;x<size;++x)if(qr.getModule(x,y))svg+="M"+std::to_string(x+4)+","+std::to_string(y+4)+"h1v1h-1z";svg+="\"/></svg>";
        response.set_content(svg,"image/svg+xml");response.set_header("Cache-Control","no-store");
    });
}
}
