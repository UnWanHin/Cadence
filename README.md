# Cadence

Cadence is a local pose-fitness prototype with a C++ movement-evaluation core.

## C++ core

The native code lives under `src/` and is split into deep modules:

- `geometry`: joint-angle and distance calculations.
- `pose_features`: converts MediaPipe-style 33-landmark frames into exercise features.
- `exercise_rules`: evaluates squat, push-up, lunge, plank, and jumping-jack form.
- `repetition_counter`: hysteresis state machine for complete repetitions.
- `workout_session`: aggregates repetitions, points, calories, quality, and elapsed time.
- `pose_source`: pluggable frame sources, including text replay.
- `pose_backend`: pluggable native inference backend seam.

The browser prototype under `prototype/` remains the working MediaPipe WASM camera/demo layer. `prototype/cadence-wasm-bridge.js` is the thin browser adapter for a C++-compiled WebAssembly module: JavaScript supplies the 33 MediaPipe landmarks, while C++ owns movement rules, repetition counting, quality, points, calories, and summaries. The native C++ build deliberately has no hidden SDK dependency: a MediaPipe Tasks or ONNX backend is added as an adapter implementing `PoseBackend`.

## Build and test

```bash
cmake -S src -B build
cmake --build build
ctest --test-dir build --output-on-failure
./build/cadence_demo
```

## Replay a pose frame

`TextPoseSource` reads one frame per line. Each line contains 33 landmarks, each encoded as `x y z visibility`, followed by `timestamp_ms`.

```text
x0 y0 z0 v0 x1 y1 z1 v1 ... x32 y32 z32 v32 timestamp_ms
```

## Native inference integration

Implement the backend seam:

```cpp
class MediaPipePoseBackend final : public cadence::PoseBackend {
 public:
  cadence::BackendStatus load(const std::string& model_path) override;
  bool detect(const std::string& frame_source, cadence::PoseFrame& frame) override;
};
```

The adapter owns model loading and landmark conversion. It must populate all 33 `PoseFrame::landmarks` values with normalized coordinates and visibility scores. The movement core then remains unchanged.

The current environment does not ship MediaPipe C++ or OpenCV development headers, so the default build reports the native backend as unavailable rather than pretending that camera inference is wired.

## 保持类动作与质量分

**平板支撑是保持型**：没有"次数"，记的是秒数。每接受一帧就按 `0.15 千卡/秒` 折算，6 秒约 9 分。
1 秒以下不记分。

平板的直线原来只认 肩-髋-膝，可实测一条侧向平板视频里 **88% 的帧膝盖被身体挡住**，于是整段
只记到 3.1 秒（视频 6.2 秒）。现在膝盖不可见时退回 **肩-髋-踝**，同一条片子能完整记满。

俯卧撑的门槛是**单独**定的：肘角 16°（深蹲/弓步仍是膝角 40°）。同一个模型量肘角比量膝角小得多，
一条专业侧身俯卧撑素材每下只测到 15–25°，用 40° 就是 0 次。16° 下这条素材稳定 3 次。

**质量分只在动作过程里取样**（走深度门的动作取下沉过半的帧）。以前每帧都取样，于是出现
"质量 96 但 0 次"这种自相矛盾的显示；现在没做动作就是 0，并且会说明差多少：

```
没有检测到完整动作：肘角只变化了 32°，一次动作需要至少 40° 的起伏。
```

顺带修了一个吃时间的问题：视频模式原来在 3 秒倒计时**就开始播放**，开头 3 秒的画面从未被分析，
6 秒的片子只算 3 秒。现在倒计时期间停在第一帧，倒数结束才起播。

## 骨架开关与播放控制

视频右上角有一枚「骨架 开/关」角标，工具条里也有同样的按钮，两处同步。

暂停中拖动进度条会**立刻在新帧上重画一次骨架**；跳播、重播、视频结束都会重置 MediaPipe 的追踪状态并清空画布——
否则追踪器会抱着旧的感兴趣区域，人动了骨架却钉在原地。帧循环另有看门狗：万一某帧抛异常导致
`requestAnimationFrame` 链断掉，1.5 秒内会自动重启，不会留下一个冻住的骨架。

`window.__cadenceDebug()` 里 `loopErrors / loopRestarts / trackerResets` 三个计数就是给这类问题用的。

## 判次按"相对深度"，不按固定角度

`src/cadence/workout_session.cpp` 用本人站直时的读数当基线，只数"相对自己蹲下去 40°、再起来回到 20° 以内"的循环。
换姿态模型时不会因为几度差异把判次改掉。

```bash
bash tools/run-core-tests.sh    # 本机 C++ 测试（含判次阈值性质测试）
bash tools/build-wasm.sh        # 改了核心后重建 ui/wasm/（需要 emscripten）
```

骨架平滑和"画面里两个人时跟踪谁"在 `ui/pose-smoothing.js`，`node ui/test-smoothing.mjs` 可以单独验证。

## 姿态骨架叠加

主页面在视频上方叠了一层 canvas，把 MediaPipe 的 33 个关键点画成骨架，右上角「显示骨架」可以关掉。
两个细节：`object-fit: contain` 会留黑边，所以骨架按视频实际显示区域换算，不是直接拉伸；镜像画面时 canvas 一起翻转，
否则骨架会跑到人的反方向。

姿态模型**优先读本机** `/prototype/` 下的 bundle、wasm 和 `pose_landmarker_full.task`，取不到才退回 CDN——
教室没有外网时照样能判定动作。原型的独立页面在同一服务下也能直接打开：`/prototype/pose-prototype.html`。

## 网页上的分享二维码

页面底部「分享到手机」有两个按钮：房间二维码（手机扫了直接进同一个房间）和安装包二维码（扫了直接下载 `cadence.apk`）。
二维码在浏览器里现算，`ui/qr.js` 是一个无依赖的编码器（byte 模式、纠错级别 M、版本 1-10），
`ui/test-qr.mjs` 会把矩阵渲染成图片再用 OpenCV 解码回来验证，`ui/test-qr-page.mjs` 则在真浏览器里
点了按钮、截了图、再解码一次。

## 一条命令起 HTML 房间

```bash
./start.sh            # 前台，端口 8080
./start.sh --bg       # 后台，日志在 .run/server.log
./start.sh --stop     # 停止
./start.sh --status   # 看状态和局域网地址
./start.sh --port 9000
```

启动后会打印本机和局域网地址，手机浏览器直接打开局域网地址即可进入同一个房间；
页面与 `/api/v1` 同源，不需要再拼 `?api=` 参数。`/app.apk` 会分发最新的安装包。
