#include "core.hpp"
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <iphlpapi.h>
#include <bcrypt.h>
#include <fstream>
#include <iostream>
#include <set>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/pem.h>
#include <mbedtls/pk.h>
#include <mbedtls/x509_crt.h>
#include <mbedtls/oid.h>
#include <mbedtls/sha256.h>
#include <mbedtls/threading.h>

namespace nas {
void initializeTlsThreads() {
    static bool initialized=[] {
        mbedtls_threading_set_alt(
            [](mbedtls_threading_mutex_t* m){InitializeCriticalSection(&m->section);m->valid=1;},
            [](mbedtls_threading_mutex_t* m){if(m->valid){DeleteCriticalSection(&m->section);m->valid=0;}},
            [](mbedtls_threading_mutex_t* m)->int{if(!m->valid)return MBEDTLS_ERR_THREADING_BAD_INPUT_DATA;EnterCriticalSection(&m->section);return 0;},
            [](mbedtls_threading_mutex_t* m)->int{if(!m->valid)return MBEDTLS_ERR_THREADING_BAD_INPUT_DATA;LeaveCriticalSection(&m->section);return 0;});
        return true;
    }();
    (void)initialized;
}
namespace {
std::streambuf* const initialOutputBuffer=std::cout.rdbuf();
std::streambuf* const initialErrorBuffer=std::cerr.rdbuf();
std::mutex consoleMutex;
void checked(NTSTATUS result) { if (result < 0) throw std::runtime_error("Windows cryptography operation failed"); }
std::string hex(const unsigned char* bytes, size_t count) {
    static const char* digits = "0123456789abcdef";
    std::string out(count * 2, '0');
    for (size_t i = 0; i < count; ++i) { out[i * 2] = digits[bytes[i] >> 4]; out[i * 2 + 1] = digits[bytes[i] & 15]; }
    return out;
}
std::wstring nativePath(const fs::path& path) {
    auto value = fs::absolute(path).lexically_normal().wstring();
    if (value.rfind(L"\\\\?\\", 0) == 0) return value;
    return value.rfind(L"\\\\", 0) == 0 ? L"\\\\?\\UNC\\" + value.substr(2) : L"\\\\?\\" + value;
}
void tlsCheck(int value) { if (value != 0) throw std::runtime_error("TLS certificate operation failed: " + std::to_string(value)); }
}
void consoleText(const std::string& utf8,bool error) {
    std::lock_guard<std::mutex> guard(consoleMutex);
    auto& stream=error?std::cerr:std::cout;
    HANDLE handle=GetStdHandle(error?STD_ERROR_HANDLE:STD_OUTPUT_HANDLE);DWORD mode=0;
    // A redirected stream (including the SCM service log) keeps its original UTF-8 bytes.
    if(stream.rdbuf()!=(error?initialErrorBuffer:initialOutputBuffer)||!GetConsoleMode(handle,&mode)) {
        stream.write(utf8.data(),static_cast<std::streamsize>(utf8.size()));stream.flush();return;
    }
    if(utf8.empty())return;
    int length=MultiByteToWideChar(CP_UTF8,0,utf8.data(),static_cast<int>(utf8.size()),nullptr,0);
    if(length<=0){stream.setstate(std::ios::badbit);return;}
    std::wstring wide(static_cast<size_t>(length),L'\0');
    MultiByteToWideChar(CP_UTF8,0,utf8.data(),static_cast<int>(utf8.size()),wide.data(),length);
    std::wstring normalized;normalized.reserve(wide.size()+32);
    for(size_t i=0;i<wide.size();++i){if(wide[i]==L'\n'&&(i==0||wide[i-1]!=L'\r'))normalized+=L'\r';normalized+=wide[i];}
    for(size_t offset=0;offset<normalized.size();) {
        DWORD count=static_cast<DWORD>(std::min<size_t>(8192,normalized.size()-offset)),written=0;
        if(offset+count<normalized.size()&&normalized[offset+count-1]>=0xd800&&normalized[offset+count-1]<=0xdbff)--count;
        if(!WriteConsoleW(handle,normalized.data()+offset,count,&written,nullptr)||!written){stream.setstate(std::ios::badbit);return;}
        offset+=written;
    }
}
uint64_t millis() { return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count()); }
uint64_t number(const json& j, const char* field) {
    try {
        auto it = j.find(field);
        if (it == j.end()) throw std::invalid_argument("missing");
        if (it->is_number_unsigned()) return it->get<uint64_t>();
        if (it->is_number_integer()) { auto n = it->get<int64_t>(); if (n >= 0) return static_cast<uint64_t>(n); }
        if (it->is_string()) {
            auto s = it->get<std::string>();
            if (s.empty() || s.size() > 20 || s.find_first_not_of("0123456789") != std::string::npos) throw std::invalid_argument("integer");
            size_t pos = 0; auto n = std::stoull(s, &pos); if (pos == s.size()) return n;
        }
    } catch (...) {}
    throw Error(400, "INVALID_INTEGER", std::string("无效的整数：") + field);
}
std::string text(const json& j, const char* field, size_t maximum, bool optional) {
    auto it = j.find(field);
    if (optional && it == j.end()) return {};
    if (it == j.end() || !it->is_string()) throw Error(400, "INVALID_TEXT", std::string("缺少文字字段：") + field);
    auto s = it->get<std::string>();
    if ((!optional && s.empty()) || s.size() > maximum || s.find_first_of(std::string("\0\r\n", 3)) != std::string::npos)
        throw Error(400, "INVALID_TEXT", std::string("文字字段过长或含控制字符：") + field);
    return s;
}
std::string readText(const fs::path& path, size_t maximum) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("Cannot read file: " + path.u8string());
    std::string data; char buffer[4096];
    while (input) { input.read(buffer, sizeof(buffer)); data.append(buffer, static_cast<size_t>(input.gcount())); if (data.size() > maximum) throw std::runtime_error("Text file exceeds size limit"); }
    if (input.bad()) throw std::runtime_error("File read failed");
    if (data.rfind("\xef\xbb\xbf", 0) == 0) data.erase(0, 3);
    return data;
}
void writeText(const fs::path& path, const std::string& data) {
    fs::create_directories(path.parent_path());
    File f(path, true, true); f.truncate(0); f.write(0, data.data(), data.size()); f.flush();
}
std::string randomHex(size_t bytes) {
    std::vector<unsigned char> value(bytes);
    checked(BCryptGenRandom(nullptr, value.data(), static_cast<ULONG>(value.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG));
    return hex(value.data(), value.size());
}
std::string sha256(const void* data, size_t size) {
    unsigned char out[32]; tlsCheck(mbedtls_sha256(static_cast<const unsigned char*>(data), size, out, 0)); return hex(out, 32);
}
std::string hashFile(const fs::path& path, const std::atomic<bool>* stopping) {
    File f(path, false); auto length = f.size();
    mbedtls_sha256_context context; mbedtls_sha256_init(&context);
    try {
        tlsCheck(mbedtls_sha256_starts(&context, 0)); std::vector<char> buffer(1024 * 1024);
        for (uint64_t offset = 0; offset < length;) {
            if (stopping && stopping->load()) throw Error(503, "STOPPING", "服务器正在停止，任务稍后继续");
            size_t n = f.read(offset, buffer.data(), static_cast<size_t>(std::min<uint64_t>(buffer.size(), length - offset)));
            if (!n) throw std::runtime_error("Unexpected end of file");
            tlsCheck(mbedtls_sha256_update(&context, reinterpret_cast<const unsigned char*>(buffer.data()), n)); offset += n;
        }
        unsigned char result[32]; tlsCheck(mbedtls_sha256_finish(&context, result)); mbedtls_sha256_free(&context); return hex(result, 32);
    } catch (...) { mbedtls_sha256_free(&context); throw; }
}
std::string passwordHash(const std::string& password, const std::string& salt) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    checked(BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, BCRYPT_ALG_HANDLE_HMAC_FLAG));
    unsigned char result[32];
    auto status = BCryptDeriveKeyPBKDF2(algorithm, reinterpret_cast<PUCHAR>(const_cast<char*>(password.data())), static_cast<ULONG>(password.size()),
        reinterpret_cast<PUCHAR>(const_cast<char*>(salt.data())), static_cast<ULONG>(salt.size()), 200000, result, 32, 0);
    BCryptCloseAlgorithmProvider(algorithm, 0); checked(status); return hex(result, 32);
}
bool secureEqual(const std::string& a, const std::string& b) {
    if (a.size() != b.size()) return false;
    unsigned char different = 0; for (size_t i = 0; i < a.size(); ++i) different |= static_cast<unsigned char>(a[i] ^ b[i]); return different == 0;
}
fs::path executableDirectory() {
    std::vector<wchar_t> buffer(32768); auto size = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
    if (!size || size >= buffer.size()) throw std::runtime_error("Cannot resolve executable directory");
    return fs::path(std::wstring(buffer.data(), size)).parent_path();
}
File::File(const fs::path& path, bool write, bool create) {
    handle_ = CreateFileW(nativePath(path).c_str(), write ? (GENERIC_READ | GENERIC_WRITE) : GENERIC_READ,
        FILE_SHARE_READ, nullptr, create ? OPEN_ALWAYS : OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle_ == INVALID_HANDLE_VALUE) { handle_ = nullptr; throw Error(507, "FILE_OPEN_FAILED", "文件无法打开，检查磁盘、目录权限及空间（Windows " + std::to_string(GetLastError()) + "）"); }
}
File::~File() { if (handle_) CloseHandle(handle_); }
uint64_t File::size() { LARGE_INTEGER size; if (!GetFileSizeEx(handle_, &size)) throw std::runtime_error("Cannot obtain file size"); return static_cast<uint64_t>(size.QuadPart); }
size_t File::read(uint64_t offset, char* buffer, size_t count) {
    std::lock_guard<std::mutex> guard(mutex_); LARGE_INTEGER position; position.QuadPart = static_cast<LONGLONG>(offset); DWORD done = 0;
    if (!SetFilePointerEx(handle_, position, nullptr, FILE_BEGIN) || !ReadFile(handle_, buffer, static_cast<DWORD>(count), &done, nullptr)) throw std::runtime_error("File read failed"); return done;
}
void File::write(uint64_t offset, const char* buffer, size_t count) {
    std::lock_guard<std::mutex> guard(mutex_); LARGE_INTEGER position; position.QuadPart = static_cast<LONGLONG>(offset);
    if (!SetFilePointerEx(handle_, position, nullptr, FILE_BEGIN)) throw std::runtime_error("Cannot seek file");
    while (count) { DWORD done = 0; if (!WriteFile(handle_, buffer, static_cast<DWORD>(std::min<size_t>(count, 1024 * 1024)), &done, nullptr) || !done) throw Error(507, "DISK_WRITE_FAILED", "写盘失败，已保留确认过的进度"); buffer += done; count -= done; }
}
void File::truncate(uint64_t length) { LARGE_INTEGER p; p.QuadPart = static_cast<LONGLONG>(length); if (!SetFilePointerEx(handle_, p, nullptr, FILE_BEGIN) || !SetEndOfFile(handle_)) throw std::runtime_error("Cannot truncate temporary file"); }
void File::flush() { if (!FlushFileBuffers(handle_)) throw Error(507, "DISK_FLUSH_FAILED", "磁盘持久化失败，本块尚未确认"); }

