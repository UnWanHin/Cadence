#include "pose_features.h"

#include "geometry.h"

#include <algorithm>

namespace cadence {
namespace {
constexpr int LS = 11;
constexpr int RS = 12;
constexpr int LE = 13;
constexpr int RE = 14;
constexpr int LW = 15;
constexpr int RW = 16;
constexpr int LH = 23;
constexpr int RH = 24;
constexpr int LK = 25;
constexpr int RK = 26;
constexpr int LA = 27;
constexpr int RA = 28;

std::optional<float> angle(const PoseFrame& frame, int a, int b, int c) {
  if (std::min({frame.landmarks[a].visibility,
                frame.landmarks[b].visibility,
                frame.landmarks[c].visibility}) < 0.5f) {
    return {};
  }
  return angle_at(frame.landmarks[a], frame.landmarks[b], frame.landmarks[c]);
}

std::optional<float> minimum(std::optional<float> left, std::optional<float> right) {
  if (left && right) return std::min(*left, *right);
  return left ? left : right;
}
}

PoseFeatures extract_features(const PoseFrame& frame) {
  PoseFeatures features;
  features.left_knee_angle = angle(frame, LH, LK, LA);
  features.right_knee_angle = angle(frame, RH, RK, RA);
  features.left_elbow_angle = angle(frame, LS, LE, LW);
  features.right_elbow_angle = angle(frame, RS, RE, RW);
  features.knee_angle = minimum(features.left_knee_angle, features.right_knee_angle);
  features.elbow_angle = minimum(features.left_elbow_angle, features.right_elbow_angle);
  features.hip_angle = angle(frame, LS, LH, LK);
  features.shoulder_hip_ankle_angle = angle(frame, LS, LH, LA);
  if (frame.landmarks[LW].visibility >= 0.5f && frame.landmarks[RW].visibility >= 0.5f) {
    features.wrist_distance = distance_between(frame.landmarks[LW], frame.landmarks[RW]);
  }
  return features;
}
}
