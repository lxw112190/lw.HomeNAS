#pragma once
#include "core.hpp"
namespace nas {
int runServer(Config config,std::optional<bool> insecureLanOverride=std::nullopt,const std::function<void()>& ready = {});
void requestServerStop();
int runWindowsService(const fs::path& config,std::optional<bool> insecureLanOverride=std::nullopt);
}
