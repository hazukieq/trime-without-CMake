#!/usr/bin/env python3
"""生成 12 键展开面板用的「通用字频」表：app/src/main/assets/rime/hakka_char_freq.txt

词表本身没有 weight（sort: by_weight 但条目只有 `词<TAB>读音`），拿 librime 排序会退回
文件顺序（喏/偌 这类生僻字排前面）。这里用词表里每个字的出现次数当代理指标：

    score(ch) = Σ 词表权重 × 出现次数
    hakka_oftenwords ×3 / hakka_mobilewords ×2 / 其余词表 ×1

用法：python3 tools/gen-char-freq.py  （在 hkrime 仓库根目录执行）
"""
import collections
import os

BASE = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "rime")
WEIGHTS = {
    "hakka_oftenwords": 3,
    "hakka_mobilewords": 2,
    "hakka_sougou_words": 1,
    "hakka_places": 1,
    "hakka_poems": 1,
    "hakka_website": 1,
    "hakka_zon": 1,
    "hakka_cn_en": 1,
}


def is_cjk(ch: str) -> bool:
    return "\u4e00" <= ch <= "\u9fff" or "\u3400" <= ch <= "\u4dbf"


def main() -> None:
    counter = collections.Counter()
    for name, weight in WEIGHTS.items():
        path = os.path.join(BASE, name + ".dict.yaml")
        if not os.path.exists(path):
            continue
        started = False
        with open(path, encoding="utf-8", errors="ignore") as f:
            for raw in f:
                line = raw.strip()
                if not started:
                    started = line == "..."
                    continue
                if not line or line.startswith("#"):
                    continue
                word = line.split("\t")[0].strip()
                for ch in word:
                    if is_cjk(ch):
                        counter[ch] += weight

    # 词典里出现但词表没有的字也给 0 分占位，保证文件覆盖所有可用字
    with open(os.path.join(BASE, "hakka.dict.yaml"), encoding="utf-8", errors="ignore") as f:
        for raw in f:
            parts = raw.strip().split("\t")
            if len(parts) >= 2 and len(parts[0].strip()) == 1 and is_cjk(parts[0].strip()):
                counter.setdefault(parts[0].strip(), 0)

    out = os.path.join(BASE, "hakka_char_freq.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write("# 由 tools/gen-char-freq.py 从词表统计生成：字<TAB>权重（越高越常用）\n")
        for ch, value in sorted(counter.items(), key=lambda kv: (-kv[1], kv[0])):
            f.write(f"{ch}\t{value}\n")
    print(f"wrote {out}: {len(counter)} chars")


if __name__ == "__main__":
    main()
