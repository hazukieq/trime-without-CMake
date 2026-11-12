# 十二键输入流程

## 处理阶段

1. 主题发送 12 键码，Rime 在 ENGINE_LOCK 内处理并更新上下文。
2. TextInputManager 先处理 commit buffer，再更新 composition，通知统一候选刷新入口。
3. TwelveKeyController 在后台读取 local 状态、命中缓存或构建快照，Main 线程发布。
4. 候选条、面板、preedit 与嵌入式预览消费同一份快照；旧 generation 结果被丢弃。
5. 点击按候选的 index/segment 分发，保留全局引擎下标并更新本地学习。

## 状态与上屏

`pendingPick` 是 App 暂存的已选文字，`heldPicks` 保存每次部分选择的文字和选择前完整码；
`uncommittedPrefix` 保存引擎中已选但未提交的前缀。它们与引擎输入共同构成当前组合。

选择只覆盖前几节的候选时，`holdPartialPick` 把词留在 preedit，清组合并重喂剩余码。
剩余码由 `remainingCodeAfter(reading, input)` 按输入解码出的前 N 个音节扣除，
不能按候选读音重新编码后做字符串前缀匹配。全部选完或引擎提交时，将暂存前缀与 commit buffer
按顺序一次提交。退格先清待撤销引擎前缀，再弹出 heldPicks 一整段并恢复原完整码。

`express_editor` 可能在候选 end 等于输入长度时自动提交，因此部分选词必须在点击入口拦截，
不能调用 select_candidate 后再把剩余码补回去。切换输入框、回车、清组合与退出要防止前缀残留。

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
