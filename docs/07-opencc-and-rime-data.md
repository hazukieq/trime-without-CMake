# Rime 数据、部署与 OpenCC

## 目录与资源

`AppPrefs.conf.sharedDataDir` 与 `userDataDir` 默认都是 `/sdcard/hkrime`，可以分别设置。
以运行时偏好和日志为准，不假定设备使用 APK 的 assets 或某个私有目录。

| 内容 | 位置与用途 |
|---|---|
| 随包资源 | `app/src/main/assets/rime/`：方案、字典、主题、Lua、辅助字表及 OpenCC |
| 源数据 | 实际 shared/user 目录里的 yaml、txt 与 opencc 配置 |
| 部署结果 | `build/` 下的方案配置与 table/prism/reverse 等二进制 |
| 学习 | librime userdb 与 App 的 SelectionMemory/SegmentMemory/PhraseMemory |
| 同步导出 | `sync/<installation_id>/*.userdb.txt`，由 librime sync 生成 |
| 日志 | `<userDataDir>/logs/` |

`RimeDictSource` 的字典、辅助文本读取优先查询 userDataDir，缺失时回退 assets。
它递归展开主词典 import_tables，去重且限制深度；只把 YAML 头结束后的本体交给解析器。
`DataManager.getDataDir` 用于其它资源定位，按 shared 目录优先再回退 user 目录。

## 复制与部署

`Config.prepareRime`：版本变化覆盖复制 assets；目录不存在时完整复制；同版本已有目录时只补默认主题。
默认主题为 `hakka_theme.trime.yaml`。同版本安装不能当作资源同步命令。

`RimeUtils.deploy(context, full=false)` 默认增量，只重建变更；
`com.hazukieq.hkrime.deploy` 广播强制全量。部署异步，不能只凭广播返回判断完成。
大方案首次部署可能耗时较长，以维护完成与日志为准。
词表在 App 进程启动时建索引，部署后还需要重启 IME 才会被 App 重新读取。

同步广播为 `com.hazukieq.hkrime.sync`。`RimeUtils.sync` 初始化引擎、排 sync 任务、
后台 join 等待结束，再销毁并重新初始化；sync 是本地 librime 数据导出，不表示网络备份。
目录迁移逻辑会从 `/sdcard/rime` 补充用户学习数据，不删除来源目录。

## OpenCC

`opencc/` 随包提供 18 份 txt 和 16 份 json。txt 是字典源数据，json 描述转换链，
`OpenCCDictManager` 在部署时将 txt 编译成 ocd2，转换时加载 json 与其依赖。
不要只复制 json 而漏掉所需字典；以真实部署结果判断可用性。

`ywz`、`emoji` 的字典一行可包含空格分隔的多个目标值，librime simplifier 负责候选扩展（客语方案已移除 `lenth` 联想过滤）。
JNI `openccConvert` 的文本转换结果不能替代面板多候选行为测试。

```bash
python3 script/verify-rime-assets.py
```

静态检查验证方案/字典/OpenCC 引用及扩展语义，不替代设备部署或输入验收。
不要把个人 userdb、sync 导出或诊断语料打包进 assets。
