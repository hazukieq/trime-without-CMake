# RENAME-REPORT — hkrime 整包改名 `com.hazukieq.hkrime`

- 仓库：`/home/hazukie/Projects/common/trime-workspace/hkrime`，branch `hakka`，origin `https://github.com/hazukieq/trime-without-CMake.git`
- 任务：task-2（整包改名 + 可校验 APK）
- 执行者：renamer
- 状态：**完成**（debug 构建 + 校验通过；release 构建 + 签名校验通过，仅作签名配置验证）
- **task-3 后记（2026-11-08）**：本报告记录的旧 JNI `.so` / `loadLibrary("rime_jni_1_5")` / 旧 APK 数值均已被 task-3 取代。最终状态见 `artifacts/INTEGRATION-REPORT.md`；本报告中相应段落已就地加注「已被 task-3 取代」，保留原文作为改名阶段的现场记录。
- 未执行 `git commit` / `git push`（由 Lead 统一提交）

## 1. 结论摘要

| 项目 | 结果 |
|---|---|
| APK 包名 | `com.hazukieq.hkrime`（aapt2 badging 确认） |
| namespace / applicationId | 均 `com.hazukieq.hkrime` |
| 源码目录 | `app/src/main/java/com/osfans/trime` → `app/src/main/java/com/hazukieq/hkrime`（`git mv`，保留历史） |
| 改名涉及文件 | 103 个（其中 391 行逐行替换） |
| dex 内旧包名残留 | `com/osfans/trime` 出现 0 次（debug 15 个 dex、release 2 个 dex 全为 0） |
| resources.arsc 旧包名残留 | 0 次 |
| 旧包名残留位置 | ~~仅 2 个旧 JNI `.so`（各 10–11 处，预期内，task-3 替换）+ 故意保留的上游 URL~~ → **task-3 已替换新 `.so`：`.so` 内旧包名 0 处**；现仅剩故意保留的上游 URL |
| debug APK | ~~`artifacts/hkrime-debug.apk`，15,804,283 B，sha256 `ce7ccf1a…fe8e2f`~~ → **task-3 重建：11,418,991 B，sha256 `8cc718eb…cce545`** |
| release APK | ~~`artifacts/hkrime-release.apk`，13,407,731 B，sha256 `9c7e2ae1…982982df`~~ → **task-3 重建：9,586,652 B，sha256 `9fe31e15…3575fe`**，apksigner 签名仍有效 |

## 2. 环境（构建实测）

| 项 | 值 |
|---|---|
| JDK | `$HOME/opt/jdk17/jdk-17.0.9`（`java -version` → 17.0.9，含 javac） |
| Gradle | wrapper 7.5（`gradle/wrapper/gradle-wrapper.properties` 未改） |
| AGP | 7.4.2（未升级） |
| Kotlin | 1.8.20（未升级） |
| compileSdk / targetSdk / minSdk | 33 / 33 / 21（未改） |
| NDK | 24.0.8215888 |
| SDK | `/home/hazukie/Android/Sdk`；build-tools 33.0.1；cmdline-tools/latest（apkanalyzer） |
| abiFilters | `arm64-v8a` |
| 构建网络 | 本地代理对部分 CDN 的并发 TLS 握手不稳定（见 §5.2），依赖已缓存 |

## 3. 改名实施

### 3.1 执行命令

```bash
cd /home/hazukie/Projects/common/trime-workspace/hkrime

# 1) 目录改名（保留 git 历史）
mkdir -p app/src/main/java/com/hazukieq
git mv app/src/main/java/com/osfans/trime app/src/main/java/com/hazukieq/hkrime

# 2) 包名引用全量替换（对 git ls-files 中 150 个文本文件；排除 .so/.jar/图片/字体/.jks）
#    文件清单存于 /tmp/rename-files.txt
sed -i \
  -e 's/com\.osfans\.trime/com.hazukieq.hkrime/g' \
  -e 's/\bcom\/osfans\/trime\b/com\/hazukieq\/hkrime/g' \
  -e 's/com\.tumuyan\.trime/com.hazukieq.hkrime/g' \
  $(cat /tmp/rename-files.txt)

# 3) 误伤还原：sed 的 slashed 模式会把上游 URL 的域名路径改坏
#    github.com/osfans/trime 被改成 github.com/hazukieq/hkrime（不存在），已还原；
#    play.google.com?id=com.osfans.trime、coolapk.com/apk/com.osfans.trime 同属外部引用，还原为上游值。
#    涉及：app/src/main/res/xml/about_preference.xml、app/src/main/assets/rime/trime.yaml、
#          app/src/main/assets/rime/tongwenfeng.trime.yaml

# 4) 构建配置修正（见 §4.6 / §5）
#    app/build.gradle：namespace + applicationId（见 §3.3）
#    build.gradle：spotless target 路径（见 §3.3）
#    gradle.properties：merge 重复的 org.gradle.jvmargs（-Xmx2048M 原先被第二段覆盖）
#    release.properties：storeFile 绝对路径 → 相对路径 skeys.jks（Lead 已批准）
```

