-- =============================================================================
-- 校园智能运动平台 · 开发用种子数据（MySQL 8.0.16+）
-- -----------------------------------------------------------------------------
-- 只提供模型已实现的五种运动项目及其 v1 规则，便于其他模块联调。
-- 权威口径：model/Cadence-main/docs/canonical-rules.md
-- 重要：下列角度、距离、质量和积分参数是课程原型联调值，不是学校体育评分标准；
--       正式试用前必须由体育指导人员确认（需求 Q02）。重复执行不会覆盖已有行。
-- 账号不在此处创建：密码必须由后端以 bcrypt/argon2 生成后写入 sys_user。
-- =============================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- -----------------------------------------------------------------------------
-- 运动项目（五种，与模型 ExerciseType 对齐）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO exercise (code, name, exercise_type, description, is_enabled)
VALUES
    ('squat', '深蹲', 'reps',
     '双脚与肩同宽，下蹲至膝角不超过90°，再站起回位。建议侧向机位，左侧身体朝向镜头，保证髋、膝、踝可见。',
     1),
    ('pushup', '俯卧撑', 'reps',
     '身体保持直线，下降至肘角不超过90°，再撑起。建议侧向机位，左侧身体朝向镜头，保证肩、肘、腕、髋、膝可见。',
     1),
    ('lunge', '弓步蹲', 'reps',
     '前后分腿，下蹲至前膝角不超过90°，再回位。建议侧向机位，左侧身体朝向镜头，保证髋、膝、踝可见。',
     1),
    ('plank', '平板支撑', 'hold',
     '以前臂和脚尖支撑，肩、髋、膝、踝保持一条直线。建议侧向机位，左侧身体朝向镜头，保证肩、髋、膝、踝可见。',
     1),
    ('jumpingjack', '开合跳', 'reps',
     '双臂展开至腕距不小于0.70，再并拢回位。建议正面机位，保证肩、肘、腕、髋、膝、踝可见。',
     1);

-- -----------------------------------------------------------------------------
-- 规则版本 1（已发布，供联调）
--   计数型：进入 0.75 / 离开 0.55；每次 floor(10 × 质量分) 分
--   保持型：每秒 floor(0.15 × 10) = 1.5 分
--   日上限：600 分（平台级，按模型分值标定）
-- -----------------------------------------------------------------------------

-- 深蹲
INSERT IGNORE INTO exercise_rule (
    exercise_id, version_no, status,
    joint_definition, threshold_config,
    main_metric, target_direction, target_low, target_high,
    enter_threshold, exit_threshold, min_visibility,
    points_per_rep, points_per_second, calories_per_rep, calories_per_second,
    max_session_duration_ms, daily_points_cap,
    effective_from, published_at, published_by
)
SELECT
    e.exercise_id, 1, 'published',
    CAST('{
      "required_joints": ["left_shoulder", "right_shoulder", "left_hip", "right_hip",
                          "left_knee", "right_knee", "left_ankle", "right_ankle"],
      "angle_triplets": {
        "left_knee_angle": ["left_hip", "left_knee", "left_ankle"],
        "right_knee_angle": ["right_hip", "right_knee", "right_ankle"],
        "hip_angle": ["left_shoulder", "left_hip", "left_knee"]
      },
      "issues": [
        {"code": "knee_asymmetry", "condition": "abs(left_knee_angle - right_knee_angle) > 20", "quality": 0.7, "message": "左右膝屈度不一致，调整重心"},
        {"code": "torso_lean", "condition": "hip_angle < 60", "quality": 0.7, "message": "躯干前倾过多，注意挺胸"}
      ],
      "note": "课程联调示例；主膝角取左右较小值，髋角取左侧"
    }' AS JSON),
    CAST('{
      "quality_formula": "base 1.0; knee_asymmetry: 0.7; torso_lean: 0.7",
      "score_formula": "points += floor(10 * quality) on each completed rep",
      "note": "课程联调示例，非学校体育评分标准"
    }' AS JSON),
    'knee_angle', 'lte', 90.000, NULL,
    0.750, 0.550, 0.50,
    10, 0.000, 0.600, 0.000,
    1200000, 600,
    '2026-11-04 00:00:00.000', '2026-11-04 00:00:00.000', NULL
FROM exercise e
WHERE e.code = 'squat';

-- 俯卧撑
INSERT IGNORE INTO exercise_rule (
    exercise_id, version_no, status,
    joint_definition, threshold_config,
    main_metric, target_direction, target_low, target_high,
    enter_threshold, exit_threshold, min_visibility,
    points_per_rep, points_per_second, calories_per_rep, calories_per_second,
    max_session_duration_ms, daily_points_cap,
    effective_from, published_at, published_by
)
SELECT
    e.exercise_id, 1, 'published',
    CAST('{
      "required_joints": ["left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
                          "left_wrist", "right_wrist", "left_hip", "right_hip",
                          "left_knee", "right_knee"],
      "angle_triplets": {
        "left_elbow_angle": ["left_shoulder", "left_elbow", "left_wrist"],
        "right_elbow_angle": ["right_shoulder", "right_elbow", "right_wrist"],
        "hip_angle": ["left_shoulder", "left_hip", "left_knee"]
      },
      "issues": [
        {"code": "body_not_straight", "condition": "hip_angle < 150 or hip_angle > 200", "quality": 0.7, "message": "保持身体直线，避免塌腰或翘臀"}
      ],
      "note": "课程联调示例；主肘角取左右较小值"
    }' AS JSON),
    CAST('{
      "quality_formula": "base 1.0; body_not_straight: 0.7",
      "score_formula": "points += floor(10 * quality) on each completed rep",
      "note": "课程联调示例，非学校体育评分标准"
    }' AS JSON),
    'elbow_angle', 'lte', 90.000, NULL,
    0.750, 0.550, 0.50,
    10, 0.000, 0.500, 0.000,
    1200000, 600,
    '2026-11-04 00:00:00.000', '2026-11-04 00:00:00.000', NULL
