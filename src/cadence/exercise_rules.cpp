#include "exercise_rules.h"
#include <algorithm>
#include <cmath>
namespace cadence {
EvaluationResult ExerciseEvaluator::evaluate(const PoseFeatures& f) const {
  EvaluationResult r;
  switch (exercise_) {
    case ExerciseType::Plank: {
      // The line matters, not which joints measured it: when the knee is hidden
      // (legs pointing away from the camera) the ankle still gives the same
      // straight-line reading, and the hold can keep counting.
      const std::optional<float> line = f.hip_angle ? f.hip_angle : f.shoulder_hip_ankle_angle;
      if (!line) return r;
      r.pose_detected=true; r.is_hold=true; r.main_value=*line;
      r.quality_score=std::max(0.0f, 1.0f-std::fabs(*line-180.0f)/60.0f);
      if (r.quality_score < .65f) r.feedback.push_back("保持身体直线，收紧核心");
      return r;
    }
    case ExerciseType::JumpingJack:
      if (!f.wrist_distance) return r;
      r.pose_detected=true; r.main_value=*f.wrist_distance;
      r.in_target_position=*f.wrist_distance >= .7f;
      r.quality_score=r.in_target_position ? 1.0f : .7f;
      return r;
    case ExerciseType::PushUp:
      if (!f.elbow_angle) return r;
      r.pose_detected=true; r.main_value=*f.elbow_angle;
      r.in_target_position=*f.elbow_angle <= 90.0f;
      r.quality_score=1.0f;
      if (f.hip_angle && (*f.hip_angle < 150.0f || *f.hip_angle > 200.0f)) { r.quality_score=.7f; r.feedback.push_back("保持身体直线，避免塌腰或翘臀"); }
      return r;
    case ExerciseType::Squat:
    case ExerciseType::Lunge:
      if (!f.knee_angle) return r;
      r.pose_detected=true; r.main_value=*f.knee_angle;
      r.in_target_position=*f.knee_angle <= 90.0f;
      r.quality_score=1.0f;
      if (f.left_knee_angle && f.right_knee_angle && std::fabs(*f.left_knee_angle-*f.right_knee_angle)>20.0f) { r.quality_score=.7f; r.feedback.push_back("左右膝屈度不一致，调整重心"); }
      if (f.hip_angle && *f.hip_angle < 60.0f) { r.quality_score=std::min(r.quality_score,.7f); r.feedback.push_back("躯干前倾过多，注意挺胸"); }
      return r;
  }
  return r;
}
}
