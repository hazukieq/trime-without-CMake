# 项目概览

hkrime 是 Trime v3.2.7 的客家话 Android 分支。包名 `com.hazukieq.hkrime`，
版本名 `3.2.7`、versionCode `20220701`；关于页显示本次编译信息与实际安装包信息。

## 工程边界

| 位置 | 职责 |
|---|---|
| `hkrime/` | App、方案、词典、主题、JVM/设备测试及开发工具 |
| `rime-jni/` | librime 1.17.0、Lua/charcode/octagram 插件、JNI 胶水与构建 |
| `rime-jni/librime/` | 独立 Git 源码树，必须含当前本地补丁 |
| `jni-build/`、`backups/` | 只读构建参考与备份，不作为当前产物来源 |
| `artifacts/`、`dist/` | 本地证据与交付文件；需逐件核验，不代表当前代码已通过验收 |
| `tools/` | 工作区辅助工具 |

工作区根不是 Git 仓库；App、JNI 和 librime 各自管理版本。进入对应目录后查看
`git status --short --branch` 与 `git log -1`，不要把不同仓库的提交混为一体。

## 技术栈

| 项 | 当前配置 |
|---|---|
| UI | Kotlin/Java，Android View，ViewBinding，RecyclerView |
| 工具链 | JDK 17、Gradle 7.5、AGP 7.4.2、Kotlin Gradle 插件 1.8.20 |
| Android | compile/target 33，min 21，arm64-v8a |
| Native | C++17、NDK 24.0.8215888、SDK CMake 3.22.1、Release |
| 数据目录 | shared/user 默认均为 `/sdcard/hkrime`，以实际偏好为准 |
| 方案 | `hakka_jiugong`、`hakka_qwerty`、`ipa_yunlong` |

12 键以不可变快照连接引擎、候选条、面板和预览。引擎 session 不支持并发访问，
所有访问共用 `Rime.ENGINE_LOCK`。候选条使用前 64 条引擎候选，展开面板使用最多 600 条。
排序和面板语义见 [12](12-candidate-ranking.md)、[17](17-candidate-panel.md)。

当前库事实见 [02](02-jni-so.md)，构建与安装见 [03](03-build.md)。