### 3.2 统计

`git diff --stat`（改名相关文件，排除他人对 `README.md` 的文档改动）：

```
103 files changed, 393 insertions(+), 392 deletions(-)
```

拆分口径：

- **纯包名逐行替换**：`391 insertions / 391 deletions`（`app/**` + 根 `build.gradle`）
  - 389 行含 dotted 引用（`com.osfans.trime` → `com.hazukieq.hkrime`）
  - 2 行含 slashed 路径（spotless `target 'app/src/main/java/com/osfans/trime/**'` → `com/hazukieq/hkrime/**'`）
- **构建配置修复**：`gradle.properties` +1 行、`release.properties` 1 行替换
- 合计替换包名引用 **393 处**（391 处 dotted + 2 处 slashed）
- 校验：改名后仓库内已无 `package com.osfans.trime` / `package com.tumuyan.trime`，也无 `hkrime.hkrime` 双写

改动文件按类型：`60 kt` + `27 java` + `10 xml` + `2 yaml` + `2 gradle` + `2 properties` = 103。

> 口径注（2026-11-08 独立验收 DEV-3）：`gh api repos/hazukieq/trime-without-CMake/commits/922a2bae` 显示 **104 个条目、+394/−393**——比本表的 103 files/+393/−392 多 1 个条目与 1 行。差异来自同一提交还删除了二进制 `app/libs/arm64-v8a/librime_jni_1_5.so`（0/0 行，故不入 diffstat 文本口径）以及 Rime.java `loadLibrary` 一行替换的行统计口径差；两份数字均保留，以 gh 条目数 104 = 103 文本文件 + 1 二进制删除为准。

### 3.3 覆盖点逐项核对

| 位置 | 改动 | 证据 |
|---|---|---|
| `app/build.gradle` | `namespace 'com.osfans.trime'` → `'com.hazukieq.hkrime'`；`applicationId "com.tumuyan.trime"` → `"com.hazukieq.hkrime"`；版本/SDK/abiFilters 未动 | `git diff app/build.gradle` |
| 源码目录 | `app/src/main/java/com/osfans/trime` → `app/src/main/java/com/hazukieq/hkrime`（`git mv`） | `git status` 显示 87 个 RM 条目 |
| `package` / `import` | 87 个源码文件全部改为 `com.hazukieq.hkrime.*`（27 java + 60 kt） | 编译成功即证明 import 自洽 |
| `AndroidManifest.xml` | `android:name="com.osfans.trime.TrimeApplication"` → 新包；`activity-alias "com.osfans.trime.PrefLauncherAlias"` → 新包；`.TrimeImeService` 相对名不变（由 namespace 解析） | `git diff app/src/main/AndroidManifest.xml`；apkanalyzer manifest print |
| Provider authority | `com.blankj.utilcode.util.UtilsFileProvider` → `com.hazukieq.hkrime.utilcode.fileprovider`；`CaocInitProvider` → `com.hazukieq.hkrime.customactivityoncrashinitprovider`；`androidx.startup` → `com.hazukieq.hkrime.androidx-startup` | manifest print |
| 自定义权限 | `com.hazukieq.hkrime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（AGP 按包名自动生成） | aapt2 badging |
| `res/layout/*.xml`（6 个） | 自定义 View 全限定名：`<com.osfans.trime.settings.components.LogView>`、`Candidate`/`Composition`/`KeyboardView`/`TabView` 等 | `git diff app/src/main/res/layout/` |
| `res/xml/prefs.xml` | 4 个 `android:fragment="com.hazukieq.hkrime.settings.fragments.*Fragment"` | grep 确认 |
| `res/xml/keyboard_preference.xml` | `DialogSeekBarPreference` 全限定名 4 处 | `git diff` |
| `res/xml/method.xml` | `android:settingsActivity="com.hazukieq.hkrime.settings.PrefMainActivity"` | `git diff` |
| `res/values` 字符串 | `trime_app_name`/`trime_app_slogan` 等无包名，值未改（application-label 仍为 `Ftrime`） | aapt2 badging `application-label:'Ftrime'` |
| `assets/rime/trime.yaml` | `TrimeApp` run → `"com.hazukieq.hkrime"`；`TrimeCmp` → `"com.hazukieq.hkrime/.Pref"`；`Deploy` broadcast → `"com.hazukieq.hkrime.deploy"`；`Sync` → `"com.hazukieq.hkrime.sync"` | grep assets |
| `assets/rime/tongwenfeng.trime.yaml` | `TrimeApp` run → `"com.hazukieq.hkrime"`；`TrimeCmp` → `"com.hazukieq.hkrime/.Pref"` | grep assets |
| 根 `build.gradle` spotless | `target 'app/src/main/java/com/hazukieq/hkrime/**/*.java'` 与 `.../*.kt` | `git diff build.gradle` |
| ProGuard | `app/proguard-rules.pro` 中无包名规则（`proguard-android.txt` 在仓库中不存在）；`minifyEnabled false`，无需改 | 全仓 grep `com.osfans.trime` 无 proguard 命中 |
| `release.properties` | `storeFile=/home/hazukie/gitprojects/trime-without-CMake/skeys.jks`（原开发机绝对路径，已失效）→ `storeFile=skeys.jks`；密码/别名/storeType 未动，`skeys.jks` 未动 | `git diff release.properties` |
| `gradle.properties` | `org.gradle.jvmargs` 原被定义两次，第二段（仅 `--add-exports`）覆盖了第一段的 `-Xmx2048M -XX:+UseParallelGC`；已将内存参数并入第二段 | `git diff gradle.properties` |

