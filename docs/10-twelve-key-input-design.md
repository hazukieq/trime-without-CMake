# 十二键输入流程

## 处理阶段

1. 主题发送 12 键码，Rime 在 ENGINE_LOCK 内处理并更新上下文。
2. TextInputManager 先处理 commit buffer，再更新 composition，通知统一候选刷新入口。
3. TwelveKeyController 在后台读取 local 状态、命中缓存或构建快照，Main 线程发布。
4. 候选条、面板、preedit 与嵌入式预览消费同一份快照；旧 generation 结果被丢弃。
5. 点击按候选的 index/segment 分发，保留全局引擎下标并更新本地学习。

## 状态与上屏

`CompositionSession` 保存剩余码、选择日志（文字/读音/选择前原码/实际消费码/来源）
与一次性提交许可；引擎自身仍可能保存已选未提交的前缀（`getCompositionPrefix`）。
两者与引擎输入共同构成当前组合。

候选条、展开面板、空格与回车都走同一个 `chooseTwelveKey`：先用快照 contextKey 排除过期点击，
再由 `CandidateSelection.consumedEnd` 证实在当前码串上的消费边界，然后记入选择日志。
未选完时清组合并重喂剩余码，已选文字只留在 preedit；剩余码由 `selectedInputEnd`
按已选读音约束的实际输入跨度扣除，不能按候选读音重新编码后做字符串前缀匹配。
全部码位消耗后状态机才发放一次提交许可，`commitThrough` 写入编辑器成功后才清空日志。
`express_editor` 可能在候选 end 等于输入长度时自动提交，因此部分选择不能直接调 select_candidate；
引擎意外 commit 由 `consumeTwelveKeyEngineCommit` 拦截并恢复待选码。
DEL 在任意界面撤回最后一次选择并恢复该次选择前的完整原码，可逐次回退重选。
切换输入框、回车与退出不清空未完成组合以外的选择记录。

## 候选与学习

候选条按引擎顺序加少量 SelectionMemory 调整，混入 PhraseMemory 合成词并按文字去重。
词与单字同池竞争。SegmentMemory 为面板字频和邻字偏好提供评分。
PhraseMemory 的读音索引也并入面板，运行时不解析 sync userdb 快照。

固定条只从前 64 条窗口产生 12 项；面板最多枚举 600 项，五源和引擎组统一合并。
面板关闭时 groups 为空，判断可选项必须用 hasCandidates。引擎空时先用字典段组兜底，
字典也空才显示原字母。fallback/空快照不写入缓存。

## 必须维持的不变量

- 快照不可变，候选条/预览/面板同源；打开面板不改变候选条顺序。
- 部分选择不上屏、退格整段撤回，最终提交不得重复或遗漏前缀。
- 面板显示具体音节，联想标题为两个完整音节；同读音只保留一行。
- 全量枚举和分组不加入主线程热路径，session 访问共用 ENGINE_LOCK。
- 改规则同步脚本、golden、真实语料测试和文档，再做真机与性能验证。

代码定位见 [01](01-architecture.md)，preedit 细节见 [14](14-preedit.md)，
面板规则见 [17](17-candidate-panel.md)，验证见 [16](16-librime-coverage-test.md)。
