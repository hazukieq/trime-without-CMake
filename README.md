# hkrime 客家话输入法

hkrime 是基于 Trime v3.2.7 的 Android 客家话输入法，包名与 namespace 为
`com.hazukieq.hkrime`。App 使用 Kotlin/Java 和 Android View；Rime 引擎由相邻的
`rime-jni` 工程独立构建，Gradle 直接打包 `app/libs/arm64-v8a/librime_jni.so`。

## 功能

- 客语 12 键、客语 26 键、雲龍国际音标；朙月拼音资源用于普通话反查。
- 12 键候选条、按具体读音分组的展开面板、逐段选词和本地学习。
- 简繁转换、颜文字、词语扩展、剪贴板和草稿。
- 默认主题 `hakka_theme.trime.yaml`；方案、词典、主题和日志默认在 `/sdcard/hkrime`。

## 构建与安装

环境：JDK 17、Gradle Wrapper 7.5、AGP 7.4.2、Android SDK 33；APK 当前仅打包 arm64-v8a。
在 `local.properties` 中设置 SDK 路径，然后在本仓库执行：

```bash
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"
./gradlew :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb -s <serial> shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
adb -s <serial> shell ime set com.hazukieq.hkrime/.TrimeImeService
```

先用 `adb devices -l` 选择设备。安装后运行权限、启用输入法、选择输入法的引导。
覆盖安装不保证替换用户目录里已有的同版本资源；更新数据和主题的方法见构建文档。

## 开发

先读工作区 `../AGENTS.md`，再读 [文档索引](docs/README.md) 和 [贡献指南](CONTRIBUTING.md)。
只改 App、资源或主题时不重编 JNI；JNI 只通过 `../rime-jni/script/build.sh` 进行 Release 构建。
当前引擎为 librime 1.17.0，必须保留本地性能、全码路径兜底和方案扫描守卫补丁。

```bash
./gradlew :app:testDebugUnitTest
python3 script/verify-rime-assets.py
python3 tools/panel_probe.py selftest
./script/verify-jni-symbols.sh
```

仓库没有自动构建 workflow。不要运行 `spotlessApply`。
发布构建、签名与设备验收见 [构建说明](docs/03-build.md)。

## 许可与来源

项目基于 [Trime](https://github.com/osfans/trime) 与
[trime-without-CMake](https://github.com/tumuyan/trime-without-CMake)，感谢各项目作者、
贡献者、词典及主题维护者。许可证见 [LICENSE](LICENSE)，隐私说明见 [PRIVACY.md](PRIVACY.md)。
