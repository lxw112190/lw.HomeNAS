#include "core.hpp"
#include "shell_icons.hpp"
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <wincodec.h>
#include <wrl/client.h>

namespace nas {
std::string shellIconPng(const std::string& kind){
    // Only synthetic extensions reach the shell; never open an uploaded file or path.
    if(kind.empty()||kind.size()>16||kind.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789")!=std::string::npos)
        throw Error(400,"INVALID_ICON","图标类型无效");
    static std::mutex mutex;static std::map<std::string,std::string> cache;
    std::lock_guard<std::mutex> lock(mutex);auto cached=cache.find(kind);if(cached!=cache.end())return cached->second;
    auto init=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
    struct Apartment {bool active;~Apartment(){if(active)CoUninitialize();}} apartment{SUCCEEDED(init)};
    auto check=[](HRESULT hr){if(FAILED(hr))throw Error(503,"ICON_UNAVAILABLE","系统图标暂不可用");};
    if(FAILED(init)&&init!=RPC_E_CHANGED_MODE)check(init);
    SHFILEINFOW info{};const bool folder=kind=="folder";
    std::wstring name=folder?L"folder":L"file."+std::wstring(kind.begin(),kind.end());
    auto found=SHGetFileInfoW(name.c_str(),folder?FILE_ATTRIBUTE_DIRECTORY:FILE_ATTRIBUTE_NORMAL,&info,sizeof(info),SHGFI_ICON|SHGFI_LARGEICON|SHGFI_USEFILEATTRIBUTES);
    if(!found||!info.hIcon){SHSTOCKICONINFO stock{};stock.cbSize=sizeof(stock);check(SHGetStockIconInfo(folder?SIID_FOLDER:SIID_DOCNOASSOC,SHGSI_ICON|SHGSI_LARGEICON,&stock));info.hIcon=stock.hIcon;}
    struct Icon {HICON value;~Icon(){if(value)DestroyIcon(value);}} icon{info.hIcon};
    using Microsoft::WRL::ComPtr;
    ComPtr<IWICImagingFactory> factory;check(CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&factory)));
    ComPtr<IWICBitmap> bitmap;check(factory->CreateBitmapFromHICON(icon.value,&bitmap));
    ComPtr<IStream> stream;check(CreateStreamOnHGlobal(nullptr,TRUE,&stream));
    ComPtr<IWICBitmapEncoder> encoder;check(factory->CreateEncoder(GUID_ContainerFormatPng,nullptr,&encoder));check(encoder->Initialize(stream.Get(),WICBitmapEncoderNoCache));
    ComPtr<IWICBitmapFrameEncode> frame;check(encoder->CreateNewFrame(&frame,nullptr));check(frame->Initialize(nullptr));
    UINT width=0,height=0;check(bitmap->GetSize(&width,&height));check(frame->SetSize(width,height));
    WICPixelFormatGUID format=GUID_WICPixelFormat32bppBGRA;check(frame->SetPixelFormat(&format));check(frame->WriteSource(bitmap.Get(),nullptr));check(frame->Commit());check(encoder->Commit());
    STATSTG stat{};check(stream->Stat(&stat,STATFLAG_NONAME));if(stat.cbSize.QuadPart>65536)throw Error(503,"ICON_UNAVAILABLE","系统图标过大");
    LARGE_INTEGER start{};check(stream->Seek(start,STREAM_SEEK_SET,nullptr));std::string data(static_cast<size_t>(stat.cbSize.QuadPart),'\0');ULONG read=0;check(stream->Read(data.data(),static_cast<ULONG>(data.size()),&read));if(read!=data.size())throw Error(503,"ICON_UNAVAILABLE","系统图标读取失败");
    if(cache.size()>=256)cache.clear();cache[kind]=data;return data;
}
}
