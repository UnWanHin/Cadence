-- =============================================================================
-- 校园智能运动平台 · 逻辑数据库结构（MySQL 8.0.16+）
-- -----------------------------------------------------------------------------
-- 依据：
--   - 《校园智能运动平台_需求分析文档》8.1/8.2（模型对齐版 V1.1）
--   - model/Cadence-main/docs/canonical-rules.md（动作与积分权威口径）
--   - database/API.md / database/openapi.yaml（服务端接口契约）
--
-- 定位：提供给各业务模块共同依赖的数据库契约（DDL + 视图），不是数据库实例、
--       种子数据或后端 ORM。首次建库时执行；后续结构变更请新增 migration 文件。
--
-- 约定：
--   1. 时间统一以 UTC 写入 DATETIME(3)，连接必须设置 time_zone = '+00:00'；
--      业务日界线（Asia/Shanghai 零点）由服务层换算后写入 attribution_date。
--   2. 时长统一为非负整数毫秒；积分、次数为非负整数；质量分为 0—1 的小数。
--   3. 主键使用 BIGINT UNSIGNED 代理键；会话额外提供对外 UUID（public_id）。
--   4. 已发布规则不可覆盖、每日积分上限、结算幂等中的“读-算-写”由服务层事务保证，
--      数据库负责唯一性、外键、数值边界和“一个会话至多一条原始入账”。
--   5. 原始视频、逐帧关键点、明文密码不进入本库；本地视频库只在
--      local_video_asset 中保存元数据，二进制文件留在设备/局域网主机。
-- =============================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 如需固定库名，取消下面两行注释后执行：
-- CREATE DATABASE IF NOT EXISTS campus_sport
--   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- USE campus_sport;


-- -----------------------------------------------------------------------------
-- 1. 用户 User
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    user_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    login_id        VARCHAR(64)     NOT NULL                COMMENT '登录标识（学号/测试账号），全局唯一',
    nickname        VARCHAR(20)     NOT NULL                COMMENT '昵称，排行榜展示名，1-20字符',
    password_hash   VARCHAR(255)    NOT NULL                COMMENT '密码不可逆验证值（bcrypt/argon2），禁止明文',
    role            ENUM('student', 'admin') NOT NULL DEFAULT 'student' COMMENT '角色：学生/管理员',
    ranking_opt_in  TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否自愿参加排行榜，默认关闭',
    status          ENUM('active', 'disabled', 'deleted') NOT NULL DEFAULT 'active' COMMENT '账号状态',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_user_login (login_id),
    KEY idx_user_ranking (ranking_opt_in, status),
    CONSTRAINT chk_user_nickname_len CHECK (CHAR_LENGTH(nickname) BETWEEN 1 AND 20)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户（需求 8.1 User）';


