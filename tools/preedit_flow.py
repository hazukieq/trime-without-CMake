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
import math
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
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
    index: int  # 引擎候選下標；-1 表示 App 學習詞（合成候選）


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

    def __init__(self, engine: Engine, prefs_dir: str | None = None,
                 apply_learning: bool = True):
        self.engine = engine
        self.apply_learning = apply_learning
        self.phrase = PhraseMemory(prefs_dir) if apply_learning else PhraseMemory(None)
        self.sel = SelectionMemory(prefs_dir) if apply_learning else SelectionMemory(None)
        self.state = EngineState()
        self.pending = ""            # pendingPick：已選但未上屏的詞（preedit 前綴）
        self.held: list[tuple[str, str]] = []  # (text, 選中前完整碼)
        self.committed = ""          # 目前已上屏文字
        self.left_reading = ""       # 面板左列目前選中的段音節
        self.failures = 0            # assert 失敗數（scenario 當回歸測試用）
        # 逐段拼出的整串（App maybeRecordPhrase 的口徑）
        self.picked_code = ""
        self.picked_pieces = 0
        self.picked_text = ""
        self.picked_readings: list[str] = []
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
    def _note_picked(self, text: str, reading: str, code: str) -> None:
        if not text:
            return
        if not self.picked_code:
            self.picked_code = code
        self.picked_pieces += 1
        self.picked_text += text
        self.picked_readings.append(reading)
        if not reading:
            self.picked_readings = [""]  # 缺讀音就不記整串

    def _maybe_record_phrase(self) -> None:
        if not self.apply_learning:
            self._reset_picked()
            return
        readings = " ".join(r for r in self.picked_readings if r)
        complete = bool(self.picked_readings) and all(self.picked_readings)
        if (len(self.picked_text) >= 2 and self.picked_pieces >= 2
                and self.picked_code and complete):
            self.phrase.record(self.picked_code, self.picked_text, readings)
        self._reset_picked()

    def _reset_picked(self) -> None:
        self.picked_code = ""
        self.picked_pieces = 0
        self.picked_text = ""
        self.picked_readings = []

    def _absorb_commit(self, st: EngineState) -> None:
        if st.commit:
            self.committed += self.pending + st.commit
            self.pending = ""
            self.held.clear()
            self._maybe_record_phrase()
        elif not st.composing and self.picked_pieces:
            self._maybe_record_phrase()

    # ---------- 選中 ----------
    def _decode(self, code: str) -> list[str]:
        try:
            return data().decode(code) or []
        except Exception:
            return [code] if code else []

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
        return self._ordered(flat)[: self.BAR_ITEMS]

    def panel_items(self) -> list[BarItem]:
        """展開面板：全量引擎候選（host-test 最多印 600）再排序，供左列/右列查找。"""
        return self._ordered(self._engine_items())

    def bar(self, n: int) -> None:
        items = self.bar_items()
        if 0 <= n < len(items):
            self._pick_item(items[n])
        else:
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
        print(f"  ! 候選條找不到「{text}」")
        return False

    def _pick_item(self, it: BarItem) -> None:
        if it.index < 0:
            # App 合成詞（學習詞）：直接併入 pending 並上屏，不經引擎 select。
            self.pending += it.text
            self.committed += self.pending
            self.pending = ""
            self.held.clear()
            if self.apply_learning:
                # App commitPhrase：合成詞已存在時只 bumpPicked。
                if any(e.text == it.text for e in
                       self.phrase.by_code.get(PhraseMemory.normalize(self.state.input), [])):
                    self.phrase.bump_picked(self.state.input, it.text)
                else:
                    self.phrase.learn_pick(self.state.input, it.text, it.comment)
                self.sel.bump(it.text, it.comment)
            st = self.engine.send("clear")
            self._reset_from(st)
            return

        tokens = reading_syllables(it.comment)
        remaining_code = self.unconsumed()
        pieces = self._decode(remaining_code)
        # App：只在「多音節整詞」被部分覆蓋時 hold；單音節/單字走引擎原生部分選中。
        if len(tokens) >= 2 and pieces and len(pieces) > len(tokens):
            covered = "".join(pieces[: len(tokens)])
            rest = remaining_code[len(covered):]
            prefix = self.selected_prefix()
            picked = prefix + it.text
            self.pending += picked
            self.held.append((picked, self.state.input))
            if self.apply_learning:
                # App holdPartialPick：engineIndex>=0 與 App 詞都記 learnPick（單字不記）。
                if len(it.text) >= 2:
                    self.phrase.learn_pick(self.state.input, it.text, it.comment)
                    self.sel.bump(it.text, it.comment)
            self._note_picked(it.text, it.comment, self.state.input)
            st = self.engine.send("clear")
            self._reset_from(st)
            if rest:
                st = self.engine.send("type " + rest)
                self._reset_from(st)
            else:
                self.committed += self.pending
                self.pending = ""
                self.held.clear()
            self._absorb_commit(st)
        else:
            if self.apply_learning:
                # App：selectCandidate 前先 bump 排序記憶；多字詞同時記 PhraseMemory.learnPick。
                self.sel.bump(it.text, it.comment)
                if len(it.text) >= 2:
                    self.phrase.learn_pick(self.state.input, it.text, it.comment)
            self._note_picked(it.text, it.comment, self.state.input)
            st = self.engine.send(f"select {it.index}")
            self._reset_from(st)
            self._absorb_commit(st)

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
        wanted = self.left_reading
        for it in self.panel_items():
            if it.text == text and (not wanted or reading_syllables(it.comment)[:1] == [wanted]):
                self._pick_item(it)
                return True
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
        self._reset_picked()

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


