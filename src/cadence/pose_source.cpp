#include "pose_source.h"

namespace cadence {

bool TextPoseSource::next(PoseFrame& frame) {
  for (auto& landmark : frame.landmarks) {
    if (!(input_ >> landmark.x >> landmark.y >> landmark.z >> landmark.visibility)) {
      return false;
    }
  }
  return static_cast<bool>(input_ >> frame.timestamp_ms);
}

}