-- -----------------------------------------------------------------------------
-- 2. 运动项目 Exercise
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exercise (
    exercise_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '运动项目ID',
    code            VARCHAR(32)     NOT NULL                COMMENT '稳定项目编码：squat/pushup/lunge/plank/jumpingjack',
    name            VARCHAR(50)     NOT NULL                COMMENT '项目名称',
    exercise_type   ENUM('reps', 'hold') NOT NULL DEFAULT 'reps' COMMENT '计数型/保持型；对应模型 WorkoutSession 分支',
    description     TEXT            NOT NULL                COMMENT '动作说明、机位要求、判断范围',
    is_enabled      TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否启用；启用前须有完整已发布规则（服务层校验）',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (exercise_id),
    UNIQUE KEY uk_exercise_code (code),
    UNIQUE KEY uk_exercise_name (name),
    KEY idx_exercise_enabled (is_enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '运动项目（需求 8.1 Exercise；与模型五种动作对齐）';


-- -----------------------------------------------------------------------------
-- 3. 运动规则 Rule（版本化，历史版本只读）
--    规则字段直接对应 model/Cadence-main 的 ExerciseEvaluator / WorkoutSession：
--      - main_metric         主特征：knee_angle / elbow_angle / hip_angle / wrist_distance
--      - target_direction    lte（角度越小越到位）/ gte（距离越大越到位）/ range（区间）/ hold（保持型）
--      - target_low/high     目标边界；hold 型为空
--      - enter/exit_threshold 计数状态机进入/离开阈值（模型 0.75/0.55）
--      - points_per_rep      计数型每次基础积分（模型 floor(10 × q) 的 10）
--      - points_per_second   保持型每秒基础积分（模型 0.15 × 10 = 1.5）
--      - calories_*          模型卡路里常量
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exercise_rule (
    rule_id                 BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '规则ID',
    exercise_id             BIGINT UNSIGNED  NOT NULL                COMMENT '所属运动项目ID',
    version_no              INT UNSIGNED     NOT NULL                COMMENT '项目内版本号，从 1 递增',
    status                  ENUM('draft', 'published', 'retired') NOT NULL DEFAULT 'draft' COMMENT '草稿/已发布/已停用',
    joint_definition        JSON             NOT NULL                COMMENT '必要关节、角度三元组、纠错提示等定义',
    threshold_config        JSON             NOT NULL                COMMENT '质量分公式、备注等补充配置；与显式列重名时以显式列为准',
    main_metric             VARCHAR(32)      NOT NULL                COMMENT '主特征：knee_angle/elbow_angle/hip_angle/wrist_distance',
    target_direction        ENUM('lte', 'gte', 'range', 'hold') NOT NULL DEFAULT 'lte' COMMENT '目标方向：角度越小/距离越大/区间/保持型',
    target_low              DECIMAL(8, 3)    NULL                    COMMENT '目标下界；lte 型表示最大允许值，gte 型表示最小允许值',
    target_high             DECIMAL(8, 3)    NULL                    COMMENT '目标上界；range 型使用',
    enter_threshold         DECIMAL(4, 3)    NOT NULL DEFAULT 0.750  COMMENT '计数进入目标阈值（模型 0.75）',
    exit_threshold          DECIMAL(4, 3)    NOT NULL DEFAULT 0.550  COMMENT '计数离开目标阈值（模型 0.55）',
    min_visibility          DECIMAL(3, 2)    NOT NULL DEFAULT 0.50   COMMENT '必要关节最低可见性（模型 0.50）',
    points_per_rep          SMALLINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '计数型每次基础积分；模型为 10 × 质量分',
    points_per_second       DECIMAL(6, 3)    NOT NULL DEFAULT 0.000  COMMENT '保持型每秒基础积分；模型为 1.500',
    calories_per_rep        DECIMAL(6, 3)    NOT NULL DEFAULT 0.000  COMMENT '计数型每次卡路里',
    calories_per_second     DECIMAL(6, 3)    NOT NULL DEFAULT 0.000  COMMENT '保持型每秒卡路里',
    max_session_duration_ms INT UNSIGNED     NOT NULL DEFAULT 1200000 COMMENT '单次自然时长上限（默认20分钟）',
    daily_points_cap        SMALLINT UNSIGNED NOT NULL DEFAULT 600   COMMENT '每日积分上限，跨项目共同计算（模型分值标定）',
    effective_from          DATETIME(3)      NULL                    COMMENT '生效时间（UTC）；为空表示发布即生效',
    published_at            DATETIME(3)      NULL                    COMMENT '发布时间（UTC）',
    published_by            BIGINT UNSIGNED  NULL                    COMMENT '发布操作人用户ID',
    created_at              DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at              DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (rule_id),
    UNIQUE KEY uk_rule_version (exercise_id, version_no),
    UNIQUE KEY uk_rule_identity (rule_id, exercise_id),
    KEY idx_rule_active (exercise_id, status, effective_from),
    CONSTRAINT fk_rule_exercise FOREIGN KEY (exercise_id) REFERENCES exercise (exercise_id),
    CONSTRAINT fk_rule_publisher FOREIGN KEY (published_by) REFERENCES sys_user (user_id) ON DELETE SET NULL,
    CONSTRAINT chk_rule_visibility CHECK (min_visibility BETWEEN 0 AND 1),
    CONSTRAINT chk_rule_thresholds CHECK (
        enter_threshold > exit_threshold
        AND enter_threshold <= 1
        AND exit_threshold >= 0
    ),
    CONSTRAINT chk_rule_target CHECK (
        (target_direction = 'hold' AND target_low IS NULL AND target_high IS NULL)
        OR (target_direction = 'lte' AND target_low IS NOT NULL)
        OR (target_direction = 'gte' AND target_low IS NOT NULL)
        OR (target_direction = 'range' AND target_low IS NOT NULL
            AND target_high IS NOT NULL AND target_low <= target_high)
    ),
    CONSTRAINT chk_rule_points CHECK (points_per_rep >= 0 AND points_per_second >= 0),
    CONSTRAINT chk_rule_calories CHECK (calories_per_rep >= 0 AND calories_per_second >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '运动规则版本（需求 8.1 Rule；发布后不可覆盖；字段对齐模型）';


-- -----------------------------------------------------------------------------
-- 4. 运动会话 Session
--    valid_duration_ms = 有效运动时长（展示/统计；计数型不参与积分）
--    repetitions       = 模型完成次数（保持型为 0）
--    average_quality   = 模型平均质量分（0—1）
--    calories          = 模型卡路里
--    model_points      = 模型基础积分（未受日上限）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS training_session (
    session_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    public_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对外UUID，结算幂等键',
    user_id             BIGINT UNSIGNED NOT NULL                COMMENT '会话拥有者',
    exercise_id         BIGINT UNSIGNED NOT NULL                COMMENT '运动项目ID（与规则联合校验）',
    rule_id             BIGINT UNSIGNED NOT NULL                COMMENT '开始时锁定的规则版本',
    status              ENUM('preparing', 'detecting', 'timing', 'auto_paused',
                             'user_paused', 'pending_submit', 'settled', 'abnormal_end')
                        NOT NULL DEFAULT 'preparing'            COMMENT '会话状态，见需求 7.5',
    started_at          DATETIME(3)     NOT NULL                COMMENT '开始时间（UTC）',
    ended_at            DATETIME(3)     NULL                    COMMENT '结束时间（UTC）',
    natural_duration_ms INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '自然时长（毫秒）',
    valid_duration_ms   INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '有效运动时长（毫秒）；展示/统计用',
    repetitions         INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '模型完成次数；保持型为0',
    average_quality     DECIMAL(5, 4)   NOT NULL DEFAULT 0.0000 COMMENT '模型平均质量分0-1',
    calories            DECIMAL(8, 2)   NOT NULL DEFAULT 0.00   COMMENT '模型卡路里',
    model_points        INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '模型基础积分（未受日上限）',
    anomaly_summary     JSON            NULL                    COMMENT '未计数原因摘要，仅存原因码与毫秒数',
    submitted_at        DATETIME(3)     NULL                    COMMENT '首次提交时间（UTC）',
    settled_at          DATETIME(3)     NULL                    COMMENT '服务端确认结算时间（UTC）',
    deleted_at          DATETIME(3)     NULL                    COMMENT '用户删除时间（UTC）；软删除，统计与榜单排除',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (session_id),
    UNIQUE KEY uk_session_public_id (public_id),
    UNIQUE KEY uk_session_owner (session_id, user_id),
    KEY idx_session_user_time (user_id, started_at),
    KEY idx_session_user_status (user_id, status),
    KEY idx_session_exercise_time (exercise_id, started_at),
    KEY idx_session_status (status),
    KEY idx_session_rule (rule_id, exercise_id),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_session_rule FOREIGN KEY (rule_id, exercise_id)
        REFERENCES exercise_rule (rule_id, exercise_id),
    CONSTRAINT chk_session_duration CHECK (valid_duration_ms <= natural_duration_ms),
    CONSTRAINT chk_session_time CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT chk_session_quality CHECK (average_quality BETWEEN 0 AND 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '运动会话（需求 8.1 Session；摘要字段对齐模型）';


-- -----------------------------------------------------------------------------
-- 5. 积分账本 Ledger
--    credit   = 原始入账，一个会话至多一条；
--    reversal = 撤销记录，引用原入账，一个原入账至多被撤销一次。
--    日上限与周榜只汇总 status='active' 的 credit，reversal 仅作审计。
--    base_points 来自 training_session.model_points；actual_points 受日上限扣减。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS points_ledger (
    entry_id           BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '入账/撤销记录ID',
    session_id         BIGINT UNSIGNED   NOT NULL                COMMENT '关联会话ID',
    user_id            BIGINT UNSIGNED   NOT NULL                COMMENT '归属用户ID（冗余用于日上限与榜单聚合）',
    entry_type         ENUM('credit', 'reversal') NOT NULL       COMMENT '原始入账/撤销',
    base_points        SMALLINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '基础积分=模型 model_points；撤销记录复制原值',
    actual_points      SMALLINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '实际入账积分（受日上限扣减；撤销记录复制原值）',
    attribution_date   DATE              NOT NULL                COMMENT '归属日（Asia/Shanghai 零点口径）',
    week_start         DATE GENERATED ALWAYS AS
                       (attribution_date - INTERVAL WEEKDAY(attribution_date) DAY) STORED
                                                                COMMENT '归属周周一（生成列，便于周榜）',
    status             ENUM('active', 'revoked') NOT NULL DEFAULT 'active' COMMENT '入账是否仍有效',
    reverses_entry_id  BIGINT UNSIGNED   NULL                    COMMENT '撤销记录指向的原入账ID',
    created_at         DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (entry_id),
    UNIQUE KEY uk_ledger_session_type (session_id, entry_type),
    UNIQUE KEY uk_ledger_reversal (reverses_entry_id),
    KEY idx_ledger_user_day (user_id, attribution_date, entry_type, status),
    KEY idx_ledger_week (week_start, entry_type, status),
    KEY idx_ledger_session_user (session_id, user_id),
    CONSTRAINT fk_ledger_session FOREIGN KEY (session_id, user_id)
        REFERENCES training_session (session_id, user_id),
    CONSTRAINT fk_ledger_reversal FOREIGN KEY (reverses_entry_id)
        REFERENCES points_ledger (entry_id),
    CONSTRAINT chk_ledger_shape CHECK (
        (entry_type = 'credit'   AND reverses_entry_id IS NULL)
        OR
        (entry_type = 'reversal' AND reverses_entry_id IS NOT NULL)
    ),
    CONSTRAINT chk_ledger_points CHECK (actual_points <= base_points)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '积分账本（需求 8.1 Ledger；不设可编辑总分）';


-- -----------------------------------------------------------------------------
-- 6. 反馈 Feedback
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS feedback (
    feedback_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
    user_id       BIGINT UNSIGNED NOT NULL                COMMENT '提交学生',
    session_id    BIGINT UNSIGNED NULL                    COMMENT '关联会话，可为空',
    category      VARCHAR(32)     NOT NULL                COMMENT '问题类别：misjudge/timing/privacy/other 等',
    content       VARCHAR(1000)   NOT NULL                COMMENT '文字说明，最多1000字',
    status        ENUM('open', 'processing', 'resolved', 'rejected') NOT NULL DEFAULT 'open' COMMENT '处理状态',
    handled_by    BIGINT UNSIGNED NULL                    COMMENT '处理管理员',
    handled_at    DATETIME(3)     NULL                    COMMENT '处理时间（UTC）',
    handle_note   VARCHAR(1000)   NULL                    COMMENT '处理结果说明',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (feedback_id),
    KEY idx_feedback_user (user_id, created_at),
    KEY idx_feedback_status (status, created_at),
    KEY idx_feedback_session (session_id, user_id),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_feedback_session FOREIGN KEY (session_id, user_id)
        REFERENCES training_session (session_id, user_id),
    CONSTRAINT fk_feedback_handler FOREIGN KEY (handled_by) REFERENCES sys_user (user_id),
    CONSTRAINT chk_feedback_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 1000),
    CONSTRAINT chk_feedback_handle CHECK (
        (handled_by IS NULL AND handled_at IS NULL)
        OR (handled_by IS NOT NULL AND handled_at IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '误判/问题反馈（需求 8.1 Feedback）';


-- -----------------------------------------------------------------------------
-- 7. 审计 Audit（追加写，不存密码与原始影像）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    event_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '事件ID',
    actor_user_id  BIGINT UNSIGNED NULL                    COMMENT '操作者；系统事件可为空',
    action         VARCHAR(64)     NOT NULL                COMMENT '动作：rule.publish/session.revoke/record.delete 等',
    target_type    VARCHAR(32)     NOT NULL                COMMENT '对象类型：user/exercise/rule/session/feedback',
    target_id      VARCHAR(64)     NOT NULL                COMMENT '对象标识（ID或UUID）',
    result         ENUM('success', 'failure') NOT NULL     COMMENT '结果',
    detail         JSON            NULL                    COMMENT '去标识补充信息，禁止密码、令牌、影像',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_audit_target (target_type, target_id, created_at),
    KEY idx_audit_actor (actor_user_id, created_at),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user (user_id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '重要操作审计（需求 8.1 Audit）';


-- -----------------------------------------------------------------------------
-- 8. 本地视频元数据 LocalVideoAsset（局域网/端侧存储模拟）
--    原始视频文件不进入数据库，只保存设备或局域网主机上的本地文件索引。
--    删除视频时同时删除本地文件；本表仅用于未来接入 MySQL 时的元数据契约。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS local_video_asset (
    video_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对外视频UUID',
    owner_key       VARCHAR(64)     NOT NULL                COMMENT '本地档案名/设备标识，不是登录账号',
    original_name   VARCHAR(255)    NOT NULL                COMMENT '原始文件名',
    mime_type       VARCHAR(100)    NOT NULL                COMMENT 'video/mp4 等',
    byte_size       BIGINT UNSIGNED NOT NULL                COMMENT '文件字节数',
    duration_ms     INT UNSIGNED    NULL                    COMMENT '视频时长（毫秒）',
    width_px        INT UNSIGNED    NULL                    COMMENT '视频宽度',
    height_px       INT UNSIGNED    NULL                    COMMENT '视频高度',
    sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件内容SHA-256',
    storage_key     VARCHAR(255)    NOT NULL                COMMENT '本地文件键，不含目录前缀',
    storage_mode    ENUM('device', 'lan') NOT NULL DEFAULT 'lan' COMMENT '设备本地/局域网主机本地',
    status          ENUM('active', 'deleted') NOT NULL DEFAULT 'active' COMMENT '可用/已删除',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted_at      DATETIME(3)     NULL                    COMMENT '本地文件删除时间（UTC）',
    PRIMARY KEY (video_id),
    UNIQUE KEY uk_video_storage_key (storage_key),
    KEY idx_video_owner_time (owner_key, created_at),
    KEY idx_video_status (status),
    CONSTRAINT chk_video_byte_size CHECK (byte_size >= 0),
    CONSTRAINT chk_video_sha256 CHECK (CHAR_LENGTH(sha256) = 64)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '本地视频元数据；二进制保存在用户设备/局域网主机，不存数据库';


-- -----------------------------------------------------------------------------
-- 9. 聚合视图
-- -----------------------------------------------------------------------------

-- 9.1 个人每日已入账积分：结算时用于计算“本次还能入账多少”
CREATE OR REPLACE VIEW v_user_daily_points AS
SELECT
    l.user_id,
    l.attribution_date,
    CAST(SUM(l.actual_points) AS UNSIGNED) AS credited_points
FROM points_ledger l
JOIN training_session s ON s.session_id = l.session_id
WHERE l.entry_type = 'credit'
  AND l.status = 'active'
  AND s.status = 'settled'
  AND s.deleted_at IS NULL
GROUP BY l.user_id, l.attribution_date;

-- 9.2 周排行榜：只统计已结算、未删除、仍有效且自愿公开的用户。
--     同分同名次（1、1、3）；user_id 仅用于同分稳定排序与“我的名次”，
--     对外接口不得输出真实学号、姓名或 user_id。
CREATE OR REPLACE VIEW v_weekly_leaderboard AS
SELECT
    w.week_start,
    w.user_id,
    w.nickname,
    w.total_points,
    RANK() OVER (PARTITION BY w.week_start ORDER BY w.total_points DESC) AS rank_no
FROM (
    SELECT
        l.week_start,
        u.user_id,
        u.nickname,
        CAST(SUM(l.actual_points) AS UNSIGNED) AS total_points
    FROM points_ledger l
    JOIN training_session s ON s.session_id = l.session_id
    JOIN sys_user u ON u.user_id = l.user_id
    WHERE l.entry_type = 'credit'
      AND l.status = 'active'
      AND s.status = 'settled'
      AND s.deleted_at IS NULL
      AND u.ranking_opt_in = 1
      AND u.status = 'active'
    GROUP BY l.week_start, u.user_id, u.nickname
) w
ORDER BY w.week_start, w.total_points DESC, w.user_id ASC;
