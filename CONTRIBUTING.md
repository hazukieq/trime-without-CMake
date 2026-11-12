# 贡献指南

先读工作区 `../AGENTS.md` 和 [文档索引](docs/README.md)。开发线为 dev；
App、JNI 与 librime 是独立仓库。修改前查看各自工作树，保留不属于本任务的改动。

## 实现约定

- 保持 Kotlin/Java 现有风格，不运行 spotlessApply，不提交无关格式重排。
- 提交信息用中文和范围前缀；行为与协议改动同步文档。
- 面板标题、合并或选词语义改动先写文档并确认，不能自行改变产品规则。
- 引擎 session 共用 ENGINE_LOCK，全量枚举和分组不放主线程；维护不能在主线程 join。
- 只改 App/资源不重编 so；Native 只走 rime-jni 的 Release 构建，补丁和产物一致。
- 删除文件用 trash；不修改只读参考/备份，不提交签名秘密、真实语料或个人词库。

## 验证

```bash
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"
./gradlew :app:testDebugUnitTest :app:assembleDebug
python3 script/verify-rime-assets.py
python3 tools/panel_probe.py selftest
./script/verify-jni-symbols.sh
```

preedit/分组变更须走 [真实语料](docs/16-librime-coverage-test.md)；面板规则同步脚本与 golden；
排序/分组/快照后运行设备性能门禁；Native 补丁后跑 PC 覆盖并安装验收。
设备操作明确序列号，重装后恢复 appops 与 ime，截图前核对 IME 包名。

## 提交说明

描述具体触发条件、最终行为、修改范围、已执行检查及未验证项。
注明 App 或 Native 的责任边界，不把所有问题泛称为引擎问题。日志和截图去除个人输入内容。
仓库没有自动构建 workflow，不以不存在的 CI 作为验收证据。
社区协作遵循 [行为准则](CODE_OF_CONDUCT.md)。
