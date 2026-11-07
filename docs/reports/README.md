# 构建 / 改名 / 集成 证据存档

三份原始报告，**原样保留**（未做任何编辑），作为 `v2` 文档结论的现场证据：

| 文件 | 覆盖阶段 | 一句话 |
|---|---|---|
| [BUILD-REPORT-jni.md](BUILD-REPORT-jni.md) | task-1 · 重建 `.so` | 从上游 v3.2.7 构建 73 符号的 `librime_jni.so`：pin 校验、boost/capnproto 偏离、`-UBOOST_EXCLUDE_LIBRARIES` 坑、18–21 s 冷构建、三次 sha256 一致 |
| [RENAME-REPORT.md](RENAME-REPORT.md) | task-2 · 整包改名 | `com.osfans.trime` → `com.hazukieq.hkrime`，103 文件 / 391 行替换、dex 与 resources.arsc 零残留、旧 `.so` 残留已由 task-3 取代 |
| [INTEGRATION-REPORT.md](INTEGRATION-REPORT.md) | task-3 · 集成 | 装入新 `.so`、删 85 MB 废库、统一 `loadLibrary("rime_jni")`、符号契约 73/73、APK 静态校验（未做真机冒烟） |

> 说明：报告中出现的 `/home/hazukie/...`、`/tmp/...` 等为**构建机绝对路径**，换机器不可直接复现；
> 可复现的部分已固化为 `script/build-jni-so.sh` 与 `script/verify-jni-symbols.sh`（用法见 [../02-jni-so.md](../02-jni-so.md) §5-6）。
>
> 时间线：task-1（05:26–05:36 构建）→ task-2（05:29–05:40 改名）→ task-3（05:34–05:41 集成）三者有交叉，
> 以 `INTEGRATION-REPORT.md` 为**最终状态**的权威来源；`RENAME-REPORT.md` 中旧 `.so`/旧 APK 数值已就地标注「已被 task-3 取代」。
