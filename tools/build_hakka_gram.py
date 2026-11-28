#!/usr/bin/env python3
"""
Build a compact, Hakka-first librime-octagram language model (`hakka-zh-hans.gram`)
from real userdb exports (PC + phone), Hakka dictionaries, and high-frequency modern
Chinese phrases, then compile it with librime-octagram's `build_grammar`.

Privacy note:
  - Raw userdb exports stay in /tmp and are NEVER committed.
  - Only pure-CJK 2..4-char statistical collocations (+ 1..2-char sentence-end `$`
    markers) are emitted into the compiled `.gram` binary.
"""

from __future__ import annotations

import argparse
import math
import os
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

CJK_RE = re.compile(r"^[\u3400-\u9fff\U00020000-\U0002ebef]+$")


def is_pure_cjk(text: str) -> bool:
    return bool(text) and bool(CJK_RE.match(text))


def add_phrase_ngrams(
    scores: dict[str, float],
    text: str,
    base_weight: float,
    include_rear: bool = True,
) -> None:
    if not is_pure_cjk(text):
        return
    n = len(text)
    if n < 2 or n > 16:
        return
    # Full phrase if within octagram collocation_max_length (4)
    if 2 <= n <= 4:
        scores[text] += base_weight
    # Sliding 2..4-char windows for longer phrases
    if n >= 3:
        for win in (4, 3, 2):
            if win >= n:
                continue
            scale = 0.65 if win >= 3 else 0.35
            for i in range(0, n - win + 1):
                scores[text[i : i + win]] += base_weight * scale
    if include_rear and n >= 2:
        scores[text[-2:] + "$"] += base_weight * 0.4


def load_userdb_export(path: Path, scores: dict[str, float], multiplier: float) -> int:
    if not path.is_file():
        return 0
    count = 0
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            if not line or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            word = parts[0].strip()
            meta = parts[2].strip()
            if meta.isdigit():
                c = int(meta)
            else:
                m = re.search(r"c=(\d+)", meta)
                c = int(m.group(1)) if m else 0
            if c <= 0 or not is_pure_cjk(word) or len(word) < 2:
                continue
            # Sublinear boost so frequent phrases dominate without saturating
            w = multiplier * (1.0 + math.log1p(c) * 2.5)
            add_phrase_ngrams(scores, word, w, include_rear=True)
            count += 1
    return count


def load_bigram_tsv(path: Path, scores: dict[str, float], multiplier: float) -> int:
    if not path.is_file():
        return 0
    count = 0
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            if not line or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            prev_w, next_w = parts[0].strip(), parts[1].strip()
            try:
                c = float(parts[2])
            except ValueError:
                continue
            if c <= 0 or prev_w == "^":
                continue
            joined = prev_w + next_w
            if is_pure_cjk(joined) and 2 <= len(joined) <= 8:
                w = multiplier * (1.0 + math.log1p(c) * 3.0)
                add_phrase_ngrams(scores, joined, w, include_rear=True)
                count += 1
    return count


def load_rime_dict(
    path: Path,
    scores: dict[str, float],
    min_freq: int,
    base_scale: float,
    max_entries: int | None = None,
) -> int:
    if not path.is_file():
        return 0
    in_body = False
    collected: list[tuple[str, int]] = []
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            s = line.strip()
            if not in_body:
                if s == "...":
                    in_body = True
                continue
            if not s or s.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            word = parts[0].strip()
            if not is_pure_cjk(word) or len(word) < 2 or len(word) > 6:
                continue
            freq = 1
            for p in parts[1:]:
                p = p.strip()
                if p.isdigit():
                    freq = int(p)
                    break
            if freq < min_freq:
                continue
            collected.append((word, freq))
    if max_entries is not None and len(collected) > max_entries:
        collected.sort(key=lambda x: x[1], reverse=True)
        collected = collected[:max_entries]
    for word, freq in collected:
        w = base_scale * (1.0 + math.log1p(freq) * 0.35)
        add_phrase_ngrams(scores, word, w, include_rear=False)
    return len(collected)


def main() -> int:
    ap = argparse.ArgumentParser(description="Build hakka-zh-hans.gram for librime-octagram")
    ap.add_argument("--build-grammar", required=True, help="Path to build_grammar binary")
    ap.add_argument("--assets-dir", required=True, help="Path to hkrime/app/src/main/assets/rime")
    ap.add_argument("--userdb-txt", action="append", default=[], help="Exported userdb .txt files")
    ap.add_argument("--bigram-tsv", action="append", default=[], help="User bigram .tsv files")
    ap.add_argument("--lang", default="hakka-zh-hans", help="Output language name (produces <lang>.gram)")
    ap.add_argument("--out-dir", required=True, help="Directory to write <lang>.gram")
    ap.add_argument("--max-grams", type=int, default=80000, help="Max collocation entries to keep (<2MB)")
    args = ap.parse_args()

    assets = Path(args.assets_dir)
    scores: dict[str, float] = defaultdict(float)

    n_udb = 0
    for u in args.userdb_txt:
        n = load_userdb_export(Path(u), scores, multiplier=18.0)
        print(f"userdb {u}: {n} phrases")
        n_udb += n

    n_bi = 0
    for b in args.bigram_tsv:
        n = load_bigram_tsv(Path(b), scores, multiplier=28.0)
        print(f"bigram {b}: {n} transitions")
        n_bi += n

    n_hakka = load_rime_dict(assets / "hakka.dict.yaml", scores, min_freq=1, base_scale=6.0)
    n_zon = load_rime_dict(assets / "hakka_zon.dict.yaml", scores, min_freq=1, base_scale=8.0)
    n_ice = load_rime_dict(
        assets / "cn_dicts" / "rime_ice_words.dict.yaml",
        scores,
        min_freq=5000,
        base_scale=2.0,
        max_entries=35000,
    )
    print(f"dicts: hakka={n_hakka}, hakka_zon={n_zon}, rime_ice_words(top)={n_ice}")

    # Filter and cap to max_grams highest-weight unique entries
    items = [(k, v) for k, v in scores.items() if v >= 2.0]
    items.sort(key=lambda kv: (-kv[1], kv[0]))
    if len(items) > args.max_grams:
        items = items[: args.max_grams]

    print(f"selected {len(items)} collocations (top 15): {[(k, round(v, 1)) for k, v in items[:15]]}")

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = "".join(f"{k}\t{v:.4f}\n" for k, v in items)

    proc = subprocess.run(
        [str(Path(args.build_grammar).resolve()), args.lang],
        input=payload,
        text=True,
        cwd=str(out_dir),
        capture_output=True,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        return proc.returncode

    gram_path = out_dir / f"{args.lang}.gram"
    size_kb = gram_path.stat().st_size / 1024.0
    print(f"built {gram_path} ({size_kb:.1f} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
