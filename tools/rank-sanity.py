#!/usr/bin/env python3
"""離線校驗候選排序（不裝 App、不點 UI）。

用途
----
改字表 / 權威字頻 / 排序鍵之後，先在電腦上確認「同一個音節的漢字是不是高頻在前」，
再把包裝到手機上；排序規則見 docs/12-candidate-ranking.md。

兩種模式
--------
1) 指定音節，列出該音節的字（按綜合分降序）：
       python3 tools/rank-sanity.py --syllable ji
       python3 tools/rank-sanity.py --syllable ga --limit 20

2) 全量體檢：每個音節的字表都檢查一次「綜合分是否非遞增」：
       python3 tools/rank-sanity.py --audit
       （並印出「綜合分全是 0」的音節數，那代表兩張字頻表都沒覆蓋到）

綜合分算法與 App 保持一致（TwelveKeyPreedit.buildGeneralScores）：
    percentile(table, ch) = (size - rank_desc) * 10000 // size
    generalScore(ch) = max(percentile(char_freq), percentile(char_rank))
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RIME = ROOT / "app/src/main/assets/rime"
DICT = RIME / "hakka.dict.yaml"
FREQ = RIME / "hakka_char_freq.txt"
RANK = RIME / "hakka_char_rank.txt"


def load_weights(path: Path) -> dict[str, int]:
    """`字<TAB>權重` → dict；文件不存在時返回空表（不報錯，方便只改了一張表時試跑）。"""
    out: dict[str, int] = {}
    if not path.exists():
        return out
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) < 2:
            continue
        try:
            out[parts[0]] = int(parts[1])
        except ValueError:
            continue
    return out


def percentile(weights: dict[str, int]) -> dict[str, int]:
    """排名 → 百分位分（0..10000），與 App 的 (size - rank) * 10000 / size 一致。"""
    if not weights:
        return {}
    ordered = sorted(weights.items(), key=lambda kv: -kv[1])
    size = len(ordered)
    return {ch: (size - index) * 10000 // size for index, (ch, _) in enumerate(ordered)}


def load_general_scores() -> dict[str, int]:
    freq = percentile(load_weights(FREQ))
    rank = percentile(load_weights(RANK))
    out = dict(freq)
    for ch, score in rank.items():
        out[ch] = max(out.get(ch, 0), score)
    return out


def load_syllables() -> dict[str, list[str]]:
    """hakka.dict.yaml → {syllable: [字, ...]}（保持詞典順序，即引擎「無 weight」時的文件順序）。"""
    if not DICT.exists():
        sys.exit(f"找不到字表：{DICT}")
    table: dict[str, list[str]] = {}
    for raw in DICT.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) < 2:
            continue
        ch, reading = parts[0].strip(), parts[1].strip()
        if len(ch) != 1 or not reading:
            continue
        chars = table.setdefault(reading, [])
        if ch not in chars:
            chars.append(ch)
    return table


def print_syllable(table: dict[str, list[str]], scores: dict[str, int], syllable: str, limit: int) -> None:
    chars = table.get(syllable)
    if not chars:
        near = [s for s in table if s.startswith(syllable)][:10]
        sys.exit(f"音節 {syllable!r} 沒有字；相近音節：{', '.join(near) or '（無）'}")
    ordered = sorted(chars, key=lambda ch: (-scores.get(ch, 0), chars.index(ch)))
    print(f"音節 {syllable}：{len(chars)} 個字（按綜合分降序，右側為分）")
    for ch in ordered[:limit]:
        print(f"  {ch}  {scores.get(ch, 0):>5}")
    if len(ordered) > limit:
        print(f"  … 其餘 {len(ordered) - limit} 個")


def audit(table: dict[str, list[str]], scores: dict[str, int]) -> int:
    violations = 0
    zero_syllables = 0
    checked = 0
    for syllable, chars in table.items():
        if not chars:
            continue
        checked += 1
        ordered = sorted(chars, key=lambda ch: (-scores.get(ch, 0), chars.index(ch)))
        seq = [scores.get(ch, 0) for ch in ordered]
        if all(score == 0 for score in seq):
            zero_syllables += 1
        for i in range(len(seq) - 1):
            if seq[i] < seq[i + 1]:
                violations += 1
                print(f"  ✗ 音節 {syllable} 排序違反非遞增：{seq[max(0, i - 1):i + 3]}")
                break
    print(f"體檢完成：{checked} 個音節，排序違規 {violations} 個，全為 0 分（兩表都未覆蓋）{zero_syllables} 個")
    return 1 if violations else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="候選排序離線校驗（規則見 docs/12-candidate-ranking.md）")
    parser.add_argument("--syllable", help="只看這個音節（如 ji / ga / nzia）")
    parser.add_argument("--limit", type=int, default=15, help="打印多少個字（默認 15）")
    parser.add_argument("--audit", action="store_true", help="全量體檢：每個音節的綜合分是否非遞增")
    args = parser.parse_args()

    scores = load_general_scores()
    table = load_syllables()
    print(f"字頻表：char_freq={len(load_weights(FREQ))} 字，char_rank={len(load_weights(RANK))} 字，"
          f"綜合分覆蓋 {len(scores)} 字；音節 {len(table)} 個")

    if args.syllable:
        print_syllable(table, scores, args.syllable, args.limit)
        return 0
    if args.audit:
        return audit(table, scores)
    parser.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
