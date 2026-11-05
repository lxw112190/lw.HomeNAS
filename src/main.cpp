#include "service.hpp"
#include <windows.h>
#include <iostream>

namespace {
std::string readPassword() {
    HANDLE input=GetStdHandle(STD_INPUT_HANDLE);DWORD mode=0;
    if(!GetConsoleMode(input,&mode)){std::string value;if(!std::getline(std::cin,value))throw std::runtime_error("Password input cancelled");if(!value.empty()&&value.back()=='\r')value.pop_back();return value;}
    struct Restore{HANDLE handle;DWORD mode;~Restore(){SetConsoleMode(handle,mode);}}restore{input,mode};
    if(!SetConsoleMode(input,mode&~ENABLE_ECHO_INPUT))throw std::runtime_error("Cannot disable console echo");
    wchar_t buffer[1024]{};DWORD count=0;
    if(!ReadConsoleW(input,buffer,1023,&count,nullptr))throw std::runtime_error("Password input cancelled");
    while(count&&(buffer[count-1]==L'\r'||buffer[count-1]==L'\n'))--count;
    int size=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,buffer,count,nullptr,0,nullptr,nullptr);
    if(count&&size<=0){SecureZeroMemory(buffer,sizeof(buffer));throw std::runtime_error("Invalid password encoding");}
    std::string value(static_cast<size_t>(size),'\0');if(size)WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,buffer,count,value.data(),size,nullptr,nullptr);
    SecureZeroMemory(buffer,sizeof(buffer));nas::consoleText("\n");return value;
}
int initializeAdmin(const nas::Config& config) {
    nas::fs::create_directories(config.data);
    HANDLE instance=CreateFileW((config.data/"instance.lock").c_str(),GENERIC_READ|GENERIC_WRITE,0,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(instance==INVALID_HANDLE_VALUE)throw std::runtime_error("请先停止 Windows 服务或前台程序，再设置管理员密码");
    struct Close{HANDLE handle;~Close(){CloseHandle(handle);}}close{instance};
    nas::consoleText("设置本机管理员 admin 密码（不改其他账号，不删除资料）。\n请输入至少6位、方便你输入的密码：");
    auto password=readPassword();nas::consoleText("请再次输入：");auto repeated=readPassword();
    struct Clear{std::string& a;std::string& b;~Clear(){if(!a.empty())SecureZeroMemory(a.data(),a.size());if(!b.empty())SecureZeroMemory(b.data(),b.size());}}clear{password,repeated};
    if(password!=repeated)throw std::runtime_error("两次密码不一致，未修改");
    if(nas::passwordCharacters(password)<6||password.size()>256||password.find_first_of(std::string("\0\r\n",3))!=std::string::npos)throw std::runtime_error("密码至少6个字符、最多256字节，且不含换行，未修改");
    nas::Store store(config,false);
    store.updateUser("administrator",{{"new_password",password},{"role","admin"},{"enabled",true}},nas::Identity{"administrator","admin","本机维护","admin",true});
    nas::consoleText("已设置管理员 admin 密码；旧管理员会话已失效。请启动服务后使用新密码登录。\n");return 0;
}
}

int wmain(int argc,wchar_t** argv) {
    try {
        auto path=nas::executableDirectory()/"config/server.json";std::optional<bool> insecure;bool service=false,adminPassword=false;
        for(int i=1;i<argc;++i){std::wstring arg=argv[i];
            if(arg==L"--config"&&i+1<argc)path=nas::fs::absolute(argv[++i]);
            else if(arg==L"--allow-insecure-lan")insecure=true;
            else if(arg==L"--no-allow-insecure-lan")insecure=false;
            else if(arg==L"--service")service=true;
            else if(arg==L"--initialize-admin")adminPassword=true;
            else if(arg==L"--version"){nas::consoleText(std::string(nas::Version)+"\n");return 0;}
            else if(arg==L"--sha256"&&i+1<argc){nas::consoleText(nas::hashFile(nas::fs::path(argv[++i]))+"\n");return 0;}
            else throw std::runtime_error("Usage: NasServer.exe [--config file] [--service] [--allow-insecure-lan|--no-allow-insecure-lan] [--initialize-admin] [--version] [--sha256 file]");
        }
        if(adminPassword){if(service||insecure.has_value())throw std::runtime_error("--initialize-admin is an offline maintenance command");return initializeAdmin(nas::Config::load(path));}
        if(service)return nas::runWindowsService(path,insecure);
        return nas::runServer(nas::Config::load(path),insecure);
    }catch(const std::exception& error){nas::consoleText(std::string("启动失败：")+error.what()+"\n",true);return 1;}
}
