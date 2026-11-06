#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace cadence {
struct Landmark { float x{}, y{}, z{}, visibility{}; };
struct PoseFrame { std::array<Landmark, 33> landmarks{}; std::int64_t timestamp_ms{}; };
enum class ExerciseType { Squat, PushUp, Lunge, Plank, JumpingJack };
struct PoseFeatures {
  std::optional<float> knee_angle, elbow_angle, hip_angle, wrist_distance;
  // Shoulder-hip-ankle: the plank line. Used when the knee is hidden, which is
  // common in a plank because the legs point away from the camera.
  std::optional<float> shoulder_hip_ankle_angle;
  std::optional<float> left_knee_angle, right_knee_angle, left_elbow_angle, right_elbow_angle;
};
struct EvaluationResult {
  bool pose_detected{false}; bool in_target_position{false}; bool repetition_completed{false}; bool is_hold{false};
  float main_value{}; float quality_score{}; std::vector<std::string> feedback;
};
struct CounterThresholds { float enter{0.75f}; float exit{0.55f}; };
struct CounterEvent { bool entered_target{false}; bool repetition_completed{false}; };
struct SessionSummary { int repetitions{}; int points{}; float calories{}; float average_quality{}; float elapsed_seconds{}; };
}
