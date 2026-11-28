# preedit、剩余码与部分选词

## 读音渲染

统一入口为 `PreeditPresentation`：`publish` 绑定当前快照，`render`/`preview` 以实际输入码、
原始 preedit、候选 comment 与本地解码结果计算显示，`TwelveKeyPreedit` 只负责解码与改写。
候选条首选、快照预览和待选拼音要保持同源。
关闭“候选算法排序”后，同源首选直接取引擎第一个候选；不应用 App 学习重排、完整匹配置顶或单字补位。
切换设置只更新候选显示，不消费原码、不提交、不丢失已选前缀或撤销记录。
已选文字（`CompositionSession.selectedText`）与引擎已选前缀只能各带一次，不能把光标标记当成已选文字。

十二键待选内容仅在输入法自己的 preedit 中显示，不调用宿主的 `setComposingText`，
嵌入式编辑模式只用于其他输入模式。Android 切换焦点时可能自行结束 composing 并保留文字，
事后清理旧 InputConnection 不能可靠撤回；因此只有全部选完后的 `commitText` 才能写入宿主。
触屏模式的悬浮 preedit 由 `TouchPreeditPopupWindow` 集中管理 Android `PopupWindow` 生命周期、固定位置与纯数据/UI 分离渲染（与外设键盘模式 `ime/hardware/` 的 `Hardware*Overlay` 彻底隔离）：
不再读取主题中的 `layout/*`、`window`、`preedit_text_size`、`preedit_disable_touch` 等自定义参数；
展示容器由 `PreeditScrollView` 锁定系统最大宽度（`280dp`），单行裁切溢出并支持用户触摸左右滚动查看；
输入新编码时自动滚至尾端，主动滑看前缀时不强行复位。
连续组字编码上限设为 `48`（`CompositionInput.MAX_CODE_LENGTH`），达到上限后忽略追加编码键，
退格或选词释放空位后恢复输入，避免超长编码引发 UI 测量与候选布局开销退化。

preedit 按首候选的原始读音逐码位具体化已输入内容，不补齐没有输入的拼音。
例如首候选读音为 `sin ki`：输入 `7i45` 显示 `sin k`，输入 `7i45i` 才显示 `sin ki`。
完整音节、简拼及最后一节前缀均须实际匹配原码；失配部分保留原码，不按读音长度猜覆盖范围。
`ng/th/nz` 各占一个键位，显示时保留完整声母。合法别名按实际输入拼写显示。
表情说明不是读音，使用候选的原始读音元数据；所有显示匹配均不参与消费码位。
开启“候选算法排序”时，候选条首位优先取实际跨度覆盖全部剩余原码的候选，匹配候选内部仍按引擎/学习混排顺序。
仅覆盖首段的候选不能因学习记录抢占完整匹配的首位；其余项保序，不按文字长度或音节数分区。
第一段单字兜底仅在条尾补位，不能替换此首选。
如 `7i4aa4` 首选「平板」时显示 `pin ban`，不能因单字补位变成 `sin aa4`。

左栏确认首拼或音节会推进待确认码位，preedit 显示已确认拼音与后续未确认拼音，
例如 `l'h'…`；待确认段的末尾按首候选读音具体化（`k'c`），不补未输入的字母。
细化首拼（`b ↔ c`）只改已确认段读音，不改变原码与 preedit 的码位。
这一步尚未选字，不获得提交许可；完整原码仍用于候选跨度验证。
DEL 按操作顺序撤销最近一次拼音确认/细化或选字，并恢复当时的确认序列与原码。

## 剩余输入

