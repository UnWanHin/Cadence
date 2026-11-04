#include "cadence/exercise_rules.h"
#include "cadence/pose_source.h"
#include "cadence/workout_session.h"

#include <iostream>
#include <memory>
#include <sstream>

int main() {
  cadence::ExerciseEvaluator evaluator(cadence::ExerciseType::Squat);
  cadence::PoseFeatures features;
  features.knee_angle = 85.0f;
  const auto result = evaluator.evaluate(features);
  std::cout << "Cadence C++ core demo\nquality=" << result.quality_score
            << " target=" << result.in_target_position << '\n';

  std::istringstream sample;
  auto source = std::make_unique<cadence::TextPoseSource>(sample);
  cadence::PoseFrameProcessor processor(std::move(source));
  std::cout << "frames=" << processor.process_all([](const cadence::PoseFrame&) {}) << '\n';
  return 0;
}
