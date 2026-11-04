#pragma once

#include "types.h"

#include <string>

namespace cadence {

struct BackendStatus {
  bool ready{false};
  std::string message;
};

class PoseBackend {
 public:
  virtual ~PoseBackend() = default;
  virtual BackendStatus load(const std::string& model_path) = 0;
  virtual bool detect(const std::string& frame_source, PoseFrame& frame) = 0;
};

class UnavailablePoseBackend final : public PoseBackend {
 public:
  BackendStatus load(const std::string& model_path) override;
  bool detect(const std::string& frame_source, PoseFrame& frame) override;
};

}
