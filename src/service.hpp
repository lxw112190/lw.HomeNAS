#pragma once
#include "core.hpp"
namespace nas {
int runServer(Config config,bool insecureLan,const std::function<void()>& ready = {});
void requestServerStop();
int runWindowsService(const fs::path& config);
}
