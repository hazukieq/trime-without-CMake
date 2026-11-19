#!/usr/bin/env python3
"""PC librime + App 部分选词、撤销、学习排序的行为模拟。

--data 同时指定引擎与面板索引的数据目录；不指定时使用现有设备快照，否则随包数据。
bar/pick 使用 64 条引擎窗口、最多展示 12 项；left/right 查询最多 600 条引擎候选。
panel 诊断 App 字典来源，不渲染 UI。assert 支持 remaining、panel_has、no_isolated，
以及 committed/pending/input/composing/bar_first。找不到候选、未知命令或断言失败均退出非零。
示例见 docs/14-preedit.md。
"""

from __future__ import annotations

import argparse
import math
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field, replace

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
WORKSPACE = os.path.dirname(REPO)
HOST_RUNNER = os.path.join(WORKSPACE, "rime-jni", "host-test", "run-host-test.sh")

_DATA = None
_DATA_DIR = os.environ.get("HKRIME_REAL_DATA", "/tmp/hkrime-real-data")
if not os.path.isdir(_DATA_DIR):
    _DATA_DIR = os.path.join(REPO, "app", "src", "main", "assets", "rime")
DEFAULT_PREFS = os.path.join(HERE, "flow-data")


def data():
    """與引擎共用資料來源的音節、選詞跨度與面板字典索引。"""
    global _DATA
    if _DATA is None:
        sys.path.insert(0, HERE)
        import panel_probe  # noqa: E402

        _DATA = panel_probe.Data(_DATA_DIR)
    return _DATA


# ---------- App 側記憶（與 Kotlin 同口徑） ----------


@dataclass
class PhraseEntry:
    text: str
    readings: str
    typed: int
    last_used_at: int
    shown: int = 0
    picked: int = 0


def _parse_prefs_xml(path: str):
    """Android 的 SharedPreferences XML 會寫 &#1; 這種 XML 1.0 非法控制字元，
    ElementTree 直接 parse 會炸；先用哨兵替換再還原。"""
    raw = open(path, encoding="utf-8").read()
    raw = raw.replace("&#1;", "@@SEP@@").replace("&#10;", "@@NL@@")
    return ET.fromstring(raw)


