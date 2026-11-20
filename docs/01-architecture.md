# 架构与线程

## 入口与职责

源码包根为 `app/src/main/java/com/hazukieq/hkrime/`。

| 入口 | 职责 |
|---|---|
| `TrimeApplication` | 偏好、日志、崩溃处理和进程初始化 |
| `TrimeImeService` | 系统绑定的 IME 壳类，实际行为在 `ime/core/Trime.java` |
| `setup/`、`settings/` | 首次权限引导、输入法启用、配置、方案与关于页 |
| `ime/keyboard/` | 布局、绘制、触摸、按键事件 |
| `ime/text/TextInputManager.kt` | 输入状态、候选点击、部分选词、提交、学习 |
| `core/Rime.java` | JNI 门面、session、上下文、候选枚举、维护 |
| `core/twelvekey/` | 快照及原码跨度、选择日志、preedit 显示、左栏导航、排序、学习记忆和性能微基准 |
| `core/TwelveKeyPreedit.kt` | 编码解码、preedit、字表、词表与联想索引 |
| `data/`、`util/` | 配置、目录、OpenCC、部署、同步、日志及方案适配 |

## 按键与候选流

```text
KeyboardView / Event → Trime / TextInputManager → Rime.onKey → librime
→ commit buffer / composition / context
→ TextInputManager.onCandidatesRefreshed
→ TwelveKeyController.refresh
→ TwelveKeySnapshotBuilder.build
→ TwelveKeySnapshot
→ CandidateBarBinder / CandidatePanelController / 输入法 preedit
```

`CandidateItem.index` 保留引擎全局下标；排序后的界面位置不能当作引擎下标。
面板只消费快照并回调点击，提交与学习统一在 TextInputManager 处理。
非 12 键方案复用面板与快照类型，不生成依赖 12 键码表的本地段组。

## 线程与缓存

- `process_key` 与后台候选枚举共用 `Rime.ENGINE_LOCK`；不得并发访问同一 session。
- `refresh` 在 `Dispatchers.Default` 构建，Main 发布；generation 检查丢弃过期结果。
- 普通刷新去抖 120ms，主动操作可 immediate。LRU 容量 8；缓存键包含输入、分段、
  面板状态、学习版本、选项版本、引擎版本和联想索引版本。
- 只缓存有候选的快照。无候选最多延迟重试 3 次，每次 90ms。
- 全量分组与枚举应留在后台，UI 线程不得承担重构建。

开面板只请求 full 快照并等发布后展示，不再在主线程同步构建；
首次开面板与连续选字的面板延迟仍需性能门禁验证。

## 初始化、维护与部署

`Config.prepareRime` 按目录是否存在及版本变化复制资源，确保 `hakka_theme.trime.yaml`
存在，然后初始化 Rime。shared/user 默认同为 `/sdcard/hkrime`，可分别配置。
App 侧字表与词表从 `RimeDictSource` 读用户目录，缺失时回退 assets。

主线程调用 `Rime.check` 不得 join 维护线程；维护待完成期间引擎不就绪，按键保护直接返回。
后台完成维护后建立 session 并更新界面。部署默认增量；deploy 广播触发全量。
同步必须等用户词库维护任务完成再销毁 session。

`TrimeImeService` 的组件名是系统 IME 标识，不随意改动。退出与重建必须使缓存失效，
提交缓冲先落地，preedit 刷新异常只记日志，避免丢字或中断后续刷新。