引擎候选在快照构建时直接使用 Native 候选的实际 start/end，不从表情说明或显示读音推测。
App 合成候选与旧库兼容路径由 `CandidateInputBoundary` 用候选原始读音约束实际输入的前 N 段词典跨度，
无法匹配时回退到输入自身的最少音节、同段数最高频切分。不能直接重编码 comment 后删前缀，
多读音或别名可能与输入不同。没有可靠跨度或完整切分时不猜剩余。
每个可选择条目携带 `InputCodeSpan`，绑定生成时的剩余原码、消费终点及原始读音；
点击只校验并使用该跨度，不再解析显示 comment 或 preedit。显示改写不改变扣码或撤销记录。
`9aii48i5i73` 选 `tai` 的「太」后剩余必须为 `i48i5i73`，面板保留 `go`。
PC 复现入口：

```bash
python3 tools/panel_probe.py preedit 5i4ii8i9934i54iei9 --pick "lin gi jit dung"
python3 tools/preedit_flow.py --scenario tools/flow-scenarios/lingji-partial.flow
```

## 选择、撤回与提交许可

候选条、展开面板、空格和回车使用同一个选择入口与 CompositionSession 状态机。
每次选择先确认快照仍对应当前引擎输入，再确认实际消费的码位；无法确认消费范围时保留原码，
不把“无法解析剩余码”当作“全部选完”。单字组使用其明确的剩余码，孤单联想选字只消费首键。
标点/符号等非码串输入没有可证实的音节码位，不进选择日誌，直接交引擎选中并提交。

每次选择均记录文字、读音和选择前原码；部分选择只暂存，重喂未选尾码。
DEL 从任意界面撤回最后一次选择并恢复该次选择前的完整原码，逐次回退可以重选所有已选部分。
候选条和面板切换不改变记录，也不触发提交。最终选择的提交失败时保留记录，仍可撤销或重试。

只有明确的最后一次选择消耗全部剩余编码后，状态机才发放一次提交许可。
刷新、开合面板、引擎自动提交、回车或空格均不能绕过未选编码；回车/空格每次只选择当前首候选。
意外的引擎 commit buffer 被拦截并恢复仍待选择的码，不直接落入 InputConnection。
编辑器写入成功才清理暂存与撤销栈。取消或切换输入框清理会话，不提交未完成的组合。

preedit 的发布与渲染由独立显示模型管理，词典层仅负责解码与改写。
首候选提示绑定具体输入/分段状态，旧提示不能用于新输入；暂存前缀、候选条首选与未选码同帧显示。
显示刷新不消费编码，不修改选择栈，不提交文字。面板项携带其生成快照的标识，过期点击只刷新。

## 验证

PartialWordCommitTest 验证剩余码，SnapshotDecouplingTest 验证快照状态一致，
RealCorpusTest 验证真实逐键帧和用户词库音节组合。真实语料必须通过实际 librime 生成，
流程见 [16](16-librime-coverage-test.md)。真机还须验证连续部分选词、整段退格、改选及最后一次提交。

## PC 全流程模拟（上屏/候选条/面板/退格）

`tools/preedit_flow.py` 在 PC 上驱动真实 librime（`rime-jni/host-test --script -`），
并在 Python 侧复刻 CompositionSession 的选择日志、一次性提交许可与整段撤销规则，
可逐步执行候选条选词、展开面板左列选行后右列选字、部分选中、退格整段反悔与最终上屏。

```bash
# 临时组合
python3 tools/preedit_flow.py --exec "type 5a4i5i; left lang; right 冷; left ki; right 起; state"
# 回归场景（含 assert，失败退出码非 0）
python3 tools/preedit_flow.py --scenario tools/flow-scenarios/lang-ki.flow
python3 tools/preedit_flow.py --scenario tools/flow-scenarios/lingji-partial.flow
python3 tools/preedit_flow.py --scenario tools/flow-scenarios/backspace-reselect.flow
python3 tools/preedit_flow.py --no-learning --no-save --scenario tools/flow-scenarios/preedit-first-word.flow
python3 tools/preedit_flow.py --engine-order --no-save --scenario tools/flow-scenarios/engine-order.flow
python3 tools/preedit_flow.py --prefs-dir tools/flow-scenarios/prefs-demo \
    --scenario tools/flow-scenarios/learned-word.flow
python3 tools/preedit_flow.py --prefs-dir tools/flow-scenarios/prefs-selection \
    --scenario tools/flow-scenarios/selection-memory.flow
python3 tools/preedit_flow.py --scenario tools/flow-scenarios/userdb-learned.flow
```

