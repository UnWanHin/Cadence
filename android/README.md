# 校园智能运动 Android 主程序（JNI / C++ 版）

这是最终 APK 的 Android 应用入口，不是 HTML 页面。动作判定、计数、质量分、积分和卡路里由仓库根目录 `src/cadence` 的 C++ 核心通过 JNI 计算，Kotlin 只负责相机、MediaPipe 关键点、UI 和生命周期。

## 技术栈

- Kotlin + XML 布局
- CameraX（实时预览 + ImageAnalysis）
- MediaPipe Tasks Vision `PoseLandmarker`（33 个关键点）
- 自定义 `PoseOverlayView` 绘制骨架
- NDK / JNI：`NativeCadenceEngine` → `libcadence.so` → C++ `WorkoutSession`
- `CadenceEngine` 是 Kotlin 接口，UI 只依赖这个接口；生产实现是 `NativeCadenceEngine`

## 目录

```text
android/
  settings.gradle.kts
  build.gradle.kts
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      assets/pose_landmarker_full.task
      cpp/
        CMakeLists.txt
        jni_bridge.cpp
      java/com/campus/sport/
        MainActivity.kt
        CadenceExercise.kt
        CadenceEngine.kt          # interface + NativeCadenceEngine
        PoseAnalyzer.kt
        PoseOverlayView.kt
      res/layout/activity_main.xml
      res/values/...
```

## 当前功能

- 选择五种动作：深蹲、俯卧撑、弓步蹲、平板支撑、开合跳
- 申请摄像头权限
- CameraX 后置摄像头预览
- MediaPipe PoseLandmarker 实时推理
- 骨架 overlay
- 显示状态、有效次数/保持时长、质量分、积分、卡路里、有效运动时长、自然时长
- 结束训练时显示汇总
- 所有动作判定和计分走 C++ JNI，不再有 Kotlin 规则副本

## 构建前提

需要：

- JDK 21（**必须带 javac 的完整 JDK**）。实测：只装 JRE 时 Gradle 报
  `does not provide the required capabilities: [JAVA_COMPILER]`；JDK 27 早期版本会在
  `JdkImageTransform` 的 jlink 步骤失败。JDK 21 构建通过。
- Android SDK：command-line tools、platform 35、build-tools、platform-tools
- Android NDK：`27.0.12077973`
- CMake：`3.22.1`（通过 SDK Manager 安装）
- Gradle 9.0

`android/local.properties` 写入本机 SDK 路径（该文件不入库）：

```properties
sdk.dir=/home/<user>/android-sdk
```

构建：

```bash
cd android
ANDROID_HOME=$HOME/android-sdk JAVA_HOME=$HOME/jdk/jdk-21.0.13+9 bash ./gradlew :app:assembleDebug
```

已验证通过，产物：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

APK 输出：

```text
android\app\build\outputs\apk\debug\app-debug.apk
```

安装：

```powershell
adb install -r app-debug.apk
```

## Native 构建说明

`app/src/main/cpp/CMakeLists.txt` 直接引用：

```text
../src/cadence/*.cpp
```

不复制 C++ 源码，保证 APK 与 WASM/桌面测试共用同一套规则。JNI 只做三件事：

1. 把 Kotlin 的 33×4 个 `Float` 关键点传给 `cadence_process_landmarks`；
2. 把 C++ 返回的 JSON 转成 Kotlin `FrameResult`；
3. 转发 `cadence_session_summary`。

## 当前限制

- 还没有后端、账号、记录持久化；这些在 `database/API.md` 的接口落地后接入。
- 摄像头 overlay 使用简单的归一化坐标映射；`PreviewView` 为 `FILL_CENTER`，在极端屏幕比例下可能需要按实际裁剪区域校正。
- 模型文件使用 `../prototype/pose_landmarker_full.task`，约 9.4 MB；后续可换 lite 模型减小 APK。
- Windows 下如果仓库路径包含中文，NDK/CMake 可能对路径敏感；如果构建失败，可在 ASCII 路径（如 `E:\campus-sport-build`）复制项目后构建。

## Publishing a release (hot update)

The app checks GitHub for a newer release and can install it in place. Two
things make that work:

1. **A stable signing key.** Android refuses an update whose signature differs
   from the installed app, so every release must be signed with the same
   keystore. Create it once:

   ```bash
   keytool -genkeypair -v -keystore android/release.jks -alias cadence \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

   Then add these repository secrets (`Settings -> Secrets and variables -> Actions`):

   | Secret | Value |
   | --- | --- |
   | `KEYSTORE_BASE64` | `base64 -w0 android/release.jks` |
   | `KEYSTORE_PASSWORD` | the store password |
   | `KEY_ALIAS` | `cadence` |
   | `KEY_PASSWORD` | the key password |

2. **A tag that matches `versionName`.** Bump `versionName` in
   `app/build.gradle.kts`, commit, then:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

   `.github/workflows/release.yml` builds `assembleRelease`, renames the output
   to `cadence.apk` and publishes it with the tag. The in-app updater reads
   `https://github.com/<repo>/releases/latest`, so the asset name matters.

Phones on an older build then see 「检查更新」 offer the new version.

The updater deliberately does not rely on the GitHub REST API: it is limited to
60 anonymous requests per hour per IP and a shared campus address burns through
that immediately. The primary path is the `/releases/latest` redirect, which
costs no quota; the API is only a fallback for a differently named asset.
