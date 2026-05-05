# 本地 AAR 投放目录

`LocalAsrEngine`（本地 ASR 引擎）需要 sherpa-onnx 的 Android JNI 库。
官方未发布到 Maven Central / JitPack，所以需要把 AAR 手动放进这里。

构建脚本 `app/build.gradle.kts` 用 `fileTree(... "*.aar")` 自动拾取本目录的所有 `.aar`。

## 不放也能编译

`LocalAsrEngine` 通过反射调用 sherpa-onnx 类，缺 AAR 时：
- 编译正常
- 启动正常
- 只有"设置 → 选择本地引擎 → 下载完模型 → 实际转写"那一刻会报：「sherpa-onnx 原生库未集成，请到本目录放置 AAR 后重新构建」

云端 GLM-ASR 引擎不受影响。

## 如何获取 AAR

1. 打开 https://github.com/k2-fsa/sherpa-onnx/releases
2. 找到对应版本（建议 `v1.12.39` 或更新）的 Android 资源：`sherpa-onnx-v<version>-android.tar.bz2`
3. 解压，里面会有 `sherpa-onnx-<version>.aar` 或类似命名（含四个 ABI 的 `libsherpa-onnx-jni.so` + Kotlin 绑定 classes.jar）
4. 把 AAR 拷贝到本目录，重新 `./gradlew assembleDebug`

## ABI 与体积

AAR 自带四个 ABI 的 .so 文件，单个 .so 大约 5–8 MB。`app/build.gradle.kts` 里 `splits { abi { ... } }`
仍然只在 release 任务里启用，所以 release 仍然按 ABI 拆分；debug 一个通用 APK 包含全部。

预期 release arm64-v8a 的体积增量 ≤ 20 MB（不含模型，模型走 `AsrModelManager` 首次启用时下载）。

## ProGuard

`app/proguard-rules.pro` 已经加了 `-keep class com.k2fsa.sherpa.onnx.** { *; }`，
release 混淆不会动这些类。