Config Config::load(const fs::path& path) {
    auto j = json::parse(readText(path));
    const std::set<std::string> allowed = {"schema_version", "name", "http_port", "https_port", "https_host", "allow_external_hosts", "data_dir", "web_dir", "chunk_bytes", "minimum_free_bytes", "maximum_file_bytes"};
    if (!j.is_object()) throw std::runtime_error("Configuration must be a JSON object");
    for (auto it = j.begin(); it != j.end(); ++it) if (!allowed.count(it.key())) throw std::runtime_error("Unknown configuration field: " + it.key());
    if (number(j, "schema_version") != 1) throw std::runtime_error("Unsupported configuration schema");
    Config c; c.name = text(j, "name", 128); c.host = text(j, "https_host", 255);
    auto hp = number(j, "http_port"), sp = number(j, "https_port");
    if (hp < 1024 || hp > 65535 || sp < 1024 || sp > 65535 || hp == sp) throw std::runtime_error("Invalid ports");
    c.httpPort = static_cast<int>(hp); c.httpsPort = static_cast<int>(sp);
    if(j.contains("allow_external_hosts")) {
        if(!j["allow_external_hosts"].is_boolean())throw std::runtime_error("allow_external_hosts must be true or false");
        c.allowExternalHosts=j["allow_external_hosts"].get<bool>();
    }
    c.chunkBytes = number(j, "chunk_bytes"); c.minFree = number(j, "minimum_free_bytes"); c.maxFile = number(j, "maximum_file_bytes");
    if (c.chunkBytes < 65536 || c.chunkBytes > 16 * 1024 * 1024 || !c.maxFile || c.maxFile > (1ULL << 48) || c.minFree > (1ULL << 48)) throw std::runtime_error("Invalid size limits");
    c.data = fs::absolute(path.parent_path() / fs::u8path(text(j, "data_dir", 2048))).lexically_normal();
    c.web = fs::absolute(path.parent_path() / fs::u8path(text(j, "web_dir", 2048))).lexically_normal();
    if (!fs::is_regular_file(c.web / "index.html")) throw std::runtime_error("Web resources missing");
    return c;
}
std::vector<std::string> ipv4Addresses() {
    ULONG bytes = 16384; std::vector<unsigned char> buffer(bytes);
    auto result = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr, reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data()), &bytes);
    if (result == ERROR_BUFFER_OVERFLOW) { buffer.resize(bytes); result = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr, reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data()), &bytes); }
    std::set<std::string> addresses{"127.0.0.1"};
    if (result == NO_ERROR) for (auto* a = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data()); a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp) continue;
        for (auto* u = a->FirstUnicastAddress; u; u = u->Next) { char ip[INET_ADDRSTRLEN]{}; auto* s = reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr); if (InetNtopA(AF_INET, &s->sin_addr, ip, sizeof(ip))) addresses.insert(ip); }
    }
    return {addresses.begin(), addresses.end()};
}
void ensureCertificate(const fs::path& data) {
    auto cert = data / "server-cert.pem", key = data / "server-key.pem";
    if (fs::exists(cert) && fs::exists(key)) return;
    if (fs::exists(cert) || fs::exists(key)) throw std::runtime_error("Certificate/key incomplete; restore the matching pair");
    mbedtls_entropy_context entropy; mbedtls_ctr_drbg_context rng; mbedtls_pk_context pk;
    mbedtls_x509write_cert crt; mbedtls_mpi serial;
    mbedtls_entropy_init(&entropy); mbedtls_ctr_drbg_init(&rng); mbedtls_pk_init(&pk); mbedtls_x509write_crt_init(&crt); mbedtls_mpi_init(&serial);
    auto cleanup = [&] { mbedtls_mpi_free(&serial); mbedtls_x509write_crt_free(&crt); mbedtls_pk_free(&pk); mbedtls_ctr_drbg_free(&rng); mbedtls_entropy_free(&entropy); };
    try {
        const unsigned char personal[] = "lw.HomeNAS-certificate";
        tlsCheck(mbedtls_ctr_drbg_seed(&rng, mbedtls_entropy_func, &entropy, personal, sizeof(personal)));
        tlsCheck(mbedtls_pk_setup(&pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY)));
        tlsCheck(mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, mbedtls_pk_ec(pk), mbedtls_ctr_drbg_random, &rng));
        unsigned char serialBytes[16]; tlsCheck(mbedtls_ctr_drbg_random(&rng, serialBytes, sizeof(serialBytes))); serialBytes[0] &= 0x7f;
        tlsCheck(mbedtls_mpi_read_binary(&serial, serialBytes, sizeof(serialBytes)));
        mbedtls_x509write_crt_set_version(&crt, MBEDTLS_X509_CRT_VERSION_3);
        mbedtls_x509write_crt_set_md_alg(&crt, MBEDTLS_MD_SHA256);
        mbedtls_x509write_crt_set_subject_key(&crt, &pk); mbedtls_x509write_crt_set_issuer_key(&crt, &pk);
        tlsCheck(mbedtls_x509write_crt_set_serial(&crt, &serial));
        tlsCheck(mbedtls_x509write_crt_set_subject_name(&crt, "CN=lw.HomeNAS"));
        tlsCheck(mbedtls_x509write_crt_set_issuer_name(&crt, "CN=lw.HomeNAS"));
        auto now = std::time(nullptr); std::tm start{}, end{}; auto yesterday = now - 86400; auto expiry = now + 365LL * 86400 * 3;
        gmtime_s(&start, &yesterday); gmtime_s(&end, &expiry); char a[16], b[16]; std::strftime(a, sizeof(a), "%Y%m%d%H%M%S", &start); std::strftime(b, sizeof(b), "%Y%m%d%H%M%S", &end);
        tlsCheck(mbedtls_x509write_crt_set_validity(&crt, a, b));
        tlsCheck(mbedtls_x509write_crt_set_basic_constraints(&crt, 0, -1));
        tlsCheck(mbedtls_x509write_crt_set_key_usage(&crt, MBEDTLS_X509_KU_DIGITAL_SIGNATURE));
        std::vector<unsigned char> names{0x82, 9, 'l','o','c','a','l','h','o','s','t'};
        for (const auto& ip : ipv4Addresses()) { IN_ADDR address{}; if (InetPtonA(AF_INET, ip.c_str(), &address) == 1) { names.push_back(0x87); names.push_back(4); const auto* p = reinterpret_cast<const unsigned char*>(&address); names.insert(names.end(), p, p + 4); } }
        if (names.size() > 240) throw std::runtime_error("Too many network interfaces for certificate");
        std::vector<unsigned char> san{0x30}; if (names.size() >= 128) san.push_back(0x81); san.push_back(static_cast<unsigned char>(names.size())); san.insert(san.end(), names.begin(), names.end());
        tlsCheck(mbedtls_x509write_crt_set_extension(&crt, MBEDTLS_OID_SUBJECT_ALT_NAME, MBEDTLS_OID_SIZE(MBEDTLS_OID_SUBJECT_ALT_NAME), 0, san.data(), san.size()));
        unsigned char keyPem[4096]{}, certPem[8192]{};
        tlsCheck(mbedtls_pk_write_key_pem(&pk, keyPem, sizeof(keyPem)));
        tlsCheck(mbedtls_x509write_crt_pem(&crt, certPem, sizeof(certPem), mbedtls_ctr_drbg_random, &rng));
        writeText(key, reinterpret_cast<char*>(keyPem)); writeText(cert, reinterpret_cast<char*>(certPem)); cleanup();
    } catch (...) { cleanup(); throw; }
}
std::string certificateFingerprint(const fs::path& path) {
    auto pem = readText(path); mbedtls_x509_crt crt; mbedtls_x509_crt_init(&crt);
    auto status = mbedtls_x509_crt_parse(&crt, reinterpret_cast<const unsigned char*>(pem.c_str()), pem.size() + 1);
    if (status) { mbedtls_x509_crt_free(&crt); throw std::runtime_error("Cannot parse server certificate"); }
    auto digest = sha256(crt.raw.p, crt.raw.len); mbedtls_x509_crt_free(&crt); return digest;
}
}
