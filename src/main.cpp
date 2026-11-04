#include "service.hpp"
#include <windows.h>
#include <iostream>

int wmain(int argc,wchar_t** argv) {
    SetConsoleOutputCP(CP_UTF8);SetConsoleCP(CP_UTF8);
    try {
        auto path=nas::executableDirectory()/"config/server.json";bool insecure=false,service=false;
        for(int i=1;i<argc;++i){std::wstring arg=argv[i];
            if(arg==L"--config"&&i+1<argc)path=nas::fs::absolute(argv[++i]);
            else if(arg==L"--allow-insecure-lan")insecure=true;
            else if(arg==L"--service")service=true;
            else if(arg==L"--version"){std::cout<<nas::Version<<std::endl;return 0;}
            else if(arg==L"--sha256"&&i+1<argc){std::cout<<nas::hashFile(nas::fs::path(argv[++i]))<<std::endl;return 0;}
            else throw std::runtime_error("Usage: NasServer.exe [--config file] [--service] [--allow-insecure-lan] [--version] [--sha256 file]");
        }
        if(service){if(insecure)throw std::runtime_error("Service mode does not allow insecure LAN HTTP");return nas::runWindowsService(path);}
        return nas::runServer(nas::Config::load(path),insecure);
    }catch(const std::exception& error){std::cerr<<"启动失败："<<error.what()<<std::endl;return 1;}
}
