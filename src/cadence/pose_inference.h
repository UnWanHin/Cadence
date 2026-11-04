#pragma once

#include "pose_source.h"

#include <functional>
#include <memory>
#include <string>

namespace cadence {

struct InferenceConfig {
  std::string model_path;
  float detection_confidence{0.5f};
  float presence_confidence{0.5f};
  float tracking_confidence{0.5f};
};

using PoseInference = std::function<bool(const InferenceConfig&, PoseFrame&)>;

class InferencePoseSource final : public PoseSource {
 public:
  InferencePoseSource(InferenceConfig config, PoseInference inference);
  bool next(PoseFrame& frame) override;
  const std::string& last_error() const { return last_error_; }

 private:
  InferenceConfig config_;
  PoseInference inference_;
  std::string last_error_;
};

}
