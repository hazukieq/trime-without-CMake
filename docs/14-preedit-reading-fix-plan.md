# 14 · 12 鍵 preedit 拼音與候選讀音對齊（✅ 已實施，2026-11-10）

> 交辦文檔：**先讀再動手**。基線 `b431ece`（2026-11-10），下文行號以該提交為準；
> 改動前先 `git log -1` 確認，工作樹若有改動行號會偏移，**以內容為準**。
> **已實施**：見文末 §9「實施記錄」，變更說明在 `docs/10` §15.61；真機驗收見
> `artifacts/12key/verify/PREEDIT-READING-FIX.md`。原方案正文保留（作為設計依據），
> 實施時與方案的差異在 §9 逐條列出。

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

> 下段是**原方案提案**（含當時的採信校驗）。最終實現按 §9 第 3 點改為「comment 一律優先、
> 不做本地校驗」，代碼以 `TwelveKeyPreedit.kt` 為準。

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

> 註：上表第 5 個用例（`rewriteIgnoresCommentWithDifferentSyllableCount`）在實施時**被推翻**：
> 共產黨 = kiung than dong 正好吃完 `5i34iwe`，引擎首條是它的話就該採信（見 §9 第 3 點）。
> 實際測試清單以 `AssociatedWordsTest` 為準。

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

- [x] 三個檔案改動完成，且沒有其它檔案被「順手」改到（`git status` 只有這三個 + 測試檔 + 本文檔 + 驗收產物）
- [x] 新增 19 個測試，全量 `failures 0`（74 tests：基線 55 + 14 preedit + 5 排序）
- [x] 真機 `5i34i` 顯示 `li voi`（首條是詞）、`5i34iw` 顯示 `kiung than`（截圖 + 日誌，見 `artifacts/12key/verify/`）
- [x] `perf-gate.py` PASS
- [x] `docs/10` 加 §15.61 指向本文件並標記 ✅

## 9. 實施記錄（2026-11-10）

與方案正文的**差異**（前兩點是靜態閱讀沒看到、真機跑出來才發現的；第三點是用戶對語義的糾正）：

1. **多了「快照落地後重畫 preedit」這一步**（方案沒寫）。
   `getComposition()` 在按鍵鏈路裡比快照先跑（`onEngineChanged().updateComposing()` →
   之後才有 `onTwelveKeySnapshot()`），所以剛發布的鉤子要等下一次預編輯刷新才會生效 ——
   preedit 會永遠**落後一個按鍵**（顯示上一條候選的讀音）。真機第一版日誌：
   `raw=[5i34i w‸] hint=[理会] preferred=[li voi] decoded=[kiung th]`，而候選條第一條是「窮酸」。
   修法：`TextInputManager.onTwelveKeySnapshot()` 比較首條 (text, comment) 是否變化，
   變化且仍在組字時調一次 `trime.updateComposing()`；並用 `suppressSnapshotRefresh` 抑制
   `updateComposing()` 內部那一輪重複的快照重建（快照已是最新）。
2. **decode 要逐段做半截碼兜底**（方案只寫了「整串 decode 失敗再從尾部切」）。
   librime 的 preedit 會用空格分隔「已消費前綴 / 半截碼」，且 `w`(th) 這種鍵本身不是完整音節，
   整串 `decode("5i34i w")` 會走 decode() 的空格分支、把 `w` 原樣留在結果裡。
   改成按空格逐段 `decodeOne()`：先 `decode()`，失敗再「最長可解碼頭部 + 尾部碼位字母」。