## 4. 故意保留项（外部引用，不改）

| 保留内容 | 位置 | 判断依据 |
|---|---|---|
| `https://github.com/osfans/trime`（及 `.../blob/develop/PRIVACY.md`、`.../wiki/UserGuide`、`.../issues`、`.../tree/v3.2.7`） | `res/xml/about_preference.xml`、`assets/rime/*.yaml` 的 `Homepage`、README/docs | 上游项目 URL，是外部文档/源码地址，不属于本 APK 的包命名空间；改了会指向 404 |
| `https://play.google.com/store/apps/details?id=com.osfans.trime` | `res/xml/about_preference.xml:116` | 上游同文在 Google Play 的应用页，指向真实存在的第三方应用；改成 `com.hazukieq.hkrime` 会 404 |
| `https://www.coolapk.com/apk/com.osfans.trime` | `res/xml/about_preference.xml:121` | 同上（酷安上游应用页） |
| 版权头 `Copyright (C) 2015-present, osfans` / `waxaca@163.com` / `https://github.com/osfans` | 大量源文件头、`res/xml/method.xml` | GPL 版权署名，必须保留 |
| ~~`com.osfans.trime` 出现在 **旧 JNI `.so` 二进制内**（各 10–11 处）~~ **——已被 task-3 取代** | 旧 `app/libs/arm64-v8a/librime_jni.so`、`librime_jni_1_5.so` | JNI `FindClass` 硬编码的 Java 类路径，属 native 产物。task-3 已用新编 `.so`（`Java_com_hazukieq_hkrime_*`，73 符号，旧前缀 0）替换，`librime_jni_1_5.so` 已删除，见 `INTEGRATION-REPORT.md` |

APK 内旧包名残留实测（非 dex/so 条目）只剩上述 URL：

```
assets/rime/tongwenfeng.trime.yaml -> 1   (Homepage URL)
assets/rime/trime.yaml            -> 2   (Homepage + CommitHomepage URL)
res/xml/about_preference.xml      -> 6   (github ×4 + play + coolapk)
```

## 5. 行为敏感点清单

### 5.1 JNI / native（task-2 当时状态；**已被 task-3 取代**，见 `INTEGRATION-REPORT.md`）

| 事实 | 位置 |
|---|---|
| ~~`System.loadLibrary("rime_jni_1_5")`~~ → **已改为 `System.loadLibrary("rime_jni")`**（task-3） | `app/src/main/java/com/hazukieq/hkrime/core/Rime.java:238` |
| `System.loadLibrary("rime_jni")`（task-3 未改动，保持） | `app/src/main/java/com/hazukieq/hkrime/data/opencc/OpenCCDictManager.kt:15` |
| ~~旧 `.so` 内硬编码 `com/osfans/trime`~~ → **新 `.so` 内旧包名 0 处**（`strings` 实测） | `app/libs/arm64-v8a/librime_jni.so`（13,587,824 B，sha256 `d6b295b6…ca098ec`） |

