# preedit、剩余码与部分选词

## 读音渲染

统一入口为 `PreeditPresentation`：`publish` 绑定当前快照，`render`/`preview` 以实际输入码、
原始 preedit、候选 comment 与本地解码结果计算显示，`TwelveKeyPreedit` 只负责解码与改写。
候选条首选、previewText、inlineText 和待选拼音要保持同源。
已选文字（`CompositionSession.selectedText`）与引擎已选前缀只能各带一次，不能把光标标记当成已选文字。

候选读音可能使用别名拼写。`canonicalSpelling` 规范化 w/u、入声尾与鼻音尾等别名；
覆盖长度按本地解码的整节输入消耗。对不同音节不能套用别名规则。
半截尾码保持原始输入，不用猜出的拼音掩盖未完成按键。

## 剩余输入

`remainingCodeAfter(wordReading, input)` 先用已选读音约束实际输入的前 N 段词典跨度，
无法匹配时回退到输入自身的最少音节、同段数最高频切分。不能直接重编码 comment 后删前缀，
多读音或别名可能与输入不同。没有可靠跨度或完整切分时不猜剩余。
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
python3 tools/preedit_flow.py --prefs-dir tools/flow-scenarios/prefs-demo \
    --scenario tools/flow-scenarios/learned-word.flow
python3 tools/preedit_flow.py --prefs-dir tools/flow-scenarios/prefs-selection \
    --scenario tools/flow-scenarios/selection-memory.flow
python3 tools/preedit_flow.py --scenario tools/flow-scenarios/userdb-learned.flow
```

命令：`type <码>`、`bar <n>`、`pick <文字>`、`left <音節>`、`right <文字>`、
`back`/`del`、`commit`、`clear`、`state`、
`panel`、`assert committed|pending|input|remaining|composing|bar_first <值>`、
`assert panel_has <音节>`、`assert no_isolated 1`；`bar` 无参数时打印当前候选条。
`left` 只列 comment 首节等于该音节的候选。

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
- `--no-learning`：完全按引擎顺序（对照用）。

候选条与面板窗口：`bar`/`pick` 只在前 64 条引擎候选中做学习排序与学习词混排、展示 12 项；
`left`/`right` 用全量候选（host-test 最多印 600 条），对应展开面板路径。

引擎侧 userdb 也在本地闭环：先跑一次 `rime-jni/host-test/pull-real-data.sh`
（会拉 `*.userdb` LevelDB 目录）作为初始快照；`run-host-test.sh` 把它复制进 user dir，
librime 自己加载并在本次 session 结束时写回 user dir。脚本再把 user dir 的 `*.userdb`
存到 `$HKRIME_HOST_USERDB`（默认 `/tmp/hkrime-host-userdb`），下次运行叠加回来——
所以「真机 userdb 快照 + 本地模拟里新学的词」会一直累积，不需要灌回任何装置。
（候选排序里既有引擎 userdb 的 commits，也有 App 两份记忆。）

`--data <目录>` 同时指定引擎部署与 App 字典索引来源；默认优先现有设备快照，缺失时使用 assets。
`panel` 与 `assert panel_has` 查询 App 字典来源；`left/right` 仍查询引擎候选，不等同于 Android 点击测试。
找不到候选、引擎提前退出、未知命令及断言失败均报错，不能据此宣称 UI 或设备用户词库验收通过。

复现选「太」后的面板与退格还原（fixture 只提供 SelectionMemory 排序，使太出现在候选条）：

```bash
python3 tools/preedit_flow.py --data app/src/main/assets/rime \
  --prefs-dir tools/flow-scenarios/prefs-tai --no-save \
  --scenario tools/flow-scenarios/tai-go.flow
```
