#pragma once

#include "exercise_rules.h"
#include "pose_features.h"
#include "repetition_counter.h"

namespace cadence {

class WorkoutSession {
 public:
  explicit WorkoutSession(ExerciseType exercise);

  EvaluationResult process(const PoseFrame& frame, float elapsed_seconds);
  EvaluationResult process_progress(float progress, float elapsed_seconds,
                                    const std::vector<std::string>& feedback);
  SessionSummary summary() const;
  void reset();

  /** The dip this exercise needs, in degrees — the page prints it in hints. */
  float enter_depth_degrees() const {
    return exercise_ == ExerciseType::PushUp ? kPushUpEnterDepthDegrees : kEnterDepthDegrees;
  }

 private:
  // A rep is measured against this lifter's own standing angle rather than a
  // fixed knee angle: two pose models disagree by more than ten degrees on the
  // same clip, and a fixed threshold then either never closes a rep or closes
  // one on a bob. The baseline follows the highest angle seen recently.
  static constexpr float kBaselineRisePerSecond = 120.0f;
  static constexpr float kBaselineFallPerSecond = 10.0f;
  static constexpr float kMinimumDipPerFrame = 0.5f;
  static constexpr float kEnterDepthDegrees = 40.0f;
  // A push-up is measured at the elbow, and a side-on push-up hides most of
  // that flexion: a professional clip read only 15-25 degrees per rep where a
  // knee read 100. The bar has to sit where the joint is actually measured.
  static constexpr float kPushUpEnterDepthDegrees = 16.0f;

  float extension_baseline_{0.0f};
  bool extension_baseline_ready_{false};
  float last_elapsed_seconds_{-1.0f};

  ExerciseType exercise_;
  ExerciseEvaluator evaluator_;
  RepetitionCounter counter_;
  int points_{0};
  float calories_{0};
  float quality_sum_{0};
  int quality_samples_{0};
  float elapsed_seconds_{0};
};

}
