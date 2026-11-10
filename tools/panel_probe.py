#!/usr/bin/env python3
"""12 键展开面板的「左列音节 / 单字 / 联想词」离线复现脚本。

用途：**不装机、不开面板**，用同一份真实词库把展开面板左列算出来，方便对着看、对着测。
它镜像的是 App 侧 Kotlin 实现：

- `core/TwelveKeyPreedit.kt`：`codeOf` / `decode` / `splitHeads` / `syllablesForSegment` /
  `charsForSyllable` / 联想索引 / 整串码索引；
- `core/twelvekey/TwelveKeySnapshotBuilder.kt`：`segmentGroupsFor`（切法/配额/仅精确音节的规则）、
  联想行、多音节词行、`collapsePrefixRows`、分档排序。

> 脚本是**参考实现**，不是生产代码；行为改了就同步改这里，并且把 §15.3 的样例输出贴进
> `docs/17-candidate-panel.md`，保证「文档 ↔ 脚本 ↔ App」三方一致。

用法::

    # 1) 准备数据（默认读 /tmp/hkrime-real-data；也可 --data 指定）
    python3 tools/panel_probe.py pull          # 从真机 adb 拉一份到 /tmp/hkrime-real-data

    # 2) 左列音节组（最常用）：给码串**或键名**，按 App 同一套规则列读音
    python3 tools/panel_probe.py syllables 4i
    python3 tools/panel_probe.py syllables "mno ghi"          # 等价于 4i
    python3 tools/panel_probe.py prefix "kl ghi mno abc ps"   # 等价于 5i4a7
    python3 tools/panel_probe.py syllables ea
    python3 tools/panel_probe.py syllables 74ii
    python3 tools/panel_probe.py syllables 5i4a57

    # 3) 某个音节能出哪些单字（默认全部，--limit 截断）
    python3 tools/panel_probe.py chars mi
    python3 tools/panel_probe.py chars ji --limit 20

    # 4) 联想词（两音节，按「第一音节 + 第二音节首字母」）
    python3 tools/panel_probe.py assoc li n
    #    整串码前缀联想（多音节词，如 Linux = li nak si）
    python3 tools/panel_probe.py prefix 5i4a57

    # 5) 整个左列（含联想行/多音节词行/去重/收敛/排序），就是面板最终顺序
    python3 tools/panel_probe.py panel 5i4a57
    python3 tools/panel_probe.py panel 5we

数据文件（与 App 的 `core/RimeDictSource.kt` 一致：共享目录优先，这里就是 --data 目录）：

- `hakka.dict.yaml`：音节表 + 单字表（`字<TAB>读音`）
- 其它 `*.dict.yaml`：词表（`词<TAB>读音[<TAB>权重]`），只取第 2 列
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from collections import OrderedDict, defaultdict

DEFAULT_DATA = "/tmp/hkrime-real-data"

# 12 键：键码 → 该键覆盖的字母（与 TwelveKeyPreedit.keyLetters 一致）
KEY_LETTERS = {
    "a": "abc",
    "e": "def",
    "i": "ghi",
    "5": "kl",
    "4": "mn",
    "7": "ps",
    "9": "qt",
    "3": "vur",
    "8": "zj",
    "1": "ng",
    "w": "th",
    "y": "nz",
}

# App 侧配额（TwelveKeyTuning）
SEGMENT_SYLLABLE_LIMIT = 16
SEGMENT_SYLLABLE_LIMIT_RICH = 24
RICH_SYLLABLE_THRESHOLD = SEGMENT_SYLLABLE_LIMIT
SECONDARY_SPLIT_DIVISOR = 2
MAX_SYLLABLES_TOTAL = SEGMENT_SYLLABLE_LIMIT
ASSOC_WORDS = 6
ASSOC_MAX_ROWS = 4
ASSOC_PREFIX_READINGS = 4


# ---------------------------------------------------------------- 基础工具


def code_of(syllable: str) -> str:
    """音节拼写 → 12 键码串（镜像 TwelveKeyPreedit.codeOf）。"""
    s = syllable
    if s.startswith("nz"):
        s = "y" + s[2:]
    elif s.startswith("th"):
        s = "w" + s[2:]
    elif s.startswith("ng"):
        s = "1" + s[2:]
    s = re.sub(r"(\w)o", r"\g<1>4", s)
    s = re.sub(r"(\w)u", r"\g<1>3", s)
    xlit = {
        "a": "a", "b": "a", "c": "a",
        "d": "e", "e": "e", "f": "e",
        "g": "i", "h": "i", "i": "i",
        "k": "5", "l": "5",
        "m": "4", "n": "4",
        "p": "7", "s": "7",
        "v": "3", "r": "3",
        "z": "8", "j": "8",
        "q": "9", "t": "9",
    }
    return "".join(xlit.get(c, c) for c in s)


class Data:
    """词库索引（音节表 / 单字 / 联想 / 整串码）。"""

    def __init__(self, data_dir: str):
        self.dir = data_dir
        self.syllable_freq: dict[str, int] = {}
        self.syllable_chars: dict[str, list[str]] = defaultdict(list)
        self.code_to_syllables: dict[str, list[str]] = defaultdict(list)
        # 联想（两音节词）：第一音节 → 第二音节 → 词表（按热度降序）
        self.assoc: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
        # 整串码（只收词表自带读音的条目）：code → [(tokens, word)]
        self.word_codes: list[tuple[list[str], str, str]] = []

        main = os.path.join(data_dir, "hakka.dict.yaml")
        if not os.path.isfile(main):
            sys.exit(f"[panel-probe] 找不到 {main}；先跑 `panel_probe.py pull` 或 --data 指定目录")
        self._load_dict(main)
        for name in sorted(os.listdir(data_dir)):
            if not name.endswith(".dict.yaml") or name == "hakka.dict.yaml":
                continue
            self._load_words(os.path.join(data_dir, name))
        self._sort_assoc()

    # -- 载入 ------------------------------------------------------------

    def _load_dict(self, path: str) -> None:
        for line in _iter_body(path):
            if "\t" not in line:
                continue
            ch, rest = line.split("\t", 1)
            ch = ch.strip()
            syl = rest.split("\t")[0].strip()
            if not syl or not syl.isalpha():
                continue
            self.syllable_freq[syl] = self.syllable_freq.get(syl, 0) + 1
            if len(ch) == 1:
                chars = self.syllable_chars[syl]
                if ch not in chars:
                    chars.append(ch)
        for syl in self.syllable_freq:
            self.code_to_syllables[code_of(syl)].append(syl)

    def _load_words(self, path: str) -> None:
        for line in _iter_body(path):
            if "\t" not in line:
                continue
            word, rest = line.split("\t", 1)
            word = word.strip()
            # 词表第 2 列是读音，第 3 列起是权重/频次 —— 只取第 2 列
            reading = rest.split("\t")[0].strip()
            tokens = [t for t in reading.split(" ") if t and t.isalpha()]
            if not word or len(tokens) < 2:
                continue
            # 联想索引：只收两音节词（与 App 的 assocWords 一致）
            if len(tokens) >= 2:
                bucket = self.assoc[tokens[0]][tokens[1]]
                if word not in bucket:
                    bucket.append(word)
            code = "".join(code_of(t) for t in tokens)
            if code:
                self.word_codes.append((tokens, word, code))

    def _sort_assoc(self) -> None:
        for by_second in self.assoc.values():
            for words in by_second.values():
                words.sort()

    # -- 查询 ------------------------------------------------------------

    def decode(self, codes: str) -> list[str] | None:
        """最少音节切法（镜像 TwelveKeyPreedit.decode 的 DP 结果形态）。"""
        n = len(codes)
        best: list[list[str] | None] = [None] * (n + 1)
        best[0] = []
        for i in range(1, n + 1):
            for j in range(max(0, i - 6), i):
                if best[j] is None:
                    continue
                head = codes[j:i]
                if head in self.code_to_syllables:
                    cand = best[j] + [head]
                    if best[i] is None or len(cand) < len(best[i]):
                        best[i] = cand
        return best[n]

    def is_single_syllable(self, codes: str) -> bool:
        return codes in self.code_to_syllables

    def split_heads(self, codes: str, limit: int = 2) -> list[tuple[str, str]]:
        """镜像 TwelveKeyPreedit.splitHeads：由长到短找「head 恰为一个完整音节」的切法。"""
        if len(codes) < 2:
            return []
        out: list[tuple[str, str]] = []
        for k in range(min(len(codes) - 1, 5), 0, -1):
            head = codes[:k]
            if head in self.code_to_syllables:
                out.append((head, codes[k:]))
                if len(out) >= limit:
                    break
        if not out:
            out.append((codes[:1], codes[1:]))
        return out

    def syllable_count_for_code(self, code: str) -> int:
        n = 0
        for sc, syllables in self.code_to_syllables.items():
            if sc == code or sc.startswith(code):
                n += len(syllables)
        return n

    def syllables_for_segment(
        self, code: str, limit: int = 12, exact_only: bool = False
    ) -> list[str]:
        """镜像 TwelveKeyPreedit.syllablesForSegment（exact 优先 + 两级桶轮转前缀展开）。"""
        if not code:
            return []
        exact: list[str] = []
        prefix: list[tuple[str, str]] = []
        for sc, syllables in self.code_to_syllables.items():
            if sc == code:
                exact.extend(syllables)
            elif not exact_only and len(sc) > len(code) and sc.startswith(code):
                for syl in syllables:
                    prefix.append((sc[len(code)], syl))
        exact_sorted = sorted(exact, key=lambda s: -self.syllable_freq.get(s, 0))

        outer: OrderedDict[str, OrderedDict[str, list[str]]] = OrderedDict()
        for next_code, syl in prefix:
            outer.setdefault(next_code, OrderedDict()).setdefault(syl[0], []).append(syl)
        for inner in outer.values():
            for key in inner:
                inner[key].sort(key=lambda s: -self.syllable_freq.get(s, 0))
        order = [
            inner
            for _, inner in sorted(
                outer.items(),
                key=lambda kv: -max(self.syllable_freq.get(v[0], 0) for v in kv[1].values()),
            )
        ]

        distinct_exact = list(dict.fromkeys(exact_sorted))
        total_cap = max(limit, min(limit * len(order), MAX_SYLLABLES_TOTAL))
        diversified: list[str] = []
        per_initial: dict[str, int] = defaultdict(int)
        initial_count = max(1, sum(len(inner) for inner in order))
        per_initial_cap = max(limit, total_cap // initial_count)

        round_idx = 0
        while len(diversified) + len(distinct_exact) < total_cap and any(
            any(len(lst) > round_idx for lst in inner.values()) for inner in order
        ):
            for inner in order:
                for initial, lst in inner.items():
                    if (
                        round_idx < len(lst)
                        and per_initial[initial] < per_initial_cap
                        and len(diversified) + len(distinct_exact) < total_cap
                    ):
                        diversified.append(lst[round_idx])
                        per_initial[initial] += 1
            round_idx += 1

        tail = sorted(dict.fromkeys(diversified))
        result = list(dict.fromkeys(distinct_exact + tail))
        return result[: max(limit, total_cap)]

    # -- 左列 ------------------------------------------------------------

    def segment_groups(self, codes: str) -> list[dict]:
        """镜像 TwelveKeySnapshotBuilder.segmentGroupsFor 的音节部分（不含引擎组）。"""
        codes = codes.replace("'", "")
        if not codes:
            return []
        single = [(codes, "")] if self.is_single_syllable(codes) else []
        splits = [(h, r) for h, r in single]
        for h, r in self.split_heads(codes):
            if h not in [x[0] for x in splits]:
                splits.append((h, r))
        if not splits:
            return []

        groups: list[dict] = []
        seen: set[str] = set()
        for index, (head, rest) in enumerate(splits):
            pool = self.syllable_count_for_code(head)
            base = SEGMENT_SYLLABLE_LIMIT_RICH if pool >= RICH_SYLLABLE_THRESHOLD else SEGMENT_SYLLABLE_LIMIT
            quota = base if index == 0 else base // SECONDARY_SPLIT_DIVISOR
            head_complete = self.is_single_syllable(head)
            syllables = self.syllables_for_segment(head, quota, head_complete)
            if not syllables:
                syllables = self.syllables_for_segment(head, quota, False)
            source = "exact" if head_complete else "prefix"
            for syl in syllables:
                if syl in seen:
                    continue
                seen.add(syl)
                chars = self.syllable_chars.get(syl, [])
                if not chars:
                    continue
                groups.append(
                    {
                        "reading": syl,
                        "syllables": [syl],
                        "segment": 0,
                        "rest": rest,
                        "chars": chars,
                        "source": source,
                        "split": index,
                    }
                )
            # 联想行进位（两音节，按 rest 的首字母）
            for syllable in syllables[:4]:
                for reading, words in self.assoc_rows(syllable, rest):
                    if reading in seen:
                        continue
                    seen.add(reading)
                    groups.append(
                        {
                            "reading": reading,
                            "syllables": reading.split(" "),
                            "segment": 1,
                            "items": words,
                            "source": "assoc",
                        }
                    )
        return groups

    def initials_for_prefix(self, code: str, limit: int = 2) -> list[str]:
        """镜像 TwelveKeyPreedit.initialsForPrefix：码前缀能覆盖的音节，按首字母加权取前 N。"""
        if not code:
            return []
        weight: dict[str, int] = {}
        for sc, syllables in self.code_to_syllables.items():
            if len(sc) < len(code) or not sc.startswith(code):
                continue
            for syl in syllables:
                initial = syl[0]
                weight[initial] = weight.get(initial, 0) + self.syllable_freq.get(syl, 0)
        return [k for k, _ in sorted(weight.items(), key=lambda kv: -kv[1])][:limit]

    def assoc_rows(self, first: str, rest: str) -> list[tuple[str, list[str]]]:
        """联想行：第一音节确定、第二音节由 rest 的码前缀决定（镜像 associatedBySegmentCode）。"""
        rows: list[tuple[str, list[str]]] = []
        initials = set(self.initials_for_prefix(rest, 2)) if rest else set()
        by_second = self.assoc.get(first)
        if not by_second:
            return rows
        for second, words in by_second.items():
            if not second:
                continue
            if initials and second[0] not in initials:
                continue
            rows.append((f"{first} {second}", words[:ASSOC_WORDS]))
        rows.sort(key=lambda x: x[0])
        return rows[:ASSOC_MAX_ROWS]

    def prefix_word_rows(self, codes: str) -> list[dict]:
        """整串码前缀联想（多音节词），镜像 wordsByCodePrefix。"""
        prefix = codes.replace("'", "")
        rows: OrderedDict[str, list[str]] = OrderedDict()
        for tokens, word, code in self.word_codes:
            if not code.startswith(prefix):
                continue
            rows.setdefault(" ".join(tokens), []).append(word)
        if not rows:
            return []
        picked = sorted(rows.items(), key=lambda kv: -len(kv[1]))[:ASSOC_PREFIX_READINGS]
        out = []
        for reading, words in sorted(picked, key=lambda kv: kv[0]):
            out.append(
                {
                    "reading": reading,
                    "syllables": reading.split(" "),
                    "segment": 1,
                    "items": sorted(words)[:ASSOC_WORDS],
                    "source": "prefix",
                }
            )
        return out

    def collapse(self, groups: list[dict]) -> list[dict]:
        """镜像 collapsePrefixRows：多音节行按「最长已有前缀读音」收敛。"""

        def tokens(g: dict) -> list[str]:
            return [t for t in g["reading"].split(" ") if t]

        out: list[dict] = [g for g in groups if len(tokens(g)) < 2]
        for row in sorted(
            [g for g in groups if len(tokens(g)) >= 2], key=lambda g: len(tokens(g))
        ):
            row_tokens = tokens(row)
            target = -1
            best = 0
            for i, cand in enumerate(out):
                cand_tokens = tokens(cand)
                if len(cand_tokens) < 2 or len(cand_tokens) >= len(row_tokens):
                    continue
                if row_tokens[: len(cand_tokens)] == cand_tokens and len(cand_tokens) > best:
                    best = len(cand_tokens)
                    target = i
            if target < 0:
                out.append(row)
                continue
            existing = out[target]
            texts = {t.lower() for t in existing.get("items", [])}
            extra = [w for w in row.get("items", []) if w.lower() not in texts]
            if extra:
                out[target] = {**existing, "items": existing.get("items", []) + extra}
        return out

    def panel(self, codes: str) -> list[dict]:
        groups = self.segment_groups(codes)
        groups += self.prefix_word_rows(codes.replace("'", ""))
        groups = self.collapse(groups)

        def tier(g: dict) -> int:
            n = len([t for t in g["reading"].split(" ") if t])
            return 2 if n == 0 else (0 if n == 1 else 1)

        return sorted(groups, key=lambda g: (tier(g), g["reading"].lower()))


# 键名（键面标签）→ 码：允许直接写键盘上的键名，如 `"kl ghi mno abc ps"`
KEY_NAME_TO_CODE = {
    "nz": "y", "qt": "9", "abc": "a", "def": "e",
    "ng": "1", "ghi": "i", "kl": "5", "mno": "4",
    "th": "w", "ps": "7", "vur": "3", "zj": "8",
}

CODE_CHARS = set("1234567890aeiwy5")


def parse_code(text: str) -> str:
    """把用户输入解析成 12 键码串。

    支持三种写法（可混用，用空格/`+`/`'` 分隔）::

        "5i4a57"              # 直接写码
        "kl ghi mno abc ps"   # 写键面（kl=5, ghi=i, mno=4, abc=a, ps=7 → 5i4a7）
        "kl ghi mno abc ps 7i"# 混写

    键面按 12 键标签识别（`nz/qt/abc/def/ng/ghi/kl/mno/th/ps/vur/zj`）。
    """
    raw = text.strip()
    if not raw:
        return ""
    tokens = [t for t in re.split(r"[\s'+]+", raw.lower()) if t]
    out: list[str] = []
    for token in tokens:
        if token in KEY_NAME_TO_CODE:
            out.append(KEY_NAME_TO_CODE[token])
            continue
        # 允许「键名连写」：abc→a 之类已在上面处理；其余按码字符逐字收
        for ch in token:
            if ch not in CODE_CHARS:
                sys.exit(
                    f"[panel-probe] 无法识别的码/键名: {ch!r}（可用键名："
                    + "/".join(KEY_NAME_TO_CODE)
                    + "，或直接写码如 5i4a57）"
                )
            out.append(ch)
    return "".join(out)


def _iter_body(path: str):
    """只读 YAML 本體（跳過 `...` 之前的頭），與 RimeDictSource.body 一致。"""
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    start = 0
    for i, line in enumerate(lines):
        if line.strip() == "...":
            start = i + 1
            break
    for line in lines[start:]:
        line = line.rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        yield line


# ---------------------------------------------------------------- 子命令


def cmd_pull(args) -> None:
    out = args.data
    os.makedirs(out, exist_ok=True)
    files = subprocess.run(
        ["adb", "shell", "ls", "/sdcard/hkrime/"],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.split()
    wanted = [f for f in files if f.endswith(".dict.yaml")]
    for name in wanted:
        subprocess.run(
            ["adb", "pull", f"/sdcard/hkrime/{name}", os.path.join(out, name)],
            check=False,
        )
    print(f"[panel-probe] pulled {len(wanted)} dict files → {out}")


def cmd_syllables(args, data: Data) -> None:
    args.codes = parse_code(args.codes)
    groups = data.segment_groups(args.codes)
    print(f"# 码串 {args.codes!r} → 左列音节组 {len(groups)} 个")
    for g in groups:
        if g["segment"] != 0:
            continue
        print(
            f"  {g['reading']:<8} 字 {len(g['chars']):>4}  "
            f"[{g['source']}, 切法{g['split']}, rest={g['rest']!r}]"
        )


def cmd_chars(args, data: Data) -> None:
    chars = data.syllable_chars.get(args.syllable, [])
    if not chars:
        sys.exit(f"[panel-probe] 词库里没有音节 {args.syllable!r}")
    shown = chars if args.limit <= 0 else chars[: args.limit]
    print(f"# 音节 {args.syllable!r} 共 {len(chars)} 字：")
    print("".join(shown))


def cmd_assoc(args, data: Data) -> None:
    rows = data.assoc.get(args.first, {})
    picked = [
        (f"{args.first} {second}", words[:ASSOC_WORDS])
        for second, words in rows.items()
        if second and second[0] == args.initial
    ]
    picked.sort(key=lambda x: x[0])
    print(f"# 联想 {args.first!r} + 首字母 {args.initial!r} → {len(picked)} 行")
    for reading, words in picked:
        print(f"  {reading:<16} {' '.join(words)}")


def cmd_prefix(args, data: Data) -> None:
    args.codes = parse_code(args.codes)
    rows = data.prefix_word_rows(args.codes)
    print(f"# 整串码前缀 {args.codes!r} → 多音节词行 {len(rows)} 行")
    for row in rows:
        print(f"  {row['reading']:<20} {' '.join(row['items'])}")


def cmd_panel(args, data: Data) -> None:
    args.codes = parse_code(args.codes)
    groups = data.panel(args.codes)
    print(f"# 码串 {args.codes!r} → 面板左列（不含「推荐」）共 {len(groups)} 行")
    for g in groups:
        kind = {0: "单字", 1: "联想/词"}.get(g["segment"], "?")
        if g["segment"] == 0:
            preview = "".join(g["chars"][:6])
            print(f"  {g['reading']:<16} {kind} {len(g['chars']):>4} 字  {preview}")
        else:
            preview = " ".join(g.get("items", [])[:4])
            print(f"  {g['reading']:<16} {kind}  {preview}")


def cmd_selftest(args, data: Data) -> None:
    """把文档里的关键样例跑一遍，任何一条不符就退出码 1（可放进 CI / 本地回归）。"""

    def expect(cond: bool, message: str) -> None:
        print(("  ok   " if cond else "  FAIL ") + message)
        if not cond:
            failures.append(message)

    failures: list[str] = []

    # 1) 段码恰好是完整音节：4i → 只该有 mi/mi? 精确音节（mi, mh, ni, nh）
    seg0 = [g["reading"] for g in data.segment_groups("4i") if g["segment"] == 0 and g["split"] == 0]
    expect(set(seg0) == {"mi", "mh", "ni", "nh"}, f"4i 首切法精确音节 = {sorted(seg0)}")

    # 2) ea → da/fa（仅精确音节）
    seg0 = [g["reading"] for g in data.segment_groups("ea") if g["segment"] == 0 and g["split"] == 0]
    expect(set(seg0) == {"da", "fa"}, f"ea 首切法精确音节 = {sorted(seg0)}")

    # 3) 74ii：首段 poi/soi，第二段 po/so
    panel = [g["reading"] for g in data.panel("74ii")]
    expect({"poi", "soi"} <= set(panel), f"74ii 含 poi/soi：{panel}")
    expect({"po", "so"} <= set(panel), f"74ii 含 po/so：{panel}")

    # 3.5) 键名写法（键面标签）要能和码串等价
    expected = {"mno ghi": "4i", "kl ghi mno abc kl ps": "5i4a57", "5i4a57": "5i4a57"}
    for text, code in expected.items():
        got = parse_code(text)
        expect(got == code, f"parse_code({text!r}) = {got!r}（期望 {code!r}）")
    ok = any(r["reading"] == "li nak si" for r in data.prefix_word_rows(parse_code("kl ghi mno abc kl ps")))
    expect(ok, "键名写法 kl ghi mno abc kl ps 也能查到 li nak si")

    # 4) 整串码前缀：5i4a57 → li nak si → Linux
    rows = data.prefix_word_rows("5i4a57")
    ok = any(r["reading"] == "li nak si" and any(w.lower() == "linux" for w in r["items"]) for r in rows)
    expect(ok, f"5i4a57 的前缀词行含 li nak si→Linux：{[r['reading'] for r in rows]}")

    # 5) 联想：li + 4a5（= nak）→ 至少一行 li nak（含 Linux）
    assoc = data.assoc_rows("li", "4a5")
    expect(
        any(r[0] == "li nak" and any(w.lower() == "linux" for w in r[1]) for r in assoc),
        f"li + nak 联想行：{[(r[0], r[1][:3]) for r in assoc]}",
    )

    # 6) 单字：ji 全部字（>>100，验证「列全部」而不是 60 字截断）
    expect(len(data.syllable_chars.get("ji", [])) > 100, f"ji 字数 = {len(data.syllable_chars.get('ji', []))}")

    print(f"[selftest] {len(failures)} 条失败")
    if failures:
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description="12 键展开面板离线复现")
    parser.add_argument("--data", default=DEFAULT_DATA, help=f"词库目录（默认 {DEFAULT_DATA}）")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_pull = sub.add_parser("pull", help="从真机拉词库到 --data 目录")
    p_pull.set_defaults(func=None)

    p_syl = sub.add_parser("syllables", help="码串 → 左列音节组")
    p_syl.add_argument("codes")
    p_syl.set_defaults(func=cmd_syllables)

    p_chars = sub.add_parser("chars", help="音节 → 单字")
    p_chars.add_argument("syllable")
    p_chars.add_argument("--limit", type=int, default=0, help="0 = 全部")
    p_chars.set_defaults(func=cmd_chars)

    p_assoc = sub.add_parser("assoc", help="联想词（两音节）")
    p_assoc.add_argument("first")
    p_assoc.add_argument("initial")
    p_assoc.set_defaults(func=cmd_assoc)

    p_prefix = sub.add_parser("prefix", help="整串码前缀联想（多音节词）")
    p_prefix.add_argument("codes")
    p_prefix.set_defaults(func=cmd_prefix)

    p_panel = sub.add_parser("panel", help="整个左列（含联想/词行/收敛/排序）")
    p_panel.add_argument("codes")
    p_panel.set_defaults(func=cmd_panel)

    p_self = sub.add_parser("selftest", help="跑文档样例自检（CI 可用）")
    p_self.set_defaults(func=cmd_selftest)

    args = parser.parse_args()
    if args.cmd == "pull":
        cmd_pull(args)
        return
    data = Data(args.data)
    args.func(args, data)


if __name__ == "__main__":
    main()