命令：`type <码>`、`bar <n>`、`pick <文字>`、`left <音節>`、`right <文字>`、
`initial k` / `initial l` / `initial`（解除）、`back`/`del`、`commit`、`clear`、`state`、
`panel`、`assert committed|pending|input|remaining|composing|bar_first <值>`、
`assert panel_has <音节>`、`assert no_isolated 1`；`bar` 无参数时打印当前候选条。
`left` 只切换展示组，不选字、不改选择记录；`right` 才选择该组的字词。
PC 工具的 `initial` 是底层单段过滤探针；Android 的逐段拼音确认尚未镜像到此模拟器，
不能用该命令验证新版首拼推进和撤销交互。

学习记忆（与 Kotlin 同口径）：

- `PhraseMemory`：读 `--prefs-dir`（默认 `tools/flow-data/`）下的
  `twelvekey_phrases.xml`；可见条件、score、`learnPick/bumpPicked` 与 Kotlin 一致，
  学习词以合成候选参与候选条混排（score 与引擎顺序分 `1-i/n` 取大，同分引擎在前）。
- `SelectionMemory`：读 `twelvekey_selection.xml`；选词前按 `次数*1000+最近序` 排序，
  选中后 bump（模拟器只在内存里记，除非另行导回装置）。
- `--pull-prefs <serial>`：一次性从装置 `run-as` 拉两份 xml 到 `--prefs-dir`（默认
  `tools/flow-data/`，已 gitignore），作为本地模擬的初始记忆。
- 学习写入默认落盘：每次运行结束把 PhraseMemory/SelectionMemory 写回 `--prefs-dir`，
  下一次模拟直接续用（不需要灌回装置）；`--no-save` 关闭。
- `--no-learning`：不加载 App 学习记忆，仍使用完整匹配首位优先与单字补位。
- `--engine-order`：对应关闭“候选算法排序”，候选条和面板仅使用原始引擎候选，保留顺序与重复项。

候选条与面板窗口：`bar`/`pick` 只在前 64 条引擎候选中做学习排序与学习词混排、展示 12 项；
`left`/`right` 合并 App 字典行与全量引擎候选（host-test 最多印 600 条），对应展开面板路径。

引擎侧 userdb 也在本地闭环：先跑一次 `rime-jni/host-test/pull-real-data.sh`
（会拉 `*.userdb` LevelDB 目录）作为初始快照；`run-host-test.sh` 把它复制进 user dir，
librime 自己加载并在本次 session 结束时写回 user dir。脚本再把 user dir 的 `*.userdb`
存到 `$HKRIME_HOST_USERDB`（默认 `/tmp/hkrime-host-userdb`），下次运行叠加回来——
所以「真机 userdb 快照 + 本地模拟里新学的词」会一直累积，不需要灌回任何装置。
（候选排序里既有引擎 userdb 的 commits，也有 App 两份记忆。）

`--data <目录>` 同时指定引擎部署与 App 字典索引来源；默认优先现有设备快照，缺失时使用 assets。
`panel` 与 `assert panel_has` 查询 App 字典来源；`left/right` 模拟展示分组与原码跨度选择，不等同于 Android 点击测试。
找不到候选、引擎提前退出、未知命令及断言失败均报错，不能据此宣称 UI 或设备用户词库验收通过。

复现选「太」后的面板与退格还原（fixture 只提供 SelectionMemory 排序，使太出现在候选条）：

```bash
python3 tools/preedit_flow.py --data app/src/main/assets/rime \
  --prefs-dir tools/flow-scenarios/prefs-tai --no-save \
  --scenario tools/flow-scenarios/tai-go.flow
```
