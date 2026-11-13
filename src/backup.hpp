#pragma once
#include "core.hpp"
namespace nas {
void migrateLibrary(const fs::path& config,const fs::path& target,const fs::path& metadataBackup={});
json verifyLibrary(const fs::path& target);
}
