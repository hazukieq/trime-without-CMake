#!/usr/bin/env python3
"""从词词典生成 `Syllables.kt` 的音节表。

音节 = `app/src/main/assets/rime/hakka.dict.yaml` 里每条词编码列（第二列）的音节，
按空格切分后去重。客家话高峰腔的音节表就在这里，不要另外手写/拼普通话拼音表。

用法：在仓库根目录执行 `python3 script/gen-syllables.py`
"""

import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
DICT = ROOT / "app/src/main/assets/rime/hakka.dict.yaml"
OUT = ROOT / "app/src/main/java/com/hazukieq/hkrime/ime/text/Syllables.kt"

HEAD = '''package com.hazukieq.hkrime.ime.text

/**
 * 音節表：**直接取自 [hakka.dict.yaml] 每條詞的編碼列**（高峰腔客家話羅馬字），
 * 共 __N__ 個音節，例如 `ngin`、`ngai`、`hau`、`mh`、`ngh`、`nh`、`cn`、`cw`、`zw`。
 * 不摻任何普通話拼音。
 *
 * 重新生成：`python3 script/gen-syllables.py`（詞典更新後跑一次）。
 */
object Syllables {

  private val TABLE: Set<String> =
    (
__BODY__
        ""
      )
      .split(' ')
      .filter { it.isNotBlank() }
      .toSet()

  private const val MAX_LEN = 8

  data class Segment(
    val text: String,
    val startChar: Int,
    val endChar: Int,
    /** 詞典裡匹配不到（輸入進行中、或鍵盤按出的非常規串） */
    val incomplete: Boolean,
  )

  private fun longestAt(raw: String, i: Int): Int {
    var len = minOf(MAX_LEN, raw.length - i)
    while (len >= 1) {
      if (TABLE.contains(raw.substring(i, i + len).lowercase())) return len
      len--
    }
    return 0
  }

  private fun isSep(c: Char) = c == '\\'' || c == ' '

  /**
   * 把連續輸入串切成音節：
   * - `'` 與空格視為分隔，不進入結果；
   * - 貪心最長匹配（表裡就是 hakka 詞典的音節）；
   * - 某位置不是合法音節開頭時，向後找到第一個能起音節的位置並把中間併成一組
   *   （`ja` 不會被拆成 `j`+`a`）；
   * - 匹配不到完整音節的剩餘串作為一組並標記 incomplete。
   */
  fun split(raw: String): List<Segment> {
    if (raw.isEmpty()) return emptyList()
    val out = ArrayList<Segment>()
    var i = 0
    while (i < raw.length) {
      if (isSep(raw[i])) {
        i++
        continue
      }
      val m = longestAt(raw, i)
      if (m > 0) {
        out.add(Segment(raw.substring(i, i + m), i, i + m, false))
        i += m
        continue
      }
      var j = i + 1
      while (j < raw.length && !isSep(raw[j]) && longestAt(raw, j) == 0) j++
      if (j < raw.length && !isSep(raw[j])) {
        val m2 = longestAt(raw, j)
        out.add(Segment(raw.substring(i, j + m2), i, j + m2, true))
        i = j + m2
      } else {
        out.add(Segment(raw.substring(i, j), i, j, true))
        i = j
      }
    }
    return out
  }
}
'''


def main() -> None:
    syllables = set()
    in_body = False
    for line in DICT.read_text(encoding="utf-8").splitlines():
        if line.strip() == "...":
            in_body = True
            continue
        if not in_body or not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        for token in parts[1].split():
            if re.fullmatch(r"[a-z]+", token):
                syllables.add(token)
    if not syllables:
        raise SystemExit("no syllables parsed from " + str(DICT))
    words = sorted(syllables)
    lines = []
    for i in range(0, len(words), 16):
        lines.append('      "' + " ".join(words[i : i + 16]) + '" +')
    body = "\n".join(lines)
    OUT.write_text(
        HEAD.replace("__N__", str(len(words))).replace("__BODY__", body),
        encoding="utf-8",
    )
    print(f"wrote {OUT} ({len(words)} syllables)")


if __name__ == "__main__":
    main()
