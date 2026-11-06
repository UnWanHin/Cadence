#include "cadence/exercise_rules.h"
#include "cadence/geometry.h"
#include "cadence/pose_features.h"
#include "cadence/pose_source.h"
#include "cadence/pose_inference.h"
#include "cadence/pose_backend.h"
#include "cadence/repetition_counter.h"
#include "cadence/workout_session.h"
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>
#include <cmath>

#include <cmath>
#include <iostream>
#include <sstream>
#include <stdexcept>

namespace {
void expect(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

void expect_close(float actual, float expected, float epsilon, const char* message) {
  expect(std::fabs(actual - expected) <= epsilon, message);
}

void test_right_angle() {
  expect_close(cadence::angle_at({0, 0, 0, 1}, {0, 1, 0, 1}, {1, 1, 0, 1}), 90.0f, 0.01f,
               "angle_at should return a right angle");
}

void test_squat_evaluation() {
  cadence::ExerciseEvaluator evaluator(cadence::ExerciseType::Squat);
  cadence::PoseFeatures features;
  features.knee_angle = 85.0f;
  features.left_knee_angle = 84.0f;
  features.right_knee_angle = 86.0f;
  features.hip_angle = 100.0f;
  const auto result = evaluator.evaluate(features);
  expect(result.in_target_position, "squat below target angle should be in target");
  expect(result.quality_score > 0.8f, "balanced squat should have high quality");
}

void test_counter_requires_full_cycle() {
  cadence::RepetitionCounter counter({0.75f, 0.55f});
  expect(!counter.update(0.8f).repetition_completed, "entering target must not count yet");
  expect(counter.update(0.4f).repetition_completed, "returning from target should count one rep");
  expect(counter.count() == 1, "counter should contain one repetition");
}

void test_plank_is_hold() {
  cadence::ExerciseEvaluator evaluator(cadence::ExerciseType::Plank);
  cadence::PoseFeatures features;
  features.hip_angle = 178.0f;
  const auto result = evaluator.evaluate(features);
  expect(result.is_hold, "plank should be a hold exercise");
  expect(result.quality_score > 0.9f, "straight plank should score highly");
}

void test_missing_landmarks_are_not_used() {
  cadence::PoseFrame frame;
  frame.landmarks[23].visibility = 1.0f;
  frame.landmarks[25].visibility = 1.0f;
  frame.landmarks[27].visibility = 0.1f;
  const auto features = cadence::extract_features(frame);
  expect(!features.knee_angle.has_value(), "invisible ankle should invalidate knee angle");
}

void test_text_pose_source_reads_frame() {
  std::ostringstream serialized;
  for (int i = 0; i < 33; ++i) {
    serialized << i << " " << i + 0.5f << " 0 1 ";
  }
  serialized << "10\n";
  std::istringstream input(serialized.str());
  cadence::TextPoseSource source(input);
  cadence::PoseFrame frame;
  expect(source.next(frame), "text pose source should read a frame");
  expect(frame.landmarks[0].x == 0.0f, "text pose source should parse landmark x");
  expect(frame.timestamp_ms == 10, "text pose source should parse timestamp");
}

void test_inference_source_uses_backend() {
  cadence::InferenceConfig config;
  config.model_path = "local-model.task";
  bool called = false;
  cadence::InferencePoseSource source(config, [&](const cadence::InferenceConfig& received, cadence::PoseFrame& frame) {
    called = received.model_path == "local-model.task";
    frame.timestamp_ms = 42;
    return true;
  });
  cadence::PoseFrame frame;
  expect(source.next(frame), "configured inference source should produce a frame");
  expect(called && frame.timestamp_ms == 42, "inference source should pass config and frame through");
}

void test_unavailable_backend_reports_setup_state() {
  cadence::UnavailablePoseBackend backend;
  const auto status = backend.load("pose_landmarker_full.task");
  expect(!status.ready, "unconfigured native backend should not report ready");
  expect(!status.message.empty(), "unconfigured native backend should explain setup");
}

void test_inference_source_reports_missing_backend() {
  cadence::InferencePoseSource source({}, {});
  cadence::PoseFrame frame;
  expect(!source.next(frame), "missing inference backend should stop source");
  expect(!source.last_error().empty(), "missing backend should expose an error");
}

void test_session_processes_pose_frame() {
  cadence::WorkoutSession session(cadence::ExerciseType::Squat);
  cadence::PoseFrame frame;
  for (auto& landmark : frame.landmarks) landmark.visibility = 1.0f;
  frame.landmarks[23] = {0.0f, 0.0f, 0.0f, 1.0f};
  frame.landmarks[25] = {0.0f, 1.0f, 0.0f, 1.0f};
  frame.landmarks[27] = {1.0f, 1.0f, 0.0f, 1.0f};
  frame.landmarks[11] = {0.0f, -1.0f, 0.0f, 1.0f};
  const auto result = session.process(frame, 1.0f);
  expect(result.pose_detected, "session should evaluate a visible pose frame");
}

void test_session_accumulates_metrics() {
  cadence::WorkoutSession session(cadence::ExerciseType::Squat);
  session.process_progress(0.8f, 0.0f, {});
  session.process_progress(0.4f, 1.0f, {});
  const auto summary = session.summary();
  expect(summary.repetitions == 1, "session should accumulate completed repetitions");
  expect(summary.points > 0, "completed repetition should award points");
}

// Builds a frame whose knee angle is exactly [knee_degrees]. The rest of the
// body is put where it keeps every joint visible and the hip angle sane.
cadence::PoseFrame frame_at_knee_angle(float knee_degrees) {
  cadence::PoseFrame frame;
  for (auto& landmark : frame.landmarks) landmark.visibility = 1.0f;
  const float radians = knee_degrees * 3.14159265358979f / 180.0f;
  const auto knee = std::sin(radians);
  const auto drop = -std::cos(radians);
  // left leg: knee at origin, hip straight up, ankle swung out by the angle
  frame.landmarks[25] = {0.0f, 0.0f, 0.0f, 1.0f};
  frame.landmarks[23] = {0.0f, -1.0f, 0.0f, 1.0f};
  frame.landmarks[27] = {knee, drop, 0.0f, 1.0f};
  frame.landmarks[11] = {0.0f, -2.0f, 0.0f, 1.0f};
  // right leg mirrors it a little to the side
  frame.landmarks[26] = {1.0f, 0.0f, 0.0f, 1.0f};
  frame.landmarks[24] = {1.0f, -1.0f, 0.0f, 1.0f};
  frame.landmarks[28] = {1.0f + knee, drop, 0.0f, 1.0f};
  frame.landmarks[12] = {1.0f, -2.0f, 0.0f, 1.0f};
  return frame;
}

/** Runs [cycles] squats that stand up to [top] and descend to [bottom]. */
int count_cycles(float top, float bottom, int cycles) {
  cadence::WorkoutSession session(cadence::ExerciseType::Squat);
  float time = 0.0f;
  int repetitions = 0;
  for (int cycle = 0; cycle < cycles; ++cycle) {
    for (int i = 0; i < 12; ++i) {
      session.process(frame_at_knee_angle(top), time);
      time += 0.04f;
    }
    for (int i = 1; i <= 12; ++i) {
      session.process(frame_at_knee_angle(top - (top - bottom) * i / 12.0f), time);
      time += 0.04f;
    }
    for (int i = 1; i <= 12; ++i) {
      const auto result = session.process(frame_at_knee_angle(bottom + (top - bottom) * i / 12.0f), time);
      time += 0.04f;
      if (result.repetition_completed) ++repetitions;
    }
    for (int i = 0; i < 8; ++i) {
      session.process(frame_at_knee_angle(top), time);
      time += 0.04f;
    }
  }
  return repetitions;
}

// Field report: a clip that used to count stopped counting when the pose model
// changed. The pose model reports a shallower standing angle (~119) than the
// previous one (~133), and the counter demanded 121.5 degrees to close a rep, so
// every squat entered the target zone and none was ever completed.
void test_reps_survive_a_shallower_standing_angle() {
  expect(count_cycles(119.0f, 30.0f, 3) == 3,
         "three squats must count three reps even when standing reads 119 degrees");
  expect(count_cycles(133.0f, 30.0f, 3) == 3,
         "three squats must count three reps when standing reads 133 degrees");
  expect(count_cycles(175.0f, 40.0f, 3) == 3,
         "three squats must count three reps for a near-straight standing pose");
}

void test_shallow_bobbing_is_not_a_rep() {
  expect(count_cycles(119.0f, 100.0f, 3) == 0,
         "a dip of only 19 degrees must not count as a squat");
  expect(count_cycles(160.0f, 145.0f, 4) == 0,
         "a dip of only 15 degrees must not count as a squat");
}

void test_noise_around_standing_is_not_a_rep() {
  cadence::WorkoutSession session(cadence::ExerciseType::Squat);
  float time = 0.0f;
  int repetitions = 0;
  const float sequence[] = {119.0f, 117.0f, 120.0f, 118.0f, 121.0f, 116.0f, 119.0f};
  for (int cycle = 0; cycle < 40; ++cycle) {
    for (const float angle : sequence) {
      if (session.process(frame_at_knee_angle(angle), time).repetition_completed) ++repetitions;
      time += 0.04f;
    }
  }
  expect(repetitions == 0, "jitter while standing must not count reps");
}

// Field report: a plank video showed quality but no time and no points. Root
// cause was the rule needing the knee, which a plank usually hides, so almost
// no frame was accepted at all.
cadence::PoseFrame plank_frame(bool hide_knee) {
  cadence::PoseFrame frame;
  for (auto& landmark : frame.landmarks) landmark.visibility = 1.0f;
  // shoulder, hip and ankle on one straight line
  frame.landmarks[11] = {0.0f, 0.0f, 0.0f, 1.0f};
  frame.landmarks[23] = {1.0f, 0.0f, 0.0f, 1.0f};
  frame.landmarks[27] = {2.0f, 0.0f, 0.0f, 1.0f};
  frame.landmarks[12] = {0.0f, 0.3f, 0.0f, 1.0f};
  frame.landmarks[24] = {1.0f, 0.3f, 0.0f, 1.0f};
  frame.landmarks[28] = {2.0f, 0.3f, 0.0f, 1.0f};
  // the knee sits between hip and ankle; hide it when the test asks for it
  frame.landmarks[25] = {1.5f, 0.0f, 0.0f, hide_knee ? 0.1f : 1.0f};
  frame.landmarks[26] = {1.5f, 0.3f, 0.0f, hide_knee ? 0.1f : 1.0f};
  return frame;
}

void test_plank_survives_a_hidden_knee() {
  cadence::ExerciseEvaluator evaluator(cadence::ExerciseType::Plank);
  const auto hidden = evaluator.evaluate(cadence::extract_features(plank_frame(true)));
  expect(hidden.pose_detected, "a plank must be accepted when the knee is hidden but the ankle is visible");
  expect(hidden.is_hold, "a plank with a hidden knee is still a hold");
  expect(hidden.quality_score > 0.9f, "a straight shoulder-hip-ankle line should score high");

  const auto visible = evaluator.evaluate(cadence::extract_features(plank_frame(false)));
  expect(visible.pose_detected && visible.is_hold, "a plank with a visible knee still works");
}

void test_plank_session_accumulates_time_and_points() {
  cadence::WorkoutSession session(cadence::ExerciseType::Plank);
  const auto frame = plank_frame(true);
  for (float time = 0.0f; time <= 6.0f; time += 1.0f / 24.0f) {
    session.process(frame, time);
  }
  const auto summary = session.summary();
  expect(summary.repetitions == 0, "a hold has no repetitions");
  expect(summary.elapsed_seconds > 5.9f, "a six second hold should record six seconds");
  expect(summary.points > 5, "a six second hold should award points");
  expect(summary.average_quality > 0.9f, "a straight plank should score high");
}

// Quality must not be inflated by the idle frames a clip spends standing (or
// lying) around doing nothing: a clip with zero repetitions reported 96.
void test_quality_is_not_inflated_without_movement() {
  cadence::WorkoutSession session(cadence::ExerciseType::PushUp);
  float time = 0.0f;
  // elbows stay almost straight: a plank filmed in push-up mode
  for (int frame = 0; frame < 200; ++frame) {
    cadence::PoseFrame pose;
    for (auto& landmark : pose.landmarks) landmark.visibility = 1.0f;
    pose.landmarks[11] = {0.0f, 0.0f, 0.0f, 1.0f};
    pose.landmarks[13] = {1.0f, 0.0f, 0.0f, 1.0f};
    pose.landmarks[15] = {2.0f, -0.05f, 0.0f, 1.0f};
    pose.landmarks[12] = {0.0f, 0.3f, 0.0f, 1.0f};
    pose.landmarks[14] = {1.0f, 0.3f, 0.0f, 1.0f};
    pose.landmarks[16] = {2.0f, 0.25f, 0.0f, 1.0f};
    session.process(pose, time);
    time += 1.0f / 24.0f;
  }
  const auto summary = session.summary();
  expect(summary.repetitions == 0, "a still elbow must not count a rep");
  expect(summary.average_quality == 0.0f,
         "a clip with no movement must not report a quality score");
}

// Field report: a professional side-on push-up clip was measured at 15-25
// degrees of elbow flexion per rep, well under the 40 degree knee bar, so it
// counted nothing. The push-up bar is 16 degrees; noise still must not count.
cadence::PoseFrame pushup_frame(float elbow_angle) {
  cadence::PoseFrame frame;
  for (auto& landmark : frame.landmarks) landmark.visibility = 1.0f;
  const float radians = elbow_angle * 3.14159265f / 180.0f;
  // left arm: shoulder -> elbow -> wrist, the asked-for angle between them
  frame.landmarks[11] = {0.0f, 0.0f, 0.0f, 1.0f};
  frame.landmarks[13] = {1.0f, 0.0f, 0.0f, 1.0f};
  frame.landmarks[15] = {1.0f - std::cos(radians), std::sin(radians), 0.0f, 1.0f};
  frame.landmarks[12] = {0.0f, 0.3f, 0.0f, 1.0f};
  frame.landmarks[14] = {1.0f, 0.3f, 0.0f, 1.0f};
  frame.landmarks[16] = {2.0f, 0.3f, 0.0f, 1.0f};
  frame.landmarks[23] = {0.0f, 0.6f, 0.0f, 1.0f};
  frame.landmarks[25] = {1.0f, 0.6f, 0.0f, 1.0f};
  frame.landmarks[27] = {2.0f, 0.6f, 0.0f, 1.0f};
  return frame;
}

/** Cycles of `depth` degrees starting from a straight elbow, at 24 fps. */
int count_pushup_cycles(float depth, int cycles) {
  cadence::WorkoutSession session(cadence::ExerciseType::PushUp);
  float time = 0.0f;
  const float frame_step = 1.0f / 24.0f;
  for (int cycle = 0; cycle < cycles; ++cycle) {
    for (int down = 0; down < 24; ++down) {
      session.process(pushup_frame(175.0f - depth * down / 24.0f), time);
      time += frame_step;
    }
    for (int up = 0; up < 24; ++up) {
      session.process(pushup_frame(175.0f - depth + depth * up / 24.0f), time);
      time += frame_step;
    }
  }
  return session.summary().repetitions;
}

// Real measurements, not guesses: these two series are the elbow angles the
// core produced for actual clips (see tests/fixtures). Feeding them back means
// the thresholds are pinned to what a camera really sees.
std::vector<float> load_angle_series(const char* name) {
  const std::string path = std::string(CADENCE_FIXTURE_DIR) + "/" + name;
  std::ifstream file(path);
  if (!file) throw std::runtime_error("cannot open fixture " + path);
  std::string text((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  std::vector<float> angles;
  const auto start = text.find('[', text.find("angles"));
  for (const char* cursor = text.c_str() + start; *cursor; ++cursor) {
    if (*cursor >= '0' && (*cursor <= '9' || *cursor == '.' || *cursor == '-')) {
      char* end = nullptr;
      const float value = std::strtof(cursor, &end);
      angles.push_back(value);
      cursor = end - 1;
    } else if (*cursor == ']') {
      break;
    }
  }
  return angles;
}

int count_reps_in_series(const std::vector<float>& angles, cadence::ExerciseType type,
                         float fps = 29.8f) {
  cadence::WorkoutSession session(type);
  float time = 0.0f;
  for (const float angle : angles) {
    cadence::PoseFrame frame;
    for (auto& landmark : frame.landmarks) landmark.visibility = 1.0f;
    const float radians = angle * 3.14159265f / 180.0f;
    frame.landmarks[11] = {0.0f, 0.0f, 0.0f, 1.0f};
    frame.landmarks[13] = {1.0f, 0.0f, 0.0f, 1.0f};
    frame.landmarks[15] = {1.0f - std::cos(radians), std::sin(radians), 0.0f, 1.0f};
    // the right arm stays straight, so the core's minimum() sees the left one
    frame.landmarks[12] = {0.0f, 0.3f, 0.0f, 1.0f};
    frame.landmarks[14] = {1.0f, 0.3f, 0.0f, 1.0f};
    frame.landmarks[16] = {2.0f, 0.3f, 0.0f, 1.0f};
    frame.landmarks[23] = {0.0f, 0.6f, 0.0f, 1.0f};
    frame.landmarks[25] = {1.0f, 0.6f, 0.0f, 1.0f};
    frame.landmarks[27] = {2.0f, 0.6f, 0.0f, 1.0f};
    session.process(frame, time);
    time += 1.0f / fps;
  }
  return session.summary().repetitions;
}

void test_a_professional_pushup_counts() {
  const auto pushup = load_angle_series("pushup-elbow.json");
  expect(!pushup.empty(), "the push-up fixture loaded");
  expect(count_reps_in_series(pushup, cadence::ExerciseType::PushUp) == 3,
         "a real side-on push-up clip must count three reps");
}

void test_pushup_noise_is_not_a_rep() {
  // Same core, same fps, but a clip where the arms never move: a forearm plank.
  const auto plank = load_angle_series("plank-elbow.json");
  expect(!plank.empty(), "the plank fixture loaded");
  const int spurious = count_reps_in_series(plank, cadence::ExerciseType::PushUp);
  expect(spurious <= 1, "arm jitter must not turn into a pile of reps");
}

void test_squat_still_needs_a_real_dip() {
  // The push-up bar is looser; the knee bar must not move with it.
  cadence::WorkoutSession session(cadence::ExerciseType::Squat);
  float time = 0.0f;
  for (int cycle = 0; cycle < 2; ++cycle) {
    for (int down = 0; down < 24; ++down) {
      session.process(frame_at_knee_angle(170.0f - 25.0f * down / 24.0f), time);
      time += 1.0f / 24.0f;
    }
    for (int up = 0; up < 24; ++up) {
      session.process(frame_at_knee_angle(145.0f + 25.0f * up / 24.0f), time);
      time += 1.0f / 24.0f;
    }
  }
  expect(session.summary().repetitions == 0, "a 25 degree knee dip is still not a squat");
}
}

int main() {
  try {
    test_right_angle();
    test_squat_evaluation();
    test_counter_requires_full_cycle();
    test_plank_is_hold();
    test_missing_landmarks_are_not_used();
    test_text_pose_source_reads_frame();
    test_inference_source_uses_backend();
    test_unavailable_backend_reports_setup_state();
    test_inference_source_reports_missing_backend();
    test_session_processes_pose_frame();
    test_session_accumulates_metrics();
    test_reps_survive_a_shallower_standing_angle();
    test_shallow_bobbing_is_not_a_rep();
    test_noise_around_standing_is_not_a_rep();
    test_plank_survives_a_hidden_knee();
    test_plank_session_accumulates_time_and_points();
    test_quality_is_not_inflated_without_movement();
    test_a_professional_pushup_counts();
    test_pushup_noise_is_not_a_rep();
    test_squat_still_needs_a_real_dip();
    std::cout << "cadence_tests: all tests passed\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "cadence_tests: " << error.what() << '\n';
    return 1;
  }
}
