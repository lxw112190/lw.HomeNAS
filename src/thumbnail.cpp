#include "core.hpp"
#include <windows.h>
#include <wincodec.h>
#include <wrl/client.h>
#include <sstream>
#include <iomanip>

namespace nas {
using Microsoft::WRL::ComPtr;
namespace { void check(HRESULT hr) { if(FAILED(hr))throw std::runtime_error("Image preview codec unavailable"); } }
void generateThumbnail(const fs::path& input,const fs::path& output) {
    auto init=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
    struct Cleanup { bool active; ~Cleanup(){if(active)CoUninitialize();} } cleanup{SUCCEEDED(init)};
    if(FAILED(init)&&init!=RPC_E_CHANGED_MODE)check(init);
    ComPtr<IWICImagingFactory> factory;check(CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&factory)));
    ComPtr<IWICBitmapDecoder> decoder;check(factory->CreateDecoderFromFilename(input.c_str(),nullptr,GENERIC_READ,WICDecodeMetadataCacheOnDemand,&decoder));
    ComPtr<IWICBitmapFrameDecode> frame;check(decoder->GetFrame(0,&frame));UINT w=0,h=0;check(frame->GetSize(&w,&h));
    if(!w||!h||w>16384||h>16384||static_cast<uint64_t>(w)*h>80000000)throw std::runtime_error("Image preview exceeds pixel budget");
    ComPtr<IWICBitmapSource> source;check(frame.As(&source));
    unsigned orientation=1;ComPtr<IWICMetadataQueryReader> metadata;
    if(SUCCEEDED(frame->GetMetadataQueryReader(&metadata))){PROPVARIANT v;PropVariantInit(&v);if(SUCCEEDED(metadata->GetMetadataByName(L"/app1/ifd/{ushort=274}",&v))&&v.vt==VT_UI2)orientation=v.uiVal;PropVariantClear(&v);}
    const WICBitmapTransformOptions transforms[]={WICBitmapTransformRotate0,WICBitmapTransformRotate0,WICBitmapTransformFlipHorizontal,WICBitmapTransformRotate180,WICBitmapTransformFlipVertical,
        static_cast<WICBitmapTransformOptions>(WICBitmapTransformRotate270|WICBitmapTransformFlipHorizontal),WICBitmapTransformRotate90,
        static_cast<WICBitmapTransformOptions>(WICBitmapTransformRotate90|WICBitmapTransformFlipHorizontal),WICBitmapTransformRotate270};
    ComPtr<IWICBitmapFlipRotator> rotated;
    if(orientation>=2&&orientation<=8){check(factory->CreateBitmapFlipRotator(&rotated));check(rotated->Initialize(source.Get(),transforms[orientation]));check(rotated.As(&source));check(source->GetSize(&w,&h));}
    double ratio=std::min(1.0,640.0/std::max(w,h));UINT width=std::max(1U,static_cast<UINT>(w*ratio)),height=std::max(1U,static_cast<UINT>(h*ratio));
    ComPtr<IWICBitmapScaler> scaler;check(factory->CreateBitmapScaler(&scaler));check(scaler->Initialize(source.Get(),width,height,WICBitmapInterpolationModeFant));
    ComPtr<IWICFormatConverter> converted;check(factory->CreateFormatConverter(&converted));check(converted->Initialize(scaler.Get(),GUID_WICPixelFormat24bppBGR,WICBitmapDitherTypeNone,nullptr,0,WICBitmapPaletteTypeCustom));
    auto temporary=output;temporary+=L".tmp";
    try {
        ComPtr<IWICStream> stream;check(factory->CreateStream(&stream));check(stream->InitializeFromFilename(temporary.c_str(),GENERIC_WRITE));
        ComPtr<IWICBitmapEncoder> encoder;check(factory->CreateEncoder(GUID_ContainerFormatJpeg,nullptr,&encoder));check(encoder->Initialize(stream.Get(),WICBitmapEncoderNoCache));
        ComPtr<IWICBitmapFrameEncode> result;ComPtr<IPropertyBag2> options;check(encoder->CreateNewFrame(&result,&options));check(result->Initialize(options.Get()));check(result->SetSize(width,height));
        WICPixelFormatGUID pixelFormat=GUID_WICPixelFormat24bppBGR;check(result->SetPixelFormat(&pixelFormat));check(result->WriteSource(converted.Get(),nullptr));check(result->Commit());check(encoder->Commit());
        result.Reset();encoder.Reset();stream.Reset();
        File flushed(temporary,true);flushed.flush();
    }catch(...){std::error_code error;fs::remove(temporary,error);throw;}
    if(!MoveFileExW(temporary.c_str(),output.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)){std::error_code ec;fs::remove(temporary,ec);throw std::runtime_error("Cannot publish image preview");}
}
json imageMetadata(const fs::path& input) {
    auto init=CoInitializeEx(nullptr,COINIT_MULTITHREADED);struct Cleanup{bool active;~Cleanup(){if(active)CoUninitialize();}}cleanup{SUCCEEDED(init)};
    if(FAILED(init)&&init!=RPC_E_CHANGED_MODE)check(init);
    ComPtr<IWICImagingFactory> factory;check(CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&factory)));
    ComPtr<IWICBitmapDecoder> decoder;check(factory->CreateDecoderFromFilename(input.c_str(),nullptr,GENERIC_READ,WICDecodeMetadataCacheOnDemand,&decoder));
    ComPtr<IWICBitmapFrameDecode> frame;check(decoder->GetFrame(0,&frame));UINT width=0,height=0;check(frame->GetSize(&width,&height));
    json result={{"available",true},{"raw_width",width},{"raw_height",height},{"exif",json::object()}};ComPtr<IWICMetadataQueryReader> reader;
    unsigned orientation=1;
    if(SUCCEEDED(frame->GetMetadataQueryReader(&reader))) {
        auto value=[&](unsigned tag,bool exif)->json {
            for(const auto* root:{L"/app1/ifd",L"/ifd"}) {
                std::wstring path=root;if(exif)path+=L"/exif";path+=L"/{ushort="+std::to_wstring(tag)+L"}";
                PROPVARIANT v;PropVariantInit(&v);json out=nullptr;
                if(SUCCEEDED(reader->GetMetadataByName(path.c_str(),&v))) {
                    if(v.vt==VT_LPSTR&&v.pszVal){auto raw=std::string(v.pszVal,strnlen_s(v.pszVal,256));out=json::parse(json(raw).dump(-1,' ',false,json::error_handler_t::replace));}
                    else if(v.vt==VT_UI2)out=v.uiVal;else if(v.vt==VT_UI4)out=v.ulVal;
                    else if(v.vt==VT_UI8){auto packed=v.uhVal.QuadPart;auto numerator=static_cast<uint32_t>(packed),denominator=static_cast<uint32_t>(packed>>32);if(denominator)out=static_cast<double>(numerator)/denominator;}
                    else if(v.vt==(VT_VECTOR|VT_UI2)&&v.caui.cElems&&v.caui.pElems)out=v.caui.pElems[0];
                }
                PropVariantClear(&v);if(!out.is_null())return out;
            }return nullptr;
        };
        auto orientationValue=value(274,false);if(orientationValue.is_number_unsigned())orientation=orientationValue.get<unsigned>();
        for(auto pair:{std::pair<const char*,unsigned>{"camera_make",271},{"camera_model",272},{"software",305}}){auto v=value(pair.second,false);if(!v.is_null())result["exif"][pair.first]=v;}
        for(auto pair:{std::pair<const char*,unsigned>{"taken_at",36867},{"exposure_seconds",33434},{"f_number",33437},{"iso",34855},{"focal_length_mm",37386},{"lens_model",42036}}){auto v=value(pair.second,true);if(!v.is_null())result["exif"][pair.first]=v;}
    }
    if(orientation>=5&&orientation<=8)std::swap(width,height);
    result["width"]=width;result["height"]=height;result["orientation"]=orientation;return result;
}
namespace {
std::wstring argument(const std::wstring& value) {
    std::wstring out=L"\"";size_t slashes=0;for(auto c:value){if(c==L'\\'){++slashes;continue;}if(c==L'"'){out.append(slashes*2+1,L'\\');out+=c;}else{out.append(slashes,L'\\');out+=c;}slashes=0;}out.append(slashes*2,L'\\');return out+L'"';
}
struct NativeHandle{HANDLE h=nullptr;~NativeHandle(){if(h&&h!=INVALID_HANDLE_VALUE)CloseHandle(h);}};
}
void generateVideoThumbnail(const fs::path& executable,const fs::path& input,const fs::path& output,const std::atomic<bool>& stopping) {
    if(executable.empty()||!fs::is_regular_file(executable))throw std::runtime_error("FFmpeg is not configured");
    auto temporary=output;temporary+=L".frame.tmp.jpg";struct Temp{fs::path path;~Temp(){std::error_code ec;fs::remove(path,ec);}}temp{temporary};
    std::wstring command=argument(executable.wstring())+L" -hide_banner -loglevel error -nostdin -y -threads 1 -filter_threads 1 -protocol_whitelist file,pipe -format_whitelist mov,matroska,webm,avi,mpegts,mpeg,flv,asf,ogg -i "+argument(input.wstring())+L" -map 0:v:0 -an -sn -dn -frames:v 1 -vf scale=640:640:force_original_aspect_ratio=decrease -q:v 3 -threads 1 -fs 2097152 -f image2 -update 1 "+argument(temporary.wstring());
    NativeHandle job;job.h=CreateJobObjectW(nullptr,nullptr);if(!job.h)throw std::runtime_error("Cannot create video preview job");
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE|JOB_OBJECT_LIMIT_PROCESS_MEMORY;limits.ProcessMemoryLimit=512ULL*1024*1024;
    if(!SetInformationJobObject(job.h,JobObjectExtendedLimitInformation,&limits,sizeof(limits)))throw std::runtime_error("Cannot bound video preview job");
    SECURITY_ATTRIBUTES security{sizeof(SECURITY_ATTRIBUTES),nullptr,TRUE};NativeHandle null;null.h=CreateFileW(L"NUL",GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,OPEN_EXISTING,0,nullptr);if(null.h==INVALID_HANDLE_VALUE)throw std::runtime_error("Cannot redirect video preview output");
    STARTUPINFOW startup{};startup.cb=sizeof(startup);startup.dwFlags=STARTF_USESTDHANDLES;startup.hStdInput=startup.hStdOutput=startup.hStdError=null.h;
    PROCESS_INFORMATION info{};if(!CreateProcessW(executable.c_str(),command.data(),nullptr,nullptr,TRUE,CREATE_NO_WINDOW|CREATE_SUSPENDED|BELOW_NORMAL_PRIORITY_CLASS,nullptr,nullptr,&startup,&info))throw std::runtime_error("Cannot start FFmpeg");
    NativeHandle process;process.h=info.hProcess;NativeHandle thread;thread.h=info.hThread;
    if(!AssignProcessToJobObject(job.h,process.h)){TerminateProcess(process.h,1);WaitForSingleObject(process.h,5000);throw std::runtime_error("Cannot isolate video preview process");}
    if(ResumeThread(thread.h)==static_cast<DWORD>(-1)){TerminateJobObject(job.h,1);WaitForSingleObject(process.h,5000);throw std::runtime_error("Cannot resume video preview");}
    auto start=GetTickCount64();while(WaitForSingleObject(process.h,100)==WAIT_TIMEOUT){if(stopping||GetTickCount64()-start>20000){TerminateJobObject(job.h,1);WaitForSingleObject(process.h,5000);throw std::runtime_error("Video preview cancelled or timed out");}}
    DWORD code=1;if(!GetExitCodeProcess(process.h,&code)||code||!fs::is_regular_file(temporary)||fs::file_size(temporary)>2*1024*1024)throw std::runtime_error("Video frame unavailable");
    generateThumbnail(temporary,output);
}
}
