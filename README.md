# TRIME：安卓同文輸入法without CMake版 /Android-rime
[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

## Hakka fork / hkrime（中文）

这是 [tumuyan/trime-without-CMake](https://github.com/tumuyan/trime-without-CMake) 的下游 fork，
目标是把同文输入法（Trime，RIME 的 Android 前端）改造成面向**客家话**的版本。
**已完成整包改名**：`com.osfans.trime` / `com.tumuyan.trime` → **`com.hazukieq.hkrime`**。

- **基座版本**：上游 [`osfans/trime` v3.2.7](https://github.com/osfans/trime/tree/v3.2.7)（Android 端仍以 Java 为主）。
  v3.3.x 起 Kotlin 化重构、JNI 接口全变，**不能直接合并**。
- **继承自 without-CMake**：不在 Gradle 里编译 C++，而是直接提交预编译的 `librime_jni.so`；
  代价是 `.so` 与 Java 的一致性没有编译期保障，因此本仓库另配了构建/校验脚本。
- **分支**：`dev`（原 `hakka`，hkrime 开发线）；`main`（原 `develop`，稳定的 without-CMake 主线，保持旧包名）。
  两条线内容不同，**不要互相 merge**。

### 当前状态

| 项 | 状态 |
|---|---|
| 包名 / namespace | ✅ `com.hazukieq.hkrime`（103 文件 / 391 行改名） |
| native 库 | ✅ `app/libs/arm64-v8a/librime_jni.so`，13,587,744 B，73 符号，新包名，**16 KB 页对齐** |
| 输入方案 | ✅ **3 个**：客语12键（`hakka_jiugong`）/ 客语26键（`hakka_qwerty`）/ 雲龍國際音標（`ipa_yunlong`）+ 朙月拼音（`luna_pinyin`，供反查），共 11 个方案/词典文件 |
| OpenCC 简繁 | ✅ **34 个**词典/配置（18 txt + 16 json），fresh 部署生成 **18 个 `.ocd2`**（实测 s2t/t2s/s2tw/s2twp） |
| 数据资产 | ✅ `assets/rime/` 共 **23 项**（22 文件 + `opencc/`）；静态自检 **53 passed, 0 failed** |
| 构建/校验脚本 | ✅ `build-jni-so.sh` + `verify-jni-symbols.sh` + `verify-rime-assets.py` + `gen-keystore.sh` |
| 签名 | ✅ 密钥已移出仓库（`~/.android-keys/` 或 `HKREME_*` 环境变量）；无密钥时产出 `-unsigned.apk`（不报错） |
| APK | ✅ debug **13,437,603 B** / release **11,405,775 B**（新 key 签名，zipalign16 通过） |
| 真机 | ✅ **数据链路仪器测试通过**（`OK (1 test)`：fresh 部署 / 18 ocd2 / 10 bin / 3 方案）；⬜ 键盘界面人工冒烟未做 |
| 已知限制 | ⬜ IME 界面未冒烟；不预置 `*.userdb`（设计取舍）；仅 `arm64-v8a`；release lint 被 `-x lintVital*` 绕过；历史密钥仍在 git 历史 → 详见 [`docs/06`](docs/06-troubleshooting-roadmap.md) §5 |

> 事实快照：`ebb18c1`（当前 HEAD 以 `git log -1` 为准）。完整文档见 [`docs/`](docs/README.md)。

### 快速开始

```bash
# 1) 环境：必须用 JDK 17（AGP 7.4.2 / Gradle 7.5 不支持 JDK 21）
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"
# 2) SDK 路径（local.properties 不入库）
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
# 3) 构建 Debug APK
./gradlew :app:assembleDebug
#    产物：app/build/outputs/apk/debug/trime-3.2.7-debug.apk   ← 不是 app-debug.apk
# 4) 装机
adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
# 5) 授权「所有文件访问」（Android 11+ 必需，否则部署失败甚至卡死；重装后会重置）
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
# 6) 正式包（本机 lint 需绕过，详见 docs/03-build.md）
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
#    有密钥 → app/build/outputs/apk/release/trime-3.2.7-release.apk
#    无密钥 → …/trime-3.2.7-release-unsigned.apk（构建不失败，但需自行 apksigner sign）
```

首次启动会走「存储权限 → 启用输入法 → 选择输入法」引导。
数据目录：`/sdcard/Android/data/com.hazukieq.hkrime/files/rime/`（方案/词典/主题/`opencc/`）。
**改数据后**：`adb shell am broadcast -a com.hazukieq.hkrime.deploy` 重新部署，或 `adb shell pm clear com.hazukieq.hkrime` 强制重来。

真机仪器测试（验证部署 + OpenCC + 词典编译）：

```bash
JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9 ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
timeout 300 adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner   # 期望 OK (1 test)
```

> 必须套 `timeout`：权限被拒时 `Config.setTheme` ↔ `init` 会无限递归，把调用方挂死（`docs/06` §4.2）。

修改 native / 改包名后必须重建并校验 `.so`（Java 包名写死在二进制里，不能只改代码）：

```bash
./script/build-jni-so.sh          # 克隆上游 v3.2.7 → 校验子模块 pin → patch 包名 → cmake+ninja → strip → 16KB 对齐
./script/verify-jni-symbols.sh    # 期望：OK: JNI 符号契约一致（73 个声明全部导出，无旧包名残留）
```

改 `assets/rime/` 下的方案/词典/OpenCC 配置后，必须先跑静态自检（无需设备）：

```bash
python3 script/verify-rime-assets.py   # 期望：53 passed, 0 failed
```

### 常用命令

| 目的 | 命令 |
|---|---|
| 编译 Debug / Release | `./gradlew :app:assembleDebug` / `./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease` |
| 编译仪器测试 APK | `./gradlew :app:assembleDebugAndroidTest` |
| 重建 native 库 | `./script/build-jni-so.sh`（`--clean` 强制冷构建，约 20 s） |
| 校验 `.so` 与 Java 契约 | `./script/verify-jni-symbols.sh` |
| 校验 rime 数据引用 | `python3 script/verify-rime-assets.py`（53 项） |
| 生成发布密钥 | `./script/gen-keystore.sh`（写入 `~/.android-keys/`，不覆盖已有） |
| 校验 16 KB 对齐 | `$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 4 <apk>` |
| 格式检查 / 自动修 | `./gradlew spotlessCheck` / `./gradlew spotlessApply` |
| 触发部署 / 同步 | `adb shell am broadcast -a com.hazukieq.hkrime.deploy`（同步用 `.sync`） |
| 清 App 数据（强制重新部署 assets） | `adb shell pm clear com.hazukieq.hkrime` |

### 开发文档

完整中文开发参考在 [`docs/`](docs/README.md)：

| 文档 | 内容 |
|---|---|
| [docs/00-overview.md](docs/00-overview.md) | 定位、当前状态、谱系、仓库地图、关键事实速查 |
| [docs/01-architecture.md](docs/01-architecture.md) | 分层与包职责、按键链路、启动/部署时序、数据目录、线程模型 |
| [docs/02-jni-so.md](docs/02-jni-so.md) | **`.so` 定稿事实 / 历史崩溃根因 / 重建 / 验证 / 16 KB 对齐** |
| [docs/03-build.md](docs/03-build.md) | 环境版本、编 APK 与编 `.so`、**签名配置**、**仪器测试**、常见报错 |
| [docs/04-hakka.md](docs/04-hakka.md) | 提交史、**客家话数据清单**、缺口与下一步 |
| [docs/05-branches-release.md](docs/05-branches-release.md) | 分支模型、改名影响面 10 层、发布流程、**签名与密钥安全** |
| [docs/06-troubleshooting-roadmap.md](docs/06-troubleshooting-roadmap.md) | 四层排障、症状表、诊断命令、技术债与路线图 |
| [docs/07-opencc-and-rime-data.md](docs/07-opencc-and-rime-data.md) | OpenCC 三种文件关系、部署管线、客家话数据、**扩充指引** |
| [docs/08-theme-trime-yaml.md](docs/08-theme-trime-yaml.md) | **trime.yaml 主题全解**（style / 配色 / 按键 / 键盘 / 悬浮键盘 / 15 个坑） |
| [tools/theme-previewer/](tools/theme-previewer/README.md) | **主题实时预览器**：浏览器里渲染真实布局与配色，改主题不用装机 |
| [docs/reports/](docs/reports/README.md) | 构建 / 改名 / 集成 / 真机测试报告（证据存档） |

### 安全提示

签名密钥**已从仓库移出**（历史提交中的 `skeys.jks` / `release.properties` 仍是明文，
旧密钥应视为已泄露；彻底清除需改写 Git 历史，见 [`docs/05-branches-release.md`](docs/05-branches-release.md) §6）。
当前签名走三级查找：① 仓库根 `release.properties`（已 gitignore）② `~/.android-keys/hkrime-release.properties`
③ 环境变量 `HKREME_STORE_FILE/HKREME_STORE_PASSWORD/HKREME_KEY_ALIAS/HKREME_KEY_PASSWORD`；
都没有时 release **不签名但不失败**，产物为 `trime-3.2.7-release-unsigned.apk`（`apksigner verify` 会报 `Missing META-INF/MANIFEST.MF`）。
换签名后旧版本无法覆盖升级，发布前请确认。

---

## 關於/About
源於開源的[注音倉頡輸入法]前端，  
基於著名的[RIME]輸入法框架，  
使用JNI的C語言和安卓的Java語言書寫，  
旨在保護漢語各地方言母語，  
音碼形碼通用的輸入法平臺。  

## 編譯/Build
由于移除了原仓库的CMake相关文件，改为内置预编译的so文件，使得clone和build难度得到了极大地下降。  
直接下载源码，或者clone，然后使用Android Studio打开即可。  
如需clone，建议使用如下命令，只获取最新的一个版本从而降低clone用时:  
`git clone --depth 1 https://github.com/tumuyan/trime-without-CMake`

## 下載/Download
本仓库所有功能性的变更，均会提交pr给原仓库。因此这里永远都不会编译并发布正式版。 
每次提交pr时，都会分享测试版本到同文用户QQ群。[github release页面](https://github.com/osfans/trime/releases)随缘更新。
已经修改包名、App name，与原版同文不存在冲突；但是如果在此版本和原版之间切换，建议先结束进程再切换。

## 使用/Use
使用方法与原版完全相同。

由于可能存在版本差异，建议与原版同文使用不同的文件夹。（设置方法：打开同文设置-输入配置-用户文件夹，修改路径并新建对应文件夹）
新增和调整的功能可以在github commit和pr说明中查看，wiki也有整理较大的变化： https://github.com/tumuyan/trime-without-CMake/wiki  

## 在線編譯客製化APK✨
本仓库提供了在线编译客制化APK的功能。  
Fork本仓库后，先根据需求修改`.github/workflows/build-my-trime.yml`中的参数；然后在Github Action中可以看到多个Workflows。点击`Build My Trime`，在右侧再点击`Run workflow`，等待2分钟即可完成build。  
![](ci_1.jpg)

编译结束后，点击对应项，在最下方有Artifacts。点击zip文件并下载、解压即可获得在线编译好的APK文件。  
![](ci_2.jpg)

#### 支持的客製化參數
| 项目                                        | 参数                                                       |
|-------------------------------------------|----------------------------------------------------------|
| ”关于“界面中的编译信息                              | CI_NAME: Custom CI                                       |
| ”关于“界面中的修改版QQ群                            | CUSTOM_QQ:                                               |
| ”关于“界面中的修改版QQ群链接                         | CUSTOM_QQ_LINK:                                          |
| 在点击`Run workflow`时如填入true,会使用此参数指定的仓库进行编译 | REPO_URL: https://github.com/tumuyan/trime-without-CMake |
| 上个参数指定的分支                                 | REPO_BRANCH: develop                                     |
| 仓库中的包名                                    | PACKAGE_NAME: com.tumuyan.trime                          |
| 修改后的包名                                    | PACKAGE_NAME_NEW: com.tumuyan.trime.pro                  |
| 仓库中的默认App名称                               | APP_NAME: Trime·Trime                                    |
| 仓库中的简中App名称                               | APP_NAME_CN: 同文输入法・Trime                                 |
| 仓库中的繁中App名称                               | APP_NAME_TW: 同文輸入法・Trime                                 |
| 修改后的默认App名称                               | APP_NAME_NEW: TrimePro                                   |
| 修改后的简中App名称                               | APP_NAME_NEW_CN: 同文输入法Pro                                |
| 修改后的繁中App名称                               | APP_NAME_NEW_TW: 同文輸入法Pro                                |

#### 更多客製化内容

计划依顺序自动完成以下工作

- [ ] 打包仓库中的主题
- [ ] 打包仓库中的方案
- [ ] 打包指定仓库的主题和方案
- [ ] 对图标进行替换
- [x] 对`关于`界面做客制化
- [x] 对`寻求帮助`界面做客制化
- [ ] 对UI、弹出信息中多次出现的Trime、同文等文本内容进行替换

## 鳴謝/Credits
- Developer: [osfans](https://github.com/osfans)
- Contributors: [boboIqiqi](https://github.com/boboIqiqi)、[Bambooin](https://github.com/Bambooin)、[senchi96](https://github.com/senchi96)、[heiher](https://github.com/heiher)、[abay](https://github.com/a342191555)、[iovxw](https://github.com/iovxw)、[huyz-git](https://github.com/huyz-git)、[tumuyan](https://github.com/tumuyan)、[WhiredPlanck](https://github.com/WhiredPlanck)...
- [Wiki Editors](https://github.com/osfans/trime/wiki): [xiaoqun2016](https://github.com/xiaoqun2016)、[boboIqiqi](https://github.com/boboIqiqi)...
- Translators: 天真可爱的满满 (Chinese Traditional), 点解 (English) ...
- Keyboard Designers: 天真可爱的满满、皛筱晓小笨鱼、吴琛11、熊猫阿Bo、默默ㄇㄛˋ...
- Donations: See QR Code in [Releases](https://github.com/osfans/trime/releases)
- Community: [Issues](https://github.com/osfans/trime/issues), [QQ Group（811142286）](https://jq.qq.com/?_wv=1027&k=AXdR80HN), [QQ Group（458845988）](https://jq.qq.com/?_wv=1027&k=n6xT4G3q), [Coolapk](http://www.coolapk.com/apk/com.osfans.trime), [Google Play](https://play.google.com/store/apps/details?id=com.osfans.trime) and [Tieba](http://tieba.baidu.com/f?kw=rime)
- Projects: [RIME]、[OpenCC]、[Android Traditional Chinese IME] and so on.

## 沿革/History
- 最初，輸入法是寫給[泰如拼音](http://taerv.nguyoeh.com/ime/)（tae5 rv2）的，中文名爲“泰如輸入法”。  
- 然後，添加了吳語等方言碼表，做成了一個輸入法平臺，更名爲“漢字方言輸入法”。  
- 後來，兼容了五筆、兩筆等形碼，在太空衛士、徵羽的建議下，更名爲“[同文輸入法平臺2.x](https://github.com/osfans/trime-legacy)”。寓意音碼形碼同臺，方言官話同文。  
- 之後，藉助JNI技術，享受了[librime](https://github.com/rime/librime)的成果，升級爲“同文輸入法平臺3.x”，簡稱“同文輸入法”。  
- 所以，TRIME是Tongwen RIME或是ThaeRvInputMEthod的縮寫。

## 第三方庫/Third Party Libraries
- [Boost C++ Libraries](https://www.boost.org/) (Boost Software License)
- [Cap'n Proto](https://capnproto.org/) (MIT License)
- [darts-clone](https://github.com/s-yata/darts-clone) (New BSD License)
- [LevelDB](https://github.com/google/leveldb) (New BSD License)
- [libiconv](https://www.gnu.org/software/libiconv/) (LGPL License)
- [marisa-trie](https://github.com/s-yata/marisa-trie) (BSD License)
- [minilog](http://ceres-solver.org/) (New BSD License)
- [OpenCC](https://github.com/BYVoid/OpenCC) (Apache License 2.0)
- [RIME](https://rime.im) (BSD License)
- [snappy](https://github.com/google/snappy)(BSD License)
- [UTF8-CPP](http://utfcpp.sourceforge.net/) (Boost Software License)
- [yaml-cpp](https://github.com/jbeder/yaml-cpp) (MIT License)
- [Android Traditional Chinese IME](https://code.google.com/p/android-traditional-chinese-ime/) (Apache License 2.0)

[Android Traditional Chinese IME]: https://code.google.com/p/android-traditional-chinese-ime/
[RIME]: http://rime.im
[OpenCC]: https://github.com/BYVoid/OpenCC
