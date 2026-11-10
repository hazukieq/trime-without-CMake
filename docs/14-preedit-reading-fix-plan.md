# 14 · 12 鍵 preedit 拼音與候選讀音對齊（修復方案，待實施）

> 交辦文檔：**先讀再動手**。基線 `b431ece`（2026-11-10），下文行號以該提交為準；
> 改動前先 `git log -1` 確認，工作樹若有改動行號會偏移，**以內容為準**。
> 這是「方案」，不是已實施記錄；實施完把本文件改成 ✅ 並在 `docs/10` 加 §15.x。

## 1. 症狀

12 鍵（`hakka_jiugong`）輸入碼串時，固定候選區上方的拼音 preedit 與**第一條候選的實際讀音**
不一致，例如：

| 輸入 | 首條候選 | preedit 顯示（現狀） | 應該顯示 |
|---|---|---|---|
| `5i34i w`（kiung + th 鍵） | 共產（comment `kiung than`） | `kiung the` 或 `5i34i w` | `kiung than` |
| `5i34i w` | 穷酸（comment `kiung son`） | 同上 | `kiung son` |

補充症狀（同一根因鏈）：
- 有時 preedit 直接顯示原始碼字元（`5i34i w`），不是拼音；
- 選完前段後（preedit 形如 `共w`）碼段解析失效；
- 個別情況拼音像「別的詞」（如 `li voi`），與畫面上的字無關。

## 2. 根因（四層，缺一不可）

1. **讀音靠本地 DP 猜**：`app/src/main/java/com/hazukieq/hkrime/core/TwelveKeyPreedit.kt`
   `decodeSingle()` 的策略是「音節數少優先、再比詞頻高」，所以 `5i34iwe` 被切成
   `kiung the`（2 音節），而引擎候選「共產」的真實讀音在 `comment`（`spelling_hints`）裡是
   `kiung than`。**comment 從來沒被採用。**
2. **hint 取錯對象**：`app/src/main/java/com/hazukieq/hkrime/core/Rime.java` 的
   `getComposition()`（約 :440）用 `mContext.getCandidates()[0].text` 當 hint，
   但候選條順序是 App 排過的（詞組優先 / `SegmentMemory` 記憶），
   **候選條第一條 ≠ 引擎 menu 第一條**，hint 因此可能對不上。
3. **光標標記與半截碼沒處理**：`TwelveKeyPreedit.rewrite()`（約 :659）把 preedit 尾部的
   連續 ASCII 當碼段；librime 的 preedit 帶 `‸`(U+2038) 光標，且半截碼可能被空格分隔
   （`5i34i w‸`）。`‸` 被當成「已選前綴」→ 碼段為空/切不出 → 回落原文；
   半截碼 `w` 也沒翻譯成鍵盤字母 `th`。
4. **沒有校驗就採信**：如果直接拿 `comment` 覆蓋，殘留/錯位的候選（例如 `理會` / `li voi`）
   會被套到這串碼上，出現「字是共產、拼音是 li voi」。

## 3. 目標不變量（實作時逐條自檢）

1. preedit 顯示 = **候選條第一條候選的真實讀音**（有 comment 時），否則 = 自身解碼結果。
2. 已選中的前綴（如 `咧`）必須保留，只改寫尾部碼段。
3. **絕不寫回** `Rime.mContext.composition`（髒讀取會讓下一次 `raw=[共the]`、`decoded=null`）；
   只改 `composition.copy()`。
4. 只在 12 鍵方案（`hakka_jiugong`）生效；26 鍵/其他方案走原路徑，零改動。
5. 全流程只影響「顯示」，不能改變組字/學習/上屏語義。

## 4. 改動清單（3 個檔案 + 1 個測試檔）

### 4.1 `app/src/main/java/com/hazukieq/hkrime/core/TwelveKeyPreedit.kt`

新增兩個發布鉤子（放在 `rewrite` 附近）：

