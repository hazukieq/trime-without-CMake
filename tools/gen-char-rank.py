#!/usr/bin/env python3
"""生成权威字频表：app/src/main/assets/rime/hakka_char_rank.txt

数据来源：rime-ice（雾凇拼音）的 cn_dicts/8105.dict.yaml —— 《通用规范汉字表》8105 字，
每个字带 25 亿字语料统计的字频权重（同音候选就是按它排序的）。
本表只用来做「引擎原顺序」之前的硬排序：常用字在前、生僻字在后。

用法：python3 tools/gen-char-rank.py [本地 8105.dict.yaml 路径]
不带参数时自动下载到 /tmp/8105.dict.yaml。
"""
import os
import subprocess
import sys

URL = "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/8105.dict.yaml"
BASE = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "rime")


def load(path: str) -> dict:
    weights = {}
    started = False
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not started:
                started = line == "..."
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3 or len(parts[0]) != 1:
                continue
            try:
                weight = int(parts[2])
            except ValueError:
                continue
            ch = parts[0]
            if weight > weights.get(ch, 0):
                weights[ch] = weight
    return weights


def main() -> None:
    path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/8105.dict.yaml"
    if not os.path.exists(path):
        subprocess.check_call(["curl", "-sL", "-o", path, URL])
    weights = load(path)
    out = os.path.join(BASE, "hakka_char_rank.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write("# 由 tools/gen-char-rank.py 从 rime-ice cn_dicts/8105.dict.yaml 生成\n")
        f.write("# 字<TAB>权威字频权重（25 亿字语料统计，出自《通用规范汉字表》8105 字表）\n")
        for ch, weight in sorted(weights.items(), key=lambda kv: -kv[1]):
            f.write(f"{ch}\t{weight}\n")
    print(f"wrote {out}: {len(weights)} chars")


if __name__ == "__main__":
    main()
