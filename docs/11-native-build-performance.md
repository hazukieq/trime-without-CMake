# Native 构建与性能

## 构建边界

`rime-jni` 独立构建 librime 1.17.0、合并插件和 JNI 胶水，App Gradle 只负责打包。
唯一当前构建入口是 `../rime-jni/script/build.sh`，必须 Release。
`hkrime/script/build-jni-so.sh` 面向只读参考树，不用于当前库。

| 项 | 配置 |
|---|---|
| C++ | C++17 |
| NDK | `24.0.8215888` |
| CMake | SDK `3.22.1`，Ninja |
| Android | `android-21`、arm64-v8a |
| 引擎 | librime `1.17.0` + 当前 0001/0002/0003 补丁 |
| 插件 | librime-lua（含 thirdparty Lua）、librime-charcode、lotem/librime-octagram |
| 依赖 | boost、capnproto、libiconv、snappy；librime deps 中的 leveldb/yaml-cpp/marisa/glog/opencc |
| 日志 | `ENABLE_LOGGING=ON`，写 userDataDir/logs |

## 准备和重建

`script/setup-deps.sh` 准备依赖和复制插件，但不自动应用补丁，也不锁定所有插件提交。
已有依赖目录会跳过 clone。新环境必须记录实际依赖 revision，再按补丁顺序应用；
不能把“运行 setup 成功”当作构建源码已满足约束。

在 `rime-jni/` 执行，以下 git am 仅用于未应用补丁的新 librime 树：

```bash
./script/setup-deps.sh
git -C librime am ../patches/0001-12key-abbrev-perf.patch
git -C librime am ../patches/0002-switcher-empty-dir-guard.patch
git -C librime am ../patches/0003-table-query-full-path-fallback.patch
./script/build.sh --build-type Release
```

build.sh 使用 `build-arm64-v8a`，strip 后默认复制到 `../hkrime/app/libs/arm64-v8a/`
并生成 sha256 文件。`--out-dir <dir>` 指定额外输出，`--no-install` 禁止默认安装。
不要在日常 App 改动中重建库。当前库指纹、实际对齐与接口见 [02](02-jni-so.md)。

## 完整验证链

改 Native 后：更新 patches → PC 真实覆盖 → Release 重建 → JNI 符号校验 → App 构建 →
安装并恢复 appops/IME → 12 键及全字母方案验收 → 性能门禁。
改 preedit/分组还须重新生成真实语料并跑 RealCorpusTest。

## 性能定位

先跑 [性能门禁](13-perf-gate.md)，区分解码、段组、联想、合并、枚举和 UI 发布成本。
Native 耗时通过 `onkey() keycode=…` 到 `process_key result=` 的日志差定位；
单键超过 150ms 作为异常线索。必要时在目标设备记录 simpleperf：

```bash
adb -s <serial> shell simpleperf record --app com.hazukieq.hkrime -f 400 -g --duration 90
```

重点区分 Table::Query 搜索预算、user_dict DFS、候选枚举、JNI 分配与 UI 等锁。
packed 枚举减少逐候选 JNI 分配，但不取消引擎搜索成本；限流 64 也不等于整条按键链已异步。
补丁覆盖与预算见 [15](15-librime-local-mods.md)。
