#include "pose_backend.h"

namespace cadence {

BackendStatus UnavailablePoseBackend::load(const std::string&) {
  return {false, "No native pose backend is configured. Add a MediaPipe Tasks or ONNX adapter."};
}

bool UnavailablePoseBackend::detect(const std::string&, PoseFrame&) {
  return false;
}

}
