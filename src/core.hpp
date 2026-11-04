#pragma once
#include <atomic>
#include <condition_variable>
#include <filesystem>
#include <functional>
#include <memory>
#include <map>
#include <stdexcept>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>
#include "json.hpp"

struct sqlite3;
namespace nas {
namespace fs = std::filesystem;
using json = nlohmann::json;
using Params = std::vector<std::string>;
constexpr const char* Version = "0.2.4-dev";

struct Error : std::runtime_error {
    int status;
    std::string code;
    Error(int status, std::string code, std::string message)
        : std::runtime_error(std::move(message)), status(status), code(std::move(code)) {}
};
struct Config {
    std::string name, host;
    fs::path data, web;
    int httpPort = 8787, httpsPort = 9443;
    bool allowExternalHosts = false;
    uint64_t chunkBytes = 8388608, minFree = 268435456, maxFile = 137438953472ULL;
    static Config load(const fs::path& path);
};
std::string randomHex(size_t bytes = 24);
std::string sha256(const void* data, size_t size);
std::string hashFile(const fs::path& path, const std::atomic<bool>* stopping = nullptr);
std::string passwordHash(const std::string& password, const std::string& salt);
bool secureEqual(const std::string& a, const std::string& b);
uint64_t millis();
uint64_t number(const json& value, const char* field);
std::string text(const json& value, const char* field, size_t maximum, bool optional = false);
std::string readText(const fs::path& path, size_t maximum = 65536);
void writeText(const fs::path& path, const std::string& data);
void consoleText(const std::string& utf8, bool error = false);
fs::path executableDirectory();
std::vector<std::string> ipv4Addresses();
void ensureCertificate(const fs::path& data);
std::string certificateFingerprint(const fs::path& path);

class File {
public:
    File(const fs::path& path, bool write, bool create = false);
    ~File();
    File(const File&) = delete;
    uint64_t size();
    size_t read(uint64_t offset, char* buffer, size_t count);
    void write(uint64_t offset, const char* buffer, size_t count);
    void truncate(uint64_t length);
    void flush();
private:
    void* handle_ = nullptr;
    std::mutex mutex_;
};

class Database {
public:
    explicit Database(const fs::path& file);
    ~Database();
    void exec(const std::string& sql, const Params& values = {});
    json query(const std::string& sql, const Params& values = {});
    void transaction(const std::function<void()>& action);
private:
    sqlite3* db_ = nullptr;
    std::recursive_mutex mutex_;
};

struct Identity {
    std::string userId, username, displayName, role;
    bool admin = false;
    bool canUpload() const { return role == "admin" || role == "member"; }
};

class Store {
public:
    explicit Store(Config config,bool startBackground=true);
    ~Store();
    Config config;
    Database db;
    std::string serverId;
    json login(const json& input);
    Identity authenticate(const std::string& token);
    json users(const Identity& caller);
    json createUser(const json& input, const Identity& caller);
    json updateUser(const std::string& id, const json& input, const Identity& caller);
    void changePassword(const json& input, const Identity& caller);
    json createUpload(const json& input, const Identity& who);
    json upload(const std::string& id, const Identity& who);
    json writeChunk(const std::string& id, const Identity& who, uint64_t offset,
                    uint64_t size, const std::string& digest,
                    const std::function<bool(const std::function<bool(const char*, size_t)>&)>& reader);
    json complete(const std::string& id, const Identity& who);
    json cancel(const std::string& id, const Identity& who);
    json assets(const std::string& before, const std::string& category, const std::string& search);
    json asset(const std::string& id);
    json tasks(const Identity& who);
    json overview(const Identity& caller);
    fs::path originalPath(const json& asset) const;
    std::atomic<bool> stopping{false};
    std::atomic<unsigned> activeUploads{0};
private:
    std::thread worker_;
    std::mutex queueMutex_, sessionsMutex_;
    std::condition_variable queueCondition_;
    std::queue<std::string> queue_;
    std::map<std::string, std::weak_ptr<std::mutex>> sessions_;
    std::shared_ptr<std::mutex> sessionMutex(const std::string& id);
    std::string setting(const std::string& key);
    void setting(const std::string& key, const std::string& value);
    void enqueue(const std::string& id);
    void recover();
    void process(const std::string& id);
    void makePreview(const json& asset);
    void finishCommit(const json& task, const json& asset);
    void worker();
    fs::path part(const std::string& id) const;
    json taskRow(const std::string& id);
    std::string credential(const std::string& id, uint64_t expires);
    void migrateAccounts();
};
}
