#include "pose_inference.h"

namespace cadence {

InferencePoseSource::InferencePoseSource(InferenceConfig config, PoseInference inference)
    : config_(std::move(config)), inference_(std::move(inference)) {}

bool InferencePoseSource::next(PoseFrame& frame) {
  if (!inference_) {
    last_error_ = "pose inference backend is not configured";
    return false;
  }
  if (!inference_(config_, frame)) {
    last_error_ = "pose inference backend returned no frame";
    return false;
  }
  last_error_.clear();
  return true;
}

}
