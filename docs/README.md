# hkrime 开发文档

本文档只描述当前源码、资源和构建工具。工程硬规则见工作区 `../../AGENTS.md`。
运行状态以目标设备日志为准；发布产物的大小、签名和校验值须针对实际文件验证。

| 文档 | 内容 |
|---|---|
| [00 项目概览](00-overview.md) | 工程边界、技术栈、目录、当前能力 |
| [01 架构](01-architecture.md) | 入口、数据流、线程、维护与生命周期 |
| [02 JNI 契约](02-jni-so.md) | 当前库、符号、ELF 对齐、打包边界 |
| [03 构建](03-build.md) | 环境、APK、签名、安装、设备测试 |
| [04 客家话方案](04-hakka.md) | 方案、12 键编码、数据扩展 |
| [05 分支与发布](05-branches-release.md) | 分支使用、提交规范、发布检查 |
| [06 排障](06-troubleshooting.md) | 定位顺序、日志、当前限制 |
| [07 Rime 数据与 OpenCC](07-opencc-and-rime-data.md) | 目录、复制、部署、同步、转换 |
| [08 主题](08-theme-trime-yaml.md) | 配置结构、尺寸、按键、布局、预览 |
| [09 候选界面](09-candidate-ui.md) | 候选条、面板、推荐、点击语义 |
| [10 十二键输入](10-twelve-key-input-design.md) | 当前输入状态机与不变量 |
| [11 Native 构建与性能](11-native-build-performance.md) | 独立构建、依赖、本地补丁、性能定位 |
| [12 候选排序](12-candidate-ranking.md) | 引擎顺序、学习、面板组内与组间排序 |
| [13 性能门禁](13-perf-gate.md) | 指标、阈值、命令与结果解释 |
| [14 preedit](14-preedit.md) | 解码、读音对齐、剩余码、部分选词 |
| [15 librime 本地补丁](15-librime-local-mods.md) | 生效补丁、诊断补丁、排查入口 |
| [16 真实数据测试](16-librime-coverage-test.md) | PC 覆盖、用户词库、语料和 JVM 回归 |
| [17 候选面板](17-candidate-panel.md) | 五源、合并、分档、配额、日志和验收 |
| [18 音节集合](18-syllables.md) | 运行时集合、标准集、校验与生成 |
| [19 面板实现约定](19-panel-maintenance.md) | 函数边界、规则同步、golden 和调参 |

常见任务：App 改动读 01/03；面板或音节改动读 12/14/17/18/19；Native 改动读 02/11/15/16；
主题读 08；卡顿先跑 13，候选缺失按 06/15 的顺序定位。
