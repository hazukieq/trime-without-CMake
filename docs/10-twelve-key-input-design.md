# 十二键输入流程

## 处理阶段

1. 主题发送 12 键码，Rime 在 ENGINE_LOCK 内处理并更新上下文。
2. TextInputManager 先处理 commit buffer，再更新 composition，通知统一候选刷新入口。
3. TwelveKeyController 在后台读取 local 状态、命中缓存或构建快照，Main 线程发布。
4. 候选条、面板与输入法 preedit 消费同一份快照；旧 generation 结果被丢弃。
5. 点击按候选的 index/segment 分发，保留全局引擎下标并更新本地学习。

## 状态与上屏

`CompositionSession` 保存剩余码、选择日志（文字/读音/选择前原码/实际消费码/来源）
与一次性提交许可；引擎自身仍可能保存已选未提交的前缀（`getCompositionPrefix`）。
两者与引擎输入共同构成当前组合。

候选条、展开面板、空格与回车都走同一个 `chooseTwelveKey`：先用快照 contextKey 排除过期点击，
再由 `CandidateSelection.consumedEnd` 校验条目绑定的 `InputCodeSpan`，然后记入选择日志。
原码边界在快照构建时由 `CandidateInputBoundary` 计算，绑定剩余原码及原始读音；
点击不再解析显示 comment 或 preedit。未选完时清组合并重喂剩余码，已选文字只留在 preedit；
不能按显示拼音长度扣码，不能按候选读音重新编码后做字符串前缀匹配。
全部码位消耗后状态机才发放一次提交许可，`commitThrough` 写入编辑器成功后才清空日志。
`express_editor` 可能在候选 end 等于输入长度时自动提交，因此部分选择不能直接调 select_candidate；
引擎意外 commit 由 `consumeTwelveKeyEngineCommit` 拦截并恢复待选码。
DEL 在任意界面撤回最后一次选择并恢复该次选择前的完整原码，可逐次回退重选。
切换输入框或退出取消未完成组合；回车只选择一次首候选。
十二键待选文本不写入宿主 composing 区，防止焦点切换自动保留尚未完成选择的文字。

## 候选与学习

候选条按引擎顺序加少量 SelectionMemory 调整，混入 PhraseMemory 合成词并按文字去重。
词与单字同池竞争。SegmentMemory 为面板字频和邻字偏好提供评分。
PhraseMemory 的读音索引也并入面板，运行时不解析 sync userdb 快照。

学习粒度：一次组合内 ≥2 次点选、且每次点选都有读音时记一条词；此外**逐段上屏**
（先打一个音节点字上屏，再打下一段点词）在 8 秒窗口内、同一输入框、总长不超过 8 字时
会把码串与读音拼起来再记一条，因此「ji 选已 → ha zai 选下载」也能形成 `8iia8ai 已下载`。
超过窗口、换输入框或真取消组合即断开；一次组合内连点多段本身已是整词，不接续（否则会记成「已下载已下载」）。
全局条目上限 400，淘汰按分数升序，最近 60 秒用过的词不参与本轮淘汰（否则满库时新词刚写入就被删）。

固定条只从前 64 条窗口产生 12 项；面板最多枚举 600 项，六个 App 源和引擎组统一合并。
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

左栏字母行与音节组同列，逐段确认拼音：字母确认/细化首拼，普通音节行确认完整音节；
CompositionSession 记录确认段及原码跨度，推进待确认尾码但保留整串原码用于选字校验。
非空 `hkrime_initial` 让引擎保留简拼分支（0006），JNI 在窗口前过滤；首段汉字由 App 字表补。
确认拼音不获得提交许可，DEL 按操作顺序撤销。
