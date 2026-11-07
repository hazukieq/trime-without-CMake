# 03 · 构建与验证

> 事实快照：`ebb18c1`（当前 HEAD 以 `git log -1` 为准）。
> 本篇覆盖：**环境版本** → **签名** → **编 APK** → **编 .so** → **仪器测试** → **产物校验** → **报错速查**。
> without-CMake 的含义：仓库不编译 librime，只提交预编译 `.so`；但本仓库额外提供**从上游源码重建 `.so` 的脚本**。

## 目录

1. [环境与前置](#1-环境与前置)
2. [签名与密钥](#2-签名与密钥)
3. [编 APK](#3-编-apk)
4. [编 .so（可选，重建 JNI 库）](#4-编-so可选重建-jni-库)
5. [仪器测试与真机验证](#5-仪器测试与真机验证)
6. [产物与校验](#6-产物与校验)
7. [工具与脚本速查](#7-工具与脚本速查)
8. [常见报错](#8-常见报错)
9. [代码风格（spotless）](#9-代码风格spotless)

---

## 1. 环境与前置

### 1.1 版本矩阵（本机实测）

| 组件 | 版本 / 路径 | 说明 |
|---|---|---|
| JDK | **17**，`$HOME/opt/jdk17/jdk-17.0.9` | **必须 17**；系统默认 JDK 21 会让 AGP 7.4.2 / lint 出问题 |
| Gradle | 7.5（`gradle/wrapper/gradle-wrapper.properties`，已缓存于 `~/.gradle/wrapper/dists`） | 由 wrapper 自带，勿升级 |
| AGP | 7.4.2（`build.gradle:37`） | 与 Gradle 7.5 / JDK 17 匹配 |
| Kotlin | 1.8.20（`build.gradle:10`），插件 1.7.20（`:53`） | 版本不一致是历史现状，能编 |
| compileSdk / targetSdk / minSdk | 33 / 33 / 21（`app/build.gradle:5/11/10`） | |
| NDK | **24.0.8215888**（`app/build.gradle:6`） | 只有「编 .so」才用；编 APK 不需要 |
| CMake | 3.22.1 | 同上 |
| Ninja | 1.10.2（NDK 自带） | 同上 |
| build-tools | **35 及以上** | `zipalign -P 16`（16 KB 校验）需要 |
| Python 3 + PyYAML | 任意 3.8+ | 跑 `script/verify-rime-assets.py` |
| Android SDK | `/home/hazukie/Android/Sdk`（`local.properties`） | 换机器需自行改 |

### 1.2 三条前置

```bash
# ① JDK 17（本机路径，按需替换）
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9
export PATH=$JAVA_HOME/bin:$PATH
java -version          # 期望 openjdk 17.0.9

# ② SDK 位置
cat local.properties   # sdk.dir=/home/hazukie/Android/Sdk

# ③ 首次构建会下载 Gradle 7.5 与依赖（网络）
./gradlew --version
```

> 所有命令都在**仓库根目录**执行；`./gradlew` 是本仓库 wrapper。

---

## 2. 签名与密钥

### 2.1 生成密钥（推荐做法）

```bash
./script/gen-keystore.sh          # 幂等：已存在则拒绝覆盖
./script/gen-keystore.sh --force  # 覆盖（会破坏旧版本升级路径，慎用）
```

| 项 | 值 |
|---|---|
| 输出目录 | `~/.android-keys/`（`chmod 700`） |
| 密钥库 | `~/.android-keys/hkrime-release.jks`（2,744 B，`chmod 600`） |
| 属性文件 | `~/.android-keys/hkrime-release.properties`（147 B，`chmod 600`） |
| 算法 | RSA 2048，有效期 **10000 天** |
| alias | `hkrime`（可用 `--alias` 改） |
| DN | `CN=hkrime, OU=hazukieq, O=hkrime-fork, L=Guiping, ST=Guangxi, C=CN` |

脚本参数：`--force` / `--alias <name>` / `--dir <dir>`；共 79 行。

### 2.2 签名配置怎么找到密钥（`app/build.gradle:26-75`）

```text
signingConfigs { release { } }            ← :26 只声明空壳
   │
   └─ keystore 查找（:43-75）
        ① rootProject.file('release.properties')                       ← 仓库根（不入库）
        ② ~/.android-keys/hkrime-release.properties                    ← 推荐
        ③ 环境变量 HKREME_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD
        │
        ├─ 找到 → 填 storeFile(:63) / storePassword(:64) / keyAlias(:65，默认 hkrime) / keyPassword(:66)
        │         → :67 `android.buildTypes.release.signingConfig = android.signingConfigs.release`
        └─ 没找到 → :73 logger.lifecycle 提示「release 签名未配置」，**不赋值**
```

要点：

- **`:67` 才是"要不要签名"的开关**——不是靠 buildType 里写死 `signingConfig signingConfigs.release`。
- 没配签名时 release **不会失败**，而是产出**未签名** APK（见 2.3）。
- 三级查找的优先级：仓库根 `release.properties` **会覆盖** `~/.android-keys`。
- 环境变量只补位 `storeFile` 与口令/alias，不替代属性文件（`storeFile` 用 `props.storeFile ?: env`）。

### 2.3 有密钥 / 无密钥的行为对照（`ebb18c1` 后）

| 场景 | `assembleRelease` 结果 | 产物文件名 | 校验 |
|---|---|---|---|
| **有** keystore | BUILD SUCCESSFUL | `trime-3.2.7-release.apk` | `apksigner verify` 通过 |
| **无** keystore | **BUILD SUCCESSFUL**（不报错） | `trime-3.2.7-release-unsigned.apk`（实测 11,316,527 B） | `apksigner verify` 报 `DOES NOT VERIFY: Missing META-INF/MANIFEST.MF` |
| 无 keystore + 强制检查 | — | — | 用 `apksigner verify` 失败即可判定未签名 |

> `ebb18c1` 之前：无 keystore 会直接报 `SigningConfig "release" is missing required property "storeFile"` 构建失败。
> 现行为对「只想编个包看看」的场景友好，但**发布前必须核验签名**。

对未签名 APK 后置签名：

```bash
apksigner sign \
  --ks ~/.android-keys/hkrime-release.jks \
  --ks-key-alias hkrime \
  --out trime-3.2.7-release-signed.apk \
  trime-3.2.7-release-unsigned.apk
apksigner verify --print-certs trime-3.2.7-release-signed.apk
```

### 2.4 为什么密钥不入库

历史教训：`92c7aa5` / `4af2a94` 曾把 `skeys.jks`（2,517 B）与 `release.properties`（74 B，明文口令 `263909`）提交进仓库，`bebad9f` 才移出当前树。
**但历史提交里仍在**，详见 [05 §6](05-branches-release.md)。当前 `.gitignore` 已忽略 `*.jks`、`release.properties`、`*.keystore`。

---

## 3. 编 APK

### 3.1 debug

```bash
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/trime-3.2.7-debug.apk
```

### 3.2 release

```bash
./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
# 产物：app/build/outputs/apk/release/trime-3.2.7-release.apk（无密钥时为 -unsigned.apk）
```

**为什么必须 `-x lintVital*`**：AGP 7.4.2 的 lint 在 JDK 17 环境下会崩（与项目代码无关）。
这是环境绕过，不是质量豁免——正式发版前建议另找 JDK 11 跑一次 lint。**该问题至今未修**（见 06 路线图 P1）。

### 3.3 APK 名与路径

`archivesBaseName "trime-$versionName"`（`app/build.gradle:14`），所以文件名固定为 `trime-3.2.7-<buildType>.apk`：

| 类型 | 路径（仓库内） | 说明 |
|---|---|---|
| debug | `app/build/outputs/apk/debug/trime-3.2.7-debug.apk` | |
| release | `app/build/outputs/apk/release/trime-3.2.7-release.apk` | 无密钥时 `…-release-unsigned.apk` |
| androidTest | `app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk` | 仪器测试用 |

### 3.4 编测试 APK

```bash
./gradlew assembleDebug assembleDebugAndroidTest
```

`app/build.gradle:29` 已配 `testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"`；
依赖 `androidx.test.ext:junit:1.1.5`、`androidx.test:runner:1.5.2`（`:104-105`）。

---

## 4. 编 .so（可选，重建 JNI 库）

**平时不需要**：仓库已提交 `app/libs/arm64-v8a/librime_jni.so`（13,587,744 B）。
只有「改包名 / 换上游版本 / 改 JNI 补丁」时才需重建。脚本 419 行，自动完成上游 clone、patch、构建、strip、校验。

```bash
# 最常用：用本机已缓存的子模块源码重建（不联网 clone）
./script/build-jni-so.sh --src-dir ../jni-build/trime-v3.2.7

# 从 GitHub 全新 clone（慢，需网络）
./script/build-jni-so.sh --clean

# 产物
#   app/libs/arm64-v8a/librime_jni.so  (+ .sha256)
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--pkg` | `com.hazukieq.hkrime` | 新包名（写进 JNI 符号） |
| `--old-pkg` | `com.osfans.trime` | 旧包名（patch 源） |
| `--upstream-repo` / `--upstream-ref` | `osfans/trime` / `v3.2.7` | 上游基准 |
| `--abi` | `arm64-v8a` | |
| `--ndk` | 自动探测 | 需 24.0.8215888 |
| `--build-type` | `Debug` | Debug=-O0（带调试串）；Release 更小（见 02 §8） |
| `--out-dir` | `app/libs/<abi>` | |
| `--expect-symbols` | 73 | 符号数期望值 |
| `--clean` | 关 | 重新 clone |
| `--log` | 自动 | 构建日志 |

**16 KB 页对齐**（Android 15+ 必需）已固化进脚本：`:348` 注释、`:366` 实参
`-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"`。详见 [02 §7](02-jni-so.md)。

冷构建实测 **18–21 s**（20 核，357 步）；增量约 1.4 s。构建细节与坑见 [02 §5](02-jni-so.md)。

---

## 5. 仪器测试与真机验证

### 5.1 测试覆盖什么（`app/src/androidTest/java/com/hazukieq/hkrime/RimeDataTest.kt`，147 行）

测试**先清空** `sharedDataDir` 与 `userDataDir/build`（`:47-48`）强制 fresh 部署——否则同版本覆盖安装不会重拷 assets。

| 断言组 | 内容 |
|---|---|
| 部署文件 | `trime.yaml`、`default.custom.yaml`、`hakka_jiugong.schema.yaml`、`hakka.dict.yaml`、`hakka_oftenwords.dict.yaml` 存在 |
| OpenCC 资源 | `opencc/` 存在；`s2t.json`、`STCharacters.txt` 存在；`.ocd2` **≥17**（fresh 实测 18）；`STCharacters.ocd2` 存在 |
| 编译产物 | `userDataDir/build/*.bin` 非空且含 `hakka.table.bin`；`hakka_qwerty.prism.bin`、`ipa_yunlong.prism.bin`、`luna_pinyin*` bin |
| 方案列表 | 3 项：客语12键 / 客语26键 / 雲龍國際音標 |
| 滤镜可加载 | `ywz.ocd2`、`lenth.ocd2` 存在；`openccConvert("不屑","ywz.json")`、`("qq","lenth.json")` 非 null |
| 转换正确性 | `汉字→漢字(s2t)`、`漢字→汉字(t2s)`、`软件→軟件(s2tw)`、`软件→軟體(s2twp)` |

> **多值断言为何只测"非 null"**：`ywz`/`lenth` 的候选词全量展开由 librime `simplifier` 的 `Opencc::ConvertWord` 完成，
> 而 JNI `Rime.openccConvert` 走 `SimpleConverter` 只取首值。细节见 [07 §3.1](07-opencc-and-rime-data.md)。

### 5.2 完整命令序列

```bash
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9
./gradlew assembleDebug assembleDebugAndroidTest

adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk

# 关键：授「所有文件访问」权限，否则测试会卡在部署
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow

# 关键：必须套 timeout（原因见 5.3）
timeout 300 adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
```

期望输出结尾：`OK (1 test)`。

### 5.3 为什么必须用 `timeout` 包住

若 `MANAGE_EXTERNAL_STORAGE` 被拒，`Config` 会进入**无限递归**：

```text
Config.setTheme(theme)            // data/Config.java:292
   └─ init(skip_delopy)           // :355
        └─ catch → setTheme(defaultName)   // :395
             └─ init(...) → 再抛 → 再 setTheme … （不终止）
```

表现：`am instrument` 一直不返回、日志刷同一段栈。**这是未修的 bug**（见 [06 §4.2](06-troubleshooting-roadmap.md)）。
处置：`timeout` + 确认 `appops` 已授权。**每次 `adb install -r` 后授权会重置，必须重设。**

### 5.4 真机结果（第二轮，缺口修复后）

| 项 | 结果 |
|---|---|
| 设备 | Xiaomi M2012K11AC / Android 16 / adb serial `5f08bc20` |
| 测试 | `OK (1 test)` |
| opencc 生成 | **18 个 `.ocd2`**（运行期目录共 52 条目 = 34 源文件 + 18 ocd2） |
| build bins | **10 个**（hakka.* / luna_pinyin.* / hakka_qwerty.prism / ipa_yunlong.*） |
| 方案 | **3 项**：客语12键 / 客语26键 / 雲龍國際音標 |
| 共享目录 | `/storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime` |
| 用户目录 | 该机为 `/storage/emulated/0/rime`（既有偏好所致，非默认值） |
| OpenCC 版本 | `ver.1.1.4` |

### 5.5 跑测试前必读：别动用户数据

该测试会在**用户真实数据目录**上部署。本机第二次测试前发现 `/sdcard/rime` 是日常输入法数据（含 `hakka_qwerty.schema.yaml`、`hakka.userdb`、`backup/`），做法是：

```bash
# 测试前备份（示例）
adb shell mv /sdcard/rime /sdcard/rime.hkrime-backup-$(date +%s)
# 测试后恢复，并把测试产物挪走
```

测试收尾也要做：默认输入法切回 Gboard、卸载测试 APK、清理 `rime.hkrime-test-*` 目录。详见 [reports/DEVICE-TEST-REPORT.md](reports/DEVICE-TEST-REPORT.md)。

---

## 6. 产物与校验

### 6.1 当前交付物（`artifacts/`，仓库外 `../artifacts/`）

| 文件 | 大小 | sha256 |
|---|---|---|
| `librime_jni.so` | 13,587,744 B | `fd4372553592830cc8af03a7cae10009eb1882f2eada957086513a7fbc60f03f` |
| `librime_jni-release-opt.so` | 6,065,152 B | `6b6173d10039262a57205efb321d195d72c1e71b568ec8ba0320beaf4696f920` |
| `hkrime-debug.apk` | 13,437,603 B | `fe6ae286697614a52afbe4ef1a8db75e3df8fbf167d404c2d22805ef3597e575` |
| `hkrime-release.apk` | 11,405,775 B | `62b63eb2555229a6df9e78bdc7719da857fd4db7cd26973b7e3533d760ddd0a5` |

release 证书：SHA-256 `d9d7d91ae5e7c789ff79b8eb9a1644461e5b7b2c48b52a184d993a90594693cb`，
DN `CN=hkrime, OU=hazukieq, O=hkrime-fork, L=Guiping, ST=Guangxi, C=CN`。

**体积说明**：APK 比上一版大（debug +1.87 MB / release +1.40 MB），原因是并入 `luna_pinyin.dict.yaml`（889,946 B）与 3 个新方案/词典，不是废数据。

### 6.2 重要口径：APK 字节 hash 不保证跨构建可复现

APK 是 zip 容器，**条目时间戳与顺序**会让同一份代码两次 clean 构建产出不同字节。因此：

- ✅ 可以承诺：clean 构建**尺寸稳定**（debug ≈13.44 MB、release ≈11.41 MB）。
- ❌ 不要承诺：任意时刻 clean build 得到**同一个 sha256**。
- 文档与发布记录里的 sha256 是**该次交付物**的指纹，用于校验收到的文件没被改动。

### 6.3 校验命令

```bash
# ① 交付物指纹
sha256sum artifacts/librime_jni.so artifacts/hkrime-debug.apk artifacts/hkrime-release.apk
cat artifacts/SHA256SUMS

# ② APK 里到底打进了哪个 .so（唯一，且字节一致）
unzip -l artifacts/hkrime-debug.apk | grep librime_jni
unzip -p artifacts/hkrime-debug.apk lib/arm64-v8a/librime_jni.so | sha256sum
#   期望：与 app/libs/arm64-v8a/librime_jni.so 的 sha256 相同

# ③ 16 KB 对齐（build-tools 35+）
zipalign -c -P 16 4 artifacts/hkrime-debug.apk && echo "zipalign OK"
zipalign -c -P 16 4 artifacts/hkrime-release.apk && echo "zipalign OK"

# ④ 包名/版本
/home/hazukie/Android/Sdk/build-tools/36.0.0/aapt2 dump badging artifacts/hkrime-release.apk | head -3
#   package: name='com.hazukieq.hkrime' versionCode='20220701' versionName='3.2.7' …

# ⑤ 签名
apksigner verify --print-certs artifacts/hkrime-release.apk

# ⑥ JNI 符号契约（源码声明 vs .so 导出）
./script/verify-jni-symbols.sh
#   OK: JNI 符号契约一致（73 个声明全部导出，无旧包名残留）

# ⑦ rime 资产静态自检
python3 script/verify-rime-assets.py
#   结果: 53 passed, 0 failed
```

`.so` 本身的手工复核（`file` / `readelf -d` / `readelf -l` / `nm -D` / `strings`）见 [02 §6.2](02-jni-so.md)。

### 6.4 还没验证的

| 项 | 状态 |
|---|---|
| 键盘界面人工冒烟（打字/上屏/翻页/主题/简繁/情联开关/反查） | ⬜ 未做 |
| release 包真机安装运行 | ⬜ 仅做了签名与对齐校验 |
| lint 全量通过 | ⬜ 被 `-x lintVital*` 绕过 |
| 真机 IME 设为默认并长期使用 | ⬜ 测试后已切回 Gboard |

---

## 7. 工具与脚本速查

| 脚本 | 行数 | 用途 |
|---|---|---|
| `script/build-jni-so.sh` | 419 | 从上游 v3.2.7 重建 `librime_jni.so`（含 16 KB 对齐、pin 校验、strip） |
| `script/verify-jni-symbols.sh` | 237 | 解析 Java/Kotlin 的 `native`/`external fun` 声明，与 `.so` 的 `Java_*` 导出逐条比对（需 python3） |
| `script/verify-rime-assets.py` | 183 | 静态自检：`schema_list`→schema 文件、schema 的 `dictionary`→`dict.yaml`、`opencc_config`→json、json 引用的 txt/ocd2（允许 ocd2 由同名 txt 生成）、ywz/lenth 多值抽查 |
| `script/gen-keystore.sh` | 79 | 生成 release 密钥与属性文件（`~/.android-keys/`） |
| `script/changelog.sh` | 8 | 旧脚本，含拼写 bug（`$curent`），**目前不可用** |

---

## 8. 常见报错

| 现象 | 原因 | 处理 |
|---|---|---|
| `Unsupported class file major version 65` / lint 崩 | 用了 JDK 21 | `export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9` |
| `SigningConfig "release" is missing required property "storeFile"` | `ebb18c1` 之前的旧行为 | 升级到 `ebb18c1`+；或提供 keystore；或直接产出 unsigned |
| `DOES NOT VERIFY: Missing META-INF/MANIFEST.MF` | 产物是未签名 APK | 用 `apksigner sign` 后置签名（2.3） |
| `Caused by: java.lang.UnsatisfiedLinkError: …` | `.so` 与 Java 不匹配（旧 85 MB 库症状） | 确认 `app/libs/*/librime_jni.so` 是最新构建；跑 `verify-jni-symbols.sh` |
| `am instrument` 长时间不返回 | 权限被拒 → `Config` 递归 | `appops set … MANAGE_EXTERNAL_STORAGE allow`；用 `timeout 300` 包裹；重装后需重设 |
| 系统弹「此应用不符合 16 KB 对齐要求」 | `.so` 未按 16 KB 对齐 | 用含 `max-page-size=16384` 的脚本重建（`9dc8c32` 已固化）；`zipalign -c -P 16 4` 复核 |
| 方案列表为空 / 点开没字 | assets 未部署完、缺 `default.custom.yaml`、或 schema 缺词典 | 见 [06 §4.1](06-troubleshooting-roadmap.md)；跑 `verify-rime-assets.py` |
| `make` 失败（`jni/OpenCC` 不存在） | Makefile 的 `opencc-data` 仍依赖被 gitignore 的 JNI 子模块 | 不要用 `make`；OpenCC 资源已随仓库提交，直接 `./gradlew` |
| `./gradlew` 卡在下载 | 网络 / 镜像 | 检查 `build.gradle:22` 的阿里云镜像配置；本地已有 `gradle-7.5-bin` 缓存 |
| `zipalign: unknown option -P` | build-tools 过旧 | 用 build-tools 35+ |
| 测试断言 `.ocd2 >= 17` 失败 | OpenCC 资源未拷进 APK | 确认 `assets/rime/opencc/` 有 34 个文件；重装 APK（同版本覆盖不会重拷 assets，需卸载或清数据） |

---

## 9. 代码风格（spotless）

```bash
./gradlew spotlessCheck     # 检查
./gradlew spotlessApply     # 自动修正（会改文件，提交前跑）
```

配置在根 `build.gradle:63-77`：Java 用 google-java-format 1.15.0，Kotlin 用 ktlint 0.44.0，
target 已改为 `app/src/main/java/com/hazukieq/hkrime/**`。

> 在 JDK 17 下跑需要 `gradle.properties` 里的 `--add-exports jdk.compiler/...` 参数（已配）。
> 注意 `gradle.properties` 有**两段** `org.gradle.jvmargs`，后者覆盖前者；当前两段都含堆参数，暂不丢配置，
> 但建议合并成一行（见 06 技术债）。