FROM exercise e
WHERE e.code = 'pushup';

-- 弓步蹲
INSERT IGNORE INTO exercise_rule (
    exercise_id, version_no, status,
    joint_definition, threshold_config,
    main_metric, target_direction, target_low, target_high,
    enter_threshold, exit_threshold, min_visibility,
    points_per_rep, points_per_second, calories_per_rep, calories_per_second,
    max_session_duration_ms, daily_points_cap,
    effective_from, published_at, published_by
)
SELECT
    e.exercise_id, 1, 'published',
    CAST('{
      "required_joints": ["left_shoulder", "right_shoulder", "left_hip", "right_hip",
                          "left_knee", "right_knee", "left_ankle", "right_ankle"],
      "angle_triplets": {
        "left_knee_angle": ["left_hip", "left_knee", "left_ankle"],
        "right_knee_angle": ["right_hip", "right_knee", "right_ankle"],
        "hip_angle": ["left_shoulder", "left_hip", "left_knee"]
      },
      "issues": [
        {"code": "knee_asymmetry", "condition": "abs(left_knee_angle - right_knee_angle) > 20", "quality": 0.7, "message": "左右膝屈度不一致，调整重心"},
        {"code": "torso_lean", "condition": "hip_angle < 60", "quality": 0.7, "message": "躯干前倾过多，注意挺胸"}
      ],
      "note": "课程联调示例；主膝角取左右较小值，髋角取左侧"
    }' AS JSON),
    CAST('{
      "quality_formula": "base 1.0; knee_asymmetry: 0.7; torso_lean: 0.7",
      "score_formula": "points += floor(10 * quality) on each completed rep",
      "note": "课程联调示例，非学校体育评分标准"
    }' AS JSON),
    'knee_angle', 'lte', 90.000, NULL,
    0.750, 0.550, 0.50,
    10, 0.000, 0.700, 0.000,
    1200000, 600,
    '2026-11-04 00:00:00.000', '2026-11-04 00:00:00.000', NULL
FROM exercise e
WHERE e.code = 'lunge';

-- 平板支撑
INSERT IGNORE INTO exercise_rule (
    exercise_id, version_no, status,
    joint_definition, threshold_config,
    main_metric, target_direction, target_low, target_high,
    enter_threshold, exit_threshold, min_visibility,
    points_per_rep, points_per_second, calories_per_rep, calories_per_second,
    max_session_duration_ms, daily_points_cap,
    effective_from, published_at, published_by
)
SELECT
    e.exercise_id, 1, 'published',
    CAST('{
      "required_joints": ["left_shoulder", "right_shoulder", "left_hip", "right_hip",
                          "left_knee", "right_knee", "left_ankle", "right_ankle"],
      "angle_triplets": {
        "hip_angle": ["left_shoulder", "left_hip", "left_knee"]
      },
      "issues": [
        {"code": "body_not_straight", "condition": "quality < 0.65", "message": "保持身体直线，收紧核心"}
      ],
      "note": "保持型；髋角越接近180°质量越高"
    }' AS JSON),
    CAST('{
      "quality_formula": "max(0, 1 - abs(hip_angle - 180) / 60)",
      "score_formula": "points = floor(elapsedSeconds * 0.15 * 10)",
      "note": "课程联调示例，非学校体育评分标准"
    }' AS JSON),
    'hip_angle', 'hold', NULL, NULL,
    0.750, 0.550, 0.50,
    0, 1.500, 0.000, 0.150,
    1200000, 600,
    '2026-11-04 00:00:00.000', '2026-11-04 00:00:00.000', NULL
FROM exercise e
WHERE e.code = 'plank';

-- 开合跳
INSERT IGNORE INTO exercise_rule (
    exercise_id, version_no, status,
    joint_definition, threshold_config,
    main_metric, target_direction, target_low, target_high,
    enter_threshold, exit_threshold, min_visibility,
    points_per_rep, points_per_second, calories_per_rep, calories_per_second,
    max_session_duration_ms, daily_points_cap,
    effective_from, published_at, published_by
)
SELECT
    e.exercise_id, 1, 'published',
    CAST('{
      "required_joints": ["left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
                          "left_wrist", "right_wrist", "left_hip", "right_hip",
                          "left_knee", "right_knee", "left_ankle", "right_ankle"],
      "distance_pairs": {
        "wrist_distance": ["left_wrist", "right_wrist"]
      },
      "issues": [],
      "note": "主度量为左右腕二维距离；C++ 核心只判定腕距，手臂协调性提示仅为原型参考，不进入平台规则"
    }' AS JSON),
    CAST('{
      "quality_formula": "target wrist_distance >= 0.70 -> 1.0, else 0.7",
      "score_formula": "points += floor(10 * quality) on each completed rep",
      "note": "课程联调示例，非学校体育评分标准"
    }' AS JSON),
    'wrist_distance', 'gte', 0.700, NULL,
    0.750, 0.550, 0.50,
    10, 0.000, 0.400, 0.000,
    1200000, 600,
    '2026-11-04 00:00:00.000', '2026-11-04 00:00:00.000', NULL
FROM exercise e
WHERE e.code = 'jumpingjack';

-- 手工建号模板（哈希必须由后端生成，禁止明文）：
-- INSERT INTO sys_user (login_id, nickname, password_hash, role)
-- VALUES ('20260001', '测试学生', '<bcrypt-or-argon2-hash>', 'student');