```kotlin
/** 候選條第一條候選的文本；由快照發布點寫入，preedit 用它對齊讀音。 */
@Volatile @JvmField var preferredText: String? = null
/** 候選條第一條候選的 comment = 引擎給的精確拼音。 */
@Volatile @JvmField var preferredComment: String? = null
```

新增碼位→字母映射（與 `TwelveKeySnapshotBuilder.FALLBACK_LETTERS` 同一套，避免兩處漂移；
若改成整包共用，請把 map 提成 `TwelveKeyTuning` 旁的 `object TwelveKeyAlphabet`）：

```kotlin
private val CODE_LETTERS = mapOf(
    '3' to "u", '1' to "ng", 'w' to "th", 'y' to "nz",
    '4' to "m", '5' to "k", '7' to "p", '8' to "z", '9' to "q",
)
private fun codeLetters(code: String) = buildString {
    for (ch in code) append(CODE_LETTERS[ch] ?: ch.toString())
}
```

改寫 `rewrite(preedit: String?, hint: String? = null, preferred: String? = null): String?`：

```kotlin
if (preedit.isNullOrEmpty() || table.isEmpty()) return null
val clean = preedit.replace("\u2038", "")          // ① 剝光標標記
if (clean.isEmpty()) return null
var split = clean.length
while (split > 0 && clean[split - 1].code < 128) split--
if (split == clean.length) return null              // 尾端沒有碼段：已選完
val prefix = clean.substring(0, split)
val codes = clean.substring(split)                  // 可能含內部空格，如 "5i34i w"
```
解碼：
- 先 `decode(codes, hint)`；失敗時對 `cut = codes.length-1 downTo 1` 試
  `decode(codes.substring(0,cut), hint)`，成功則 `head + " " + codeLetters(codes.substring(cut))`；
- 再對結果做一次 **token 正規化**：純 ASCII 的 token（如 `w`）→ `codeLetters(token)`
  （`5i34i w` → `kiung th`）。
採信 comment：
```kotlin
val engine = preferred?.replace("\uFF3B", "")?.replace("\uFF3D", "")?.trim()
    ?.split(' ')?.filter { it.isNotEmpty() }
val own = decoded.split(' ').filter { it.isNotEmpty() }
return if (engine != null && engine.size == own.size && own.isNotEmpty()
        && engine.first() == own.first()) {
    prefix + engine.joinToString(" ")
} else {
    prefix + decoded
}
```
（`own` 用正規化後的 token，這樣半截碼情況 `own = [kiung, th]`，
`engine = [kiung, than]` 段數 2=2、首音節同 → 採用 `kiung than`，正是想要的。）

### 4.2 `app/src/main/java/com/hazukieq/hkrime/ime/text/TextInputManager.kt`

`onTwelveKeySnapshot()`（約 :354，`currentSnapshot = snapshot` 之後、`candidateBarBinder?.bind`
之前）寫入鉤子：

```kotlin
val first = snapshot.barItems.firstOrNull()
TwelveKeyPreedit.preferredText = first?.text
TwelveKeyPreedit.preferredComment = first?.comment
```
空快照時 `first` 為 null → 自動清空（`isComposing()==false` 的場景 preedit 也不會顯示）。

### 4.3 `app/src/main/java/com/hazukieq/hkrime/core/Rime.java`

`getComposition()`（12 鍵分支，約 :445-460）把 hint/preferred 改成：

```java
String barText = TwelveKeyPreedit.INSTANCE.preferredText;
String barComment = TwelveKeyPreedit.INSTANCE.preferredComment;
String hint, preferred;
if (barText != null && !barText.isEmpty()) {
    hint = barText;
    preferred = barComment;                 // 候選條第一條（用戶看到的那條）
} else {
    RimeCandidate[] cs = mContext.getCandidates();
    hint = (cs != null && cs.length > 0) ? cs[0].text : null;
    preferred = (cs != null && cs.length > 0) ? cs[0].comment : null;
}
```
其餘不變：**只在 copy 上改寫**，寫法沿用現有
`RimeComposition copy = composition.copy(); copy.preedit = ...; copy.normalizeAfterRewrite();`