> task-2 当时的影响（历史记录）：Java 包名已改为 `com.hazukieq.hkrime`，而旧 `.so` 通过 `FindClass("com/osfans/trime/core/Rime")` 之类回调 Java，运行时会 `ClassNotFoundException` / `UnsatisfiedLinkError`；因此 task-2 的 debug APK 只通过静态校验、不能作为可用产品。task-3 已换用新编 `.so`（JNI 符号与类路径均为 `com.hazukieq.hkrime`，`./script/verify-jni-symbols.sh` → 73/73、0 缺失、0 旧包名、0 多余），该风险已消除；真机运行时验证仍建议冒烟（见 `INTEGRATION-REPORT.md` §5）。

### 5.2 组件与标识

- launcher：`activity-alias com.hazukieq.hkrime.PrefLauncherAlias`，`targetActivity=com.hazukieq.hkrime.settings.PrefMainActivity`，MAIN/LAUNCHER（aapt2 badging 对 alias 不输出 `launchable-activity` 行，属 aapt2 已知行为；apkanalyzer manifest print 可见 alias 配置完整）。
- IME service：`com.hazukieq.hkrime.TrimeImeService`，`permission=android.permission.BIND_INPUT_METHOD`，`exported="false"`（与改名前的上游配置一致，本次未改），`meta-data android.view.im → @xml/method`。
- IME id = `com.hazukieq.hkrime/.TrimeImeService`（由 manifest 包名+类名派生）→ 与旧版 `com.osfans.trime/.TrimeImeService` / `com.tumuyan.trime/...` 不同，**系统设置里旧的"已启用输入法"记录失效**，装机后需重新启用并选择输入法。
- `android:settingsActivity` 已改为新包（`res/xml/method.xml`），IME 设置入口正常。
- Provider authority 全部随包名改变（utilcode / customactivityoncrash / androidx-startup），无跨版本共享需求。

### 5.3 广播 / 命令 action

`app/src/main/java/com/hazukieq/hkrime/ime/broadcast/IntentReceiver.kt:61-62`：

```kotlin
private const val COMMAND_DEPLOY = "com.hazukieq.hkrime.deploy"
private const val COMMAND_SYNC = "com.hazukieq.hkrime.sync"
```

与 `assets/rime/*.yaml` 中 `Deploy`/`Sync` 的 broadcast option 已同步（grep 确认一致）。任何外部脚本/快捷方式若硬编码旧 action（`com.osfans.trime.deploy`）需同步更新——仓库内已无旧 action。

### 5.4 数据 / 数据库路径

| 项 | 行为 |
|---|---|
| 默认用户数据目录 | `AppPrefs.kt:346-352`：`EXTERNAL_PATH_PREFIX = getExternalFilesDir(null).absolutePath`，默认 `$EXTERNAL_PATH_PREFIX/rime`。该绝对路径由运行时 packageId 决定 → 新包为 `/sdcard/Android/data/com.hazukieq.hkrime/files/rime`。**旧包数据不会自动继承**（需手动迁移或重新部署）。 |
| `LauchAct.kt:112` | `Environment.getExternalStorageDirectory() + "/rime"`（即 `/sdcard/rime`）为旧版兼容探测路径，硬编码未含包名，保留不变。 |
| SharedPreferences | 设备保护存储 `"shared_psfs"`（`AppPrefs.kt:21`）文件名不变；存储位置随包名走 → 新包为全新配置。 |
| 数据库 | 使用应用私有目录（Room/原生），未出现硬编码包名；随包名隔离。 |
| 日志导出 | `LogActivity.kt:90` 文件名前缀 `$packageName` → 自动变为 `com.hazukieq.hkrime-<时间>.txt`。 |
| 权限页跳转 | `PrefMainActivity.kt:252` `Uri.parse("package:$packageName")` → 自动指向新包。 |

结论：**数据不迁移**，符合"整包改名=全新应用"的预期；如需保留用户方案，需在装机后把旧目录数据拷到新目录（task-3/后续文档事项）。

### 5.5 应用名 / traits

- `application-label` 与 `trime_app_name` 字符串为 `Ftrime`（fork 既有值），本次未改。
- `Rime.java` 内没有 `traits.app_name` 赋值（grep 无 `traits`/`appName`）；RIME 的 `RimeTraits.app_name` 由 native 侧设置，属于 `.so` 行为，**本次改名不改变它**；task-3 换库时若新库改了 app_name，需同步确认数据目录/日志中的名字表现。

### 5.6 签名