class PhraseMemory:
    """twelvekey_phrases.xml：學習詞 table（碼串 → 詞條）。"""

    MAX_PER_CODE = 8
    MIN_COUNT = 2
    MAX_COUNT = 9
    HALF_LIFE_MS = 7 * 24 * 3600 * 1000
    CTR_ALPHA = 1.0
    CTR_BETA = 2.0
    EXPOSURE_DISCOUNT = 0.5

    def __init__(self, prefs_dir: str | None):
        self.by_code: dict[str, list[PhraseEntry]] = {}
        self.version = 0
        path = os.path.join(prefs_dir, "twelvekey_phrases.xml") if prefs_dir else ""
        if path and os.path.isfile(path):
            try:
                root = _parse_prefs_xml(path)
            except ET.ParseError:
                root = None
            if root is not None:
                for node in root.findall("string"):
                    code = node.get("name") or ""
                    if not code:
                        continue
                    entries = self.decode(node.text or "")
                    if entries:
                        self.by_code[code] = entries

    @staticmethod
    def normalize(code: str) -> str:
        return "".join(c for c in code if c != "'" and not c.isspace()).lower()

    @classmethod
    def decode(cls, raw: str) -> list[PhraseEntry]:
        out: list[PhraseEntry] = []
        raw = raw.replace("@@NL@@", "\n").replace("@@SEP@@", "\u0001")
        for line in raw.split("\n"):
            parts = line.split("\u0001")
            if len(parts) < 4:
                continue
            text = parts[0]
            if not text:
                continue
            try:
                typed = int(parts[2])
                last = int(parts[3])
            except ValueError:
                continue
            shown = int(parts[4]) if len(parts) > 4 and parts[4].isdigit() else 0
            picked = int(parts[5]) if len(parts) > 5 and parts[5].isdigit() else 0
            out.append(PhraseEntry(text, parts[1], max(1, min(typed, cls.MAX_COUNT)),
                                   last, shown, picked))
        return out[: cls.MAX_PER_CODE]

    @classmethod
    def score(cls, e: PhraseEntry, now: int) -> float:
        age = max(0, now - e.last_used_at)
        fresh = math.pow(0.5, age / cls.HALF_LIFE_MS)
        denom = e.picked + cls.EXPOSURE_DISCOUNT * e.shown + cls.CTR_ALPHA + cls.CTR_BETA
        ctr = (e.picked + cls.CTR_ALPHA) / denom
        demand = 1.0 + math.log1p(e.typed)
        return fresh * ctr * demand

    @classmethod
    def should_purge(cls, e: PhraseEntry) -> bool:
        return e.shown >= 8 and e.picked == 0 and e.typed <= 2

    def candidates_for(self, code: str, now: int | None = None) -> list[PhraseEntry]:
        now = now or int(time.time() * 1000)
        entries = self.by_code.get(self.normalize(code), [])
        visible = [
            e for e in entries
            if (e.typed + e.picked) >= self.MIN_COUNT
            and len(e.text) >= 2
            and not self.should_purge(e)
        ]
        visible.sort(key=lambda e: (-self.score(e, now), -e.last_used_at))
        return visible

    def has_any(self) -> bool:
        return bool(self.by_code)

    def learn_pick(self, code: str, text: str, readings: str) -> None:
        key = self.normalize(code)
        if not key or len(text) < 2:
            return
        now = int(time.time() * 1000)
        entries = list(self.by_code.get(key, []))
        hit = next((e for e in entries if e.text == text), None)
        if hit is not None:
            hit.picked = min(hit.picked + 1, self.MAX_COUNT)
            hit.last_used_at = now
        else:
            entries.append(PhraseEntry(text, readings, 1, now, picked=1))
        entries.sort(key=lambda e: (-self.score(e, now), -e.last_used_at))
        self.by_code[key] = entries[: self.MAX_PER_CODE]
        self.version += 1

    def record(self, code: str, text: str, readings: str) -> None:
        """逐段拼出的整串：typed +1（App PhraseMemory.applyRecord）。"""
        key = self.normalize(code)
        if not key or len(text) < 2:
            return
        now = int(time.time() * 1000)
        entries = list(self.by_code.get(key, []))
        hit = next((e for e in entries if e.text == text), None)
        if hit is not None:
            hit.typed = min(hit.typed + 1, self.MAX_COUNT)
            hit.last_used_at = now
            if readings:
                hit.readings = readings
        else:
            entries.append(PhraseEntry(text, readings, 1, now))
        entries.sort(key=lambda e: (-self.score(e, now), -e.last_used_at))
        self.by_code[key] = entries[: self.MAX_PER_CODE]
        self.version += 1

    def bump_picked(self, code: str, text: str) -> None:
        key = self.normalize(code)
        for e in self.by_code.get(key, []):
            if e.text == text:
                e.picked = min(e.picked + 1, self.MAX_COUNT)
                e.last_used_at = int(time.time() * 1000)
                self.version += 1
                return


