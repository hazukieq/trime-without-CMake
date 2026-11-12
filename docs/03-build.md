# 构建、签名与安装

## 环境

App：JDK 17、Gradle Wrapper 7.5、AGP 7.4.2、Kotlin 插件 1.8.20；
compile/targetSdk 33、minSdk 21，ABI 为 arm64-v8a。
本机 JDK 为 `$HOME/opt/jdk17/jdk-17.0.9`，SDK 为 `$HOME/Android/Sdk`。
`local.properties` 的 `sdk.dir` 指向实际 SDK；不要提交本机配置或签名秘密。

## App 构建

以下命令在 `hkrime/` 执行：

```bash
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

| 构建 | 路径 |
|---|---|
| Debug | `app/build/outputs/apk/debug/trime-3.2.7-debug.apk` |
| 已签名 Release | `app/build/outputs/apk/release/trime-3.2.7-release.apk` |
| 无签名 Release | `app/build/outputs/apk/release/trime-3.2.7-release-unsigned.apk` |
| 仪器测试 | `app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk` |

依赖已缓存时可加 `--offline`。不常规跳过 lint；SDK XML 版本提示与弃用 API 警告应与真正的
构建失败区分。关于页编译信息每次构建重新生成，不能据此保证 APK 字节 hash 跨构建相同。
不要运行 `spotlessApply`，它会重排不相关文件。

## 发布签名

Gradle 按顺序读取仓库根 `release.properties`、`~/.android-keys/hkrime-release.properties`；
缺失字段回退到 `HKREME_STORE_FILE`、`HKREME_STORE_PASSWORD`、`HKREME_KEY_ALIAS`、
`HKREME_KEY_PASSWORD`。配置格式见 `release.properties.example`。
找不到有效 keystore 时 Release 不签名，必须在交付前补签并验证；不得把 unsigned 当成可安装发布包。
`script/gen-keystore.sh` 可生成本地发布密钥，不覆盖已有文件。

## 设备操作

先运行 `adb devices -l`，所有命令明确使用目标设备序列号：

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb -s <serial> shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
adb -s <serial> shell ime set com.hazukieq.hkrime/.TrimeImeService
adb -s <serial> shell settings get secure default_input_method
adb -s <serial> shell dumpsys window
```

每次重装重新设置 appops；重装或 force-stop 后重新选择本输入法。截图前核对 IME 窗口的包名。
默认共享和用户目录均为 `/sdcard/hkrime`。同版本覆盖安装可能保留目录内资源，开发时明确 push：

```bash
adb -s <serial> push app/src/main/assets/rime/hakka_theme.trime.yaml /sdcard/hkrime/
adb -s <serial> shell am force-stop com.hazukieq.hkrime
adb -s <serial> shell ime set com.hazukieq.hkrime/.TrimeImeService
```

改方案/词典还需广播 `com.hazukieq.hkrime.deploy`，部署异步，等完成日志再验证。
App 字表索引在进程启动加载，部署本身不通知它重新读取，词表更新后需重启 IME。

## 验收

```bash
python3 script/verify-rime-assets.py
python3 tools/panel_probe.py selftest
./script/verify-jni-symbols.sh
python3 tools/perf-gate.py --serial <serial>
```

preedit/面板变更还必须跑 [真实语料](16-librime-coverage-test.md)；JNI 变更走
[独立构建](11-native-build-performance.md)，再验证 12 键和全字母方案。

`RimeDataTest` 会在真实数据目录部署；仅在隔离测试环境或已妥善备份的测试设备上运行：

```bash
./gradlew :app:assembleDebugAndroidTest
adb -s <serial> install -r -t app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk
timeout 300 adb -s <serial> shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
```

设备测试不能替代键盘输入验收：确认方案、逐键输入、部分选词、整段撤回、空格上屏、面板切换、
全字母方案与输入框切换；结合 `diag.log` 和性能门禁判断。