3. **採信規則簡化成「comment 一律優先」**（方案 §4.1 寫的是「段數相同 + 首音節相同」，
   中間一版又改成「讀音能否吃完輸入碼」，最後按用戶 2026-11-10 的定調去掉本地校驗）。
   理由：12 鍵是多對多，同一串碼沒有唯一讀音 —— `5i34i` 既可以是 kiung（1 個音節），
   也可以是「理會」的 `li voi`（li=5i、voi=34i，2 個音節）。任何「本地解碼 vs comment」的比對
   都建立在「存在正確解碼」這個錯誤假設上，會把「首條是詞」這種最常見的情況擋掉。
   最終實現：`rewrite()` 裡有 comment 就直接返回它（完全不跑本地解碼），
   本地 DP 只在引擎沒給 comment（未開 `spelling_hints`）時兜底。
   一致性由「comment 與候選條文字同源發布 + 快照落地後重畫 preedit」保證，不靠比對。
   對應單測：`rewriteUsesWordReadingWhenFirstCandidateIsAWord`（雙字詞）、
   `rewriteUsesThreeSyllableWordReading`（三字詞）、`rewriteTrustsFirstCandidateCommentAsIs`、
   `rewriteFallsBackToOwnDecodeWithoutComment`。

4. **追加修復：候選學習排序不得跨「覆蓋深度」**（用戶 2026-11-10 第二輪反饋）。
   症狀：打完整三音節 `5i34i wa4 e44i`（kiung than dong = 共產黨）時 preedit 只剩 `kiung than`，
   最後一個 `dong` 被吃掉。根因不在 rewrite，在排序：引擎順序是
   `0:共产党/kiung than dong → 1:共产/kiung than → 2:穷/kiung …`，
   但 `SelectionMemory` 把之前選過的「共產」（只覆蓋兩音節）頂到了第一，
   preedit 忠實顯示首條讀音，於是少一節。
   修法：`CandidateRanking.rankWithLearning(items, topSyllables, score)` —— 主鍵 =
   覆蓋深度（comment 音節數，封頂到引擎首條的深度），次鍵 = 學習分數，再次 = 引擎下標。
   效果：三音節輸入時「共產黨」一定在「共產」前；同深度內學習照舊生效
   （`5i34iw` 的首條仍是學過的「共產」）。新增 `CandidateRankingTest` 5 個用例。

5. **追加修復（二）：按下的鍵必須全部顯示**（用戶 2026-11-10 定調）。
   「輸入的編碼一定要顯示（暫且不管對不對，這是首要的），至於音節正確是第二步」。
   症狀：打 7 節的中國人民共和國，preedit 只剩 `zung get nzin min`，後面 `kiung fo get` 被吞。
   修法（`TwelveKeyPreedit.rewrite`）：comment 節數 >= 本地解碼節數 → 只顯示 comment；
   否則 comment + **原始編碼**（未覆蓋的碼位原樣顯示）。本地解碼只用來算
   「comment 覆蓋到第幾個字元」，不用它的音節去拼尾巴（用戶：「不要試圖去拼湊」）。
   查證：引擎不給整句是因為 `translator.enable_sentence: false`（2026-11-08 ANR 後關掉），
   候選只來自詞典詞條 —— 「中國人民」在詞典（4 節）、「中國人民共和國」不在。

其餘與方案一致：

- `TwelveKeyPreedit`：新增 `@Volatile @JvmField preferredText / preferredComment`；
  `rewrite(preedit, hint, preferred)` 剝 `‸`、保留已選前綴、只改寫尾部碼段；
  採信規則 = **comment 一律優先**（見上面第 3 點；方案原寫的「段數相同」已廢除）；
  碼位→字母映射 `CODE_LETTERS` 與 `TwelveKeySnapshotBuilder.FALLBACK_LETTERS` 同值（註釋互相指向）。
- `TextInputManager.onTwelveKeySnapshot()`：`snapshot.barItems.firstOrNull()` → 鉤子（空快照寫 null）。
- `Rime.getComposition()`：12 鍵分支優先取鉤子，沒有快照才退回引擎 menu 第一條；
  仍然只改 `composition.copy()`，**不寫回** `mContext.composition`。
- 只在 `hakka_jiugong` 生效，26 鍵/其他方案零改動。

驗收：74 tests / 0 failures；`perf-gate.py` PASS（latency_p90 42.5ms，閾值 80）；
真機 `5i34i wa4 e44i` → preedit `kiung than dong`（首條「共產黨」）；`5i34i` → preedit `li voi`（首條是詞「理會」）；`5i34iw` → preedit `kiung than`（首條「共產」）、`kiung thon`（首條「窮酸」）；
`744ii`（preedit `744 ii‸`）→ 首條「船寄」`son gi`。

