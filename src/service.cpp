#include "service.hpp"
#include <windows.h>
#include <fstream>
#include <iostream>

namespace nas {
namespace {
constexpr const wchar_t* ServiceName=L"lw.HomeNAS";
fs::path serviceConfig;
SERVICE_STATUS_HANDLE statusHandle=nullptr;
SERVICE_STATUS status{};
std::mutex statusMutex;
std::condition_variable startupCondition;
bool startupPending=true;

void report(DWORD state,DWORD error=NO_ERROR) {
    std::lock_guard<std::mutex> guard(statusMutex);
    status.dwServiceType=SERVICE_WIN32_OWN_PROCESS;status.dwCurrentState=state;
    status.dwControlsAccepted=state==SERVICE_RUNNING?(SERVICE_ACCEPT_STOP|SERVICE_ACCEPT_SHUTDOWN):0;
    status.dwWin32ExitCode=error;status.dwWaitHint=(state==SERVICE_START_PENDING||state==SERVICE_STOP_PENDING)?30000:0;
    status.dwServiceSpecificExitCode=error==ERROR_SERVICE_SPECIFIC_ERROR?1:0;
    status.dwCheckPoint=status.dwWaitHint?status.dwCheckPoint+1:0;
    if(statusHandle)SetServiceStatus(statusHandle,&status);
}
DWORD WINAPI control(DWORD command,DWORD,void*,void*) {
    if(command==SERVICE_CONTROL_STOP||command==SERVICE_CONTROL_SHUTDOWN){report(SERVICE_STOP_PENDING);requestServerStop();return NO_ERROR;}
    if(command==SERVICE_CONTROL_INTERROGATE){std::lock_guard<std::mutex> guard(statusMutex);SetServiceStatus(statusHandle,&status);return NO_ERROR;}
    return ERROR_CALL_NOT_IMPLEMENTED;
}
void WINAPI serviceMain(DWORD,wchar_t**) {
    statusHandle=RegisterServiceCtrlHandlerExW(ServiceName,control,nullptr);if(!statusHandle)return;
    report(SERVICE_START_PENDING);
    std::thread heartbeat([]{
        std::unique_lock<std::mutex> lock(statusMutex);
        while(startupPending){
            if(startupCondition.wait_for(lock,std::chrono::seconds(2),[]{return !startupPending;}))break;
            status.dwCheckPoint++;SetServiceStatus(statusHandle,&status);
        }
    });
    auto finishStartup=[] { {std::lock_guard<std::mutex> guard(statusMutex);startupPending=false;}startupCondition.notify_all(); };
    DWORD exit=NO_ERROR;std::ofstream log;auto* originalOut=std::cout.rdbuf();auto* originalError=std::cerr.rdbuf();
    try {
        auto config=Config::load(serviceConfig);fs::create_directories(config.data/"logs");auto logPath=config.data/"logs/service.log";
        if(fs::exists(logPath)&&fs::file_size(logPath)>4*1024*1024){std::error_code error;fs::remove(config.data/"logs/service.previous.log",error);fs::rename(logPath,config.data/"logs/service.previous.log");}
        log.open(logPath,std::ios::app|std::ios::binary);if(!log)throw std::runtime_error("Cannot open service log");
        std::cout.rdbuf(log.rdbuf());std::cerr.rdbuf(log.rdbuf());std::cout<<"\nService starting: "<<millis()<<std::endl;
        int result=runServer(config,false,[&]{finishStartup();report(SERVICE_RUNNING);});
        if(result!=0)exit=ERROR_SERVICE_SPECIFIC_ERROR;
        std::cout<<"Service stopped: "<<millis()<<std::endl;
    }catch(const std::exception& error){
        std::cerr<<"Service failed: "<<error.what()<<std::endl;exit=ERROR_SERVICE_SPECIFIC_ERROR;
        auto size=MultiByteToWideChar(CP_UTF8,0,error.what(),-1,nullptr,0);std::wstring message(static_cast<size_t>(std::max(1,size)),L'\0');
        if(size>0)MultiByteToWideChar(CP_UTF8,0,error.what(),-1,&message[0],size);
        auto source=RegisterEventSourceW(nullptr,ServiceName);
        if(source){const wchar_t* strings[]={message.c_str()};ReportEventW(source,EVENTLOG_ERROR_TYPE,0,1001,nullptr,1,0,strings,nullptr);DeregisterEventSource(source);}
    }
    finishStartup();if(heartbeat.joinable())heartbeat.join();
    std::cout.rdbuf(originalOut);std::cerr.rdbuf(originalError);if(log.is_open())log.close();report(SERVICE_STOPPED,exit);
}
}
int runWindowsService(const fs::path& config) {
    serviceConfig=fs::absolute(config);
    SERVICE_TABLE_ENTRYW table[]={{const_cast<wchar_t*>(ServiceName),serviceMain},{nullptr,nullptr}};
    if(!StartServiceCtrlDispatcherW(table))throw std::runtime_error("Windows service dispatcher failed ("+std::to_string(GetLastError())+"). Use deploy/install_service.bat and start_service.bat, or run without --service.");
    return status.dwWin32ExitCode==NO_ERROR?0:1;
}
}
