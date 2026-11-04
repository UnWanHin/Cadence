#include "wasm_api.h"

#include "workout_session.h"

#include <array>
#include <cstdio>
#include <memory>
#include <string>

namespace {
std::string result_json(const cadence::EvaluationResult& result) {
  char buffer[256];
  std::snprintf(buffer, sizeof(buffer),
                "{\"poseDetected\":%s,\"inTarget\":%s,\"repetitionCompleted\":%s,\"hold\":%s,\"mainValue\":%.4f,\"quality\":%.4f}",
                result.pose_detected ? "true" : "false",
                result.in_target_position ? "true" : "false",
                result.repetition_completed ? "true" : "false",
                result.is_hold ? "true" : "false",
                result.main_value, result.quality_score);
  return buffer;
}

std::string summary_json(const cadence::SessionSummary& summary) {
  char buffer[256];
  std::snprintf(buffer, sizeof(buffer),
                "{\"repetitions\":%d,\"points\":%d,\"calories\":%.4f,\"averageQuality\":%.4f,\"elapsedSeconds\":%.4f}",
                summary.repetitions, summary.points, summary.calories,
                summary.average_quality, summary.elapsed_seconds);
  return buffer;
}

cadence::ExerciseType exercise_from_int(int exercise) {
  switch (exercise) {
    case 1: return cadence::ExerciseType::PushUp;
    case 2: return cadence::ExerciseType::Lunge;
    case 3: return cadence::ExerciseType::Plank;
    case 4: return cadence::ExerciseType::JumpingJack;
    default: return cadence::ExerciseType::Squat;
  }
}
}

extern "C" {

void* cadence_create_session(int exercise) {
  return new cadence::WorkoutSession(exercise_from_int(exercise));
}

void cadence_destroy_session(void* session) {
  delete static_cast<cadence::WorkoutSession*>(session);
}

void cadence_reset_session(void* session) {
  if (session) static_cast<cadence::WorkoutSession*>(session)->reset();
}

const char* cadence_process_landmarks(void* session, const float* landmarks,
                                      std::size_t count, float elapsed_seconds) {
  static thread_local std::string output;
  if (!session || !landmarks || count != 33) {
    output = "{\"error\":\"expected 33 landmarks with x,y,z,visibility\"}";
    return output.c_str();
  }

  cadence::PoseFrame frame;
  for (std::size_t i = 0; i < count; ++i) {
    frame.landmarks[i] = {landmarks[i * 4], landmarks[i * 4 + 1],
                          landmarks[i * 4 + 2], landmarks[i * 4 + 3]};
  }
  const auto result = static_cast<cadence::WorkoutSession*>(session)->process(frame, elapsed_seconds);
  output = result_json(result);
  return output.c_str();
}

const char* cadence_session_summary(void* session) {
  static thread_local std::string output;
  if (!session) {
    output = "{\"error\":\"session is null\"}";
    return output.c_str();
  }
  output = summary_json(static_cast<cadence::WorkoutSession*>(session)->summary());
  return output.c_str();
}

}
