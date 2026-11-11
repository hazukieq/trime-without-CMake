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

    # 6) 结构化输出（App 侧 golden 单测对拍用）：五源 + merged，单个 JSON 到 stdout
    python3 tools/panel_probe.py json ia 5i4a57

数据文件（与 App 的 `core/RimeDictSource.kt` 一致：共享目录优先，这里就是 --data 目录）：

- `hakka.dict.yaml`：音节表 + 单字表（`字<TAB>读音`）
- 其它 `*.dict.yaml`：词表（`词<TAB>读音[<TAB>权重]`），只取第 2 列
"""

from __future__ import annotations

import argparse
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
ASSOC_WORDS = 6
ASSOC_MAX_ROWS = 4
# 分桶輪轉後每輪實際取幾行：桶數可以很多（(g,b)/(g,c)/(h,b)/(h,c)…），
# 4 行只夠 4 個桶露頭；×3 讓更多桶有代表。與 App `ASSOC_BUCKET_ROW_BUDGET` 同值。
ASSOC_BUCKET_ROW_BUDGET = ASSOC_MAX_ROWS * 3
ASSOC_PREFIX_READINGS = 4

# --- 首字母組合（INITIAL_COMBO）常量：與 App `TwelveKeyTuning` 及 docs/19 §5.2 同值 ---
# 影響：面板流水線的 INITIAL_COMBO 源（本腳本對應 `initial_pattern_rows()`）。
# 現在值 8：`ia8` 有 8 個組合、`ia8i3` 有 32 個；組合全出會淹沒單字組/聯想行。
# 調大 → 首字母組合行更多（面板更長，單字組被推後）；調小 → `ia8i3` 這類多鍵輸入幾乎只剩單字組。
INITIAL_COMBO_MAX_ROWS = 8
# 每個首字母組合**每輪**先出幾行；現在值 1（第一輪每組合保 1 行，第二輪再補第 2 行）。
# 調大 → 少數高頻組合壟斷行數預算；調小到 0 → 不生成首字母組合行。
INITIAL_COMBO_ROWS_PER_PATTERN = 1
# 首字母組合數上限；現在值 64。6 鍵以上笛卡爾積通常超過（2^k），等於「>4 鍵長輸入不生成」。
# 調大 → 2^k 爆炸風險（組合數 × 詞條查詢變慢）；調小 → 5 鍵以上就沒有首字母組合行。
INITIAL_COMBO_PATTERN_LIMIT = 64


# ---------------------------------------------------------------- 基础工具


def _is_cjk(ch: str) -> bool:
    code = ord(ch)
    return 0x3400 <= code <= 0x4DBF or 0x4E00 <= code <= 0x9FFF


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
        # 词频：与 App `assocWords` 的 count 一致（同一词跨词表出现多次就累加），
        # 决定桶内「取哪 6 条」（App 按 count 降序、同 count 按词面）。
        self._assoc_freq: dict[str, dict[str, dict[str, int]]] = defaultdict(lambda: defaultdict(dict))
        # 整串码（只收词表自带读音的条目）：code → [(tokens, word)]
        self.word_codes: list[tuple[list[str], str, str]] = []
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
        for name in self._import_names(main):
            path = os.path.join(data_dir, name + ".dict.yaml")
            if os.path.isfile(path):
                self._load_words(path)
        self._sort_assoc()
        self._build_initials_index()

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
            # Kotlin 的 Char 是 UTF-16 單元，BMP 外字（U+24334 這類）在 App 端 `ch.length==1`
            # 不成立、整條被跳過；腳本的 Python `len(ch)==1` 卻會收——golden 會差一個字。
            if len(ch) == 1 and ord(ch) <= 0xFFFF:
                chars = self.syllable_chars[syl]
                if ch not in chars:
                    chars.append(ch)
                self.char_first.setdefault(ch, syl)
        for syl in self.syllable_freq:
            self.code_to_syllables[code_of(syl)].append(syl)

    def _load_syllables(self, data_dir: str) -> set[str]:
        """詞表校驗用的音節集 = **主詞典自帶音節**（與 App 運行時完全一致）。

        用戶 2026-11-11：「實際上 hakka 字典沒有的音節，注定不會有字，所以為了減少性能消耗，
        默認獲取 hakka 字典的音節集合」——App 端不再讀標準集資產，腳本鏡像同一口徑。

        標準集資產（`assets/rime/hakka_syllables.txt`，841）另行載入到
        [self.standard_syllables]，用途是**約束與校驗**（selftest 斷言：詞典音節必須 ⊆ 標準集），
        不是運行時資料源。
        """
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        path = os.environ.get(
            "HKRIME_SYLLABLES",
            os.path.join(root, "app", "src", "main", "assets", "rime", "hakka_syllables.txt"),
        )
        canon: set[str] = set()
        if os.path.isfile(path):
            for line in open(path, encoding="utf-8"):
                tok = line.split("#")[0].strip()
                if tok and tok.isalpha():
                    canon.add(tok)
        self.standard_syllables = canon
        self.syllable_file = path
        print(
            f"# 音節集: 詞典 {len(self.syllable_freq)}（運行時口徑）"
            f" / 標準集 {len(canon)}（約束腳本與測試）",
            file=sys.stderr,
        )
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
        self,
        code: str,
        limit: int = 12,
        exact_only: bool = False,
        allow_suffix_only: bool = False,
        total_limit: int = MAX_SYLLABLES_TOTAL,
    ) -> list[str]:
        """镜像 TwelveKeyPreedit.syllablesForSegment（exact 优先 + 两级桶轮转前缀展开）。

        [allow_suffix_only]：後綴音節（兒化 `r`）只允許落在輸入結尾；見
        `SUFFIX_ONLY_SYLLABLES`（用戶 2026-11-11 特殊規則）。
        """
        if not code:
            return []

        def allowed(syl: str) -> bool:
            return allow_suffix_only or syl not in SUFFIX_ONLY_SYLLABLES

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
            syllables = self.syllables_for_segment(head, quota, head_complete, allow_suffix_only)
            if not syllables:
                syllables = self.syllables_for_segment(head, quota, False, allow_suffix_only)
            source = "exact" if head_complete else "prefix"
            # 這一行來自哪個切法（json 子命令的五源劃分用）：
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
            # 首字母組合聯想：把每個拆法的（第一段音節候選 × 第二段候選）命中都收進桶，
            # 最後跨拆法统一轮转（用戶 2026-11-11：「ghi+abc → g b, g c, h b, h c 4 個通配聯想詞組」）。
            # 通配桶要覆盖**全部**候选音節（不受面板配額截斷），否则 (g,b) 这类组合会漏。
            huge = 1_000_000
            # 2026-11-11 起改用**首字母序列匹配**，不再用碼前綴：
            # - 兩音節聯想：第一段只看第一個碼位的首字母（`ee` → {d,f}，元音自由：da/dai/dang/di/de…）；
            # - 第二段只看剩餘碼第一個碼位的首字母（`5` → {k,l}）；
            # - 只有一段（剩餘碼為空）時仍按碼前綴展開，列出該音節的全部後續音節。
            if rest:
                assoc_first = self.syllables_by_initials(
                    self.key_initial_letters(head[0], is_last=False)
                )
                last_part = index == len(splits) - 1
                second_candidates = self.syllables_by_initials(
                    self.key_initial_letters(rest[0], is_last=last_part and len(rest) == 1)
                )
            else:
                assoc_first = self.syllables_for_segment(
                    head, huge, False, allow_suffix_only, total_limit=huge
                )
                second_candidates = None
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
        # 首字母組合行（每鍵一個音節首字母，元音不固定）：`ghi+abc` → g b / g c / h b / h c，
        # 每個組合找真實詞條，行標題仍是具體讀音（用戶 2026-11-11：「g.* z.*」）。
        for reading, words in self.initial_pattern_rows(codes):
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
        """按「音节首字母序列」索引词表（用户 2026-11-11：「g.* z.*」，元音不固定）。

        `首字母组合` 只是**查找规则**：例如 `ghi+abc` 的两个键分别能作 g/h 与 b/c 开头，
        就去词表里找读音首字母序列恰为 (g,b)/(g,c)/(h,b)/(h,c) 的真实词条，
        行标题仍然是具体读音（`gong bun`、`han coi`…）。
        """
        self.by_initials: dict[tuple[str, ...], list[tuple[list[str], str]]] = {}
        # 两音节联想词（含纯词表反推出来的）：与 App `buildInitialPatternIndex` 同口径
        for first, by_second in self.assoc.items():
            for second, words in by_second.items():
                if not first or not second:
                    continue
                key = (first[0], second[0])
                bucket = self.by_initials.setdefault(key, [])
                for word in words:
                    bucket.append(([first, second], word))
        # 三音节以上的词（带读音的词表）
        for tokens, word, _code in self.word_codes:
            if len(tokens) < 2:
                continue
            key = tuple(t[0] for t in tokens)
            bucket = self.by_initials.setdefault(key, [])
            bucket.append((tokens, word))

    def key_initial_letters(self, code_char: str, is_last: bool = False) -> list[str]:
        """某个码位能作音节首字母的字母（排除元音；按音节集合实际出现过的首字母）。

        后缀音节 `r`（儿化）**只能出现在最后一键**（用户 2026-11-11 特殊规则），
        所以非末位按键不贡献 `r`。
        """
        letters = KEY_LETTERS.get(code_char, "")
        initials = sorted(
            {
                syl[0]
                for syl in self.syllable_freq
                if syl and syl[0] in letters and syl[0] not in VOWEL_LETTERS
            }
        )
        if not is_last:
            initials = [x for x in initials if x not in SUFFIX_ONLY_SYLLABLES]
        return initials

    def syllables_by_initials(self, initials: list[str]) -> list[str]:
        """首字母集合 → 這些字母打頭的全部音節（字典序、去重）。

        鏡像 App `TwelveKeyPreedit.syllablesByInitials`：鏡像聯想行按首字母序列匹配後，
        每個音節只由「首字母」約束，元音不釘死（用戶 2026-11-11）。
        """
        if not initials:
            return []
        wanted = set(initials)
        return sorted({syl for syl in self.syllables if syl and syl[0] in wanted})

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
            if not letters:
                return []
            combos = [pre + (x,) for pre in combos for x in letters]
            if len(combos) > INITIAL_COMBO_PATTERN_LIMIT:
                return []
        return combos

    def initial_pattern_rows(
        self, codes: str, max_rows: int = INITIAL_COMBO_MAX_ROWS
    ) -> list[tuple[str, list[str]]]:
        """「每键一个音节首字母」的联想行：字母组合×真实词条，行标题仍是具体读音。

        与 App `INITIAL_COMBO` 源同一套**轮转**语义（docs/19 §5.2/步骤 3）：

        - 组合规则直接复用 `initial_pattern_combos()`（`r` 只在末键，元音不作首字母）；
          组合数超 `INITIAL_COMBO_PATTERN_LIMIT` 或任一键无可用首字母 → 整组不生成；
        - 第一轮每个组合按读音字典序取第 1 条（保证每个组合都有代表），
          第二轮再给每个组合补第 2 条；每轮内按 `combos` 顺序塞；
        - 总行数达 `max_rows`（默认 `INITIAL_COMBO_MAX_ROWS`）即停。
        - 键数不再做 `>4` 的硬限制，由 `INITIAL_COMBO_PATTERN_LIMIT` 自然封顶
          （如 `5i4a57` 恰好 64 组仍生成，7 键以上通常超限）。
        """
        codes = codes.replace("'", "")
        if len(codes) < 2:
            return []
        combos = self.initial_pattern_combos(codes)
        if not combos:
            return []
        # 先把每个组合的命中词条按读音分组、按读音字典序排好（与 App `wordsByInitialPattern` 同构：
        # 同一读音的多条词合并成一行）。跨組合輪轉時直接按序号取。
        entries_by_combo: list[list[tuple[str, list[str]]]] = []
        for combo in combos:
            grouped: "OrderedDict[str, list[str]]" = OrderedDict()
            for tokens, word in self.by_initials.get(combo) or []:
                grouped.setdefault(" ".join(tokens), []).append(word)
            entries_by_combo.append(sorted(grouped.items()))
        rows: list[tuple[str, list[str]]] = []
        seen: set[str] = set()
        # 两轮：第 1 轮每组合 1 条，第 2 轮再补第 2 条（`ROWS_PER_PATTERN` 控制每轮条数）
        for round_index in range(2):
            start = round_index * INITIAL_COMBO_ROWS_PER_PATTERN
            stop = start + INITIAL_COMBO_ROWS_PER_PATTERN
            for entries in entries_by_combo:
                for reading, words in entries[start:stop]:
                    if reading in seen:
                        continue
                    seen.add(reading)
                    rows.append((reading, list(words)))
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
                buckets.setdefault((first[0], second[0]), []).append(
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
        """首字母組合的聯想行（用戶 2026-11-11：「ghi+abc → g b, g c, h b, h c 4 個通配聯想詞組」）。

        通配只是**查找規則**，行標題仍然是具體讀音（`ga zi`、`ha boi`…）：
        對第一段的每個候選音節（`g*`/`h*`/`i*`…）× 第二段的每個候選音節（由 rest 碼展開），
        查 `(第一音節, 第二音節)` 精確哈希，湊成具體行；再把這些行按
        **(第一音節首字母, 第二音節首字母)** 分桶，輪轉取行，保證每個通配組合都有代表。
        """
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
                buckets.setdefault((first[0], second[0]), []).append(
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
            if initials and second[0] not in initials:
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
        """**實際存在**的讀音集：詞典裡真有字、且碼串能讀成它的全部音節（不受面板配額限制）。

        用途（用戶 2026-11-11：「測試脚本 syllables 还应该输出一份实际存在读音集」）：
        面板左列受 16/24 配額與輪轉限制，可能只顯示一部分；這裡給全量，方便對照
        「規則漏了哪些」「配額截掉了哪些」。

        每項：`{"split": i, "head": ..., "rest": ..., "kind": "single|split", "readings": [...]}`。
        """
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
                    head, huge, False, allow_suffix_only, total_limit=huge
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
        """逐键累积：2 键、3 键 … 直到整串，返回每个阶段的左列快照。

        用户 2026-11-11：面板是随按键逐步长出来的，脚本要能复现「两个键→三个键→四个键」
        每一步的左列，并且最后去重展示（同一个读音只保留第一次出现的位置）。
        """
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
        """累积去重：(reading, 首次出现的键数, 类别)。

        用户 2026-11-11：「那你可以这么列呀，li nak, li nak si」——面板会把 3 音节词行
        `li nak si` 收敛进 `li nak` 联想行，但脚本要**两者都列**（各自一行、互不吞并）。
        """
        seen: "OrderedDict[str, tuple[int, str]]" = OrderedDict()
        for length, prefix, rows in self.cumulative(codes, min_len):
            for g in rows:
                kind = {0: "单字", 1: "联想/词"}.get(g["segment"], "?")
                seen.setdefault(g["reading"], (length, kind))
            # 未收敛的整串词行（`li nak si`）也单独列一条，不和 `li nak` 合并
            for row in self.prefix_word_rows(prefix):
                seen.setdefault(row["reading"], (length, "词行"))
        return [(reading, length, kind) for reading, (length, kind) in seen.items()]

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

    def panel(self, codes: str) -> list[dict]:
        groups = self.segment_groups(codes)
        groups += self.prefix_word_rows(codes.replace("'", ""))
        groups = self.collapse(self.merge_groups(groups))

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

# 後綴音節（兒化 `r`）：不做首字母拼、只能出現在詞尾（用戶 2026-11-11 特殊規則）
SUFFIX_ONLY_SYLLABLES = {"r"}

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
            # 1) 合成音节：整串码恰好是一个完整音节时（`ia` = ga/ha）
            synth: list[str] = []
            if data.is_single_syllable(prefix):
                synth = list(
                    dict.fromkeys(
                        data.syllables_for_segment(prefix, 1_000_000, True, True, total_limit=1_000_000)
                    )
                )
            print("   合成音节: " + (", ".join(synth) if synth else "（无）"))
            # 2) 首字母组合（每键一个音节、元音自由；逐键累积）
            combos = data.initial_pattern_combos(prefix)
            shown = ", ".join("·".join(c) for c in combos[:8])
            tail = "" if len(combos) <= 8 else f" …（共 {len(combos)} 组）"
            print(f"   {length}键组合({len(combos)}): " + (shown + tail if combos else "（无）"))
            print('     注：组合里每个字母都隐含「≥1 个字母」（如 g·b = g.* 与 b.*，元音不限）')
            # 3) 实际存在的词语：按组合 → 真实词条（行标题=具体读音）
            words_by_combo: "OrderedDict[str, list[str]]" = OrderedDict()
            for reading, words in data.initial_pattern_rows(prefix, max_rows=32):
                combo = "·".join(t[0] for t in reading.split(" ")) if " " in reading else ""
                combo = "·".join(t[0] for t in reading.split(" "))
                words_by_combo.setdefault(combo, []).append(f"{reading}={','.join(words[:3])}")
            if words_by_combo:
                print("   实际存在词语:")
                for combo, items in words_by_combo.items():
                    print(f"     ({combo}) " + "；".join(items[:4]))
            else:
                print("   实际存在词语: （无）")
            # 4) 联想行：合成音节选完之后的后缀联想（`ga` → `ga zi=挂纸`）
            def combo_of(reading: str) -> str:
                return "·".join(t[0] for t in reading.split(" "))

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
            key = tuple(t[0] for t in tokens)
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
    hidden = [r for r in union if r not in shown]
    print(f"# 單音節：去重後實際存在 {len(union)} 個；面板規則已顯示 {len(union) - len(hidden)} 個"
          + (f"；未顯示（多半被種子/配額截斷）: {' '.join(hidden)}" if hidden else "；無遗漏"))
    if word_parts:
        print(f"# 整串詞行（多音節讀音，碼前綴匹配，面板會按「最長已有前綴」收斂）：{len(word_parts)} 條")
        for part in word_parts:
            print(f"  {part['readings'][0]:<18} 词: {'/'.join(part.get('items', [])[:6])}")
    else:
        print("# 整串詞行：無")


def cmd_build_syllables(args) -> None:
    """從 `hakkaSyllables.js` 抽出標準音節集，寫成 `assets/rime/hakka_syllables.txt`。"""
    text = open(args.source, encoding="utf-8").read()
    match = re.search(r"SYLLABLE_SOURCE\s*=\s*`(.*?)`", text, re.S)
    body = match.group(1) if match else text
    base = [t for t in body.split() if t.isalpha()]
    # 字典音節：用戶 2026-11-11 定稿「字典並入後的就是標準集」
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
        "# 客家話合法音節標準集（定稿：JS 源檔 ∪ hakka.dict.yaml 自帶音節）",
        "#   權威來源 Hkdict/neo-frontend/src/data/hakkaSyllables.js；",
        "#   用戶 2026-11-11 定稿：「字典并入后的就是标准集」（JS 源檔漏收 ji 等，已確認屬人為遺漏）。",
        f"# 共 {len(syllables)} 個 = JS {len(sets[0])} ∪ 詞典 {len(sets[1])} ∪ 補遺 {len(sets[2])}"
        "（由 panel_probe.py build-syllables 生成；App 側仍是本檔 ∪ 詞典的運行時並集）。",
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


def _row_items(g: dict) -> list[str]:
    """一行里的文字：单字组取 `chars`，联想/词行取 `items`（json 输出用，只要文字）。"""
    if g.get("segment") == 0:
        return list(g.get("chars", []))
    return list(g.get("items", []))


def build_json_payload(data: Data, raw_codes: list[str]) -> dict:
    """把若干码串算成 `{code: {codes, sources, merged}}`（json 子命令与 selftest 共用）。

    五源划分严格镜像 App 侧流水线（docs/19 §3）：
    - `synth`      = `segment_groups()` 里「整串码本身是一个完整音节」那一组（split 0、rest 空）；
    - `split`      = `segment_groups()` 里其它切法的单字组（rest 非空）；
    - `assoc`      = `segment_groups()` 里 `segment == 1` 且 `source == "assoc"` 的行；
    - `combo`      = `segment_groups()` 里 `source == "initials"` 的行（`initial_pattern_rows` 产生）；
    - `whole_word` = `prefix_word_rows()` 的整串码前缀词行。
    `merged` 是 `panel(codes)` 的最终顺序（collapse + 分档排序后），只保留文字。
    """
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
        merged = [
            {"reading": g["reading"], "segment": g["segment"], "items": _row_items(g)}
            for g in data.panel(codes)
        ]
        payload[codes] = {"codes": codes, "sources": sources, "merged": merged}
    return payload


def cmd_json(args, data: Data) -> None:
    """结构化输出（golden 单测用）：stdout 只有单个 JSON 对象，缩进 2。"""
    print(json.dumps(build_json_payload(data, args.codes), ensure_ascii=False, indent=2))


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
    # 2026-11-11 用户定案：联想改分桶轮转 + 字母序 + 行数 12，面板不一定选中 li nak；
    # 这里改为验证「联想索引本身有 li+nak（含 Linux）」+「累积汇总里有 li 开头的联想行」。
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

    # 4.45) 首字母組合規則（用戶 2026-11-11：「g.* z.*」，元音自由）
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

    # 4.52) 汇总里 `li nak` 与 `li nak si` 必须各列一条（用戶 2026-11-11：「那你可以这么列呀」）
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

    # 4.53) 詞行第一段必須是打完的音節（用戶 2026-11-11：打 ia 不該列 gam se）
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

    # 4.55) 標準集必須覆蓋詞典音節（用戶 2026-11-11 定稿：字典並入後的就是標準集）
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

    # 4.7) 後綴音節 r：只能落在詞尾（用戶 2026-11-11 特殊規則）
    r_code = code_of("r")
    expect(bool(r_code), f"r 的碼 = {r_code}")
    as_last = [g["reading"] for g in data.segment_groups(r_code)]
    expect("r" in as_last, f"r 作最後一段應出現：{as_last}")
    not_last = [g["reading"] for g in data.segment_groups(r_code + "5i")]
    expect("r" not in not_last, f"r 不在結尾時不得出現：{not_last}")
    phrase = "".join(code_of(s) for s in ("dau", "ha", "r"))
    first = [g["reading"] for g in data.segment_groups(phrase) if g["segment"] == 0]
    expect("r" not in first, f"「到下儿」第一段不得含 r：{first}")

    # 5) 联想：li + 4a5（码展开 = mak/nak）→ 行标题是具体两音节、第二音节首字母 ∈ {m,n}
    #    （2026-11-11 后选行按分桶字母序 + 12 行预算，不再钉死某一行）
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

    # 7) json 输出 schema（App golden 单测对拍）：五源恒在、merged 读音不重复、档位升序
    def tier_of(reading: str) -> int:
        n = len([t for t in reading.split(" ") if t])
        return 2 if n == 0 else (0 if n == 1 else 1)

    payload = build_json_payload(data, ["5i4a57", "ia"])
    for code, entry in payload.items():
        sources = entry.get("sources") or {}
        expect(
            list(sources) == ["synth", "split", "assoc", "combo", "whole_word"],
            f"json[{code}] sources 五个 key 均在且有序：{list(sources)}",
        )
        expect(
            all(
                isinstance(row.get("reading"), str) and isinstance(row.get("items"), list)
                for part in sources.values()
                for row in part
            ),
            f"json[{code}] 五源每行都有 reading(str)/items(list)",
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
    # 上一条在 ia8i3 上恰好是 0；再用真实有命中的 `5i` 守住「组合行能产出且不超上限」
    combo_rows_5i = data.initial_pattern_rows("5i")
    expect(
        0 < len(combo_rows_5i) <= INITIAL_COMBO_MAX_ROWS,
        f"5i 首字母组合行数应在 (0, {INITIAL_COMBO_MAX_ROWS}]（实际 {len(combo_rows_5i)}）",
    )

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
    p_panel.set_defaults(func=cmd_panel)

    p_json = sub.add_parser("json", help="结构化输出五源 + merged（App 侧 golden 单测对拍）")
    p_json.add_argument("codes", nargs="+", help="一个或多个码串/键名，如 5i4a57 'kl ghi mno abc ps'")
    p_json.set_defaults(func=cmd_json)

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
    args.func(args, data)


if __name__ == "__main__":
    main()
