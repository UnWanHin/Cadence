# 每日健身 App 技术选型调研（人体骨架建模 + 动作评定）

> 目标：相机实时 + 本地视频上传分析，检测人体、骨架动作建模、评定动作是否标准，最终打包成 Android APK。
> 调研日期：以仓库当前会话为准。所有结论均来自官方文档 / GitHub 一手来源。

---

## 1. 结论速览（TL;DR）

**核心引擎选 MediaPipe（BlazePose），动作评定用「关键点角度 + 规则/机器学习」而非现成 MCP。**

- **骨架建模**：MediaPipe Pose Landmarker（BlazePose），33 个 3D 关键点，Android 端 GPU 延迟仅 20–53ms，移动端实时，是当前落地最成熟的选择。
- **动作评定（标准/不标准）**：没有「拿来即用」的通用评定模型，业界通行做法是：① 用骨架关键点算关节角度；② 与标准动作的参考角度区间比较；③ 或用官方提供的 k-NN 姿态分类 + 计数管线。这部分要自己实现，但 MediaPipe 官方给了完整方法论。
- **APK 打包**：Android（Kotlin/Java）+ MediaPipe Tasks Vision SDK 可直接编译成 APK，相机用 CameraX，视频上传用 MediaPipe 的 Video 输入模式。
- **「人体 MCP 模型」**：需要澄清 —— MCP（Model Context Protocol）是给 LLM 调用工具的协议，不是人体模型。现有的「姿态 + MCP」仓库极少、且都面向 ROS/桌面机器人，不适合移动端 APK。你真正需要的是 **MediaPipe 这类 on-device 姿态模型**，而不是 MCP。

---

## 2. 人体姿态检测：候选框架对比

| 框架/模型 | 关键点 | 移动端 | 许可 | 适用性 |
|---|---|---|---|---|
| **MediaPipe Pose (BlazePose)** | 33（含脸/手/脚）+ 3D + 分割 | ✅ 极佳，GPU 20–53ms | Apache 2.0 | **首选** |
| **MoveNet** (TFLite) | 17 (COCO) | ✅ 50+ fps | Apache 2.0 | 轻量备选 |
| **PoseNet** | 17 (COCO) | ✅（较旧） | Apache 2.0 | 多人体，精度一般 |
| **RTMPose / MMPose** | 17 / 133 wholebody | ⚠️ 需自行转 ONNX/NCNN 部署 | Apache 2.0 | 精度高，但部署重 |
| **YOLO11/YOLOv8-pose** | 17 | ⚠️ 需转换部署 | AGPL 3.0 | 多人+检测一体，但 AGPL 商用注意 |

### MediaPipe BlazePose 关键数据（官方 benchmark）

- 33 个 3D 关键点（`pose_landmarks`）+ 世界坐标 3D（`pose_world_landmarks`，米单位）+ 人体分割掩码。
- 质量：HIIT 场景 `PCK@0.2 = 97.5`（Heavy）/ `96.3`（Full）/ `93.5`（Lite），优于 AlphaPose ResNet50 和 Apple Vision。
- 延迟：Pixel 3 TFLite GPU 上 Lite 20ms / Full 25ms / Heavy 53ms —— 完全满足实时。
- 关键点索引含 `NOSE, LEFT_SHOULDER(11), RIGHT_SHOULDER(12), LEFT_ELBOW(13), RIGHT_ELBOW(14), LEFT_WRIST(15), RIGHT_WRIST(16), LEFT_HIP(23), RIGHT_HIP(24), LEFT_KNEE(25), RIGHT_KNEE(26), LEFT_ANKLE(27), RIGHT_ANKLE(28)` 等，四肢关节完整，足够算角度。

> 来源：MediaPipe 官方 `docs/solutions/pose.md`（google-ai-edge/mediapipe），BlazePose 论文 arXiv:2006.10204。

---

## 3. 动作评定（标准/不标准）怎么做

**没有现成的「判定标准动作」模型，但有官方方法论，分两种：**

### 方案 A：关节角度规则判定（推荐，最适合「评定标不标准」）

1. 从 33 关键点取四肢关节（肩-肘-腕、髋-膝-踝 等）。
2. 计算关节夹角（如深蹲的膝角、俯卧撑的肘角、硬拉/卧推的背角）。
3. 与每个动作预设的「标准角度区间」比较，超出阈值即判「不标准」，并给出方向性提示（如「膝盖再屈一点」「背挺直」）。

- 优点：可解释、无需训练、离线运行、天然适配「标准/不标准」这种细粒度判定。
- 缺点：规则要按动作逐个编写（每个动作一套角度参考）。

### 方案 B：k-NN 姿态分类 + 计数（MediaPipe 官方，适合「计数」）

MediaPipe 官方 `pose_classification` 给出了完整管线（push-up / squat 示例）：