def push_prefs(serial: str, prefs_dir: str) -> None:
    """把本地保存的記憶 XML 寫回裝置（需 debug 包 run-as；會 force-stop App）。"""
    pkg = "com.hazukieq.hkrime"
    subprocess.run(["adb", "-s", serial, "shell", "am", "force-stop", pkg],
                   capture_output=True, text=True)
    for name in ("twelvekey_phrases.xml", "twelvekey_selection.xml"):
        path = os.path.join(prefs_dir, name)
        if not os.path.isfile(path):
            continue
        data = open(path, encoding="utf-8").read()
        r = subprocess.run(
            ["adb", "-s", serial, "shell",
             f"run-as {pkg} sh -c 'cat > shared_prefs/{name}'"],
            input=data, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"  ! 推送 {name} 失敗：{(r.stderr or r.stdout).strip()[:160]}")
        else:
            print(f"  已推送 {name} → {serial}:/data/data/{pkg}/shared_prefs/")
    # force-stop 會切走預設 IME，重設回本 App。
    subprocess.run(["adb", "-s", serial, "shell", "ime", "set",
                    f"{pkg}/.TrimeImeService"], capture_output=True, text=True)
    print("  已重設輸入法；如 App 仍在舊進程，請重開鍵盤。")


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
    ap.add_argument("--prefs-dir", default=os.environ.get(
        "HKRIME_FLOW_PREFS", "/tmp/hkrime-flow-prefs"))
    ap.add_argument("--no-learning", action="store_true",
                    help="不載入 PhraseMemory/SelectionMemory（純引擎順序）")
    ap.add_argument("--pull-prefs", metavar="SERIAL",
                    help="先從裝置拉 twelvekey_phrases/selection.xml 到 --prefs-dir")
    ap.add_argument("--save-prefs", action="store_true",
                    help="跑完把學習記憶寫回 --prefs-dir（xml）")
    ap.add_argument("--push-prefs", metavar="SERIAL",
                    help="跑完寫回 --prefs-dir，再推回裝置（debug run-as）")
    args = ap.parse_args()

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
    print(f"== 資料源與引擎就緒（host-test --script）；學習記憶 {learn}")
    if not args.no_learning:
        print(f"   PhraseMemory code 數={len(sim.phrase.by_code)}，"
              f"SelectionMemory 條目={len(sim.sel.scores)}（{args.prefs_dir}）")
    try:
        for line in lines:
            sim.run(line)
    finally:
        engine.close()

    print(f"\n== 最終上屏: {sim.committed!r}")
    if args.save_prefs or args.push_prefs:
        names = sim.save_prefs(args.prefs_dir)
        print(f"== 已保存學習記憶: {', '.join(names)} → {args.prefs_dir}")
    if args.push_prefs:
        push_prefs(args.push_prefs, args.prefs_dir)
    if sim.failures:
        print(f"== 斷言失敗 {sim.failures} 條")
        return 1
    print("== 斷言全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
