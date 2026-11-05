校园智能运动平台　运行说明（C++ WASM 版）
1. 解压后可直接双击 index.html 查看界面，但推荐在解压目录打开终端，执行：
   python -m http.server 8000 --bind 127.0.0.1
   然后在 Chrome 或 Edge 打开 http://localhost:8000 。摄像头模式首次加载需要访问 jsDelivr 和 Google 模型存储；拒绝授权、无法联网或无摄像头时可改用“离线演示”或“上传视频分析”。
2. 首版动作与模型/Cadence-main 的 C++ 核心一致：深蹲、俯卧撑、弓步蹲、平板支撑、开合跳。判定、计数、质量分、积分和卡路里由 C++ 编译出的 WebAssembly 计算，UI 只负责摄像头、展示和记录。
   - 计数型：进入目标 0.75、离开目标 0.55 记 1 次；每次得 floor(10 × 质量分) 分。
   - 平板支撑：按运动秒数得 floor(秒数 × 1.5) 分。
   - 单次自然时长上限 20 分钟；每日积分上限 600 分（演示数据不计分）。
3. WASM 文件位于 ui/wasm/：
   - cadence-core.js：Emscripten 生成的模块加载器；
   - cadence-core.wasm：C++ 动作核心。
   ui/cadence-wasm-bridge.js 负责把 33 个关键点写入 WASM 并读取 JSON 结果。
   ui/core.js 只保留动作元数据和测试用参考实现，不是运行时评估器。
4. 如果修改了 src/cadence/ 下的 C++ 代码，需要重新编译：
   emcmake cmake -S src -B build-wasm -DCADENCE_BUILD_WASM=ON
   cmake --build build-wasm
   然后把 build-wasm/cadence-core.js 和 cadence-core.wasm 复制到 ui/wasm/。
   需要 Emscripten SDK（emcc/emcmake）。
5. 摄像头模式保持单人、全身入镜；深蹲/俯卧撑/弓步蹲/平板支撑建议左侧身体朝向镜头，保证肩、髋、膝、踝可见；开合跳建议正面机位。多人、遮挡或关键点可见性低于 0.50 时显示“无法判断”，不增加次数。
6. 本页面只做本机演示：档案名可修改，用同一浏览器、同一地址查看历史；记录保存在 localStorage（键名 campus-model-v1）。“以档案昵称加入本机周榜”只影响本机周榜，不是真实账号或服务端排名。修改档案名可模拟切换学生；这不是身份认证。摄像头视频和上传的视频都不上传；清理网站存储会删除数据。仅支持单标签页操作。
7. Node.js 可执行 node test.cjs 验证 core.js 的参考算法；C++ 规则由 tests 与 WASM 构建验证。
8. “上传视频分析”会使用浏览器可解码的本地视频文件逐帧送入 MediaPipe 与 C++ WASM。支持“重播视频”“自动重播”和“镜像画面”；自动重播只重置并重新分析，不会为每一轮重复写入运动记录。页面内容较长时直接上下滚动即可。
9. 局域网本地视频库（无 MySQL 时的模拟方案）：
   node database/local-server/server.mjs
   默认监听 0.0.0.0:8787，手机与电脑在同一 Wi-Fi 时打开 http://<电脑IP>:8787/ 。服务同时托管本目录的静态页面和 /api/v1/videos 端点，数据保存在 database/local-server/data/（store.json + videos/），清理该目录即删除本地视频。若页面由其他静态服务器托管，可在地址后加 ?api=http://<电脑IP>:8787/api/v1 指向局域网服务。