- release 签名用仓库内 `skeys.jks`（PKCS12，别名 `key0`，证书 `C=cn`；`apksigner verify` 通过）。
- **安全提示（Lead 要求记录，本次不处理）**：密钥库 `skeys.jks` 及其密码已随仓库提交（`release.properties` 明文密码）。后续建议：① 换新 keystore 并轮转密码；② 从 git 历史中移除旧签名材料（`git filter-repo`/BFG），③ 把 `*.jks`/`release.properties` 放入 `.gitignore` 并改用 CI secret 注入。**当前分支尚未公开分发 APK，风险可控，但请勿用该密钥上架或对外发布正式包。**

## 6. 构建记录

### 6.1 debug（正式交付构建）

```bash
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9
export PATH=$JAVA_HOME/bin:$PATH
cd /home/hazukie/Projects/common/trime-workspace/hkrime
./gradlew :app:assembleDebug --no-daemon --stacktrace \
  -Dorg.gradle.internal.http.connectionTimeout=120000 \
  -Dorg.gradle.internal.http.socketTimeout=180000
```

结果：`BUILD SUCCESSFUL in 45s`，`34 actionable tasks: 34 executed`，EXIT=0（仅 deprecation/unchecked 警告）。
日志：`/tmp/gradle-build-3.log`
产物：`app/build/outputs/apk/debug/trime-3.2.7-debug.apk`

### 6.2 失败历史（环境问题，非改名引起）

| 尝试 | 结果 | 原因 |
|---|---|---|
| 1 | FAILED 1m9s | 首次下载 Gradle 7.5 与插件，`org.jetbrains.kotlin.android:1.7.20` 解析失败（瞬时/代理） |
| 2 | FAILED 6m22s | 依赖 aar 并发下载 TLS 握手被本地代理掐断（`SSL peer shut down incorrectly`、`Remote host terminated the handshake`），失败任务 `:app:dataBindingMergeDependencyArtifactsDebug` |
| 3 | **SUCCESS 45s** | 复用已缓存依赖 + 加长 HTTP 超时后通过 |

### 6.3 release（签名配置验证，非交付物）

```bash
./gradlew :app:assembleRelease --no-daemon --stacktrace \
  -x lintVitalAnalyzeRelease -x lintVitalRelease \
  -Dorg.gradle.internal.http.connectionTimeout=120000 \
  -Dorg.gradle.internal.http.socketTimeout=180000
```

- 首次尝试（不加 `-x`）在 `:app:lintVitalAnalyzeRelease` 崩溃：`java.lang.reflect.InvocationTargetException`（`SimplePlatformLookup.platformFromSourceProp`），属 AGP 7.4.2 lint + JDK17 环境问题，与改名无关；为不引入构建配置改动，改用命令行排除 lint 任务。
- 结果：`BUILD SUCCESSFUL in 13s`，EXIT=0；日志 `/tmp/gradle-release-2.log`
- 产物：`app/build/outputs/apk/release/trime-3.2.7-release.apk`，13,407,731 B

## 7. APK 校验

### 7.1 debug APK badging（关键行）

```
package: name='com.hazukieq.hkrime' versionCode='20220701' versionName='3.2.7'
        platformBuildVersionName='13' compileSdkVersion='33'
sdkVersion:'21'
targetSdkVersion:'33'
native-code: 'arm64-v8a'
uses-permission: name='com.hazukieq.hkrime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'Ftrime'
application-debuggable
```

### 7.2 Manifest 组件（apkanalyzer manifest print）

```
application      com.hazukieq.hkrime.TrimeApplication
activity         com.hazukieq.hkrime.setup.LauchAct
service          com.hazukieq.hkrime.TrimeImeService   (BIND_INPUT_METHOD, android.view.InputMethod, @xml/method)
activity         com.hazukieq.hkrime.settings.PrefMainActivity
activity-alias   com.hazukieq.hkrime.PrefLauncherAlias (targetActivity=…PrefMainActivity, MAIN/LAUNCHER)
activity         com.hazukieq.hkrime.settings.AboutActivity / SetupActivity / LogActivity
provider         authorities = com.hazukieq.hkrime.utilcode.fileprovider
                              com.hazukieq.hkrime.customactivityoncrashinitprovider
                              com.hazukieq.hkrime.androidx-startup
service          com.blankj.utilcode.util.MessengerUtils$ServerService (…:messenger)
activity         cat.ereza.customactivityoncrash…DefaultErrorActivity (:error_activity)
```

结论：launcher（alias）与 IME service 均指向新包，无旧包名组件。

### 7.3 dex 旧包名残留（`unzip -p <apk> <dex> | strings | grep -c 'com/osfans/trime'`）

