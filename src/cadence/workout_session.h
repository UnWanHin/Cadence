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

 private:
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