> 注意：Kotlin `@JvmField` 的字段在 Java 端是 `TwelveKeyPreedit.INSTANCE.preferredText`，
> 不是 `getPreferredText()`。

### 4.4 測試 `app/src/test/java/com/hazukieq/hkrime/core/AssociatedWordsTest.kt`

在既有 `rewrite…` 用例旁新增（`@BeforeClass` 已載入詞表/字表，沿用現有 `preedit` 實例）：

| 用例名 | 輸入 | 斷言 |
|---|---|---|
| `rewriteUsesBarCommentWhenCountsAndFirstSyllableMatch` | `rewrite("5i34i w\u2038","共产","kiung than")` | `== "kiung than"` |
| `rewriteStripsCaretMarker` | `rewrite("5i34iw\u2038","共产",null)` | `== "kiung th"` |
| `rewriteMapsPartialTailCodeToLetters` | `rewrite("5i34i w","共产",null)` | `== "kiung th"` |
| `rewriteIgnoresCommentWithDifferentFirstSyllable` | `rewrite("5i34iw","理會","li voi")` | `== "kiung th"` |
| `rewriteIgnoresCommentWithDifferentSyllableCount` | `rewrite("5i34iwe","共產黨","kiung than dong")` | `!= "kiung than dong"` 且以 `kiung` 開頭 |
| `rewriteKeepsSelectedPrefix` | `rewrite("咧5e","咧",null)` | `== "咧le"`（前綴不被吃） |
| `rewriteReturnsNullWhenNoCode` | `rewrite("咧",null,null)` | `null` |

先寫測試 → 確認紅 → 再改碼 → 全綠。全量 `./gradlew :app:testDebugUnitTest` 必須
`failures 0`（基線 55 tests，新增後應 >55）。

## 5. 驗收步驟

1. 單測全綠。
2. 裝機：
   ```bash
   export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9 && ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
   adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
   adb shell settings put secure default_input_method com.hazukieq.hkrime/.TrimeImeService
   ```
3. 真機（vivo V2520A，1080×2376）打 `kl ghi vur mno ghi th`（`5i34iw`）：
   - 候選條第一條為「共產」時，bar 上方拼音必須是 `kiung than`；
   - 日誌 `dbgpre: raw=[5i34i w‸] … decoded=[kiung than]`（現有 dbgpre 日誌即可驗證）；
   - 再抽測 `song` + `i`（`744ii`）一次，preedit 與首條詞的 comment 一致。
4. 迴歸：26 鍵方案打 `nihao`，preedit/候選不受影響；12 鍵選字上屏、退格、面板分組不變。

## 6. 邊界與不要做的事

- **不要**再引入 `segment`/`hot` 之外的排序策略改動；本任務只修 preedit 顯示。
- **不要**把改寫結果寫回 `mContext.composition`（曾導致 `raw=[共the]`、`decoded=null`）。
- **不要**用 `cs[0]` 覆蓋已有 bar 快照的 hint（候選條順序是 App 排的）。
- `preferredComment` 為空（方案沒開 `spelling_hints`）時，自然退回自身解碼，行為等於現狀。
- 效能：本路徑只在 `getComposition()`（每次預編輯刷新）跑一次 string 操作，
  不進 `TwelveKeySnapshotBuilder`；改完跑一次 `python3 tools/perf-gate.py` 確認 `op=decode`/`build_p90` 無退化。

## 7. 回退

單獨一個 commit（建議 `fix(preedit): 拼音取候選條第一條的真實讀音`）。
回退 `git revert <sha>` 即可；不影響候選條策略、熱門組、性能門禁閾值。

## 8. 交接檢查表

- [ ] 三個檔案改動完成，且沒有其它檔案被「順手」改到（`git status` 只能有這三個 + 測試檔 + 本文檔）
- [ ] 新增 7 個測試，全量 `failures 0`
- [ ] 真機 `5i34iw` 顯示 `kiung than`（截圖 + 日誌各一）
- [ ] `perf-gate.py` PASS
- [ ] `docs/10` 加 §15.x 指向本文件並標記 ✅
