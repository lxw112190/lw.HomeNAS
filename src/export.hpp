#pragma once
#include "core.hpp"
#include "httplib.h"
namespace nas {
void exportArchive(Store& store,httplib::Response& response,const json& input);
void exportFiles(httplib::Response& response,const std::vector<std::pair<fs::path,std::string>>& files,const std::string& name);
}
