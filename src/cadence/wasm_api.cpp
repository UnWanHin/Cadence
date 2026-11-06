#include "wasm_api.h"

#include "workout_session.h"

#include <array>
#include <cstdio>
#include <memory>
#include <string>

namespace {
std::string json_escape(const std::string& input) {
  std::string output;
  output.reserve(input.size() + 8);
  for (unsigned char c : input) {
    switch (c) {
      case '"': output += "\\\""; break;
      case '\\': output += "\\\\"; break;
      case '\n': output += "\\n"; break;
      case '\r': output += "\\r"; break;
      case '\t': output += "\\t"; break;
      default:
        if (c < 0x20) {
          char escaped[7];
          std::snprintf(escaped, sizeof(escaped), "\\u%04x", c);
          output += escaped;
        } else {
          output += static_cast<char>(c);
        }
    }
  }
  return output;
}

std::string result_json(const cadence::EvaluationResult& result) {
  char buffer[512];
  std::snprintf(buffer, sizeof(buffer),
                "{\"poseDetected\":%s,\"inTarget\":%s,\"repetitionCompleted\":%s,\"hold\":%s,\"mainValue\":%.4f,\"quality\":%.4f",
                result.pose_detected ? "true" : "false",
                result.in_target_position ? "true" : "false",
                result.repetition_completed ? "true" : "false",
                result.is_hold ? "true" : "false",
                result.main_value, result.quality_score);
  std::string output(buffer);
  output += ",\"feedback\":[";
  for (std::size_t i = 0; i < result.feedback.size(); ++i) {
    if (i != 0) output += ',';
    output += '"';
    output += json_escape(result.feedback[i]);
    output += '"';
  }
  output += "]}";
  return output;
}

std::string summary_json(const cadence::SessionSummary& summary, float enter_depth) {
  char buffer[320];
  std::snprintf(buffer, sizeof(buffer),
                "{\"repetitions\":%d,\"points\":%d,\"calories\":%.4f,\"averageQuality\":%.4f,"
                "\"elapsedSeconds\":%.4f,\"enterDepth\":%.1f}",
                summary.repetitions, summary.points, summary.calories,
                summary.average_quality, summary.elapsed_seconds, enter_depth);
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
  const auto* workout = static_cast<const cadence::WorkoutSession*>(session);
  output = summary_json(workout->summary(), workout->enter_depth_degrees());
  return output.c_str();
}

}
