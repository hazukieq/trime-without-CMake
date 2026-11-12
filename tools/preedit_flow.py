#!/usr/bin/env python3
"""PC 端「上屏全流程」模擬器（復刻 App 的部分選中 / 退格重選語義）。

引擎側走 rime-jni/host-test 的 `--script -` 互動模式（真實 librime + 真機詞庫），
App 側在 Python 裡復刻 TextInputManager 的 pendingPick / heldPicks / unconsumed 規則，
可以逐步命令：候選條選詞、展開面板左列選行 + 右列選字、退格反悔重選。

用法：
  python3 tools/preedit_flow.py --exec "type 5a4i5i; left lang; right 冷; right 起; state"
  python3 tools/preedit_flow.py --scenario tools/flow-scenarios/lang-ki.flow
  python3 tools/preedit_flow.py            # 內建 demo

命令：
  type <code>        逐鍵輸入（等同 App 的按鍵）
  bar <n>            候選條第 n 條（引擎順序；無學習/adjust 重排）
  pick <text>        依文字在候選條裡找（第一條匹配）
  left <reading>     展開面板左列選一段音節（印出該段可選項）
  right <text>       在目前左列/全部候選裡找文字並選（含部分選中 hold 判定）
  back / del         退格（有 hold 時整段反悔並還原原碼）
  commit             目前組字全部上屏
  clear              清空組字與暫存（對應 App onFinishInput）
  state              印目前狀態
  # ...              註解

注意：這是「行為模擬」不是 UI 模擬；候選條順序取引擎順序（App 的學習詞/SelectionMemory
調整不在模擬範圍）；候選條只枚舉前 64 條與面板全量 600 的差異、panel 左列排序等
需要時再逐步補進來。
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from dataclasses import dataclass, field

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
WORKSPACE = os.path.dirname(REPO)
HOST_RUNNER = os.path.join(WORKSPACE, "rime-jni", "host-test", "run-host-test.sh")

_DATA = None


def data():
    """面板/音節索引（與 App 同口徑），用來數「剩餘碼還有幾個音節」。"""
    global _DATA
    if _DATA is None:
        sys.path.insert(0, HERE)
        import panel_probe  # noqa: E402

        _DATA = panel_probe.Data(os.path.join(REPO, "app", "src", "main", "assets", "rime"))
    return _DATA


@dataclass
class Seg:
    start: int
    end: int
    length: int
    status: int
    selected: str


@dataclass
class EngineState:
    label: str = ""
    input: str = ""
    composing: bool = False
    commit: str = ""
    preedit: str = ""
    segs: list[Seg] = field(default_factory=list)
    cands: list[tuple[str, str]] = field(default_factory=list)


class Engine:
    """rime-jni/host-test --script - 的互動包裝。"""

    def __init__(self, runner: str = HOST_RUNNER):
        self.proc = subprocess.Popen(
            [runner, "--script", "-"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        # 先送一個 dump 當同步點：run-host-test.sh 的部署輸出會被解析器忽略，
        # 直到收到第一個 END 才代表引擎就緒。
        self.send("dump")

    def _read_until_end(self, skip_initial: bool = False) -> EngineState:
        st = EngineState()
        for raw in self.proc.stdout:  # type: ignore[union-attr]
            line = raw.rstrip("\n")
            if line == "END":
                return st
            if line == "DONE":
                return st
            if line.startswith("BEGIN\t"):
                st.label = line.split("\t", 1)[1]
            elif line.startswith("I\t"):
                st.input = line.split("\t", 1)[1]
            elif line.startswith("C\t"):
                st.composing = line.split("\t", 1)[1] == "1"
            elif line.startswith("K\t"):
                st.commit = line.split("\t", 1)[1]
            elif line.startswith("P\t"):
                st.preedit = line.split("\t", 1)[1]
            elif line.startswith("S\t"):
                parts = line.split("\t")
                if len(parts) >= 7:
                    st.segs.append(
                        Seg(int(parts[2]), int(parts[3]), int(parts[4]),
                            int(parts[5]), parts[6])
                    )
            elif line.startswith("D\t"):
                parts = line.split("\t")
                if len(parts) >= 4:
                    st.cands.append((parts[2], parts[3]))
        return st

    def send(self, cmd: str) -> EngineState:
        assert self.proc.stdin is not None
        self.proc.stdin.write(cmd + "\n")
        self.proc.stdin.flush()
        return self._read_until_end()

    def close(self) -> None:
        try:
            self.send("quit")
        except Exception:
            pass
        try:
            self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()


def reading_syllables(comment: str) -> list[str]:
    return [s for s in comment.split() if s]


class Sim:
    """App 側狀態機：pendingPick / heldPicks / unconsumedCode / 上屏折疊。"""

    def __init__(self, engine: Engine):
        self.engine = engine
        self.state = EngineState()
        self.pending = ""            # pendingPick：已選但未上屏的詞（preedit 前綴）
        self.held: list[tuple[str, str]] = []  # (text, 選中前完整碼)
        self.committed = ""          # 目前已上屏文字
        self.left_reading = ""       # 面板左列目前選中的段音節
        self.failures = 0            # assert 失敗數（scenario 當回歸測試用）
        self._reset_from(self.state)

    # ---------- 引擎狀態同步 ----------
    def _reset_from(self, st: EngineState) -> None:
        self.state = st

    def selected_prefix(self) -> str:
        """引擎 preedit 裡已選中的字（非 ASCII 前綴），用來併入 pending。"""
        out = []
        for ch in self.state.preedit:
            if ord(ch) > 127 and ch not in "‸":
                out.append(ch)
            elif out:
                break
        return "".join(out)

    def unconsumed(self) -> str:
        if not self.state.segs:
            return self.state.input
        consumed = 0
        for seg in self.state.segs:
            if seg.status >= 2:  # kSelected / kSelectedAndConfirmed
                consumed = max(consumed, seg.end)
        return self.state.input[consumed:]

    def remaining_segs(self) -> list[Seg]:
        return [s for s in self.state.segs if s.status < 2]

    # ---------- 上屏 ----------
    def _absorb_commit(self, st: EngineState) -> None:
        if st.commit:
            self.committed += self.pending + st.commit
            self.pending = ""
            self.held.clear()

    # ---------- 選中 ----------
    def _pick_index(self, index: int) -> None:
        cands = self.state.cands
        text, comment = ("", "")
        if 0 <= index < len(cands):
            text, comment = cands[index]
        tokens = reading_syllables(comment)
        remaining_code = self.unconsumed()
        pieces = self._decode(remaining_code)
        # App：候選只覆蓋前幾音節時不調 select，避免 auto-commit 整串。
        # App 只在「多音節整詞」被部分覆蓋時 hold；單音節/單字走引擎原生部分選中。
        if len(tokens) >= 2 and pieces and len(pieces) > len(tokens):
            covered = "".join(pieces[: len(tokens)])
            rest = remaining_code[len(covered):]
            prefix = self.selected_prefix()
            picked = prefix + text
            self.pending += picked
            self.held.append((picked, self.state.input))
            st = self.engine.send("clear")
            self._reset_from(st)
            if rest:
                st = self.engine.send("type " + rest)
                self._reset_from(st)
            else:
                # 沒有剩餘碼時不能留在 clear 空組字，直接 commit pending。
                self.committed += self.pending
                self.pending = ""
                self.held.clear()
            self._absorb_commit(st)
        else:
            st = self.engine.send(f"select {index}")
            self._reset_from(st)
            self._absorb_commit(st)

    def _decode(self, code: str) -> list[str]:
        try:
            return data().decode(code) or []
        except Exception:
            return [code] if code else []

    def bar(self, n: int) -> None:
        self._pick_index(n)

    def pick(self, text: str) -> bool:
        for i, (t, _c) in enumerate(self.state.cands):
            if t == text:
                self._pick_index(i)
                return True
        print(f"  ! 候選條找不到「{text}」")
        return False

    # ---------- 面板 ----------
    def left(self, reading: str) -> None:
        self.left_reading = reading
        matches = [(i, t, c) for i, (t, c) in enumerate(self.state.cands)
                   if reading_syllables(c)[:1] == [reading]]
        print(f"  左列 {reading}: {len(matches)} 項")
        for i, t, c in matches[:16]:
            print(f"    #{i:<3} {t}  ({c})")

    def right(self, text: str) -> bool:
        wanted = self.left_reading
        for i, (t, c) in enumerate(self.state.cands):
            if t == text and (not wanted or reading_syllables(c)[:1] == [wanted]):
                self._pick_index(i)
                return True
        # 左列匹配不到就退化成任何同文候選（模擬 App 的 fallback 選詞）
        return self.pick(text)

    # ---------- 退格 / 上屏 ----------
    def back(self) -> None:
        if self.held:
            picked, code = self.held.pop()
            if self.pending.endswith(picked):
                self.pending = self.pending[: -len(picked)]
            else:
                self.pending = ""
            st = self.engine.send("clear")
            self._reset_from(st)
            if code:
                st = self.engine.send("type " + code)
                self._reset_from(st)
            self._absorb_commit(st)
        else:
            st = self.engine.send("key 65288")  # XK_BackSpace
            self._reset_from(st)
            self._absorb_commit(st)

    def commit(self) -> None:
        st = self.engine.send("commit")
        self._reset_from(st)
        self._absorb_commit(st)

    def clear(self) -> None:
        st = self.engine.send("clear")
        self._reset_from(st)
        self.pending = ""
        self.held.clear()
        self.left_reading = ""

    # ---------- 顯示 ----------
    def dump(self) -> None:
        st = self.state
        print(
            f"  input={st.input!r} composing={int(st.composing)} "
            f"preedit={st.preedit!r} pending={self.pending!r} "
            f"committed={self.committed!r}"
        )
        if st.segs:
            segs = " ".join(
                f"[{s.start},{s.end}) st={s.status} sel={s.selected!r}"
                for s in st.segs
            )
            print(f"  segs: {segs}")

    # ---------- 斷言 ----------
    def _assert(self, arg: str) -> None:
        key, _, want = arg.partition(" ")
        want = want.strip()
        if want == '""':
            want = ""
        if key == "committed":
            got = self.committed
        elif key == "pending":
            got = self.pending
        elif key == "input":
            got = self.state.input
        elif key == "composing":
            got = "1" if self.state.composing else "0"
        else:
            self.failures += 1
            print(f"  ASSERT FAILED: 未知斷言 {key}")
            return
        if got != want:
            self.failures += 1
            print(f"  ASSERT FAILED: {key} 期望 {want!r} 實際 {got!r}")

    # ---------- 命令 ----------
    def run(self, cmd_line: str) -> None:
        line = cmd_line.strip()
        if not line or line.startswith("#"):
            return
        cmd, _, arg = line.partition(" ")
        arg = arg.strip()
        print(f"> {line}")
        if cmd == "type":
            st = self.engine.send("type " + arg)
            self._reset_from(st)
            self._absorb_commit(st)
        elif cmd == "bar":
            self.bar(int(arg))
        elif cmd in ("pick", "sel"):
            self.pick(arg)
        elif cmd == "left":
            self.left(arg)
        elif cmd == "right":
            self.right(arg)
        elif cmd in ("back", "del"):
            self.back()
        elif cmd == "commit":
            self.commit()
        elif cmd == "clear":
            self.clear()
        elif cmd == "state":
            pass
        elif cmd == "assert":
            self._assert(arg)
        else:
            print(f"  ! 未知命令: {cmd}")
        self.dump()


DEMO = """
# 部分選中：lang ki → 面板選「冷」→ 候選條選「起」→ 應上屏「冷起」
type 5a4i5i
left lang
right 冷
left ki
right 起
commit
state
"""


def main() -> int:
    ap = argparse.ArgumentParser(description="PC preedit/上屏 全流程模擬")
    ap.add_argument("--exec", dest="inline", help="以分號分隔的命令字串")
    ap.add_argument("--scenario", help="命令檔路徑，一行一命令")
    ap.add_argument("--host-runner", default=HOST_RUNNER)
    args = ap.parse_args()

    if not os.path.exists(args.host_runner):
        print(f"找不到 {args.host_runner}")
        return 2

    lines: list[str] = []
    if args.inline:
        lines = [x.strip() for x in args.inline.split(";")]
    elif args.scenario:
        with open(args.scenario, encoding="utf-8") as f:
            lines = [x.rstrip("\n") for x in f]
    else:
        lines = [x for x in DEMO.splitlines()]

    engine = Engine(args.host_runner)
    sim = Sim(engine)
    print("== 資料源與引擎就緒（host-test --script）")
    try:
        for line in lines:
            sim.run(line)
    finally:
        engine.close()

    print(f"\n== 最終上屏: {sim.committed!r}")
    if sim.failures:
        print(f"== 斷言失敗 {sim.failures} 條")
        return 1
    print("== 斷言全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
