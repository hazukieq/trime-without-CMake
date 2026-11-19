# PC 覆盖与真实语料测试

`rime-jni/host-test` 使用当前 librime 源码构建 PC Release 测试，依赖系统 boost、glog、
yaml-cpp、leveldb、marisa、OpenCC 等。Android 库不参与 PC 链接。
数据优先使用 `HKRIME_REAL_DATA`（默认 `/tmp/hkrime-real-data`），不存在时脚本回退 assets；
要求真实数据的验收必须明确检查目录存在，不能接受静默回退。

## 拉数据与执行

在 `rime-jni/host-test/`：

```bash
ANDROID_SERIAL=<serial> ./pull-real-data.sh
./run-host-test.sh
./run-host-test.sh --bench
./run-host-test.sh --corpus /tmp/hkrime-corpus.jsonl
./run-host-test.sh --userdb /tmp/hkrime-real-data/hakka.userdb.txt \
  --corpus-userdb /tmp/hkrime-corpus-userdb.jsonl
```

pull 脚本使用 adb，多个设备时通过 ANDROID_SERIAL 选目标。它拉取实际方案、词典、
import 表、短语、OpenCC txt 和 sync 的 userdb 导出，不提交这些个人文件。
脚本在 `/tmp/hkrime-host-run` 建可写副本并部署；再次运行会重置该临时目录，不能并行运行。
需要 sync 导出时先在设备完成本地同步，再拉取，不能把不存在的导出当空词库。

## 结果口径

| 类别 | 含义与验收 |
|---|---|
| single | 主词典每字每音节；要求 100% |
| word2 / word3 / word4+ | 主词典及 import 表完整编码；要求 100% |
| ascii | 含非汉字的词条；要求 100% |
| abbrev | 多节只打各节首键；单独报告覆盖及预算耗时，不与全拼混算 |
| abbrev-shadowed | 同码完整音节解释遮蔽简拼；按引擎拼写设计单列 |
| custom | 自定义短语，按实际输入表统计 |
| userdb | sync 导出的真实音节组合；不直接作为 12 键引擎覆盖率门槛 |

userdb key 是音节拼写，不能假定数码输入会直接命中；12 键组词学习还依赖 App PhraseMemory。
它的重要用途是生成 preedit 和面板真实语料。每次报告必须写实际分母、通过率、
数据源、源码版本、是否启用兜底与 bench 指标，不能沿用固定条数/百分比。

当前 coverage_test_nofallback 只对测试主程序定义宏，仍链接同一 rime-static，
不能用作已关闭 table.cc 兜底的有效对照；run-host-test.sh 未实现 --patch-check 分支。
需要对照时应分别编译实际启用/禁用兜底的引擎库，并隔离数据目录。

## App 回归

在 `hkrime/`：

```bash
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"
./gradlew :app:testDebugUnitTest --tests '*RealCorpusTest*'
./gradlew :app:testDebugUnitTest
python3 tools/panel_probe.py selftest
```

RealCorpusTest 读 `HKRIME_REAL_DATA`、`HKRIME_CORPUS` 和 `HKRIME_CORPUS_USERDB`，
默认路径为上述 `/tmp` 目录/文件。它们缺失时测试会跳过；验收必须核对 XML 中 skipped 为 0。
语料覆盖真实词条全码、简拼与逐键前缀，preedit 和候选 comment 来自实际 librime，
不能用手写样例代替。PanelGoldenTest 另用随包 assets 检验 Python/Kotlin 规则一致，
两种数据口径都需要。单测通过后仍需真机确认 UI、输入框提交与性能。

## 面板脚本自检的范围

`panel_probe.py selftest` 默认使用 `/tmp/hkrime-real-data` 的已有快照，不自动 adb 拉取。
它执行 69 个固定断言，并非使用该目录全部词条做穷举覆盖；多个断言依赖 Linux 固定词条。
随包词典已包含 Linux/linux，使用 `--data app/src/main/assets/rime selftest` 可验证随包数据。
报告必须注明数据目录、是否重新拉取、断言数量和失败项，不能仅写“真实数据自检通过”
并将其等同于完整语料或真机验收。0 失败只覆盖本脚本已写出的断言。

## 逐步流程工具

`tools/preedit_flow.py` 使用 host-test 的 `--script -` 交互模式复现选择日志、提交许可、
退格还原与剩余码处理，可输入 type、bar、pick、left、right、back、commit、clear、state 命令。
它按引擎顺序选候选，未覆盖 App 学习重排、64/600 窗口差异及全部面板排序，不能替代 JVM 和真机验收。
这部分工具正在工作树中开发，使用前确认源码和 PC 构建状态。