debug（15 个 dex）：`classes.dex`、`classes2..15.dex` **全部为 0**。
release（2 个 dex）：`classes.dex:0`、`classes2.dex:0`。
另：`resources.arsc` 中旧包名 0 处。

### 7.4 native libs 与尺寸口径（重要：三种"大小"不同）——**task-2 当时口径，已被 task-3 取代**

| 文件 | 文件系统大小（`app/libs/arm64-v8a/`） | APK 内条目长度 | APK 内存储后（Deflate） |
|---|---|---|---|
| `librime_jni.so` | 85,786,600 B（未 strip 的提交物） | 13,931,184 B（AGP `stripDebugDebugSymbols` 剥离符号后） | 4,386,617 B（69% 压缩） |
| `librime_jni_1_5.so` | 13,558,064 B | 13,558,064 B（本就已 strip，大小不变） | 4,234,492 B |

> 说明：APK 内两个 `.so` 条目分别为 **13,931,184 B / 13,558,064 B**（`unzip -l` 的 Length 列，即解压后大小）；它们在 APK 中实际占用的压缩存储为 4,386,617 / 4,234,492 B。`libs/` 下 85,786,600 B 的原始文件经 strip 才进包，故与 APK 条目大小不同——不是压缩造成的差异。
> 证据：`app/build/intermediates/stripped_native_libs/debug/out/lib/arm64-v8a/librime_jni.so` = 13,931,184 B。

两个旧 `.so` 内的旧包名（`strings` 计数）：`librime_jni.so` 11 处、`librime_jni_1_5.so` 10 处 → 预期，task-3 替换。

> **task-3 后（最终状态）**：`app/libs/arm64-v8a/` 只剩一个 `librime_jni.so` = 13,587,824 B（已 stripped，sha256 `d6b295b6…ca098ec`），APK 内条目同为 13,587,824 B、Deflate 后 4,236,042 B；`librime_jni_1_5.so` 已 `git rm`；旧 85,786,600 B 废库已被覆盖。APK 内 `.so` 提取后 sha256 与 `artifacts/librime_jni.so` **完全一致**；`.so` 内旧包名 0 处。

### 7.5 release APK

```
package: name='com.hazukieq.hkrime' versionCode='20220701' versionName='3.2.7'
sdkVersion:'21'  targetSdkVersion:'33'  native-code: 'arm64-v8a'
apksigner verify → Signer #1 certificate DN: C=cn
                    SHA-256: 32208d4775841c37530d755f93eff86f79602081663f8221d3130e868817e860
```