class SelectionMemory:
    """twelvekey_selection.xml：選中次數 * 1000 + 最近序號。"""

    def __init__(self, prefs_dir: str | None):
        self.scores: dict[str, int] = {}
        path = os.path.join(prefs_dir, "twelvekey_selection.xml") if prefs_dir else ""
        if path and os.path.isfile(path):
            root = _parse_prefs_xml(path)
            for node in root.findall("int"):
                name = (node.get("name") or "").replace("@@SEP@@", "\u0001")
                if name == "~seq":
                    continue
                try:
                    self.scores[name] = int(node.get("value") or 0)
                except ValueError:
                    pass

    def has_any(self) -> bool:
        return bool(self.scores)

    @staticmethod
    def key(text: str, comment: str) -> str:
        return text if not comment else f"{comment}\u0001{text}"

    def score(self, text: str, comment: str) -> int:
        return self.scores.get(self.key(text, comment), 0)

    def bump(self, text: str, comment: str) -> None:
        if not text:
            return
        k = self.key(text, comment)
        old = self.scores.get(k, 0)
        count = min(old // 1000 + 1, 9)
        seq = max(self.scores.values(), default=0) % 1000 + 1
        self.scores[k] = count * 1000 + seq


@dataclass
class BarItem:
    text: str
    comment: str
    index: int
    segment: int = -1
    rest: str = ""
    context: tuple[str, str] = ("", "")


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
            env={**os.environ, "HKRIME_REAL_DATA": os.path.abspath(_DATA_DIR)},
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
        raise RuntimeError(f"引擎在 END 前退出，returncode={self.proc.poll()}")

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

    def __init__(self, engine: Engine, prefs_dir: str | None = None,
                 apply_learning: bool = True):
        self.engine = engine
        self.apply_learning = apply_learning
        self.phrase = PhraseMemory(prefs_dir) if apply_learning else PhraseMemory(None)
        self.sel = SelectionMemory(prefs_dir) if apply_learning else SelectionMemory(None)
        self.state = EngineState()
        self.pending = ""            # pendingPick：已選但未上屏的詞（preedit 前綴）
        self.committed = ""          # 目前已上屏文字
        self.left_reading = ""       # 面板左列目前選中的段音節
        self.failures = 0            # assert 失敗數（scenario 當回歸測試用）
        self.expected_code = ""
        self.ready = False
        self.editor_available = True
        self.commit_count = 0
        self.blocked_commits = 0
        self.drill = ""
        self.saved_item = None
        # (text, reading, before code, consumed code)，與生產日誌同樣逐次撤銷。
        self.held: list[tuple[str, str, str, str]] = []
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

    # ---------- 受控提交 ----------
    def _absorb_commit(self, st: EngineState) -> None:
        if st.commit:
            self.blocked_commits += 1
            print("  blocked unsolicited engine commit")
            self._restore(self.expected_code)

    def _restore(self, code: str) -> None:
        self._reset_from(self.engine.send("clear"))
        if code:
            self._reset_from(self.engine.send("type " + code))

    def _commit_completed(self) -> None:
        if self.expected_code or not self.ready or not self.pending or not self.editor_available:
            return
        self.committed += self.pending
        self.commit_count += 1
        if self.apply_learning:
            for text, reading, before, _ in self.held:
                self.sel.bump(text, reading)
                if len(text) >= 2 and reading:
                    self.phrase.learn_pick(before, text, reading)
            if len(self.held) >= 2 and all(p[1] for p in self.held):
                self.phrase.record("".join(p[3] for p in self.held), self.pending,
                                   " ".join(p[1] for p in self.held))
        self.pending = ""
        self.held.clear()
        self.ready = False

    # ---------- 候選條（引擎 + SelectionMemory + PhraseMemory） ----------
    def _engine_items(self) -> list[BarItem]:
        return [BarItem(t, c, i) for i, (t, c) in enumerate(self.state.cands)]

    def _merge_learned(self, ranked: list[BarItem], code: str) -> list[BarItem]:
        now = int(time.time() * 1000)
        phrases = self.phrase.candidates_for(code, now)
        if not phrases:
            return ranked
        score_by_text = {e.text: self.phrase.score(e, now) for e in phrases}
        engine_texts = {it.text for it in ranked}
        n = max(1, len(ranked))
        pairs: list[tuple[BarItem, float]] = []
        for i, it in enumerate(ranked):
            engine_score = 1.0 - i / n
            boost = score_by_text.get(it.text)
            pairs.append((it, max(engine_score, boost) if boost is not None else engine_score))
        for e in phrases:
            if e.text in engine_texts:
                continue
            pairs.append((BarItem(e.text, e.readings, -1), score_by_text[e.text]))
        pairs.sort(key=lambda p: (-p[1], 1 if p[0].index < 0 else 0))
        return [p[0] for p in pairs]

    BAR_WINDOW = 64
    BAR_ITEMS = 12

    def _ordered(self, flat: list[BarItem]) -> list[BarItem]:
        ranked = flat
        if self.apply_learning and self.sel.has_any():
            ranked = sorted(
                flat,
                key=lambda it: (-self.sel.score(it.text, it.comment), it.index),
            )
        ordered = self._merge_learned(ranked, self.state.input) if self.apply_learning else ranked
        seen: set[str] = set()
        out: list[BarItem] = []
        for it in ordered:
            if not it.text or it.text in seen:
                continue
            seen.add(it.text)
            out.append(it)
        return out

    def bar_items(self) -> list[BarItem]:
        """App 候選條：永遠只取前 64 條引擎候選做學習排序+學習詞混排，展示 12 項。"""
        flat = self._engine_items()[: self.BAR_WINDOW]
        items = self._ordered(flat)[: self.BAR_ITEMS]
        return [replace(it, context=(self.state.input, self.state.preedit)) for it in items]

    def panel_items(self) -> list[BarItem]:
        if self.drill:
            items = [BarItem(ch, self.drill, -1, -5) for ch in data().syllable_chars.get(self.drill, ())]
        else:
            items = []
            for row in data().panel(self.unconsumed()):
                segment = row.get("segment", 1)
                for text in row.get("chars" if segment == 0 else "items", ()):
                    items.append(BarItem(text, row["reading"], -1, segment, row.get("rest", "")))
            items += self._ordered(self._engine_items())
        return [replace(it, context=(self.state.input, self.state.preedit)) for it in items]

    def bar(self, n: int) -> None:
        items = self.bar_items()
        if 0 <= n < len(items):
            self._pick_item(items[n])
        else:
            self.failures += 1
            print(f"  ! 候選條沒有第 {n} 項（共 {len(items)}）")

    def _print_bar(self) -> None:
        for i, it in enumerate(self.bar_items()[:16]):
            mark = "合成" if it.index < 0 else f"#{it.index}"
            print(f"    [{i}] {it.text}  ({it.comment}) {mark}")

    def pick(self, text: str) -> bool:
        for it in self.bar_items():
            if it.text == text:
                self._pick_item(it)
                return True
        self.failures += 1
        print(f"  ! 候選條找不到「{text}」")
        return False

    def _pick_item(self, it: BarItem) -> None:
        from panel_probe import selected_input_end

        if it.context != (self.state.input, self.state.preedit) or not self.state.input:
            print("  ignored stale candidate")
            return
        if it.segment == -4:
            self.drill = it.text
            return
        code = self.unconsumed()
        if it.segment == -5:
            consumed = next((i + 1 for i, ch in enumerate(code) if ch != "'" and not ch.isspace()), None)
        elif it.segment == 0 and it.index < 0:
            consumed = len(code) - len(it.rest) if code.endswith(it.rest) else None
        else:
            consumed = selected_input_end(data(), it.comment, code)
        if consumed is None or not 1 <= consumed <= len(code):
            self.failures += 1
            print("  ! unresolved candidate coverage; original code retained")
            return
        while consumed < len(code) and (code[consumed] == "'" or code[consumed].isspace()):
            consumed += 1
        self.held.append((it.text, it.comment, code, code[:consumed]))
        self.pending += it.text
        self.expected_code = code[consumed:]
        self.ready = not self.expected_code
        self.drill = ""
        if self.expected_code:
            self._restore(self.expected_code)
        else:
            if it.index >= 0:
                # 僅最後一次選擇走原生學習；buffer 由本交易消耗，不另行上屏。
                self._reset_from(self.engine.send(f"select {it.index}"))
            self._reset_from(self.engine.send("clear"))
            self._commit_completed()

    # ---------- 面板 ----------
    def left(self, reading: str) -> None:
        self.left_reading = reading
        matches = [it for it in self.panel_items()
                   if reading_syllables(it.comment)[:1] == [reading]]
        print(f"  左列 {reading}: {len(matches)} 項")
        for it in matches[:16]:
            mark = "合成" if it.index < 0 else f"#{it.index}"
            print(f"    {it.text}  ({it.comment}) {mark}")

    def right(self, text: str) -> bool:
        wanted = self.drill or self.left_reading
        for it in self.panel_items():
            if it.text == text and (not wanted or reading_syllables(it.comment)[:1] == [wanted]):
                self._pick_item(it)
                return True
        return self.pick(text)

    # ---------- 退格 / 上屏 ----------
    def back(self) -> None:
        if self.held:
            _, _, before, _ = self.held.pop()
            self.pending = "".join(p[0] for p in self.held)
            self.expected_code = before
            self.ready = False
            self.drill = ""
            self._restore(before)
        else:
            self.expected_code = self.state.input[:-1]
            st = self.engine.send("key 65288")
            self._reset_from(st)
            self._absorb_commit(st)

    def commit(self) -> None:
        if not self.expected_code and self.ready:
            self._commit_completed()
            return
        first = next(iter(self.bar_items()), None)
        if first is not None:
            self._pick_item(first)

    def clear(self) -> None:
        self._reset_from(self.engine.send("clear"))
        self.pending = ""
        self.held.clear()
        self.left_reading = ""
        self.drill = ""
        self.expected_code = ""
        self.ready = False

    # ---------- 記憶持久化 ----------
    def save_prefs(self, prefs_dir: str) -> list[str]:
        os.makedirs(prefs_dir, exist_ok=True)
        written: list[str] = []

        def esc(value: str) -> str:
            return (value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\u0001", "&#1;").replace("\n", "&#10;"))

        phrase_lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
        for code, entries in self.phrase.by_code.items():
            if not entries:
                continue
            body = "\n".join(
                "\u0001".join([e.text, e.readings, str(e.typed), str(e.last_used_at),
                                str(e.shown), str(e.picked)])
                for e in entries
            )
            phrase_lines.append(f'    <string name="{esc(code)}">{esc(body)}</string>')
        phrase_lines.append("</map>")
        with open(os.path.join(prefs_dir, "twelvekey_phrases.xml"), "w", encoding="utf-8") as f:
            f.write("\n".join(phrase_lines) + "\n")
        written.append("twelvekey_phrases.xml")

        sel_lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
        for key, value in self.sel.scores.items():
            sel_lines.append(f'    <int name="{esc(key)}" value="{value}" />')
        sel_lines.append("</map>")
        with open(os.path.join(prefs_dir, "twelvekey_selection.xml"), "w", encoding="utf-8") as f:
            f.write("\n".join(sel_lines) + "\n")
        written.append("twelvekey_selection.xml")
        return written

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
        elif key == "commit_count":
            got = str(self.commit_count)
        elif key == "blocked_commits":
            got = str(self.blocked_commits)
        elif key == "held":
            got = str(len(self.held))
        elif key == "remaining":
            got = self.unconsumed()
        elif key == "panel_has":
            rows = data().panel(self.unconsumed())
            got = want if any(row["reading"] == want for row in rows) else "(missing)"
        elif key == "no_isolated":
            got = "1" if not data().isolated_syllables(self.unconsumed()) else "0"
        elif key == "composing":
            got = "1" if self.state.composing else "0"
        elif key in ("bar0", "bar_first"):
            items = self.bar_items()
            got = items[0].text if items else ""
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
            self.expected_code = self.state.input + arg
            self.ready = False
            st = self.engine.send("type " + arg)
            self._reset_from(st)
            self._absorb_commit(st)
        elif cmd == "bar":
            if arg:
                self.bar(int(arg))
            else:
                self._print_bar()
        elif cmd in ("pick", "sel"):
            self.pick(arg)
        elif cmd == "panel":
            print("  面板字典行: " + ", ".join(row["reading"] for row in data().panel(self.unconsumed())))
        elif cmd == "left":
            self.left(arg)
        elif cmd == "right":
            self.right(arg)
        elif cmd in ("back", "del"):
            self.back()
        elif cmd in ("commit", "space", "enter", "retry"):
            self.commit()
        elif cmd == "editor_available":
            self.editor_available = arg != "0"
        elif cmd == "engine_commit":
            st = self.engine.send("commit")
            self._reset_from(st)
            self._absorb_commit(st)
        elif cmd == "remember":
            self.saved_item = next((it for it in self.bar_items() if it.text == arg), None)
            if self.saved_item is None:
                self.failures += 1
        elif cmd == "pick_saved":
            if self.saved_item is not None:
                self._pick_item(self.saved_item)
            else:
                self.failures += 1
        elif cmd == "clear":
            self.clear()
        elif cmd == "state":
            pass
        elif cmd == "assert":
            self._assert(arg)
        else:
            self.failures += 1
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
    global _DATA_DIR
    ap = argparse.ArgumentParser(description="PC preedit/上屏 全流程模擬")
    ap.add_argument("--exec", dest="inline", help="以分號分隔的命令字串")
    ap.add_argument("--scenario", help="命令檔路徑，一行一命令")
    ap.add_argument("--host-runner", default=HOST_RUNNER)
    ap.add_argument("--data", default=_DATA_DIR, help="引擎与 App 字典索引共用的数据目录")
    ap.add_argument("--prefs-dir", default=os.environ.get(
        "HKRIME_FLOW_PREFS", DEFAULT_PREFS))
    ap.add_argument("--no-learning", action="store_true",
                    help="不載入 PhraseMemory/SelectionMemory（純引擎順序）")
    ap.add_argument("--pull-prefs", metavar="SERIAL",
                    help="先從裝置拉 twelvekey_phrases/selection.xml 到 --prefs-dir")
    ap.add_argument("--no-save", action="store_true",
                    help="跑完不落盤（默認寫回 --prefs-dir，供下一次模擬續用）")
    args = ap.parse_args()
    _DATA_DIR = os.path.abspath(args.data)
    if not os.path.isfile(os.path.join(_DATA_DIR, "hakka.dict.yaml")):
        ap.error(f"找不到 {_DATA_DIR}/hakka.dict.yaml")
    data()

    if args.pull_prefs:
        os.makedirs(args.prefs_dir, exist_ok=True)
        for name in ("twelvekey_phrases.xml", "twelvekey_selection.xml"):
            with open(os.path.join(args.prefs_dir, name), "w", encoding="utf-8") as f:
                r = subprocess.run(
                    ["adb", "-s", args.pull_prefs, "shell",
                     f"run-as com.hazukieq.hkrime cat shared_prefs/{name}"],
                    capture_output=True, text=True)
                if r.returncode != 0 or not r.stdout.strip():
                    print(f"  ! 拉取 {name} 失敗：{r.stderr.strip()[:120]}")
                else:
                    f.write(r.stdout)
                    print(f"  已拉取 {name} → {args.prefs_dir}")

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
    sim = Sim(engine, args.prefs_dir, not args.no_learning)
    learn = "off" if args.no_learning else "on"
    print(f"== 資料源 {_DATA_DIR}；引擎就緒（host-test --script）；學習記憶 {learn}")
    if not args.no_learning:
        print(f"   PhraseMemory code 數={len(sim.phrase.by_code)}，"
              f"SelectionMemory 條目={len(sim.sel.scores)}（{args.prefs_dir}）")
    try:
        for line in lines:
            sim.run(line)
    finally:
        engine.close()

    print(f"\n== 最終上屏: {sim.committed!r}")
    if not args.no_save:
        names = sim.save_prefs(args.prefs_dir)
        print(f"== 已保存本地學習記憶: {', '.join(names)} → {args.prefs_dir}")
    if sim.failures:
        print(f"== 斷言失敗 {sim.failures} 條")
        return 1
    print("== 斷言全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