1. 采集每个动作「起/止」两个终止态的样本（每个几百张，覆盖不同角度/体型）。
2. 用 BlazePose 提取关键点，转成「关节两两距离」特征向量（先按躯干尺寸归一化 + 方向归一化）。
3. 用 k-NN 分类（两次检索：最小逐坐标距离过滤 + 平均逐坐标距离聚类），配合 EMA 平滑。
4. 通过类别概率阈值（进入阈值 > 退出阈值，避免抖动幻计数）做次数统计。

- 优点：官方 Colab + ML Kit quickstart 可直接套用，自动计数。
- 缺点：k-NN 偏「分类」而非「评分」，对「标不标准」的细粒度反馈弱于角度法。

**结论：你的核心需求是「评定动作标不标准」，用方案 A（角度规则）为主，方案 B 的 EMA/阈值思路用于去抖动和计数。**

> 来源：MediaPipe 官方 `docs/solutions/pose_classification.md`。

---

## 4. 相机实时 + 上传本地视频

MediaPipe 支持两种输入模式，天然覆盖你的两个需求：

- **实时相机**：`static_image_mode=False`（视频流模式），检测器仅首帧/丢失时触发，其余帧用上一帧 ROI 跟踪，省算力、低延迟。
- **上传视频**：MediaPipe Video 任务可直接吃 mp4 文件逐帧推理；或用 `static_image_mode=True` 逐帧处理。Android 侧用 `PoseLandmarker` 的 video 输入 API。

Android 具体技术栈：

- 语言：Kotlin（或 Java）。
- 相机：CameraX（实时预览帧喂给 `PoseLandmarker`）。
- 视频上传：`ActivityResultContracts.GetContent` 选本地视频 → MediaPipe 视频模式逐帧分析。
- 渲染：把 33 关键点 + 骨骼连线 overlay 到 `SurfaceView`/`TextureView`/GLSurfaceView。
- 打包：Gradle 构建 → `app-debug.apk` / `app-release.apk`（release 需签名）。

---

## 5. 关于「人体 MCP 模型」的澄清

- **MCP = Model Context Protocol**，是给 LLM 连接外部工具/服务的协议，**不是人体姿态模型**。
- GitHub 上「pose + MCP」的仓库目前只有极少数，且面向场景是 **ROS 2 机器人感知 / 桌面视觉**（如 `perception_mcp_server`、`vision-context-mcp`），**不适用于 Android APK**。
- 如果你的真实意图是「想让 LLM/AI 助手能通过 MCP 调用姿态分析能力」：正确架构是 **先把 MediaPipe 姿态引擎跑在本地（或一个服务端），再包一个 MCP server 暴露 `analyze_pose` 这类 tool**。但这对一个纯移动端健身 App 不是必需——姿态推理直接内置在 App 里更快更省事。

---

## 6. 可参考的同类开源项目（均为 MediaPipe + OpenCV 系，桌面/Web 为主）

| 项目 | 技术栈 | 功能 |
|---|---|---|
| Ai_Personal_Trainer | OpenCV + MediaPipe + Flask | 实时姿态 + 动作纠正（深蹲/俯卧撑/卧推） |
| PosePerfect | CV + 姿态估计 | 计数 + 角度纠正 + 实时反馈 |
| RepWise | MediaPipe + OpenCV + Gemini | 姿态 + 计数 + 纠正 + 语音教练 |
| AI-Fitness-Trainer | MediaPipe + OpenCV + LangChain | 姿态 + 计数 + 纠正 + 计划推荐 |

> 这些项目都是 Python/桌面/Web 为主，可作为「角度规则 + 计数」逻辑的参考实现，但移动端 APK 仍需用 MediaPipe Android SDK 重写。

---

## 7. 推荐技术路线（MVP 落地路径）

1. **引擎**：MediaPipe Tasks Vision `PoseLandmarker`（BlazePose，Full 复杂度，Android）。
2. **骨架**：33 关键点 → 四肢关节角度提取。
3. **评定**：每动作一套「参考角度区间」规则表 + EMA 去抖动 + 阈值计数。
4. **输入**：CameraX 实时 + 本地视频上传（MediaPipe video 模式）。
5. **输出**：骨架 overlay + 角度数值 + 标准/不标准判定 + 文字/语音提示。
6. **打包**：Gradle → 签名 APK。

---

## 8. 需要你确认的关键决策点

1. **「MCP」的真实意图**：你是想要「姿态模型」，还是真的想要「MCP server 给 AI 助手调用」？（第 5 节已解释差异，倾向前者）
2. **动作范围**：首期做哪几个动作？（深蹲/俯卧撑/平板支撑/卧推…决定规则表的工程量）
3. **评定方式**：要「标准/不标准」二分类，还是要「0-100 打分 + 每处关节的偏差提示」？（影响是角度阈值还是更细的评分模型）
4. **是否完全离线**：on-device 推理（无网络、隐私好、推荐） vs 服务端推理（精度可更高但需联网上传视频）？
