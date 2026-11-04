#include "workout_session.h"

#include <algorithm>

namespace cadence {

namespace {
float progress_for(const EvaluationResult& result, ExerciseType exercise) {
  if (exercise == ExerciseType::Plank) return result.quality_score;
  if (exercise == ExerciseType::JumpingJack) return result.in_target_position ? 1.0f : 0.0f;
  if (result.main_value <= 90.0f) return 1.0f;
  if (result.main_value >= 160.0f) return 0.0f;
  return (160.0f - result.main_value) / 70.0f;
}

float calories_per_repetition(ExerciseType exercise) {
  return exercise == ExerciseType::Lunge ? 0.7f
       : exercise == ExerciseType::Squat ? 0.6f
       : exercise == ExerciseType::PushUp ? 0.5f
       : exercise == ExerciseType::JumpingJack ? 0.4f
       : 0.0f;
}
}

WorkoutSession::WorkoutSession(ExerciseType exercise)
    : exercise_(exercise), evaluator_(exercise), counter_({0.75f, 0.55f}) {}

EvaluationResult WorkoutSession::process(const PoseFrame& frame, float elapsed_seconds) {
  const auto result = evaluator_.evaluate(extract_features(frame));
  if (!result.pose_detected) return result;
  if (result.is_hold) {
    elapsed_seconds_ = elapsed_seconds;
    quality_sum_ += result.quality_score;
    ++quality_samples_;
    calories_ = elapsed_seconds_ * 0.15f;
    points_ = static_cast<int>(calories_ * 10.0f);
    return result;
  }
  const auto event = counter_.update(progress_for(result, exercise_));
  elapsed_seconds_ = elapsed_seconds;
  quality_sum_ += result.quality_score;
  ++quality_samples_;
  if (event.repetition_completed) {
    points_ += static_cast<int>(10.0f * result.quality_score);
    calories_ += calories_per_repetition(exercise_);
  }
  auto updated = result;
  updated.repetition_completed = event.repetition_completed;
  return updated;
}

EvaluationResult WorkoutSession::process_progress(float progress, float elapsed_seconds,
                                                  const std::vector<std::string>& feedback) {
  const auto event = counter_.update(progress);
  elapsed_seconds_ = elapsed_seconds;
  const float quality = feedback.empty() ? 1.0f : 0.7f;
  quality_sum_ += quality;
  ++quality_samples_;
  if (event.repetition_completed) {
    points_ += static_cast<int>(10.0f * quality);
    calories_ += calories_per_repetition(exercise_);
  }
  EvaluationResult result;
  result.pose_detected = true;
  result.in_target_position = progress >= 0.75f;
  result.repetition_completed = event.repetition_completed;
  result.quality_score = quality;
  result.feedback = feedback;
  return result;
}

SessionSummary WorkoutSession::summary() const {
  return {counter_.count(), points_, calories_,
          quality_samples_ ? quality_sum_ / quality_samples_ : 0.0f,
          elapsed_seconds_};
}

void WorkoutSession::reset() {
  counter_.reset();
  points_ = 0;
  calories_ = 0.0f;
  quality_sum_ = 0.0f;
  quality_samples_ = 0;
  elapsed_seconds_ = 0.0f;
}

}
