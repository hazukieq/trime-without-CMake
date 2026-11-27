#!/usr/bin/env python3
"""十二鍵面板規則的 Python 參考實現，與 App 同步維護。

讀取 --data 指定目錄（默認 /tmp/hkrime-real-data），只使用主詞典及其 import 表。
syllables/chars/assoc/prefix/panel 用於診斷，json 輸出六源與 merged 供 golden 對拍，
preedit 復現讀音與剩餘碼，selftest 驗證規則；build-syllables 生成測試用標準音節集。
詳見 docs/17-candidate-panel.md 與 docs/19-panel-maintenance.md。
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
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
# 聯想行最多 20 詞，不足時完整展示。
ASSOC_WORDS = 20
ASSOC_MAX_ROWS = 4
# 分桶輪轉後每輪實際取幾行：桶數可以很多（(g,b)/(g,c)/(h,b)/(h,c)…），
# 4 行只夠 4 個桶露頭；×3 讓更多桶有代表。與 App `ASSOC_BUCKET_ROW_BUDGET` 同值。
ASSOC_BUCKET_ROW_BUDGET = ASSOC_MAX_ROWS * 3
ASSOC_PREFIX_READINGS = 4

# 首字母組合參數與 App TwelveKeyTuning 保持一致。
INITIAL_COMBO_MAX_ROWS = 8
# 每個首字母組合**每輪**先出幾行；現在值 1（第一輪每組合保 1 行，第二輪再補第 2 行）。
# 調大 → 少數高頻組合壟斷行數預算；調小到 0 → 不生成首字母組合行。
INITIAL_COMBO_ROWS_PER_PATTERN = 1
# 兩類模式合計上限 64；是否超額取決於實際組合數而非固定碼長。
INITIAL_COMBO_PATTERN_LIMIT = 64


# ---------------------------------------------------------------- 基础工具


def _is_cjk(ch: str) -> bool:
    code = ord(ch)
    return 0x3400 <= code <= 0x4DBF or 0x4E00 <= code <= 0x9FFF


def code_of(syllable: str) -> str:
    """音节拼写 → 12 键码串（镜像 TwelveKeyPreedit.codeOf 與 hakka_jiugong.schema.yaml）。"""
    s = syllable[:-1] + "u" if syllable.endswith("w") else syllable
    if s.startswith("nz"):
        s = "y" + s[2:]
    elif s.startswith("th"):
        s = "w" + s[2:]
    elif s.startswith("ng"):
        s = "1" + s[2:]
    xlit = {
        "a": "a", "b": "a", "c": "a",
        "d": "e", "e": "e", "f": "e",
        "g": "i", "h": "i", "i": "i",
        "k": "5", "l": "5",
        "m": "4", "n": "4", "o": "4",
        "p": "7", "s": "7",
        "u": "3", "v": "3", "r": "3",
        "z": "8", "j": "8",
        "q": "9", "t": "9",
    }
    return "".join(xlit.get(c, c) for c in s)


class Data:
    """词库索引（音节表 / 单字 / 联想 / 整串码）。"""

    def __init__(self, data_dir: str):
        self.dir = data_dir
        self.initial: str | None = None
        self.syllable_freq: dict[str, int] = {}
        self.syllable_chars: dict[str, list[str]] = defaultdict(list)
        self.code_to_syllables: dict[str, list[str]] = defaultdict(list)
        # 联想（两音节词）：第一音节 → 第二音节 → 词表（按热度降序）
        self.assoc: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
        # 词频：与 App `assocWords` 的 count 一致（同一词跨词表出现多次就累加），
        # 决定桶内「取哪 6 条」（App 按 count 降序、同 count 按词面）。
        self._assoc_freq: dict[str, dict[str, dict[str, int]]] = defaultdict(lambda: defaultdict(dict))
        # 整串码（只收词表自带读音的条目）：code → [(tokens, word)]
        self.word_codes: list[tuple[list[str], str, str]] = []
        # 纯词表多字词（3~6 个 CJK）逐字反推首读音后的首字母索引：
        # initials 元组 → [(完整 tokens, 词)]；镜像 App `multiInitialWords`。
        self.multi_initials: dict[tuple[str, ...], list[tuple[list[str], str]]] = {}
        # 单字 → 首读音（镜像 App 的 charTable）：纯词表（sougou 这类没有读音列的）
        # 靠它反推前两字读音，否则联想索引会比 App 少一大截。
        self.char_first: dict[str, str] = {}

        main = os.path.join(data_dir, "hakka.dict.yaml")
        if not os.path.isfile(main):
            sys.exit(f"[panel-probe] 找不到 {main}；先跑 `panel_probe.py pull` 或 --data 指定目录")
        self._load_dict(main)
        self.syllables = self._load_syllables(data_dir)
        # 与 App 的 RimeDictSource.namesWithImports 一致：**只加载 hakka.dict.yaml 的
        # import_tables 展开出来的词表**。目录里可能还有 luna_pinyin 这类别的方案词典
        # （`li xing` 就是这么混进来的），12 键方案不该用它们。
        imports = [os.path.join(data_dir, name + ".dict.yaml") for name in self._import_names(main)]
        for path in imports:
            if os.path.isfile(path):
                # 進口詞典的單字讀音也要並入（`渠 gia` 只在 zon），與 App load() 同口徑；
                # 只並入標準音集收錄的音節（排除 bon/hop 這類方案外拼寫）。
                self._load_dict(path, allowed=self.standard_syllables)
        self.syllables = set(self.syllable_freq)
        self.complete_codes = {code_of(s) for s in self.standard_syllables | self.syllables}
        self.non_suffix_codes = {code_of(s) for s in self.standard_syllables | self.syllables if s != "r"}
        for path in imports:
            if os.path.isfile(path):
                self._load_words(path)
        self._sort_assoc()
        self._build_initials_index()
        print(
            f"# 音節集: 詞典 {len(self.syllable_freq)}（運行時口徑，含進口詞典單字讀音）"
            f" / 標準集 {len(self.standard_syllables)}（約束腳本與測試）",
            file=sys.stderr,
        )

    # -- 载入 ------------------------------------------------------------

    def _load_dict(self, path: str, allowed: set[str] | None = None) -> None:
        for line in _iter_body(path):
            if "\t" not in line:
                continue
            ch, rest = line.split("\t", 1)
            ch = ch.strip()
            syl = rest.split("\t")[0].strip()
            if not syl or not syl.isalpha():
                continue
            if allowed is not None and syl not in allowed:
                continue
            self.syllable_freq[syl] = self.syllable_freq.get(syl, 0) + 1
            # Kotlin 的 Char 是 UTF-16 單元，BMP 外字（U+24334 這類）在 App 端 `ch.length==1`
            # 不成立、整條被跳過；腳本的 Python `len(ch)==1` 卻會收——golden 會差一個字。
            if len(ch) == 1 and ord(ch) <= 0xFFFF:
                chars = self.syllable_chars[syl]
                if ch not in chars:
                    chars.append(ch)
                self.char_first.setdefault(ch, syl)
        self.code_to_syllables = defaultdict(list)
        for syl in self.syllable_freq:
            self.code_to_syllables[code_of(syl)].append(syl)

    def _load_syllables(self, data_dir: str) -> set[str]:
        """載入標準音節集；詞表校驗集合在所有進口單字讀音載入後確定。"""
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        path = os.environ.get(
            "HKRIME_SYLLABLES",
            os.path.join(data_dir, "hakka_syllables.txt") if os.path.isfile(
                os.path.join(data_dir, "hakka_syllables.txt")
            ) else os.path.join(root, "app", "src", "main", "assets", "rime", "hakka_syllables.txt"),
        )
        canon: set[str] = set()
        if os.path.isfile(path):
            for line in open(path, encoding="utf-8"):
                tok = line.split("#")[0].strip()
                if tok and tok.isalpha():
                    canon.add(tok)
        self.standard_syllables = canon
        self.syllable_file = path
        return set(self.syllable_freq)

    def _import_names(self, main_path: str, depth: int = 0, seen: "OrderedDict[str, None]" | None = None) -> list[str]:
        """展开 hakka.dict.yaml 的 import_tables（镜像 RimeDictSource.namesWithImports）。"""
        if seen is None:
            seen = OrderedDict()
        if depth > 4:
            return []
        head: list[str] = []
        with open(main_path, encoding="utf-8") as handle:
            for line in handle:
                head.append(line.rstrip("\n"))
                if line.strip() == "...":
                    break
                if len(head) >= 200:
                    break
        names: list[str] = []
        i = 0
        while i < len(head):
            bare = head[i].split("#")[0].strip()
            if not bare.startswith("import_tables"):
                i += 1
                continue
            inline = bare.split(":", 1)[1].strip() if ":" in bare else ""
            if inline:
                for part in inline.strip("[] ").split(","):
                    part = part.strip().strip("\"'")
                    if part:
                        names.append(part.removesuffix(".dict.yaml"))
                i += 1
                continue
            i += 1
            while i < len(head):
                item = head[i].split("#")[0].strip()
                if not item:
                    i += 1
                    continue
                if not item.startswith("-"):
                    break
                names.append(item.lstrip("-").strip().strip("\"'").removesuffix(".dict.yaml"))
                i += 1
        out: list[str] = []
        for name in names:
            if name in seen:
                continue
            seen[name] = None
            out.append(name)
            sub = os.path.join(os.path.dirname(main_path), name + ".dict.yaml")
            if os.path.isfile(sub):
                out.extend(self._import_names(sub, depth + 1, seen))
        return out

    def _load_words(self, path: str) -> None:
        for line in _iter_body(path):
            if "\t" not in line:
                # 纯词表（`hakka_sougou_words` 这类没有读音列）：镜像 App
                # `TwelveKeyPreedit.loadWordLines` 的 charTable 反推——只反推**前两字**读音，
                # 且只进联想索引（不建整串码：App 端同样只对带读音的条目建 word_codes，
                # 否则 2 千条会撑到 17 万条、真机加载从 1s 变 18s）。
                word = line.strip()
                if len(word) < 2:
                    continue
                # 与 App `loadWordLines` 同口径：先反推前两字读音，再套词长规则
                #（汉字词 2~4 字；含非汉字的词 1~16 字——`你不上QQ` 这类也收）。
                cjk_only = all(_is_cjk(c) for c in word)
                # 多字纯词先记首字母索引（5/6 字词不进联想桶，但组合行要用）。
                if cjk_only and 3 <= len(word) <= 6:
                    tokens = [self.char_first.get(c) for c in word]
                    if all(t and t.isalpha() and t in self.syllables for t in tokens):
                        key = tuple(initial_unit(t) for t in tokens)
                        self.multi_initials.setdefault(key, []).append((tokens, word))
                if cjk_only and len(word) not in (2, 3, 4):
                    continue
                if not cjk_only and not (1 <= len(word) <= 16):
                    continue
                r1 = self.char_first.get(word[0])
                r2 = self.char_first.get(word[1])
                if not r1 or not r2:
                    continue
                if r1 not in self.syllables or r2 not in self.syllables:
                    continue
                bucket = self.assoc[r1][r2]
                if word not in bucket:
                    bucket.append(word)
                freq = self._assoc_freq[r1][r2]
                freq[word] = freq.get(word, 0) + 1
                continue
            word, rest = line.split("\t", 1)
            word = word.strip()
            # 词表第 2 列是读音，第 3 列起是权重/频次 —— 只取第 2 列
            reading = rest.split("\t")[0].strip()
            tokens = [t for t in reading.split(" ") if t and t.isalpha()]
            if not word or len(tokens) < 2:
                continue
            # 只认**客家话音节集合**里的音节：词表里可能混着普通话/粤语读音
            # （`例行 → li xing`），12 键方案不该把它们当音节用。
            if any(t not in self.syllables for t in tokens):
                continue
            # 词长过滤与 App `loadWordLines` 一致：汉字的词 2~4 字（三/四字词整词上屏更有用），
            # 非汉字词（Linux、iPhone…）放宽到 1~16 字。不过滤会让联想桶比 App 多收长词、
            # golden 对拍出现「脚本有、App 没有」的行。
            cjk_only = all(_is_cjk(c) for c in word)
            if cjk_only and len(word) not in (2, 3, 4):
                continue
            if not cjk_only and not (1 <= len(word) <= 16):
                continue
            # 联想索引：只收两音节词（与 App 的 assocWords 一致）
            if len(tokens) >= 2:
                bucket = self.assoc[tokens[0]][tokens[1]]
                if word not in bucket:
                    bucket.append(word)
                freq = self._assoc_freq[tokens[0]][tokens[1]]
                freq[word] = freq.get(word, 0) + 1
            code = "".join(code_of(t) for t in tokens)
            if code:
                self.word_codes.append((tokens, word, code))

    def _sort_assoc(self) -> None:
        """桶内排序镜像 App `finishWordIndex`：出现次数降序、同次数按词面字典序。

        App 的 `assocByFirst` 里词表就是按这个顺序存的，面板行内取前 `ASSOC_WORDS` 条
        依赖它；脚本若只按词面排，golden 对拍会出现同一行文字集合不同。
        """
        for first, by_second in self.assoc.items():
            for second, words in by_second.items():
                freq = self._assoc_freq[first][second]
                words.sort(key=lambda w: (-freq.get(w, 1), w))

    # -- 查询 ------------------------------------------------------------

    def decode(self, codes: str) -> list[str] | None:
        """最少音節優先，同段數按詞頻擇優，r 僅末段；與 App DP 一致。"""
        n = len(codes)
        best: list[tuple[list[str], int] | None] = [None] * (n + 1)
        best[0] = ([], 0)
        for start in range(n):
            if best[start] is None:
                continue
            spans, freq = best[start]
            for end in range(start + 1, min(n, start + 6) + 1):
                part = codes[start:end]
                syllables = self.code_to_syllables.get(part, ())
                if not syllables:
                    continue
                syllable = max(syllables, key=lambda s: self.syllable_freq[s])
                if syllable == "r" and end != n:
                    continue
                candidate = (spans + [part], freq + self.syllable_freq[syllable])
                old = best[end]
                if old is None or len(candidate[0]) < len(old[0]) or (
                    len(candidate[0]) == len(old[0]) and candidate[1] > old[1]
                ):
                    best[end] = candidate
        return best[n][0] if n and best[n] is not None else None

    def has_full_syllable(self, codes: str) -> bool:
        clean = codes.replace("'", "")
        reachable = {0}
        for start in range(len(clean)):
            if start not in reachable:
                continue
            for end in range(start + 1, min(len(clean), start + 6) + 1):
                part = clean[start:end]
                pool = self.complete_codes if end == len(clean) else self.non_suffix_codes
                if part in pool:
                    reachable.add(end)
        return bool(clean) and len(clean) in reachable

    def is_single_syllable(self, codes: str) -> bool:
        return codes in self.code_to_syllables

    def synth_syllables(self, codes: str) -> list[str]:
        """当前码串**能解码出的全部完整音节**（可能多节；尾端没打全的部分忽略）。

        这是诊断口径（`syllables` 的「合成音节」块），不是面板左列口径：面板只列
        待输入的第一段，选完一个字才轮到下一段；诊断要把 `338e9` 的 vu 和 zet 都列出来。
        """
        if not codes:
            return []
        decoded = self.decode(codes)
        if decoded is None:
            # 尾端可能只剩半截音节：从尾部逐个退，找到能完整解码的最长前缀。
            for cut in range(1, len(codes)):
                decoded = self.decode(codes[:-cut])
                if decoded is not None:
                    break
        if decoded is None:
            return []
        out: list[str] = []
        for seg in decoded:
            out.extend(
                self.syllables_for_segment(seg, 1_000_000, True, True, total_limit=1_000_000)
            )
        return list(dict.fromkeys(out))

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
        self,
        code: str,
        limit: int = 12,
        exact_only: bool = False,
        allow_suffix_only: bool = False,
        total_limit: int = MAX_SYLLABLES_TOTAL,
        initial: str | None = None,
    ) -> list[str]:
        """首段音節按 exact/前綴規則產生，r 僅允許沒有剩餘碼的末段。"""
        if not code:
            return []

        def allowed(syl: str) -> bool:
            return (allow_suffix_only or syl not in SUFFIX_ONLY_SYLLABLES) and (
                initial is None or initial_unit(syl) == initial
            )

        exact: list[str] = []
        prefix: list[tuple[str, str]] = []
        for sc, syllables in self.code_to_syllables.items():
            if sc == code:
                exact.extend(s for s in syllables if allowed(s))
            elif not exact_only and len(sc) > len(code) and sc.startswith(code):
                for syl in syllables:
                    if allowed(syl):
                        prefix.append((sc[len(code)], syl))
        # 同频按音节字典序（与 App 同一 key；不能依赖插入顺序）
        exact_sorted = sorted(exact, key=lambda s: (-self.syllable_freq.get(s, 0), s))

        outer: OrderedDict[str, OrderedDict[str, list[str]]] = OrderedDict()
        for next_code, syl in prefix:
            outer.setdefault(next_code, OrderedDict()).setdefault(syl[0], []).append(syl)
        for inner in outer.values():
            for key in inner:
                inner[key].sort(key=lambda s: (-self.syllable_freq.get(s, 0), s))
        # 桶顺序必须完全确定（与 App 同一 key）：外层「桶内最高频」降序、同频按 next-code 码位；
        # 内层按音节首字母。App 那边已不依赖 HashMap 迭代顺序，脚本这里必须镜像同一规则。
        order = [
            inner
            for _, inner in sorted(
                outer.items(),
                key=lambda kv: (
                    -max(self.syllable_freq.get(v[0], 0) for v in kv[1].values()),
                    kv[0],
                ),
            )
        ]

        distinct_exact = list(dict.fromkeys(exact_sorted))
        total_cap = max(limit, min(limit * len(order), total_limit))
        diversified: list[str] = []
        per_initial: dict[str, int] = defaultdict(int)
        initial_count = max(1, sum(len(inner) for inner in order))
        per_initial_cap = max(limit, total_cap // initial_count)

        round_idx = 0
        while len(diversified) + len(distinct_exact) < total_cap and any(
            any(len(lst) > round_idx for lst in inner.values()) for inner in order
        ):
            for inner in order:
                for initial, lst in sorted(inner.items()):
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
        assoc_buckets: OrderedDict[tuple[str, str], list[tuple[str, list[str]]]] = OrderedDict()
        for index, (head, rest) in enumerate(splits):
            pool = self.syllable_count_for_code(head)
            base = SEGMENT_SYLLABLE_LIMIT_RICH if pool >= RICH_SYLLABLE_THRESHOLD else SEGMENT_SYLLABLE_LIMIT
            quota = base if index == 0 else base // SECONDARY_SPLIT_DIVISOR
            head_complete = self.is_single_syllable(head)
            allow_suffix_only = not rest
            syllables = self.syllables_for_segment(head, quota, head_complete, allow_suffix_only, initial=self.initial)
            if not syllables:
                syllables = self.syllables_for_segment(head, quota, False, allow_suffix_only, initial=self.initial)
            source = "exact" if head_complete else "prefix"
            # 這一行來自哪個切法（json 子命令的六源劃分用）：
            # synth = 整串碼本身就是一個完整音節（split 0、rest 空）；其餘切法的單字組 = split。
            # 注意 `source` 欄位已被「exact/prefix」佔用，這裡另開 `origin`，不改既有語義。
            origin = "synth" if index == 0 and not rest else "split"
            for syl in syllables:
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
                        "origin": origin,
                        "split": index,
                    }
                )
            # 按兩段音節首字母分桶，跨桶和切法輪轉選行。
            huge = 1_000_000
            # 聯想按每段第一鍵首字母集合匹配，元音自由。
            if rest:
                completed_seconds = self.syllables_completing_prefix(rest)
                last_part = index == len(splits) - 1
                if completed_seconds:
                    # 第二音節已打全（三音節以上輸入）：第一段用 exact 音節，第二段用已完成音節，
                    # 否則 `vu zet t`（338e99）會丟掉 `vu zet` 這一行（含 武则天）。
                    assoc_first = (
                        self.syllables_for_segment(
                            head, huge, True, allow_suffix_only, total_limit=huge, initial=self.initial
                        )
                        if self.is_single_syllable(head)
                        else self.syllables_by_initials(
                            self.key_initial_letters(head[0], is_last=False)
                        )
                    )
                    second_candidates = completed_seconds
                else:
                    assoc_first = self.syllables_by_initials(
                        self.key_initial_letters(head[0], is_last=False)
                    )
                    second_candidates = self.syllables_by_initials(
                        self.key_initial_letters(rest[0], is_last=last_part and len(rest) == 1)
                    )
            else:
                assoc_first = self.syllables_for_segment(
                    head, huge, False, allow_suffix_only, total_limit=huge, initial=self.initial
                )
                second_candidates = None
            assoc_first = [x for x in assoc_first if self.initial is None or x.startswith(self.initial)]
            for key, rows in self.assoc_buckets(assoc_first, second_candidates).items():
                assoc_buckets.setdefault(key, []).extend(rows)
        for reading, words in self.pick_bucket_rows(assoc_buckets):
            groups.append(
                {
                    "reading": reading,
                    "syllables": reading.split(" "),
                    "segment": 1,
                    "items": words,
                    "source": "assoc",
                }
            )
        # 按首字母組合查真實詞條，標題保持具體讀音。
        for _label, reading, words in self.initial_pattern_rows(codes):
            groups.append(
                {
                    "reading": reading,
                    "syllables": reading.split(" "),
                    "segment": 1,
                    "items": words,
                    "source": "initials",
                }
            )
        return groups

    def _build_initials_index(self) -> None:
        """按音節首字母序列查詞，元音自由，返回具體讀音與詞面。"""
        self.by_initials: dict[tuple[str, ...], list[tuple[list[str], str]]] = {}
        # 两音节联想词（含纯词表反推出来的）：与 App `buildInitialPatternIndex` 同口径
        for first, by_second in self.assoc.items():
            for second, words in by_second.items():
                if not first or not second:
                    continue
                key = (initial_unit(first), initial_unit(second))
                bucket = self.by_initials.setdefault(key, [])
                for word in words:
                    bucket.append(([first, second], word))
        # 三音节以上的词（带读音的词表）
        for tokens, word, _code in self.word_codes:
            if len(tokens) < 2:
                continue
            key = tuple(initial_unit(t) for t in tokens)
            bucket = self.by_initials.setdefault(key, [])
            bucket.append((tokens, word))
        # 純詞表多字詞（App `multiInitialWords` 鏡像）：按整串首字母入索引。
        for key, entries in self.multi_initials.items():
            bucket = self.by_initials.setdefault(key, [])
            for entry in entries:
                if entry not in bucket:
                    bucket.append(entry)
        # 簡拼前段各出一個首字母，最後音節按完整讀音匹配。
        self.by_hybrid: dict[str, list[tuple[list[str], str]]] = {}
        def _add_hybrid(tokens: list[str], word: str) -> None:
            if len(tokens) < 2:
                return
            prefix = "|".join(initial_unit(t) for t in tokens[:-1])
            key = prefix + "|" + tokens[-1]
            self.by_hybrid.setdefault(key, []).append((tokens, word))
        for first, by_second in self.assoc.items():
            for second, words in by_second.items():
                if not first or not second:
                    continue
                for word in words:
                    # 两字纯词：前两音节就是完整读音；更长的纯词读音未知，不参与。
                    if len(word) == 2:
                        _add_hybrid([first, second], word)
        for tokens, word, _code in self.word_codes:
            _add_hybrid(tokens, word)

    def key_initial_letters(self, code_char: str, is_last: bool = False) -> list[str]:
        """每鍵取合法首字母單元，排除元音；後綴 r 僅末鍵允許。

        th/ng/nz 按整段返回（模板聲母），不拆成 t/h；鍵→單元由音節的碼首鍵決定。
        """
        units = sorted(
            {
                initial_unit(syl)
                for syl in self.syllable_freq
                if syl
                and code_of(syl)[:1] == code_char
                and initial_unit(syl)
                and not (len(initial_unit(syl)) == 1 and initial_unit(syl) in VOWEL_LETTERS)
            }
        )
        if not is_last:
            units = [x for x in units if x not in SUFFIX_ONLY_SYLLABLES]
        return units

    def syllables_completing_prefix(self, code: str) -> list[str]:
        """「整段碼的前綴恰好是一個完整音節」的那些音節（`8e99` → ze/zet；`8i` → zi/ji）。

        鏡像 App `TwelveKeyPreedit.syllablesCompletingPrefix`：三音節以上、第二音節已打全時，
        聯想行要按這些已完成音節查（否則 rest 展開不到音節，`vu zet` 行會消失）。
        """
        if not code:
            return []
        out = {
            syl
            for syl_code, syllables in self.code_to_syllables.items()
            if syl_code and code.startswith(syl_code)
            for syl in syllables
        }
        return sorted(out)

    def syllables_by_initials(self, initials: list[str]) -> list[str]:
        """每音節只按首字母匹配，返回具體讀音，元音自由。"""
        if not initials:
            return []
        wanted = set(initials)
        return sorted({syl for syl in self.syllables if syl and initial_unit(syl) in wanted})

    def initial_pattern_combos(self, codes: str) -> list[tuple[str, ...]]:
        """首字母組合的**規則**（每鍵一個音節首字母、元音自由；不管詞條存不存在）。

        `ghi+abc` → `g b / g c / h b / h c`；`ghi+abc+zj` → 8 組 `(g|h)(b|c)(z|j)`。
        每鍵只貢獻「該鍵字母裡能作音節首字母」的字母（元音不作首字母）。
        """
        codes = codes.replace("'", "")
        if not codes:
            return []
        combos: list[tuple[str, ...]] = [()]
        for index, ch in enumerate(codes):
            letters = self.key_initial_letters(ch, is_last=index == len(codes) - 1)
            if index == 0 and self.initial is not None:
                letters = [x for x in letters if x == self.initial]
            if not letters:
                return []
            combos = [pre + (x,) for pre in combos for x in letters]
            if len(combos) > INITIAL_COMBO_PATTERN_LIMIT:
                return []
        return combos

    def initial_pattern_hybrids(self, codes: str) -> list[tuple[str, ...]]:
        """前段各鍵取首字母，末段碼串必須是完整音節；枚舉合法切法。"""
        clean = codes.replace("'", "")
        if len(clean) < 2:
            return []
        out: list[tuple[str, ...]] = []
        for k in range(1, len(clean)):
            suffix = clean[k:]
            if not self.is_single_syllable(suffix):
                continue
            prefix_combos: list[tuple[str, ...]] = [()]
            ok = True
            for index, ch in enumerate(clean[:k]):
                letters = self.key_initial_letters(ch, is_last=False)
                if index == 0 and self.initial is not None:
                    letters = [x for x in letters if x == self.initial]
                if not letters:
                    ok = False
                    break
                prefix_combos = [pre + (x,) for pre in prefix_combos for x in letters]
                if len(prefix_combos) > INITIAL_COMBO_PATTERN_LIMIT:
                    ok = False
                    break
            if not ok:
                continue
            tails = self.syllables_for_segment(
                suffix, 1_000_000, True, True, total_limit=1_000_000
            )
            for pre in prefix_combos:
                for tail in tails:
                    out.append(pre + (tail,))
        return out

    def initial_patterns(self, codes: str) -> list[tuple[str, ...]]:
        """首字母组合规则 = 纯简拼组合 + 简拼+完整音节（总数封顶，两类交错）。

        纯词表长词会让纯组合排满组合行上限；若纯组合全在前，简拼+完整音节
        （如 b|cam）永远轮不到，学习词/长音节联想会被吞掉。
        """
        pure = list(self.initial_pattern_combos(codes))
        hybrids = list(self.initial_pattern_hybrids(codes))
        patterns: list[tuple[str, ...]] = []
        for i in range(max(len(pure), len(hybrids))):
            if i < len(pure):
                patterns.append(pure[i])
            if i < len(hybrids):
                patterns.append(hybrids[i])
        if len(patterns) > INITIAL_COMBO_PATTERN_LIMIT:
            patterns = patterns[:INITIAL_COMBO_PATTERN_LIMIT]
        return patterns

    def initial_pattern_rows(
        self, codes: str, max_rows: int = INITIAL_COMBO_MAX_ROWS
    ) -> list[tuple[str, str, list[str]]]:
        """鏡像 App INITIAL_COMBO：兩輪選行，每組合每輪一行，總行數受上限限制。"""
        codes = codes.replace("'", "")
        if len(codes) < 2:
            return []
        combos = self.initial_patterns(codes)
        if not combos:
            return []
        # 先把每个组合的命中词条按读音分组、按读音字典序排好（与 App `wordsByInitialPattern` 同构：
        # 同一读音的多条词合并成一行）。跨組合輪轉時直接按序号取。
        entries_by_combo: list[list[tuple[str, list[str]]]] = []
        for combo in combos:
            if len(combo[-1]) > 1:
                entries = self.by_hybrid.get("|".join(combo[:-1]) + "|" + combo[-1]) or []
            else:
                entries = self.by_initials.get(combo) or []
            grouped: "OrderedDict[str, list[str]]" = OrderedDict()
            for tokens, word in entries:
                grouped.setdefault(" ".join(tokens), []).append(word)
            entries_by_combo.append(sorted(grouped.items()))
        rows: list[tuple[str, str, list[str]]] = []
        seen: set[str] = set()
        # 两轮：第 1 轮每组合 1 条，第 2 轮再补第 2 条（`ROWS_PER_PATTERN` 控制每轮条数）
        for round_index in range(2):
            start = round_index * INITIAL_COMBO_ROWS_PER_PATTERN
            stop = start + INITIAL_COMBO_ROWS_PER_PATTERN
            for combo, entries in zip(combos, entries_by_combo):
                label = "·".join(combo)
                for reading, words in entries[start:stop]:
                    if reading in seen:
                        continue
                    seen.add(reading)
                    rows.append((label, reading, list(words)))
                    if len(rows) >= max_rows:
                        return rows
        return rows

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

    def assoc_buckets(
        self, first_candidates: list[str], second_candidates: list[str] | None
    ) -> "OrderedDict[tuple[str, str], list[tuple[str, list[str]]]]":
        """收集 (第一音節候選 × 第二音節候選) 命中的具體行，按（首字母, 首字母）分桶。"""
        second_candidates = second_candidates or []
        buckets: OrderedDict[tuple[str, str], list[tuple[str, list[str]]]] = OrderedDict()
        for first in first_candidates:
            by_second = self.assoc.get(first) or {}
            if not by_second:
                continue
            if not second_candidates:
                allowed = list(by_second.keys())
            else:
                allowed = [s for s in second_candidates if s in by_second]
            for second in allowed:
                if not second:
                    continue
                words = by_second[second][:ASSOC_WORDS]
                if not words:
                    continue
                buckets.setdefault((initial_unit(first), initial_unit(second)), []).append(
                    (f"{first} {second}", words)
                )
        return buckets

    def pick_bucket_rows(
        self,
        buckets: "OrderedDict[tuple[str, str], list[tuple[str, list[str]]]]",
        max_rows: int = ASSOC_BUCKET_ROW_BUDGET,
    ) -> list[tuple[str, list[str]]]:
        """跨拆法统一轮转取行：每个（首字母, 首字母）桶先出 1 行，保证通配组合都有代表。"""
        if not buckets:
            return []
        for rows in buckets.values():
            # 桶内排序必须确定：词数降序、同词数按读音（与 App `pickBucketRows` 同一 key）
            rows.sort(key=lambda x: (-len(x[1]), x[0]))
        picked: list[tuple[str, list[str]]] = []
        seen: set[str] = set()

        # 按「第一段首字母」分组；组内先各桶第 1 行、再第 2 行…；最后在组之间轮转。
        groups_map: "OrderedDict[str, list[list[tuple[str, list[str]]]]]" = OrderedDict()
        for key in sorted(buckets.keys()):
            groups_map.setdefault(key[0], []).append(buckets[key])
        flat_groups: "OrderedDict[str, list[tuple[str, list[str]]]]" = OrderedDict()
        for initial, bucket_rows in groups_map.items():
            flat: list[tuple[str, list[str]]] = []
            round_idx = 0
            while True:
                added = False
                for rows in bucket_rows:
                    if round_idx < len(rows):
                        flat.append(rows[round_idx])
                        added = True
                if not added:
                    break
                round_idx += 1
            flat_groups[initial] = flat
        cursors = {initial: 0 for initial in flat_groups}
        while len(picked) < max_rows:
            added = False
            for initial, flat in flat_groups.items():
                cursor = cursors[initial]
                if cursor >= len(flat):
                    continue
                reading, words = flat[cursor]
                cursors[initial] = cursor + 1
                if reading in seen:
                    continue
                seen.add(reading)
                picked.append((reading, words))
                added = True
                if len(picked) >= max_rows:
                    break
            if not added:
                break
        picked.sort(key=lambda x: x[0])
        return picked

    def assoc_rows_by_initials(
        self, first_candidates: list[str], rest: str, max_rows: int = ASSOC_MAX_ROWS
    ) -> list[tuple[str, list[str]]]:
        """按合法首字母組合產生具體讀音聯想行。"""
        second_candidates: list[str] = []
        if rest:
            huge = 1_000_000
            second_candidates = self.syllables_for_segment(rest, huge, False, True, total_limit=huge)
        buckets: OrderedDict[tuple[str, str], list[tuple[str, list[str]]]] = OrderedDict()
        for first in first_candidates:
            by_second = self.assoc.get(first) or {}
            if not by_second:
                continue
            if not second_candidates:
                allowed = list(by_second.keys())
            else:
                allowed = [s for s in second_candidates if s in by_second]
            for second in allowed:
                if not second:
                    continue
                words = by_second[second][:ASSOC_WORDS]
                if not words:
                    continue
                buckets.setdefault((initial_unit(first), initial_unit(second)), []).append(
                    (f"{first} {second}", words)
                )
        if not buckets:
            return []
        for key, rows in buckets.items():
            rows.sort(key=lambda x: -len(x[1]))
        # 輪轉：每個通配桶先出 1 行，還有額度再出第 2 行
        picked: list[tuple[str, list[str]]] = []
        round_idx = 0
        while len(picked) < max_rows:
            added = False
            for key in buckets:
                rows = buckets[key]
                if round_idx < len(rows) and len(picked) < max_rows:
                    picked.append(rows[round_idx])
                    added = True
            if not added:
                break
            round_idx += 1
        picked.sort(key=lambda x: x[0])
        return picked

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
            if initials and initial_unit(second) not in initials:
                continue
            rows.append((f"{first} {second}", words[:ASSOC_WORDS]))
        rows.sort(key=lambda x: x[0])
        return rows[:ASSOC_MAX_ROWS]

    def prefix_word_rows(self, codes: str) -> list[dict]:
        # 每行標記 source，`merge_groups` 才能套用「整串詞行大小寫不敏感」規則。
        """整串码前缀联想（多音节词），镜像 wordsByCodePrefix。"""
        prefix = codes.replace("'", "")
        rows: OrderedDict[str, list[str]] = OrderedDict()
        seen_words: dict[str, set[str]] = defaultdict(set)
        for tokens, word, code in self.word_codes:
            if self.initial is not None and not tokens[0].startswith(self.initial):
                continue
            # 第一個音節必須打全（與 App `wordsByCodePrefix` 同口徑）：
            # 打 `ia`（= ga）時不該列出 `gam se`（= ia4|7e，第一段還沒打完）。
            first_code = code_of(tokens[0])
            if not first_code or not prefix.startswith(first_code):
                continue
            if not code.startswith(prefix):
                continue
            reading = " ".join(tokens)
            # 同一读音只收一次（App 端用 LinkedHashSet 去重）
            if word in seen_words[reading]:
                continue
            seen_words[reading].add(word)
            rows.setdefault(reading, []).append(word)
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
                    "items": sorted(set(words))[:ASSOC_WORDS],
                    "source": "whole_word",
                }
            )
        return out

    def collapse(self, groups: list[dict]) -> list[dict]:
        """镜像 collapsePrefixRows：多音节行按「最长已有前缀读音」收敛。"""

        def tokens(g: dict) -> list[str]:
            return [t for t in g["reading"].split(" ") if t]

        # 先按读音去重（`ha boi` 可能同时来自联想行与词行；面板只有一行，脚本也只列一行）
        merged_rows: "OrderedDict[str, dict]" = OrderedDict()
        for g in groups:
            key = g["reading"]
            if key not in merged_rows:
                merged_rows[key] = g
                continue
            existing = merged_rows[key]
            texts = {t.lower() for t in existing.get("items", [])}
            extra = [w for w in g.get("items", []) if w.lower() not in texts]
            if extra:
                merged_rows[key] = {**existing, "items": existing.get("items", []) + extra}
        groups = list(merged_rows.values())

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

    def existing_readings(self, codes: str) -> list[dict]:
        """列出當前編碼可匹配的實際詞表讀音集合，用於對照面板覆蓋。"""
        codes = codes.replace("'", "")
        if not codes:
            return []
        single = [(codes, "")] if self.is_single_syllable(codes) else []
        splits = list(single)
        for h, r in self.split_heads(codes):
            if h not in [x[0] for x in splits]:
                splits.append((h, r))
        if not splits:
            splits = [(codes[:1], codes[1:])]
        out: list[dict] = []
        huge = 1_000_000
        for index, (head, rest) in enumerate(splits):
            head_complete = self.is_single_syllable(head)
            allow_suffix_only = not rest
            readings = self.syllables_for_segment(
                head, huge, head_complete, allow_suffix_only, total_limit=huge
            )
            if not readings:
                readings = self.syllables_for_segment(
                    head, huge, False, allow_suffix_only, total_limit=huge, initial=self.initial
                )
            out.append(
                {
                    "split": index,
                    "head": head,
                    "rest": rest,
                    "kind": "single" if index == 0 and not rest else "split",
                    "readings": readings,
                }
            )
        # 整串碼前綴匹配到的多音節詞行（`li nak si → Linux`）也算「實際存在」
        for row in self.prefix_word_rows(codes):
            out.append(
                {
                    "split": -1,
                    "head": row["reading"],
                    "rest": "",
                    "kind": "word",
                    "readings": [row["reading"]],
                    "items": row["items"],
                }
            )
        return out

    def cumulative(self, codes: str, min_len: int = 2) -> list[tuple[int, str, list[dict]]]:
        """按逐鍵前綴累積查詢結果，保持首次出現順序。"""
        codes = codes.replace("'", "")
        stages: list[tuple[int, str, list[dict]]] = []
        for length in range(max(1, min_len), len(codes) + 1):
            prefix = codes[:length]
            rows: list[dict] = []
            rows += [g for g in self.segment_groups(prefix) if g["segment"] == 0]
            rows += [g for g in self.segment_groups(prefix) if g["segment"] == 1]
            rows += self.prefix_word_rows(prefix)
            rows = self.collapse(rows)

            def tier(g: dict) -> int:
                n = len([t for t in g["reading"].split(" ") if t])
                return 2 if n == 0 else (0 if n == 1 else 1)

            stages.append((length, prefix, sorted(rows, key=lambda g: (tier(g), g["reading"].lower()))))
        return stages

    def cumulative_unique(self, codes: str, min_len: int = 2) -> list[tuple[str, int, str]]:
        """累積結果按具體完整讀音去重，保留不同長度讀音供診斷。"""
        seen: "OrderedDict[str, tuple[int, str]]" = OrderedDict()
        for length, prefix, rows in self.cumulative(codes, min_len):
            for g in rows:
                kind = {0: "单字", 1: "联想/词"}.get(g["segment"], "?")
                seen.setdefault(g["reading"], (length, kind))
            # 未收敛的整串词行（`li nak si`）也单独列一条，不和 `li nak` 合并
            for row in self.prefix_word_rows(prefix):
                seen.setdefault(row["reading"], (length, "词行"))
        return [(reading, length, kind) for reading, (length, kind) in seen.items()]

    def standard_decode(self, codes: str) -> list[str]:
        """返回所有完整標準切法涉及的音節；不展平截斷 DFS，不納入死路或中段 r。"""
        clean = codes.replace("'", "")
        by_code: dict[str, list[str]] = defaultdict(list)
        for syllable in sorted(self.standard_syllables):
            by_code[code_of(syllable)].append(syllable)
        edges = [[] for _ in range(len(clean) + 1)]
        forward = {0}
        for start in range(len(clean)):
            for end in range(start + 1, min(len(clean), start + 6) + 1):
                for syllable in by_code.get(clean[start:end], ()):
                    if syllable == "r" and end != len(clean):
                        continue
                    edges[start].append((end, syllable))
                    if start in forward:
                        forward.add(end)
        backward = {len(clean)}
        out = set()
        for start in range(len(clean) - 1, -1, -1):
            for end, syllable in edges[start]:
                if end in backward:
                    backward.add(start)
                    if start in forward:
                        out.add(syllable)
        return sorted(out)

    def isolated_syllables(self, codes: str) -> list[tuple[str, list[str]]]:
        """多鍵且整串無完整切分時，通配列出首鍵字母下的全部音節。"""
        clean = codes.replace("'", "")
        if self.initial is not None or len(clean) < 2 or self.has_full_syllable(clean):
            return []
        letters = self.key_initial_letters(clean[0], is_last=len(clean) == 1)
        universe = set(self.syllable_freq) | set(self.standard_syllables)
        out: list[tuple[str, list[str]]] = []
        for letter in letters:
            # 通配口徑：只看首字母單元，不受剩餘碼前綴限制（55 → 所有 k/l 開頭音節）；
            # th/ng/nz 是組合聲母，不能按 t/n 前綴混進來。
            syls = [syl for syl in universe if initial_unit(syl) == letter]
            syls.sort(key=lambda syl: (-self.syllable_freq.get(syl, 0), syl))
            if syls:
                out.append((letter, syls))
        return out

    @staticmethod
    def merge_groups(groups: list[dict]) -> list[dict]:
        """唯一合并点（镜像 App `PanelRow.mergeRows`）：同 reading 的行合并 item。

        - key = reading 小写（`Linux`/`linux` 是同一行），保持首次出现顺序；
        - items：整串词行（`source == "whole_word"`）大小写不敏感去重，其余来源用精确文字
          （引擎会同时给 `J` 和 `j`，按小写去重会吞掉大写候选，跟重構前口徑一致）；
        - segment/rest 取首行（拼接顺序已保证单字组在前）。
        """
        merged: "OrderedDict[str, dict]" = OrderedDict()
        for row in groups:
            key = row["reading"].lower()
            existing = merged.get(key)
            if existing is None:
                texts = list(row.get("items") or row.get("chars") or [])
                created = {
                    "reading": row["reading"],
                    "syllables": list(row.get("syllables") or row["reading"].split(" ")),
                    "segment": row.get("segment", 0),
                    "rest": row.get("rest", ""),
                    "items": texts,
                    "source": row.get("source"),
                    "origin": row.get("origin"),
                }
                if created["segment"] == 0:
                    created["chars"] = texts
                merged[key] = created
                continue
            case_insensitive = row.get("source") == "whole_word"
            seen = {
                (t.lower() if case_insensitive else t)
                for t in existing["items"]
            }
            for text in row.get("items") or row.get("chars") or []:
                probe = text.lower() if case_insensitive else text
                if probe in seen:
                    continue
                seen.add(probe)
                existing["items"].append(text)
                if existing["segment"] == 0:
                    existing["chars"] = existing["items"]
        return list(merged.values())

    def isolated_rows(self, codes: str) -> list[dict]:
        """孤單聯想行（鏡像 App `isolatedRows`）：reading=首鍵字母，items=通配單音節。"""
        rows: list[dict] = []
        for letter, syllables in self.isolated_syllables(codes):
            rows.append(
                {
                    "reading": letter,
                    "syllables": [letter],
                    "items": list(syllables),
                    "segment": -4,
                    "rest": codes.replace("'", ""),
                    "source": "isolated",
                    "origin": "isolated",
                }
            )
        return rows

    def panel(self, codes: str) -> list[dict]:
        groups = self.isolated_rows(codes)
        groups += self.segment_groups(codes)
        groups += self.prefix_word_rows(codes.replace("'", ""))
        groups = self.collapse(self.merge_groups(groups))

        def tier(g: dict) -> int:
            if g.get("source") == "isolated":
                return -1
            n = len([t for t in g["reading"].split(" ") if t])
            return 2 if n == 0 else (0 if n == 1 else 1)

        return sorted(groups, key=lambda g: (tier(g), g["reading"].lower()))


# 键名（键面标签）→ 码：允许直接写键盘上的键名，如 `"kl ghi mno abc ps"`
KEY_NAME_TO_CODE = {
    "nz": "y", "qt": "9", "abc": "a", "def": "e",
    "ng": "1", "ghi": "i", "kl": "5", "mno": "4",
    "th": "w", "ps": "7", "vur": "3", "zj": "8",
}

# 後綴 r 只允許詞尾。
SUFFIX_ONLY_SYLLABLES = {"r"}

# 組合聲母：th/ng/nz 是一個首字母單元（引擎簡拼也按整段），不得拆成 t/h。
INITIAL_UNIT_PREFIXES = ("th", "ng", "nz")


def initial_unit(syllable: str) -> str:
    if not syllable:
        return ""
    for prefix in INITIAL_UNIT_PREFIXES:
        if syllable.startswith(prefix):
            return prefix
    return syllable[0]

# 元音字母（不能作音节首字母）
VOWEL_LETTERS = set("aeiou")

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
    """逐键累积地列左列音节（2 键 → 3 键 → … → 整串），最后去重汇总。"""
    raw_input = args.codes
    args.codes = parse_code(args.codes)
    unique = data.cumulative_unique(args.codes, args.min_len)
    if not args.no_stages:
        for length, prefix, rows in data.cumulative(args.codes, args.min_len):
            print(f"## {length} 键  {prefix}")
            # 已完成的前綴音節逐節顯示，未完成尾碼保持原樣。
            synth = data.synth_syllables(prefix)
            print("   合成音节: " + (", ".join(synth) if synth else "（无）"))
            # 2) 首字母组合（每键一个音节、元音自由；逐键累积）
            pure = data.initial_pattern_combos(prefix)
            hybrids = data.initial_pattern_hybrids(prefix)
            shown = ", ".join("·".join(c) for c in pure[:8])
            tail = "" if len(pure) <= 8 else f" …（共 {len(pure)} 组）"
            print(f"   {length}键组合({len(pure)}): " + (shown + tail if pure else "（无）"))
            print('     注：组合里每个字母都隐含「≥1 个字母」（如 g·b = g.* 与 b.*，元音不限）')
            # 簡拼與完整末音節共同生成匹配模式。
            if hybrids:
                hshown = ", ".join("·".join(c) for c in hybrids[:8])
                htail = "" if len(hybrids) <= 8 else f" …（共 {len(hybrids)} 组）"
                print(f"   简拼+完整音节({len(hybrids)}): " + hshown + htail)
            # 3) 实际存在的词语：按组合 → 真实词条（行标题=具体读音）
            words_by_combo: "OrderedDict[str, list[str]]" = OrderedDict()
            for label, reading, words in data.initial_pattern_rows(prefix, max_rows=32):
                words_by_combo.setdefault(label, []).append(f"{reading}={','.join(words[:3])}")
            if words_by_combo:
                print("   实际存在词语:")
                for combo, items in words_by_combo.items():
                    print(f"     ({combo}) " + "；".join(items[:4]))
            else:
                print("   实际存在词语: （无）")
            # 4) 联想行：合成音节选完之后的后缀联想（`ga` → `ga zi=挂纸`）
            def combo_of(reading: str) -> str:
                return "·".join(initial_unit(t) for t in reading.split(" "))

            assoc_rows = [g for g in rows if g["segment"] == 1 and g.get("source") == "assoc"]
            if assoc_rows:
                print(
                    "   联想行(两音节，词典联想索引; 方括号是它的首字母序列): "
                    + ", ".join(
                        f"{g['reading']}={','.join(g['items'][:2])} [{combo_of(g['reading'])}]"
                        for g in assoc_rows[:8]
                    )
                    + (" …" if len(assoc_rows) > 8 else "")
                )
            # 5) 整串词行（多音节词条，未收敛）
            word_rows = data.prefix_word_rows(prefix)
            if word_rows:
                print(
                    "   整串词行(码前缀匹配; 方括号是它的首字母序列): "
                    + "；".join(
                        f"{w['reading']}={','.join(w['items'][:3])} [{combo_of(w['reading'])}]"
                        for w in word_rows
                    )
                )
        print()
    print(f"# 码串 {args.codes!r} 累积去重后共 {len(unique)} 个读音（按首次出现的键数排序）")
    for reading, length, kind in unique:
        print(f"  {reading:<18} {kind}  首现于 {length} 键")

    # 首字母組合（規則層面，和詞條是否存在無關）
    combos = data.initial_pattern_combos(args.codes)
    if combos:
        keys = args.codes.replace("'", "")
        # 每種首字母序列命中的詞條數（一次掃描建表，避免 O(組數×詞條數)）
        counts: dict[tuple[str, ...], int] = {}
        for tokens, _w, _c in data.word_codes:
            if len(tokens) != len(keys):
                continue
            key = tuple(initial_unit(t) for t in tokens)
            counts[key] = counts.get(key, 0) + 1
        print()
        shown_input = (
            f"{raw_input} = 碼 {' '.join(keys)}" if raw_input.replace(" ", "") != keys else f"碼 {' '.join(keys)}"
        )
        print(
            f"# 首字母組合（規則）：{len(combos)} 組 × {len(keys)} 個首字母（輸入 {len(keys)} 鍵：{shown_input}）"
        )
        print("# 每行一組，`g·b·j` 這種點號分隔的是**同一組**的首字母序列；末尾是該組合命中的詞條數。")
        width = max(len("·".join(c)) for c in combos)
        for index, combo in enumerate(combos, 1):
            pattern = "·".join(combo)
            print(f"  [{index:>2}] {pattern:<{width}}  → {counts.get(combo, 0)} 詞")
        hit = sum(1 for c in combos if counts.get(c))
        print(
            f"# 有命中：{hit} 組 / {len(combos)} 組"
            + ("（0 詞的組合在實際代碼裡直接跳過，不往面板塞空行）" if hit < len(combos) else "")
        )

    # 實際存在讀音集（詞典全量反查、不受面板配額限制）
    shown = {r[0] for r in unique if " " not in r[0]} | {g["reading"] for g in data.segment_groups(args.codes)}
    print()
    print(f"# 實際存在讀音集（碼串 {args.codes!r}，詞典全量，不受配額限制）")
    union: list[str] = []
    word_parts: list[dict] = []
    for part in data.existing_readings(args.codes):
        readings = part["readings"]
        if part["kind"] == "word":
            word_parts.append(part)
            continue
        for r in readings:
            if r not in union:
                union.append(r)
        if not readings:
            print(f"  [{part['kind']:<6} {part['head']}|{part['rest']}] （無）")
            continue
        print(
            f"  [{part['kind']:<6} {part['head']}|{part['rest']}] "
            f"共 {len(readings)}: {' '.join(readings)}"
        )
    standard = [s for s in data.standard_decode(args.codes) if s not in data.syllable_freq]
    if standard:
        print(
            f"  标准集（App 运行时不搜索；码 {args.codes!r} 可切出）: "
            + " ".join(dict.fromkeys(standard))
        )
    hidden = [r for r in union if r not in shown]
    print(f"# 單音節：去重後實際存在 {len(union)} 個；面板規則已顯示 {len(union) - len(hidden)} 個"
          + (f"；未顯示（多半被種子/配額截斷）: {' '.join(hidden)}" if hidden else "；無遗漏"))
    if word_parts:
        print(f"# 整串詞行（多音節讀音，碼前綴匹配，面板會按「最長已有前綴」收斂）：{len(word_parts)} 條")
        for part in word_parts:
            print(f"  {part['readings'][0]:<18} 词: {'/'.join(part.get('items', [])[:6])}")
    else:
        print("# 整串詞行：無")
    isolated = data.isolated_syllables(args.codes)
    if isolated:
        print()
        print("# 孤單聯想（整串碼不能構成完整音節；首鍵字母各列可達單音節，按頻率）")
        for letter, syls in isolated:
            print(f"  {letter}.* → " + ", ".join(syls))


def cmd_build_syllables(args) -> None:
    """從 `hakkaSyllables.js` 抽出標準音節集，寫成 `assets/rime/hakka_syllables.txt`。"""
    text = open(args.source, encoding="utf-8").read()
    match = re.search(r"SYLLABLE_SOURCE\s*=\s*`(.*?)`", text, re.S)
    body = match.group(1) if match else text
    base = [t for t in body.split() if t.isalpha()]
    # 標準集取 JS 源集合與主詞典音節並集。
    dict_path = args.dict or os.path.join(args.data, "hakka.dict.yaml")
    dict_syll: list[str] = []
    if os.path.isfile(dict_path):
        for line in open(dict_path, encoding="utf-8"):
            if "\t" not in line or line.lstrip().startswith("#"):
                continue
            reading = line.rstrip("\n").split("\t")[1]
            dict_syll += [t for t in reading.split() if t.isalpha()]
    # 補遺檔（可選，一行一個）：留給「兩邊都沒收、但確認合法」的音節
    extra_path = args.extra or os.path.join(os.path.dirname(args.out), "hakka_syllables_extra.txt")
    extras: list[str] = []
    if extra_path and os.path.isfile(extra_path):
        for line in open(extra_path, encoding="utf-8"):
            tok = line.split("#")[0].strip()
            if tok and tok.isalpha():
                extras.append(tok)
    sets = [set(base), set(dict_syll), set(extras)]
    syllables = sorted(set().union(*sets))
    lines = [
        "# 客家話合法音節標準集（JS 源檔 ∪ 主詞典 ∪ 可選補遺）",
        "#   權威來源 Hkdict/neo-frontend/src/data/hakkaSyllables.js；",
        "#   用於腳本與測試約束；運行時音節集合僅來自主詞典。",
        f"# 共 {len(syllables)} 個 = JS {len(sets[0])} ∪ 詞典 {len(sets[1])} ∪ 補遺 {len(sets[2])}"
        "（由 panel_probe.py build-syllables 生成；App 不載入本檔）。",
    ] + syllables
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    print(f"# 寫入 {args.out}：{len(syllables)} 個音節")


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
        if second and initial_unit(second) == args.initial
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


def _row_items(g: dict) -> list[str]:
    """一行里的文字：单字组取 `chars`，联想/词行取 `items`（json 输出用，只要文字）。"""
    if g.get("segment") == 0:
        return list(g.get("chars", []))
    return list(g.get("items", []))


def build_json_payload(data: Data, raw_codes: list[str]) -> dict:
    """鏡像 App 六個本地來源與最終 merged 行；輸出供 golden 對拍。"""
    payload: dict[str, dict] = {}
    for raw in raw_codes:
        codes = parse_code(raw)
        groups = data.segment_groups(codes)
        sources = {
            "synth": [
                {"reading": g["reading"], "items": _row_items(g)}
                for g in groups
                if g.get("origin") == "synth"
            ],
            "split": [
                {"reading": g["reading"], "items": _row_items(g)}
                for g in groups
                if g.get("origin") == "split"
            ],
            "assoc": [
                {"reading": g["reading"], "items": _row_items(g)}
                for g in groups
                if g["segment"] == 1 and g.get("source") == "assoc"
            ],
            "combo": [
                {"reading": g["reading"], "items": _row_items(g)}
                for g in groups
                if g.get("source") == "initials"
            ],
            "whole_word": [
                {"reading": r["reading"], "items": list(r.get("items", []))}
                for r in data.prefix_word_rows(codes)
            ],
        }
        sources["isolated"] = [
            {"reading": r["reading"], "items": list(r["items"])}
            for r in data.isolated_rows(codes)
        ]
        merged = [
            {"reading": g["reading"], "segment": g["segment"], "items": _row_items(g)}
            for g in data.panel(codes)
        ]
        payload[codes] = {"codes": codes, "sources": sources, "merged": merged}
    return payload


def cmd_json(args, data: Data) -> None:
    """结构化输出（golden 单测用）：stdout 只有单个 JSON 对象，缩进 2。"""
    print(json.dumps(build_json_payload(data, args.codes), ensure_ascii=False, indent=2))


def _canonical_spelling(syllable: str) -> str:
    for old, new in (("iam", "ian"), ("am", "an"), ("om", "on"), ("im", "in"),
                     ("em", "en"), ("ap", "at"), ("ep", "et"), ("up", "ut"), ("w", "u")):
        if syllable.endswith(old):
            return syllable[:-len(old)] + new
    return syllable


def selected_input_end(data: Data, reading: str, codes: str) -> int | None:
    """選擇的消費證明；None 與已消費全部碼的 len(codes) 嚴格區別。"""
    tokens = reading.replace("［", "").replace("］", "").split()
    if not tokens or any(not re.fullmatch("[a-z]+", t) for t in tokens) or "r" in tokens[:-1]:
        return None
    offsets = [i for i, ch in enumerate(codes) if ch != "'" and not ch.isspace()]
    clean = "".join(codes[i] for i in offsets)
    if not clean:
        return None
    pos = 0
    matched = True
    for token in tokens:
        if pos == len(clean):
            break
        if token == "r" and pos + 1 != len(clean):
            return None
        full = code_of(token)
        alias = next((length for length in range(min(6, len(clean) - pos), 0, -1)
                      if any(_canonical_spelling(token) == _canonical_spelling(s)
                             for s in data.code_to_syllables.get(clean[pos:pos + length], ()))), None)
        if clean.startswith(full, pos):
            pos += len(full)
        elif alias is not None:
            pos += alias
        elif token[0] in KEY_LETTERS.get(clean[pos], "") and token[0] not in "aeiou":
            pos += 1
        elif full.startswith(clean[pos:]):
            pos = len(clean)
        else:
            matched = False
            break
    if not matched:
        if any(t not in data.syllable_freq for t in tokens):
            return None
        spans = data.decode(clean)
        if not spans or len(spans) < len(tokens):
            return None
        pos = sum(len(part) for part in spans[:len(tokens)])
    return offsets[pos - 1] + 1 if pos else None


def remaining_code_after(data: Data, reading: str, codes: str) -> str:
    consumed = selected_input_end(data, reading, codes)
    return codes[consumed:].lstrip("' ") if consumed is not None else ""


def cmd_coverage(args, data: Data) -> None:
    """有限穷举：真实音节编码的有序对、短键串、实际词表全部选词边界。"""
    counts = defaultdict(int)
    failures = []

    def check(ok: bool, category: str, **case) -> None:
        counts[category] += 1
        if not ok:
            counts["failures"] += 1
            if len(failures) < 30:
                failures.append({"category": category, **case})

    # 独立正则语言作为完整性判定 oracle，不调用被测 DP。
    complete = sorted(data.complete_codes, key=lambda c: (-len(c), c))
    non_suffix = sorted(data.non_suffix_codes, key=lambda c: (-len(c), c))
    oracle = re.compile("(?:" + "|".join(map(re.escape, non_suffix)) + ")*"
                        "(?:" + "|".join(map(re.escape, complete)) + ")")
    for size in range(1, args.max_keys + 1):
        for keys in itertools.product(KEY_LETTERS, repeat=size):
            code = "".join(keys)
            expected = oracle.fullmatch(code) is not None
            check(data.has_full_syllable(code) == expected, "key_strings", code=code)
            if expected:
                check(not data.isolated_syllables(code), "complete_without_isolated", code=code)
    for first in non_suffix:
        for second in complete:
            code = first + second
            check(data.has_full_syllable(code), "ordered_code_pairs", code=code)

    runtime = {code: next(s for s in syllables if s != "r")
               for code, syllables in data.code_to_syllables.items()
               if any(s != "r" for s in syllables)}
    for first, reading in runtime.items():
        for second in data.code_to_syllables:
            code = first + second
            got = remaining_code_after(data, reading, code)
            check(got == second, "selected_pair_boundaries", code=code, pick=reading, got=got, want=second)

    readings = {tuple(tokens) for tokens, _, _ in data.word_codes}
    readings.add(("tai", "go", "ji", "li", "pu"))
    for tokens in sorted(readings):
        if "r" in tokens[:-1]:
            counts["suffix_invalid_readings"] += 1
            continue
        parts = [code_of(t) for t in tokens]
        for selected in range(1, len(tokens) + 1):
            code = "".join(parts)
            pick = " ".join(tokens[:selected])
            want = "".join(parts[selected:])
            got = remaining_code_after(data, pick, code)
            check(got == want, "dictionary_pick_boundaries", code=code, pick=pick, got=got, want=want)
    remaining = remaining_code_after(data, "tai", "9aii48i5i73")
    check(remaining == "i48i5i73" and any(g["reading"] == "go" for g in data.panel(remaining)),
          "tai_panel", remaining=remaining)
    files = [os.path.join(data.dir, "hakka.dict.yaml"), data.syllable_file]
    files += [os.path.join(data.dir, name + ".dict.yaml")
              for name in data._import_names(files[0])]
    hashes = {os.path.abspath(path): hashlib.sha256(open(path, "rb").read()).hexdigest()
              for path in files if os.path.isfile(path)}
    counts["failures"] += 0
    report = {"data": os.path.abspath(data.dir), "max_keys": args.max_keys,
              "runtime_syllables": len(data.syllable_freq), "standard_syllables": len(data.standard_syllables),
              "unique_word_readings": len(readings), "counts": dict(counts),
              "failures": failures, "sha256": hashes,
              "scope": "dictionary-derived finite coverage; no device UI or userdb refresh"}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if counts["failures"]:
        raise SystemExit(1)


def cmd_preedit(args, data: Data) -> None:
    """复现 App preedit 切分，以及「选中只覆盖前几音节的词之后剩余什么码」。

    PC 侧对拍 librime 引擎行为的命令见 `rime-jni/host-test/run-host-test.sh --probe <code> --select <i>`；
    这里复现的是 App 自己的判定（TwelveKeyPreedit.decode / CandidateInputBoundary）。
    """
    codes = parse_code(args.codes)
    spans = data.decode(codes) or []
    print(f"input={codes}")
    print("preedit=" + (" ".join(spans) if spans else "（未切出完整音节）"))
    if not args.pick:
        return
    reading = args.pick.strip()
    tokens = [t for t in reading.replace("\u3000", " ").split(" ") if t]
    remaining = remaining_code_after(data, reading, codes)
    print(f"pick={reading} syllables={len(tokens)}")
    if remaining:
        rem_spans = data.decode(remaining) or []
        print(f"remaining={remaining}")
        print("remaining_preedit=" + (" ".join(rem_spans) if rem_spans else "（切不出完整音节）"))
    else:
        print("remaining=（整串被这个词覆盖 / 切不出音节）")


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

    # 完整前綴的 vu/zet 應顯示為已完成音節。
    expect(data.synth_syllables("ia") == ["ga", "ha"], f"合成音节 ia = {data.synth_syllables('ia')}")
    expect(
        set(data.synth_syllables("ee5")) == {"de", "fe"},
        f"合成音节 ee5 = {data.synth_syllables('ee5')}",
    )
    expect(
        set(data.synth_syllables("338e9")) == {"vu", "zet", "jet"},
        f"合成音节 338e9 = {data.synth_syllables('338e9')}",
    )
    expect(
        set(data.synth_syllables("338e99")) == {"vu", "zet", "jet"},
        f"合成音节 338e99（尾端半截 9）= {data.synth_syllables('338e99')}",
    )

    # 4) 整串码前缀：5i4a57 → li nak si → Linux
    rows = data.prefix_word_rows("5i4a57")
    ok = any(r["reading"] == "li nak si" and any(w.lower() == "linux" for w in r["items"]) for r in rows)
    expect(ok, f"5i4a57 的前缀词行含 li nak si→Linux：{[r['reading'] for r in rows]}")

    # 4.5) 逐键累积：5i4a57 应给出 2..6 共 5 个阶段，去重后含 li nak si
    stages = data.cumulative("5i4a57", 2)
    expect(
        [st[0] for st in stages] == [2, 3, 4, 5, 6],
        f"累积阶段键数 = {[st[0] for st in stages]}",
    )
    unique = data.cumulative_unique("5i4a57", 2)
    last_rows = data.cumulative("5i4a57", 2)[-1][2]
    merged = [g for g in last_rows if "linux" in {t.lower() for t in g.get("items", [])}]
    expect(
        bool(merged) and merged[0]["reading"] in ("li nak", "li nak si"),
        f"整串阶段应出现含 Linux 的行（{merged[0]['reading'] if merged else 'NONE'}）",
    )
    # 聯想按首字母分桶、12 行預算選行，最終按讀音字典序顯示。
    expect(
        any(w.lower() == "linux" for w in (data.assoc.get("li") or {}).get("nak", [])),
        "联想索引 li+nak 应含 Linux",
    )
    expect(
        any(r[0].startswith("li ") for r in unique),
        f"累积去重应含 li 开头的联想行（{len(unique)} 个读音）",
    )
    expect(
        all(unique[i][0] != unique[i + 1][0] for i in range(len(unique) - 1)),
        "累积去重后没有相邻重复读音",
    )

    # 4.44) 逐鍵累積：組合數 = 2^k（首字母集合大小之積），r 只在最後一鍵
    for prefix, expected in (("ia", 4), ("ia8", 8), ("ia8i", 16), ("ia8i3", 32)):
        got = data.initial_pattern_combos(prefix)
        expect(len(got) == expected, f"{prefix} 组合数应为 {expected}，实际 {len(got)}")
    last_only = data.initial_pattern_combos("ia8i3")
    expect(
        all("r" not in c[:-1] for c in last_only),
        "r 只能出现在最后一键（儿化后缀）",
    )
    expect(
        any(c[-1] == "r" for c in last_only),
        "最后一键 vur 应允许 r",
    )

    # 驗證首字母組合匹配且元音自由。
    c2 = data.initial_pattern_combos("ia")
    expect(
        [tuple(c) for c in c2] == [("g", "b"), ("g", "c"), ("h", "b"), ("h", "c")],
        f"ia 的首字母組合應為 g b/g c/h b/h c，實際 {c2}",
    )
    c3 = data.initial_pattern_combos("ia8")
    expect(
        sorted(" ".join(c) for c in c3)
        == sorted(
            f"{a} {b} {c}"
            for a in ("g", "h") for b in ("b", "c") for c in ("j", "z")
        ),
        f"ia8 的首字母組合應為 (g|h)(b|c)(j|z) 共 8 組，實際 {c3}",
    )

    # 4.5) 實際存在讀音集：必須是詞典音節、且覆蓋面板已顯示的讀音
    exist4i = [p for p in data.existing_readings("4i") if p["kind"] != "word"]
    union4i = {r for part in exist4i for r in part["readings"]}
    expect(bool(union4i), f"4i 實際存在讀音集非空（{len(union4i)}）")
    expect(union4i <= set(data.syllable_freq), "實際存在讀音集全部來自詞典音節")
    shown4i = {g["reading"] for g in data.segment_groups("4i") if g["segment"] == 0}
    expect(
        shown4i <= union4i,
        f"面板顯示的讀音必須 ⊆ 實際存在集（漏: {sorted(shown4i - union4i)[:5]}）",
    )
    all57 = data.existing_readings("5i4a57")
    union57 = {r for part in all57 if part["kind"] != "word" for r in part["readings"]}
    words57 = [p for p in all57 if p["kind"] == "word"]
    expect(
        any(p["readings"][0] == "li nak si" for p in words57),
        f"整串詞行應含 li nak si，實際 {[p['readings'][0] for p in words57]}",
    )
    shown57 = {g["reading"] for g in data.segment_groups("5i4a57") if g["segment"] == 0}
    expect(
        union57 <= shown57 or shown57 <= union57,
        f"5i4a57 面板與實際存在集應互相覆蓋（顯示 {len(shown57)} / 存在 {len(union57)}）",
    )

    # 累積診斷分別保留 li nak 與 li nak si。
    uniq = data.cumulative_unique("5i4a57", 2)
    readings_only = [r[0] for r in uniq]
    expect(
        "li nak si" in readings_only,
        f"整串词行应列出 li nak si：{[r for r in readings_only if r.startswith('li nak')]}",
    )
    expect(
        all(r[2] in ("词行", "联想/词") for r in uniq if r[0] == "li nak si"),
        "li nak si 的类别应为词行/联想/词",
    )

    # 整串詞行要求第一音節碼打全。
    ga_code = code_of("ga")
    ga_rows = data.prefix_word_rows(ga_code)
    expect(
        all(code_of(r["syllables"][0]) == ga_code for r in ga_rows),
        f"詞行第一音節必須是 ga：{[r['reading'] for r in ga_rows][:6]}",
    )
    expect(
        all(r["reading"] != "gam se" for r in ga_rows),
        f"打 {ga_code}（=ga）時不該列 gam se：{[r['reading'] for r in ga_rows][:6]}",
    )
    expect(
        any(r["reading"] == "gam se" for r in data.prefix_word_rows(code_of("gam"))),
        "打 gam 的完整碼時應列 gam se",
    )

    # 標準集必須覆蓋主詞典全部音節。
    missing = sorted(set(data.syllable_freq) - set(data.standard_syllables))
    expect(
        not missing,
        f"標準集缺詞典音節 {missing[:8]}（共 {len(missing)} 個）",
    )

    # 4.6) 只认客家话音节：词表里混进来的普通话读音（`例行 → li xing`）必须被过滤掉
    unique_readings = [r[0] for r in data.cumulative_unique("5i4a57", 2)]
    alien = [
        r for r in unique_readings
        if any(tok not in data.syllable_freq for tok in r.split())
    ]
    expect(not alien, f"累积读音全是客家话音节（越界: {alien[:5]}）")
    expect("li xing" not in unique_readings, "不应出现普通话读音 li xing")
    assoc_readings = [r for r, _ in data.assoc_rows("li", "n")]
    expect(
        all(all(tok in data.syllable_freq for tok in r.split()) for r in assoc_readings),
        f"联想行读音全是客家话音节：{assoc_readings}",
    )

    # 後綴 r 僅詞尾可用。
    r_code = code_of("r")
    expect(bool(r_code), f"r 的碼 = {r_code}")
    as_last = [g["reading"] for g in data.segment_groups(r_code)]
    expect("r" in as_last, f"r 作最後一段應出現：{as_last}")
    not_last = [g["reading"] for g in data.segment_groups(r_code + "5i")]
    expect("r" not in not_last, f"r 不在結尾時不得出現：{not_last}")
    phrase = "".join(code_of(s) for s in ("dau", "ha", "r"))
    first = [g["reading"] for g in data.segment_groups(phrase) if g["segment"] == 0]
    expect("r" not in first, f"「到下儿」第一段不得含 r：{first}")

    # 聯想斷言檢查集合與預算，不固定某一行。
    assoc = data.assoc_rows("li", "4a5")
    expect(
        bool(assoc) and all(len(r[0].split(" ")) == 2 for r in assoc),
        f"li + 4a5 联想行应非空且标题两音节：{[(r[0], r[1][:2]) for r in assoc]}",
    )
    expect(
        all(r[0].split(" ")[1][0] in {"m", "n"} for r in assoc),
        f"li + 4a5 第二音节首字母应 ∈ m/n：{[(r[0], r[1][:2]) for r in assoc]}",
    )

    # 6) 单字：ji 全部字（>>100，验证「列全部」而不是 60 字截断）
    expect(len(data.syllable_chars.get("ji", [])) > 100, f"ji 字数 = {len(data.syllable_chars.get('ji', []))}")

    # 7) json 输出 schema（App golden 单测对拍）：六源恒在、merged 读音不重复、档位升序
    def tier_of(reading: str) -> int:
        n = len([t for t in reading.split(" ") if t])
        return 2 if n == 0 else (0 if n == 1 else 1)

    payload = build_json_payload(data, ["5i4a57", "ia"])
    for code, entry in payload.items():
        sources = entry.get("sources") or {}
        expect(
            list(sources) == ["synth", "split", "assoc", "combo", "whole_word", "isolated"],
            f"json[{code}] sources 六个 key 均在且有序：{list(sources)}",
        )
        expect(
            all(
                isinstance(row.get("reading"), str) and isinstance(row.get("items"), list)
                for part in sources.values()
                for row in part
            ),
            f"json[{code}] 六源每行都有 reading(str)/items(list)",
        )
        merged = entry.get("merged") or []
        readings = [row["reading"] for row in merged]
        expect(len(readings) == len(set(readings)), f"json[{code}] merged 读音不重复")
        tiers = [tier_of(r) for r in readings]
        expect(tiers == sorted(tiers), f"json[{code}] merged 档位顺序 0(单音节)→1(多音节)→2(无读音)：{tiers}")

    # 7.1) 首字母组合常量：组合数（PATTERN_LIMIT 内）与 `r` 仅末键
    for prefix, expected_count in (("ia", 4), ("ia8", 8), ("ia8i3", 32)):
        got = len(data.initial_pattern_combos(prefix))
        expect(got == expected_count, f"{prefix} 组合数应为 {expected_count}，实际 {got}")
    combos_3a = data.initial_pattern_combos("3a")  # vur 非末键：不得贡献 r
    expect(
        bool(combos_3a) and all("r" not in combo for combo in combos_3a),
        f"3a（vur 非末键）组合不得含 r：{combos_3a}",
    )
    combos_a3 = data.initial_pattern_combos("a3")  # vur 末键：允许 r
    expect(
        any(combo[-1] == "r" for combo in combos_a3),
        f"a3（vur 末键）应允许 r：{combos_a3}",
    )
    combo_rows = data.initial_pattern_rows("ia8i3")
    expect(
        len(combo_rows) <= INITIAL_COMBO_MAX_ROWS,
        f"ia8i3 首字母组合行数 ≤ {INITIAL_COMBO_MAX_ROWS}（实际 {len(combo_rows)}）",
    )
    # 簡拼+完整末音節應覆蓋 b cam / b can 等合法模式。
    patterns_a4 = data.initial_patterns("aaa4")
    expect(
        ("b", "can") in patterns_a4 and ("b", "cam") in patterns_a4,
        f"aaa4 简拼+完整音节应含 b·cam / b·can：{patterns_a4[-14:]}",
    )
    expect(
        all(p[0] in "bc" for p in patterns_a4 if len(p[-1]) > 1),
        "简拼前缀首字母不得含元音（元音不做开头）",
    )

    # 上一条在 ia8i3 上恰好是 0；再用真实有命中的 `5i` 守住「组合行能产出且不超上限」
    combo_rows_5i = data.initial_pattern_rows("5i")
    expect(
        0 < len(combo_rows_5i) <= INITIAL_COMBO_MAX_ROWS,
        f"5i 首字母组合行数应在 (0, {INITIAL_COMBO_MAX_ROWS}]（实际 {len(combo_rows_5i)}）",
    )

    # 驗證 preedit 切分與部分選詞後剩餘碼。
    expect(len(code_of("go")) == len("ea") and
           "go" not in data.syllables_for_segment("ea", 1000, True),
           "同長度異碼不能進 exact：go 的碼 i4 不能匹配 ea")
    expect(code_of("the") == "we" and data.is_single_syllable("we"),
           "完整性按原始碼匹配，不要求顯示音節與碼串等長")
    expect(remaining_code_after(data, "tai", "9aii48i5i73") == "i48i5i73",
           "選太按 tai 的實際跨度扣碼，不能多留一個 i")
    expect(any(row["reading"] == "go" and _row_items(row) for row in data.panel("i48i5i73")),
           "選太後面板保留 go 及其候選")
    expect(data.has_full_syllable("ea144") and not data.isolated_syllables("ea144"),
           "標準集音節可參與多段完整切分，不列 d/f 孤立行")
    expect({"tai", "go"} <= set(data.standard_decode("9aii4")),
           "標準切分診斷包含全部完整路徑中的 tai/go，不按展平音節數截斷")
    expect("r" not in data.standard_decode("35i"), "標準切分診斷禁止中段 r")

    code_partial = "5i4ii8i9934i54iei9"
    spans_partial = data.decode(code_partial) or []
    expect(
        spans_partial == ["5i4", "ii", "8i9", "934i", "54i", "ei9"],
        f"preedit 切分 = {spans_partial}",
    )
    expect(
        remaining_code_after(data, "lin gi jit dung", code_partial) == "54iei9",
        "灵机一动(dung) + 输入用 tung：remaining 应为 54iei9（按音节数扣，不按 reading→码 前缀）",
    )
    expect(
        remaining_code_after(data, "lin gi jit dung", "5i4ii8i9e34i54iei9") == "54iei9",
        "灵机一动(dung) + 输入用 dung：remaining 也应为 54iei9",
    )
    expect(
        remaining_code_after(data, "lin gi jit dung", "5i4ii8i9e34i") == "",
        "词码吃掉整串：remaining 应为空",
    )
    expect(
        remaining_code_after(data, "lin gi jit dung", "aiaa4") == "",
        "切不出完整音节的简拼码串：不猜剩余",
    )

    saved_initial = data.initial
    for code in ("5", "55", "5i", "5i5i", "i", "ia8", "aa4", "5i4a57"):
        for initial in data.key_initial_letters(code[0], is_last=len(code) == 1):
            data.initial = initial
            rows = data.panel(code)
            expect(all(row["reading"].startswith(initial) for row in rows),
                   f"首码消歧 {code}/{initial} 不得混入其它首字母")
            expect(not data.isolated_syllables(code), "消歧后不走孤单通配浏览")
    data.initial = saved_initial

    print(f"[selftest] {len(failures)} 条失败")
    if failures:
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description="12 键展开面板离线复现")
    parser.add_argument("--data", default=DEFAULT_DATA, help=f"词库目录（默认 {DEFAULT_DATA}）")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_pull = sub.add_parser("pull", help="从真机拉词库到 --data 目录")
    p_pull.set_defaults(func=None)

    p_syl = sub.add_parser("syllables", help="码串 → 逐键累积的左列音节（最后去重）")
    p_syl.add_argument("codes")
    p_syl.add_argument("--min-len", type=int, default=2, help="从几键开始累积（默认 2）")
    p_syl.add_argument("--no-stages", action="store_true", help="只打印去重汇总，不打印逐键过程")
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
    p_panel.add_argument("--initial", help="固定剩余原码首键的解释，保留后续码")
    p_panel.set_defaults(func=cmd_panel)

    p_json = sub.add_parser("json", help="结构化输出六源 + merged（App 侧 golden 单测对拍）")
    p_json.add_argument("codes", nargs="+", help="一个或多个码串/键名，如 5i4a57 'kl ghi mno abc ps'")
    p_json.add_argument("--initial")
    p_json.set_defaults(func=cmd_json)

    p_pre = sub.add_parser("preedit", help="复现 preedit 切分与「选词后剩余输入」（App CandidateInputBoundary）")
    p_pre.add_argument("codes")
    p_pre.add_argument("--pick", default="", help="被选中的词读音，如 'lin gi jit dung'")
    p_pre.set_defaults(func=cmd_preedit)

    p_bs = sub.add_parser("build-syllables", help="从 hakkaSyllables.js 同步标准音节集到 assets")
    p_bs.add_argument(
        "source",
        nargs="?",
        default="/home/hazukie/Projects/Hkdict/neo-frontend/src/data/hakkaSyllables.js",
        help="含 SYLLABLE_SOURCE 的 JS 文件（默认 Hkdict 那份）",
    )
    p_bs.add_argument(
        "--dict",
        default="",
        help="主詞典路徑（預設 <--data>/hakka.dict.yaml）",
    )
    p_bs.add_argument(
        "--extra",
        default="",
        help="補遺檔（預設與 --out 同目錄的 hakka_syllables_extra.txt）",
    )
    p_bs.add_argument(
        "--out",
        default=os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "app", "src", "main", "assets", "rime", "hakka_syllables.txt",
        ),
        help="输出文件（默认 App assets 路径）",
    )
    p_bs.set_defaults(func=cmd_build_syllables)

    p_cov = sub.add_parser("coverage", help="穷举有序音节码对、短键串与词表选词边界，JSON 报告")
    p_cov.add_argument("--max-keys", type=int, choices=range(1, 6), default=4)
    p_cov.set_defaults(func=cmd_coverage)

    p_self = sub.add_parser("selftest", help="跑文档样例自检（CI 可用）")
    p_self.set_defaults(func=cmd_selftest)

    args = parser.parse_args()
    if args.cmd == "pull":
        cmd_pull(args)
        return
    if args.cmd == "build-syllables":
        args.func(args)
        return
    data = Data(args.data)
    data.initial = getattr(args, "initial", None)
    args.func(args, data)


if __name__ == "__main__":
    main()
