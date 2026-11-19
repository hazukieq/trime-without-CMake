# 排障与当前限制

## 定位顺序

候选缺失、多给或缓慢时严格按以下顺序：

1. App：实际方案、快照、preedit、候选排序、面板行、点击下标和渲染。
2. librime 本地补丁：先查 0001 的预算与 0003 的完整路径覆盖，不能跳过。
3. 词库与部署：实际数据目录、import_tables、源文件时间、build 产物和部署日志。
4. 表与路径都正确时再定位上游引擎。

结论必须写明是 App、librime 本地修改、数据部署还是上游逻辑，不笼统归因于“引擎问题”。
候选没出现不代表词条没进表。探针与 PC 测试见 [15](15-librime-local-mods.md)、
[16](16-librime-coverage-test.md)。

## 日志与症状

| 症状 | 检查 |
|---|---|
| 按键或开面板慢 | 先跑性能门禁；核对缓存 miss、枚举耗时、emit/UI 耗时及引擎锁等待 |
| 长词/同码路径缺失 | 比较 PC 查表与真实候选；核对 0001/0003，最后才怀疑建表 |
| 字典有词但 App 面板没有 | 核对运行时音节过滤、import 表、PhraseMemory、App 索引加载与行配额 |
| 面板开关改变候选条 | 比较同输入 bar/full 前 12 项与 `bar drift`；候选条必须固定使用 64 条窗口 |
| 没候选却仍有输入 | 用 `hasCandidates` 判断；引擎空先字典兜底，再原字母兜底 |
| 选词丢后半码或退格后重复上屏 | 检查 `selectedInputEnd`、`CompositionSession` 选择日志与 commit buffer |
| 改 yaml 不生效 | 核对实际目录文件、部署完成、进程重启与 App 侧索引重新加载 |
| 看到错误输入法界面 | 重新 ime set，并用 dumpsys window 核对 IME 包名 |

日志默认在 `/sdcard/hkrime/logs/`，以实际 userDataDir 为准：

- `diag.log`：快照/面板/点击摘要；每次进程启动清空。
- `hkrime-YYYYMMDD.log`：App 日志，缓冲写入与轮转；含 `twelvekey:build`、`twelvekey:latency`。
- `hkrime-deploy.log`：每次部署覆盖，包含源文件、构建前后状态及本次完整 glog 增量。
- glog INFO/WARNING/ERROR 文件：librime 部署和候选诊断。

读取个人日志前注意其中可能含输入文本；共享报告应去掉个人内容。

## 当前限制

- APK 只配置 arm64-v8a；x86_64 模拟器不能直接验收该 Native 库。
- 当前 ELF LOAD 是 4 KB 对齐，尚不能声明支持 16 KB 页设备。
- 完整音节可能遮蔽相同码的简拼解释；简拼路径还受搜索预算限制，覆盖率须按类别解释。
- 开面板等待完整快照，不在主线程同步枚举；重点验证首次开面板的 UI 延迟。
- 主题预览器默认按钮仍请求未随包提供的 `trime.yaml`，应打开现有客家主题；预览不能替代真机。
- 普通单测可因缺少真实语料而跳过 RealCorpusTest；发版和相关行为改动必须确认实际执行。

- panel_probe selftest 默认读取已有本地快照，运行固定断言；多个断言依赖 Linux 词条。
  数据集不含该词时会失败。0 失败仅表示这些断言通过，不能证明全词库、App UI 或当前真机行为正确。
