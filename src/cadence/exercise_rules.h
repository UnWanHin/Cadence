#pragma once
#include "types.h"
namespace cadence {
class ExerciseEvaluator {
 public:
  explicit ExerciseEvaluator(ExerciseType exercise) : exercise_(exercise) {}
  EvaluationResult evaluate(const PoseFeatures& features) const;
 private:
  ExerciseType exercise_;
};
}
