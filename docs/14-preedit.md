# preedit、剩余码与部分选词

## 读音渲染

统一入口为 `TwelveKeyPreedit.render`，以实际输入码、引擎 preedit、候选 comment 与
本地解码结果计算显示。候选条首选、previewText、inlineText 和待选拼音要保持同源。
已选前缀与 App pendingPrefix 只能各带一次，不能把光标标记当成已选文字。

候选读音可能使用别名拼写。`canonicalSpelling` 规范化 w/u、入声尾与鼻音尾等别名；
覆盖长度按本地解码的整节输入消耗。对不同音节不能套用别名规则。
半截尾码保持原始输入，不用猜出的拼音掩盖未完成按键。

## 剩余输入

`remainingCodeAfter(wordReading, input)` 先解码原码串，再按选中读音的音节数扣除前 N 节。
不能把 comment 重新编码后逐字符扣除：多读音或别名会与实际键码不同。
无法切出完整音节时不猜剩余；PC 复现入口：

```bash
python3 tools/panel_probe.py preedit 5i4ii8i9934i54iei9 --pick "lin gi jit dung"
python3 tools/preedit_flow.py --scenario tools/flow-scenarios/lingji-partial.flow
```

## 选择与撤回

部分候选只覆盖输入前段时，TextInputManager.holdPartialPick 保存文字与完整原码，
更新 pendingPick/pendingPrefix，清组合并重喂剩余码；文字仍停留在 preedit。
候选 end 等于输入长度时 express_editor 会自动 commit，因此不能先 select 再补码。
TwelveKeyController.selectCandidate 不承担提交后补剩余码的职责。

DEL 先撤销待用引擎前缀；有 heldPicks 时弹出一整次选择、撤其文字、恢复选择前完整码。
最终选择完毕或引擎 commit 时，先合并暂存前缀再提交，并清理对应状态。
输入框切换、回车、清空与组合结束都必须检查状态清理，防止跨输入串串字。

## 验证

PartialWordCommitTest 验证剩余码，SnapshotDecouplingTest 验证快照状态一致，
RealCorpusTest 验证真实逐键帧和用户词库音节组合。真实语料必须通过实际 librime 生成，
流程见 [16](16-librime-coverage-test.md)。真机还须验证连续部分选词、整段退格、改选及最后一次提交。

## PC 全流程模拟（上屏/候选条/面板/退格）

`tools/preedit_flow.py` 在 PC 上驱动真实 librime（`rime-jni/host-test --script -`），
并在 Python 里复刻 TextInputManager 的 `pendingPick / heldPicks / unconsumedCode` 规则，
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
`assert committed|pending|input|composing|bar_first <值>`；`bar` 无参数时打印当前候选条。
`left` 只列 comment 首节等于该音节的候选。

学习记忆（与 Kotlin 同口径）：

- `PhraseMemory`：读 `--prefs-dir`（默认 `/tmp/hkrime-flow-prefs`）下的
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

边界：PC 模拟不渲染 UI；候选条 64 窗口、面板全量、两份 App 记忆、引擎 userdb 读写均已覆盖。