（apksigner 仅对 META-INF/*.version 等条目给出 "not protected by signature" 提示，属 AGP 产物常态。）

### 7.6 交付物与校验和（**task-3 重建后覆盖，以下为新值**）

| 交付物 | 大小 | sha256 |
|---|---|---|
| `artifacts/hkrime-debug.apk`（**正式交付**） | 11,418,991 B | `8cc718eb0b90071e2bb514c71dc999baca4de2a48d3a54deb598865a09cce545` |
| `artifacts/hkrime-release.apk`（签名验证用） | 9,586,652 B | `9fe31e156084e275612cce1ee285900f1a5fde0033eadb9bd098cfb7353575fe` |
| 构建产物 debug | `hkrime/app/build/outputs/apk/debug/trime-3.2.7-debug.apk` | 同上（拷贝前后一致） |
| 构建产物 release | `hkrime/app/build/outputs/apk/release/trime-3.2.7-release.apk` | 同上 |

> task-2 当时的数值（已被取代）：debug 15,804,283 B / `ce7ccf1a…fe8e2f`；release 13,407,731 B / `9c7e2ae1…982982df`。

## 8. 遗留风险与 task-3 交接（**task-3 已完成，本节保留交接记录**）

1. ~~**运行时不可用（P0，预期内）**：旧 `.so` 硬编码 `com/osfans/trime`……~~ → **已在 task-3 解决**：新 `.so` 的 73 个 JNI 符号全部为 `Java_com_hazukieq_hkrime_*`，符号契约校验通过，`.so` 内旧包名 0 处。仅剩「真机未跑」这一项（用户决定是否冒烟）。
2. ~~**task-3 必做**~~ → **已完成**（详见 `artifacts/INTEGRATION-REPORT.md`）：
   - 新 `librime_jni.so`（13,587,824 B / `d6b295b6…ca098ec`）已放入 `app/libs/arm64-v8a/`，`librime_jni_1_5.so` 已 `git rm`，旧 85,786,600 B 废库已被覆盖；
   - `core/Rime.java:238` 与 `data/opencc/OpenCCDictManager.kt:15` 已统一为 `System.loadLibrary("rime_jni")`；
   - 新 `.so` 的 NEEDED 仅 `liblog/libz/libm/libdl/libc`，**不含 `libc++_shared.so`**，无需额外打包；
   - debug/release 均已重建，APK 内 `.so` 提取后 sha256 与交付物完全一致，§7.1–7.4 校验重跑通过。
3. **数据不迁移**：新包为全新应用，旧配置/方案/剪贴板历史需手动迁移（见 §5.4）。
4. **lint 崩溃**：release 构建依赖 `-x lintVital*`；若后续上 CI，建议单独修 lint 环境或升级 AGP（本次按用户要求未升级）。
5. **签名材料泄露风险**：见 §5.6，暂不处理。
6. `README.md` / `docs/**` 的改动不是本任务产物（Lead/其他 teammate 的文档工作），`git status` 中显示为未提交状态由 Lead 统一提交。

## 附录 A：改动文件清单（104 项，含 1 项非本任务 README.md）

| 分组 | 数量 |
|---|---|
| `app/src/main/java/com/hazukieq/hkrime/ime/**` | 36 |
| `.../settings/**` | 16 |
| `.../util/**` | 15 |
| `.../data/**` | 11 |
| `app/src/main/res/layout/**` | 6 |
| `.../setup/**` | 5 |
| `app/src/main/res/xml/**` | 3 |
| `.../core/**` | 2 |
| `app/src/main/assets/rime/**` | 2 |
| 根目录 `TrimeApplication.kt` / `TrimeImeService.kt` | 2 |
| `AndroidManifest.xml` / `app/build.gradle` / `build.gradle` / `gradle.properties` / `release.properties` | 5 |
| `README.md`（非本任务） | 1 |

完整列表：

```
M: README.md
M: app/build.gradle
M: app/src/main/AndroidManifest.xml
M: app/src/main/assets/rime/tongwenfeng.trime.yaml
M: app/src/main/assets/rime/trime.yaml
M: app/src/main/java/com/hazukieq/hkrime/TrimeApplication.kt
M: app/src/main/java/com/hazukieq/hkrime/TrimeImeService.kt
M: app/src/main/java/com/hazukieq/hkrime/core/Rime.java
M: app/src/main/java/com/hazukieq/hkrime/core/RimeEvent.kt
M: app/src/main/java/com/hazukieq/hkrime/data/AppPrefs.kt
M: app/src/main/java/com/hazukieq/hkrime/data/Config.java
M: app/src/main/java/com/hazukieq/hkrime/data/DataManager.kt
M: app/src/main/java/com/hazukieq/hkrime/data/db/DbBean.java
M: app/src/main/java/com/hazukieq/hkrime/data/db/DbHelper.java
M: app/src/main/java/com/hazukieq/hkrime/data/db/clipboard/ClipboardDao.java
M: app/src/main/java/com/hazukieq/hkrime/data/db/draft/DraftDao.java
M: app/src/main/java/com/hazukieq/hkrime/data/opencc/OpenCCDictManager.kt
M: app/src/main/java/com/hazukieq/hkrime/data/opencc/dict/Dictionary.kt
M: app/src/main/java/com/hazukieq/hkrime/data/opencc/dict/OpenCCDictionary.kt
M: app/src/main/java/com/hazukieq/hkrime/data/opencc/dict/TextDictionary.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/broadcast/IntentReceiver.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/core/EditorInstance.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/core/Speech.java
M: app/src/main/java/com/hazukieq/hkrime/ime/core/Trime.java
M: app/src/main/java/com/hazukieq/hkrime/ime/enums/InlineModeType.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/enums/KeyCommandType.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/enums/KeyEventType.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/enums/Keycode.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/enums/PositionType.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/enums/SymbolKeyboardType.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/keyboard/Event.java
M: app/src/main/java/com/hazukieq/hkrime/ime/keyboard/InputFeedbackManager.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/keyboard/Key.java
M: app/src/main/java/com/hazukieq/hkrime/ime/keyboard/Keyboard.java
M: app/src/main/java/com/hazukieq/hkrime/ime/keyboard/KeyboardSwitcher.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/keyboard/KeyboardView.java
M: app/src/main/java/com/hazukieq/hkrime/ime/keyboard/RimeKeycode.java
M: app/src/main/java/com/hazukieq/hkrime/ime/keyboard/Sound.java
M: app/src/main/java/com/hazukieq/hkrime/ime/landscapeinput/LandscapeInputUIMode.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/lifecycle/CoroutineScopeJava.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/lifecycle/LifecycleInputMethodService.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/CandidateAdapter.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/ClipboardAdapter.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/DraftAdapter.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/LiquidKeyboard.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/SimpleAdapter.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/SimpleKeyBean.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/SimpleKeyDao.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/TabManager.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/TabTag.java
M: app/src/main/java/com/hazukieq/hkrime/ime/symbol/TabView.java
M: app/src/main/java/com/hazukieq/hkrime/ime/text/Candidate.java
M: app/src/main/java/com/hazukieq/hkrime/ime/text/Composition.java
M: app/src/main/java/com/hazukieq/hkrime/ime/text/ComputedCandidate.kt
M: app/src/main/java/com/hazukieq/hkrime/ime/text/ScrollView.java
M: app/src/main/java/com/hazukieq/hkrime/ime/text/TextInputManager.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/AboutActivity.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/LogActivity.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/PrefMainActivity.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/components/ColorPickerDialog.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/components/DialogSeekBarPreference.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/components/LogView.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/components/ResetAssetsDialog.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/components/SchemaPickerDialog.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/components/SoundPickerDialog.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/components/ThemePickerDialog.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/fragments/ConfFragment.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/fragments/KeyboardFragment.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/fragments/LooksFragment.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/fragments/OtherFragment.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/fragments/PrefFragment.kt
M: app/src/main/java/com/hazukieq/hkrime/settings/fragments/ToolkitFragment.kt
M: app/src/main/java/com/hazukieq/hkrime/setup/LauchAct.kt
M: app/src/main/java/com/hazukieq/hkrime/setup/SetupActivity.kt
M: app/src/main/java/com/hazukieq/hkrime/setup/SetupFragment.kt
M: app/src/main/java/com/hazukieq/hkrime/setup/SetupPage.kt
M: app/src/main/java/com/hazukieq/hkrime/setup/SetupViewModel.kt
M: app/src/main/java/com/hazukieq/hkrime/util/AppVersionUtils.kt
M: app/src/main/java/com/hazukieq/hkrime/util/ConfigGetter.kt
M: app/src/main/java/com/hazukieq/hkrime/util/DeviceInfo.kt
M: app/src/main/java/com/hazukieq/hkrime/util/DialogUtils.kt
M: app/src/main/java/com/hazukieq/hkrime/util/Exception.kt
M: app/src/main/java/com/hazukieq/hkrime/util/GraphicUtils.kt
M: app/src/main/java/com/hazukieq/hkrime/util/InputMethodUtils.kt
M: app/src/main/java/com/hazukieq/hkrime/util/LeakGuardHandlerWrapper.kt
M: app/src/main/java/com/hazukieq/hkrime/util/Logcat.kt
M: app/src/main/java/com/hazukieq/hkrime/util/RimeUtils.kt
M: app/src/main/java/com/hazukieq/hkrime/util/ShortcutUtils.kt
M: app/src/main/java/com/hazukieq/hkrime/util/SpvalueStorage.kt
M: app/src/main/java/com/hazukieq/hkrime/util/StringUtils.kt
M: app/src/main/java/com/hazukieq/hkrime/util/Utils.kt
M: app/src/main/java/com/hazukieq/hkrime/util/ViewUtils.kt
M: app/src/main/res/layout/activity_log.xml
M: app/src/main/res/layout/candidate_bar.xml
M: app/src/main/res/layout/composition_root.xml
M: app/src/main/res/layout/keyboard_popup_keyboard.xml
M: app/src/main/res/layout/main_input_layout.xml
M: app/src/main/res/layout/tab_bar.xml
M: app/src/main/res/xml/keyboard_preference.xml
M: app/src/main/res/xml/method.xml
M: app/src/main/res/xml/prefs.xml
M: build.gradle
M: gradle.properties
M: release.properties
```

> 注：`git diff --name-status` 中对改名文件显示为 `R100: 旧路径 -> 新路径`，其余为普通修改。

## 附录 B：复现校验命令

```bash
SDK=/home/hazukie/Android/Sdk
APK=/home/hazukie/Projects/common/trime-workspace/artifacts/hkrime-debug.apk

$SDK/build-tools/33.0.1/aapt2 dump badging "$APK" | head -5
$SDK/cmdline-tools/latest/bin/apkanalyzer manifest print "$APK" | grep -E 'service|activity|provider|authorities'
for d in $(unzip -Z1 "$APK" | grep -oE 'classes[0-9]*\.dex'); do
  echo -n "$d: "; unzip -p "$APK" "$d" | strings | grep -c 'com/osfans/trime'
done
unzip -l "$APK" | grep -E '\.so$'
sha256sum "$APK"
```
