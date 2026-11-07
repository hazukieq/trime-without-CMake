# 构建 / 改名 / 集成 / 真机 证据存档

原始报告，**原样保留**（未做任何编辑），作为文档结论的现场证据：

| 文件 | 覆盖阶段 | 一句话 | 备注 |
|---|---|---|---|
| [BUILD-REPORT-jni.md](BUILD-REPORT-jni.md) | 第一轮 · 重建 `.so` | 从上游 v3.2.7 构建 73 符号的 `librime_jni.so`：pin 校验、boost/capnproto 偏离、`-UBOOST_EXCLUDE_LIBRARIES` 坑、18–21 s 冷构建、三次 sha256 一致 | 当时的 `.so` 为 13,587,824 B；**16 KB 对齐为后续 `9dc8c32` 追加** |
| [RENAME-REPORT.md](RENAME-REPORT.md) | 第一轮 · 整包改名 | `com.osfans.trime` → `com.hazukieq.hkrime`，103 文件 / 391 行替换、dex 与 resources.arsc 零残留 | 文中旧 `.so`/旧 APK 数值已被后续提交取代 |
| [INTEGRATION-REPORT.md](INTEGRATION-REPORT.md) | 第一轮 · 集成 | 装入新 `.so`、删 85 MB 废库、统一 `loadLibrary("rime_jni")`、符号契约 73/73、APK 静态校验（当时未做真机冒烟） | 当时结论「未做真机测试」已被 `DEVICE-TEST-REPORT.md` 更新 |
| [DEVICE-TEST-REPORT.md](DEVICE-TEST-REPORT.md) | 第二轮 + 第三轮 · 真机验证 | Xiaomi M2012K11AC（Android 16 / 16 KB page）上 `OK (1 test)`。**第二轮**（2026-11-08 第二轮改造后）：assets 部署、ocd2 15、hakka 词典编译、四组 OpenCC 转换；**第三轮**（`726a209` 缺口修复后，见文末追加小节）：fresh 部署、**ocd2 18 / bin 10 / 方案 3** | 该文件按轮次**追加**（未改写旧内容）；最新数值以 [../05-branches-release.md](../05-branches-release.md) §4 为准 |

> ⚠️ **历史快照声明**：以上报告记录的是各自执行时刻的状态。凡与本目录 `../02`~`../07` 冲突的数字
> （`.so` 大小与 sha、APK 大小、证书指纹、`libs_backup/` 是否存在、客家话数据是否内置等），
> **一律以 `docs/` 正文为准**；正文对应的最新汇总见 [../05-branches-release.md](../05-branches-release.md) §4/§6。
>
> 说明：报告中出现的 `/home/hazukie/...`、`/tmp/...` 等为**构建机绝对路径**，换机器不可直接复现；
> 可复现的部分已固化为 `script/build-jni-so.sh`、`script/verify-jni-symbols.sh`、`script/gen-keystore.sh`
> （用法见 [../02-jni-so.md](../02-jni-so.md) §5-6、[../03-build.md](../03-build.md) §2）。
>
> 时间线：第一轮 task-1（05:26–05:36 构建）→ task-2（05:29–05:40 改名）→ task-3（05:34–05:41 集成）三者有交叉；
> 第二轮 `2b50c1f`（OpenCC + 客家话数据）→ `9dc8c32`（16 KB）→ `bebad9f`（密钥外置 + 仪器测试）；
> 第三轮 `63fd09e`（文档）→ `726a209`（缺口修复：方案/词典/滤镜）→ `ebb18c1`（构建健壮性）。
> 仓库外的完整证据（APK、`.so`、`SHA256SUMS`、构建日志）在
> `/home/hazukie/Projects/common/trime-workspace/artifacts/`。
