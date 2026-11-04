#include "core.hpp"
#include <windows.h>
#include <wincodec.h>
#include <wrl/client.h>

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
    fs::rename(temporary,output);
}
}
