# 本地 ASR 运行库说明

`LocalAsrEngine` 通过 sherpa-onnx Android 绑定运行本地模型。

当前仓库已经提交了两类运行时文件：

- `app/src/main/java/com/k2fsa/sherpa/onnx/`：sherpa-onnx Kotlin 绑定源码。
- `app/src/main/jniLibs/<abi>/`：`libsherpa-onnx-jni.so`、`libonnxruntime.so` 等 JNI/ONNX Runtime 原生库。

因此当前构建不依赖 `app/libs/` 下的 AAR。`app/build.gradle.kts` 仍保留
`implementation(fileTree(... "*.aar"))`，只是为了以后需要整体替换为官方 AAR 时有兼容入口。

## 缺失时的表现

如果 Kotlin 绑定或 JNI 库没有打进 APK：

- 编译可能仍然通过。
- 选择本地 ASR 并实际转写时会显示 sherpa-onnx 原生库未集成。
- 云端 GLM-ASR 不受影响。

## 更新 sherpa-onnx

如果需要升级 sherpa-onnx：

1. 从 sherpa-onnx Android release 获取对应版本的 Android 包。
2. 同步更新 Kotlin 绑定源码和 `jniLibs` 中四个 ABI 的 `.so` 文件，或改为把完整 AAR 放到本目录。
3. 确认 `SherpaBridge` 中反射使用的数据类字段仍与新版本兼容。
4. 运行 `./gradlew :app:testDebugUnitTest` 和至少一次本地模型实机转写。
