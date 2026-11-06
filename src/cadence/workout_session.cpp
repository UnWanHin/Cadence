#include "workout_session.h"

#include <algorithm>

namespace cadence {

namespace {
// Angle-based exercises are counted by how far the joint travels from its own
// extended position, so the same rule works for any camera angle or model.
bool uses_depth_gate(ExerciseType exercise) {
  return exercise == ExerciseType::Squat || exercise == ExerciseType::Lunge ||
         exercise == ExerciseType::PushUp;
}

// process_progress callers pass a 0..1 progress with the historical contract
// "0.75 means in target, 0.55 means back out". The shared counter now works in
// depth space (1.0 entered, 0.5 exited), so the two are mapped here instead of
// running two counters that would both feed the same summary.
float legacy_progress_to_depth(float progress) {
  if (progress >= 0.75f) return 1.0f;
  if (progress <= 0.55f) return 0.0f;
  return (progress - 0.55f) / 0.20f;
}

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
    : exercise_(exercise), evaluator_(exercise), counter_({1.0f, 0.5f}) {}

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
  const float delta = last_elapsed_seconds_ < 0.0f
      ? 0.0f
      : std::max(0.0f, elapsed_seconds - last_elapsed_seconds_);
  last_elapsed_seconds_ = elapsed_seconds;

  float progress = 0.0f;
  if (uses_depth_gate(exercise_)) {
    const float angle = result.main_value;
    if (!extension_baseline_ready_) {
      extension_baseline_ = angle;
      extension_baseline_ready_ = true;
    } else if (angle > extension_baseline_) {
      // Rising extension is followed quickly, so the baseline reaches standing.
      extension_baseline_ = std::min(
          angle, extension_baseline_ + kBaselineRisePerSecond * delta + kMinimumDipPerFrame);
    } else {
      // It decays slowly, so holding at the bottom does not shrink the dip.
      extension_baseline_ = std::max(angle, extension_baseline_ - kBaselineFallPerSecond * delta);
    }
    const float depth = extension_baseline_ - angle;
    const float enter_depth = exercise_ == ExerciseType::PushUp
        ? kPushUpEnterDepthDegrees
        : kEnterDepthDegrees;
    progress = std::clamp(depth / enter_depth, 0.0f, 1.0f);
  } else {
    progress = progress_for(result, exercise_);
  }
  const auto event = counter_.update(progress);
  elapsed_seconds_ = elapsed_seconds;
  // Quality is a form score for the movement, so it is sampled where the
  // movement happens. Averaging every idle frame made a clip with no reps at
  // all still report "质量 96", which reads as "it should have counted".
  if (!uses_depth_gate(exercise_) || progress >= 0.5f) {
    quality_sum_ += result.quality_score;
    ++quality_samples_;
  }
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
  const auto event = counter_.update(legacy_progress_to_depth(progress));
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
  extension_baseline_ = 0.0f;
  extension_baseline_ready_ = false;
  last_elapsed_seconds_ = -1.0f;
  points_ = 0;
  calories_ = 0.0f;
  quality_sum_ = 0.0f;
  quality_samples_ = 0;
  elapsed_seconds_ = 0.0f;
}

}
