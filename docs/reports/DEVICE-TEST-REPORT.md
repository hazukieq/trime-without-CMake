# 真机验证报告（hkrime fork）

日期：2026-11-08
设备：Xiaomi M2012K11AC（Android 16，16KB page 设备），adb serial `5f08bc20`

## 结论

**PASS**：`OK (1 test)`。部署、OpenCC 词典生成与简繁/台湾转换、客家话词典编译在真机上全部通过。

## 测试方式

App 无法用 UI 触发部署（LauchAct 权限向导 + 无输入焦点时 IME 不绑定），改用仪器测试直接调用应用代码：

`app/src/androidTest/java/com/hazukieq/hkrime/RimeDataTest.kt`
→ `Config.get(ctx)` + `Rime.destroy()/Rime.get(ctx,true)` → 断言部署与转换。

执行步骤：

```bash
JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9 ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow   # 重装后必须重设
timeout 300 adb shell am instrument -w -r -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
# 输出: OK (1 test)
```

（必须套 `timeout`：权限被拒时 `Config.setTheme`↔`init` 会无限递归。）

## 实测数据

| 项目 | 结果 |
| --- | --- |
| sharedDataDir | `/storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime` |
| userDataDir | `/storage/emulated/0/rime`（librime `build/`、userdb 落这里） |
| assets 部署 | trime.yaml、default.custom.yaml、hakka_jiugong.schema.yaml、hakka.dict.yaml、hakka_oftenwords.dict.yaml 均存在 |
| opencc 条目 / 生成 ocd2 | 44 / 15（含 STCharacters.ocd2） |
| OpenCC 版本 | `ver.1.1.4` |
| s2t | `汉字` → `漢字` |
| t2s | `漢字` → `汉字` |
| s2tw | `软件` → `軟件`（仅字形） |
| s2twp | `软件` → `軟體`（含词汇替换） |
| librime build 产物 | `hakka.table.bin`、`hakka.reverse.bin`、`hakka.prism.bin` |
| schema 列表 | 非空 |

## 16 KB 对齐问题（已修复）

修复前设备弹系统对话框：

> 此应用不符合 16 KB 对齐要求。ELF 文件对齐检查失败。以下库未进行 16 KB 对齐：lib/arm64-v8a/librime_jni.so: LOAD 区段未对齐

原因：`.so` 按 4KB 对齐（`LOAD Align=0x1000`），而该机是 16KB page。
修复：`script/build-jni-so.sh` 增加 `-Wl,-z,max-page-size=16384` 重建，`llvm-readelf -l` 四个 LOAD 全为 `Align=0x4000`；重新安装后弹窗消失，测试通过。

## 用户数据安全

- 测试前发现 `/sdcard/rime` 是用户真实 rime 数据（含 `hakka_qwerty.schema.yaml`、`hakka.userdb`、`backup/` 等，`rime.zip` 只是其子集）。
- 已 `mv /sdcard/rime /sdcard/rime.hkrime-backup-1794089473` 备份；测试后把测试产生的新目录挪到 `/sdcard/rime.hkrime-test-1794090265`，并恢复原 `/sdcard/rime`。
- 默认输入法已恢复为 Gboard；测试 APK（`com.hazukieq.hkrime.test`）已卸载。
- `com.hazukieq.hkrime` 仍安装在设备上（未设为默认输入法），供用户自行试用/卸载。
