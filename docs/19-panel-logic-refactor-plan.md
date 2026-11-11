# 展開面板「邏輯層」重構執行方案（可直接交給下一個 agent 執行）

> 狀態：**方案（未實施）** ｜ 2026-11-11 ｜ 用戶已審閱架構方向並要求：
> ①常量要清晰、解釋清楚；②架構可持續維護/可調試；③**UI 層零改動**；④同一讀音去重合併，**來源不外顯**。

---

## 0. 一句話目標

把 `TwelveKeySnapshotBuilder.segmentGroupsFor()` 這個「五種來源揉在一起」的大函數，拆成
**每源一個純函數 → 統一合併去重 → 固定分檔排序 → 固定截斷** 的流水線；行為先保持不變（步驟 1），
再把腳本裡已驗證的「首字母組合詞行」作為新來源接進同一條流水線（步驟 3）。

---

## 1. 範圍與非目標

**做**（只在 `app/src/main/java/com/hazukieq/hkrime/core/twelvekey/` 與 `core/TwelveKeyPreedit.kt`）：

- 數據模型：`PanelRow` + `RowSource`（內部用，不進 `SyllableGroup`/`CandidateItem`）；
- 五個來源函數從 `segmentGroupsFor` 拆出，各自可單測；
- 唯一合併點 `mergeRows()`（同 `reading` 合併、`items` 文字去重、來源並集）；
- 固定流水線：`build → merge → collapse → tier/sort → cap → project`；
- `INITIAL_COMBO` 新來源（規則已在 `tools/panel_probe.py` 驗證並有用例）；
- 常量治理：全部放進 `TwelveKeyTuning`，每個常量 4 行註釋（見 §5）；
- 可觀測性：`diag.log` 每幀打各源行數與合併後行數；`twelvekey:build` 保留毫秒分解。

**不做**：

- 不動 `CandidateBarBinder` / `CandidatePanelController` / `CandidateGroupAdapter` / 任何 UI 佈局與主題；
- 不動引擎側（librime/patches）與詞庫；
- 不改 `SyllableGroup` / `CandidateItem` / `TwelveKeySnapshot` 的公開字段（只允許**新增**帶默認值的內部字段，且不參與 UI）；
- 不改 `recommendFrom()` 語義、不改候選條 12/4 佈局、不改 preedit 規則（`TwelveKeyPreedit.render` 不動）。

---

## 2. 現狀地圖（執行前必讀）

| 位置 | 內容 | 重構後去向 |
|---|---|---|
| `TwelveKeySnapshotBuilder.kt`：`segmentGroupsFor(remaining, anchor)` | 合成音節單字組 + 備選切法單字組 + 聯想行 + 整串詞行 + 引擎組合併，末尾接 collapse/sort | 拆成 §3 的 5 個源函數 |
| 同文件 `debugSegmentGroups()` | 單測入口 | 保留，內部改調流水線（`wantGroups=true`、無引擎） |
| 同文件 `prefixWordRows` | 整串碼詞行 | `WHOLE_WORD()` 源 |
| 同文件 `collapsePrefixRows` | 多音節行按最長已有前綴收斂 | 流水線固定第 3 步 |
| 同文件 `mergeRowsByReading` | 按讀音合併 | **併入** `mergeRows()`（唯一合併點） |
| 同文件 `recommendFrom` / `shouldShowFallback` | 推薦組 / 兜底判據 | 不動，僅在流水線外調用 |
| `CandidateRanking.panelGroups` / `mergeAssociationGroups` | 引擎組 + 段組合併 | `ENGINE()` 源 + 合併點的一部分（先引擎後 App 織補） |
| `TwelveKeyPreedit.syllablesForSegment` | 音符展開（含 suffix-only 規則） | 各源共用，不改 |
| `TwelveKeyPreedit.associatedBySegmentCode` / `associatedByReading` / `wordsByCodePrefix` | 聯想/詞行查詢 | `ASSOC()` / `WHOLE_WORD()` 內部使用 |
| `TwelveKeyTuning.kt` | 常量集中地 | 新增 §5 的常量，剩下見 §5.3 審計 |
| `tools/panel_probe.py` | 腳本鏡像（已含五源分塊輸出） | 作為 **golden 對照**，步驟 1/2/3 都要對得上 |

---

## 3. 目標架構

### 3.1 數據模型

```kotlin
// 新文件：core/twelvekey/PanelRow.kt
enum class RowSource {
    SYNTH_SINGLE,   // 整串碼恰好一個完整音節（ia → ga/ha）
    SPLIT_SINGLE,   // 備選切法的第一段單字組（i|a → gau/gen…）
    ASSOC,          // 兩音節聯想（ga → ga zi）
    INITIAL_COMBO,  // 每鍵首字母笛卡爾積 → 按讀音首字母序列查詞表（新）
    WHOLE_WORD,     // 整串碼前綴命中的多音節詞條（li nak si → Linux）
    ENGINE,         // librime 候選組
}

data class PanelRow(
    val reading: String,               // 行的讀音（具體音節；永不含通配）
    val items: List<CandidateItem>,    // 單字（segment=0）或詞（segment=1）
    val segment: Int,                  // 0=單字組 / 1=詞行；沿用現有語義
    val restCode: String = "",         // 點擊後要餵回引擎的剩餘碼（單字組用）
    val sources: Set<RowSource>,       // 內部診斷用；投影成 SyllableGroup 時丟棄
)
```

**投影**：`PanelRow → SyllableGroup(reading, syllables=reading.split(' '), items, segment)`
——與現有構造完全一致，UI 無感。

### 3.2 流水線（唯一入口）

```kotlin
internal fun buildPanel(
    remaining: String,
    anchor: Char?,
    wantGroups: Boolean,
    engineGroups: List<SyllableGroup>,   // 來自 CandidateRanking.panelGroups
): List<SyllableGroup> {
    val synth   = if (wantGroups) SYNTH_SINGLE(remaining, anchor) else emptyList()
    val splits  = if (wantGroups) SPLIT_SINGLE(remaining, anchor) else emptyList()
    val assoc   = if (wantGroups) ASSOC(remaining) else emptyList()
    val combo   = if (wantGroups) INITIAL_COMBO(remaining) else emptyList()
    val words   = if (wantGroups) WHOLE_WORD(remaining) else emptyList()
    val engine  = ENGINE(engineGroups)

    var rows = synth + splits + assoc + combo + words + engine
    rows = mergeRows(rows)                    // ★ 唯一合併點
    rows = collapsePrefixRows(rows)           // 多音節行收斂（行為同現狀）
    rows = tierAndSort(rows)                  // 單音節 → 多音節 → 無讀音；檔內按 reading
    rows = capRows(rows)                      // 各源上限 → 全局 MAX_GROUPS
    return rows.map { it.toSyllableGroup() }
}
```

> 註：`wantGroups=false`（候選條模式）時不出 App 側分組，維持現狀（`barRows` 從同一批 rows 取）。

### 3.3 五源規格（必須逐條實現，且與 `panel_probe.py` 對齊）

| 源 | 規則 | 常數 | 空時 |
|---|---|---|---|
| `SYNTH_SINGLE` | `isSingleSyllable(remaining)` → 該音節的單字組（`exactOnly=true`） | `SEGMENT_CHARS_LIMIT`、`SEGMENT_SYLLABLE_LIMIT(_RICH)` | 不產出 |
| `SPLIT_SINGLE` | `splitHeads(remaining)` → 每種切法的第一段音節候選（配額減半，`exactOnly` 見現狀註釋） | `SECONDARY_SPLIT_DIVISOR`、`RICH_SYLLABLE_THRESHOLD` | 不產出 |
| `ASSOC` | 第一段候選（前綴展開，含 `allowSuffixOnly = rest.isEmpty()`）× 第二段碼前綴展開；`assocByFirst[s1][s2]` 精確哈希；按（首字母,首字母）分桶輪轉 | `ASSOC_WORDS`、`ASSOC_MAX_ROWS`、`ASSOC_HEAD_SYLLABLES`、`ASSOC_INITIALS`、`ASSOC_INITIAL_ROWS` | 跳過 |
| `INITIAL_COMBO` | 每鍵首字母集合（元音 `aeiou` 排除；`r` 僅末鍵）笛卡爾積 → 按「讀音首字母序列」查 `wordCodeIndex`（同長度、首字母全等） | **新增**：`INITIAL_COMBO_MAX_ROWS=8`、`INITIAL_COMBO_ROWS_PER_PATTERN=1`、`INITIAL_COMBO_PATTERN_LIMIT=64` | **整組跳過**（用戶定） |
| `WHOLE_WORD` | `wordsByCodePrefix(remaining)`（要求第一段打全，見 `TwelveKeyPreedit.wordsByCodePrefix`） | `ASSOC_PREFIX_READINGS`、`ASSOC_WORDS` | 跳過 |
| `ENGINE` | 傳入的引擎組原樣轉 `PanelRow` | `MAX_CANDIDATES` | 跳過 |

**不變式（寫進 §8 測試）**

1. `reading` 永不含通配/星號；每行讀音的音節數 = `reading.split(' ')` 長度；
2. 同一 `reading` 只允許一行（合併後）；
3. `INITIAL_COMBO` 的組合命中為 0 → 不產生行；
4. `r` 不得出現在非末位音節（`SUFFIX_ONLY_SYLLABLES` 規則）；
5. `recommend` 必須 = `fullBarOrder` 減去候選條已佔用項（現有不變式，重構不得破壞）；
6. `wantGroups=false` 與 `true` 的 `barEntries/previewText/inlineText` 必須一致（`SnapshotDecouplingTest`）。

---

## 4. 合併、分檔、截斷（固定位置，不許各源自己搞）

### 4.1 `mergeRows(rows)`（唯一合併點）

1. 按 `reading` 歸併（保持**首次出現順序**，用 `LinkedHashMap`）；
2. `items` 合併：文字大小寫不敏感去重；**引擎項在前**、App 織補在後（沿用 `mergeAssociationGroups` 口徑）；
3. `sources` 並集；`segment` 取「單字組(0) > 詞行(1)」（同讀音既有單字又有詞時，按單字組呈現）；
4. `restCode` 取首個非空。

### 4.2 `collapsePrefixRows(rows)`（行為不變，位置固定）

僅作用於 `reading` 音節數 ≥2 的行，按「最長已有前綴讀音」收斂（`ko thiau fa` → `ko thiau`）；
單音節行不吸收詞行。

### 4.3 `tierAndSort(rows)`

檔位 = **讀音音節數**：`1 → 0`，`≥2 → 1`，無讀音(`BLANK_READING`)→ `2`；檔內按 `reading.lowercase()` 字典序。
**禁止**用 `syllables.size` 判檔（引擎組 `syllables` 只有 1 個元素，歷史 bug）。

### 4.4 `capRows(rows)`

- 每源內部上限（§3.3）；
- 全局 `MAX_GROUPS=200`；
- `INITIAL_COMBO` 再按「全局行數預算」截斷：`min(INITIAL_COMBO_MAX_ROWS, 剩餘預算)`。

---

## 5. 常量治理（用戶明確要求：清晰 + 解釋清楚）

### 5.1 規則

1. **只放在 `TwelveKeyTuning.kt`**：不許在 builder/preedit 裡出現產品策略數字；
   `MAX_RESULT_GROUPS=40`（builder 裡）與 `MAX_SYLLABLES_TOTAL`（preedit 裡）本次要**搬進** Tuning；
2. 每個常量必須有 4 行 KDoc：`影響哪一步` / `現在值` / `調大的後果` / `調小的後果`；
3. 派生常量寫出公式（例：`RICH_SYLLABLE_THRESHOLD = SEGMENT_SYLLABLE_LIMIT`）；
4. 常量改名要同步：`docs/17-candidate-panel.md`、`tools/panel_probe.py` 的同名常量、`AGENTS.md` §2.4。

### 5.2 新增常量（步驟 3 用）

| 常量 | 值 | 含義 / 為什麼 |
|---|---|---|
| `INITIAL_COMBO_MAX_ROWS` | `8` | 首字母組合行總行數上限。`ia8` 有 8 個組合、`ia8i3` 有 32 個；全出會淹沒單字組。先給 8，真機覺得少再放開。 |
| `INITIAL_COMBO_ROWS_PER_PATTERN` | `1` | 每個首字母組合先出 1 行（保證「g·b / g·c / h·b / h·c」都有代表），剩餘額度再出第 2 行。 |
| `INITIAL_COMBO_PATTERN_LIMIT` | `64` | 組合數上限；超過（>4 鍵的長輸入）時**不生成**首字母組合行，避免 2^k 爆炸。 |
| `VOWEL_LETTERS`（常量集） | `aeiou` | 不能作音節首字母的字母；與 `panel_probe.py` 的 `VOWEL_LETTERS` 必須一致。 |

### 5.3 常量審計清單（步驟 4 做完）

- [ ] `ASSOC_HEAD_SYLLABLES=4` 與 `ASSOC_INITIAL_ROWS=2` 語義重疊 → 註釋寫清「前者=幾個第一音節，後者=每個第一音節幾行」；
- [ ] `ASSOC_INITIALS=2` 與 `ASSOC_READINGS=4` 註釋缺失 → 補齊；
- [ ] `MAX_SYLLABLES_TOTAL` 從 `TwelveKeyPreedit` 移到 `TwelveKeyTuning`；
- [ ] `MAX_RESULT_GROUPS=40` 從 builder 移到 Tuning；
- [ ] 所有常量在 `docs/17` §7.5.3 表格裡列一列（值 + 作用）。

---

## 6. 可維護性 / 可調試性要求（架構驗收項）

1. **每源一行日誌**（`diag.log` 與 `twelvekey:build`）：
   `panel sources synth=2 split=8 assoc=6 combo=4 word=1 engine=6 → merged=21 collapsed=19 tier=[1:6,2:11,0:2] capped=19`
   —— 出問題能直接判斷「哪源多給/少給/被截斷」；
2. **純函數可測**：五個源函數全部 `internal`，入參只有 `(remaining, anchor)`，不碰引擎、不碰 UI；
3. **golden 對照**：`panel_probe.py --json`（步驟 1 新增）輸出 `{code: {sources: {...}, merged: [...]}}`，
   單測讀同一份 JSON 斷言 App 每源集合與腳本一致（差異即失敗）；
4. **失敗可回滾**：4 個提交，每步獨立可 `git revert`（見 §7）；
5. **註釋要求**：每個源函數頭部寫「來源 / 為什麼需要它 / 與其他源的邊界 / 對應腳本函數名」。

---

## 7. 遷移步驟（4 個提交，逐步可驗收）

### 步驟 1：純拆分，行為零變化（commit A）

- 新增 `core/twelvekey/PanelRow.kt`（`RowSource`、`PanelRow`、`mergeRows`、`tierAndSort`、`capRows`）；
- 把現有 `segmentGroupsFor` 拆成 `SYNTH_SINGLE`/`SPLIT_SINGLE`/`ASSOC`/`WHOLE_WORD` 四個內部函數（**逐字搬移現有邏輯**，不改判斷）；
- `ENGINE()` 只做 `CandidateRanking.panelGroups` + 合併轉換；
- `mergeRows` 先只做「同 reading 合併 items（= 現 `mergeRowsByReading`）」；
- 驗收：
  - `./gradlew testDebugUnitTest --offline --rerun-tasks` 全綠（現 144+）；
  - `python3 tools/panel_probe.py selftest` 0 失敗；
  - 真機抽樣 `ia`、`ia8`、`5i34i`、`5i4a57`、`744ii`、`5i34iwe`：面板左列**逐行與改前一致**（用 diag 行數對比）；
  - `python3 tools/perf-gate.py` PASS（build_p90 <40ms）。
- 回滾：`git revert <A>`。

### 步驟 2：流水線固定化（commit B）

- `collapse → tierAndSort → capRows` 從原位置搬到 `buildPanel`；`recommendFrom` 外部調用不變；
- `prefixWordRows` 改走 `WHOLE_WORD()`；`mergeRowsByReading` 刪除（併入 `mergeRows`）；
- 驗收：同步驟 1（行為應完全不變 + 全綠）。

### 步驟 3：接入 `INITIAL_COMBO`（commit C）

- `TwelveKeyPreedit` 新增：
  - `keyInitialLetters(codeChar: Char, isLast: Boolean): List<Char>`（元音排除、`r` 僅末鍵）；
  - `initialCombos(codes: String): List<List<Char>>`（笛卡爾積，受 `INITIAL_COMBO_PATTERN_LIMIT`）；
  - `wordsByInitialPattern(combo: List<Char>): List<Pair<String, List<String>>>`（`wordCodeIndex` 按首字母序列匹配）；
- `INITIAL_COMBO()` 源：按組合輪轉取行（每組合先 1 行），空組合跳過；
- 常量入 Tuning（§5.2）；
- 單測：`ia` → 4 組、`ia8` → 8 組、`ia8i3` → 32 組；`g·b` 命中 `gang bung`；空組（`ia8` 的三音節組合）不產生行；`r` 僅末鍵；
- 驗收：真機 `ia`/`ia8` 面板出現對應行且不多於 `INITIAL_COMBO_MAX_ROWS` 行；腳本同 code 對照一致；perf PASS。

### 步驟 4：收尾（commit D）

- 常量審計（§5.3）全部打勾；
- `docs/17` 更新：§7 各源表、§8 合併規則、§11 不變式（含「空組合跳過」「來源不外顯」）；
- `docs/19` 標記 ✅ 已實施並記錄最終常量值；
- `AGENTS.md` §2.4 增補「面板流水線：五源 → mergeRows → collapse → tier/sort → cap」硬規則。

---

## 8. 測試與驗收命令（每次提交都跑）

```bash
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9
cd hkrime
./gradlew assembleDebug testDebugUnitTest --offline --rerun-tasks
python3 tools/panel_probe.py selftest          # 必須 0 失敗
python3 tools/perf-gate.py                     # PASS（build_p90<40 / latency_p90<80）
cd ../rime-jni/host-test && ./pull-real-data.sh && ./run-host-test.sh --corpus /tmp/hkrime-corpus.jsonl
cd ../../hkrime && ./gradlew testDebugUnitTest --tests '*RealCorpusTest*'
# 裝機（用戶要求：只裝，不替他測試）
adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
adb shell ime set com.hazukieq.hkrime/.TrimeImeService
```

**新增單測（步驟 3 必須有）**

- `PanelRowMergeTest`：同 reading 合併（items 並集、文字去重、sources 並集）；`segment` 取單字組；
- `InitialComboRowsTest`：組合數（4/8/16/32）、元音排除、`r` 僅末鍵、空組合不產行、行數上限；
- `SegmentSourceBoundaryTest`：`SYNTH_SINGLE` 與 `SPLIT_SINGLE` 邊界（`4i` 只出 4 音節；`we` 出 the + than）、
  `ASSOC` 只出 (s1,s2) 精確命中、`WHOLE_WORD` 要求第一段打全；
- 擴充 `SnapshotDecouplingTest`：`wantGroups=false/true` 的 bar/preedit 一致性在重構後仍成立。

---

## 9. 風險與已知坑（執行時務必遵守）

1. **不要用 `syllables.size` 分檔**（引擎組只有 1 個元素）——用 `reading.split(' ')` 的長度；
2. **`reading` 去重要大小寫不敏感**（`Linux`/`linux`）；`items` 文字去重同理；
3. **`INITIAL_COMBO` 必須走首字母序列匹配**，不是碼前綴（歷史錯誤：碼前綴等於把元音釘死為 a/b/c）；
4. **`WHOLE_WORD` 要求第一段音節打全**（`wordsByCodePrefix` 已修，別回退）；
5. **`r` 是後綴音節**：非末位不允許（`syllablesForSegment(allowSuffixOnly=false)`）；
6. **合併點只有一處**：禁止在源函數內自行去重/排序，否則腳本對照會漂移；
7. **UI 契約**：`SyllableGroup` 字段不變；`sources` 不得進入 UI 可觀察狀態；
8. 改完必須同步 `tools/panel_probe.py`（同一規則兩份實現，靠 `selftest` + golden JSON 鎖住）。

---

## 10. 交付物清單

- [ ] `core/twelvekey/PanelRow.kt`（模型 + `mergeRows`/`tierAndSort`/`capRows`）
- [ ] `TwelveKeySnapshotBuilder.kt` 五源函數 + `buildPanel` 流水線
- [ ] `TwelveKeyPreedit.kt`：`keyInitialLetters` / `initialCombos` / `wordsByInitialPattern`
- [ ] `TwelveKeyTuning.kt`：新增 4 常量（§5.2）+ 審計修訂（§5.3）+ 搬入 2 常量
- [ ] 新增 3 組單測 + 擴充 `SnapshotDecouplingTest`
- [ ] `tools/panel_probe.py --json` + golden 對照
- [ ] `diag.log` / `twelvekey:build` 來源統計行
- [ ] `docs/17` 各源/合併/不變式更新；`docs/19` 標 ✅；`AGENTS.md` §2.4 增補
- [ ] 4 個提交（A 拆分 / B 流水線 / C 新源 / D 收尾），每個都能獨立 revert

---

## 11. 實施結果（2026-11-11，✅ 已完成）

### 11.1 提交

| 提交 | 內容 | 說明 |
|---|---|---|
| `9ca4c1e` refactor(panel) | 步驟 A + B 合併 | 五源拆分 + 流水線固定化；與 bb20a52 乾淨 worktree 對拍：snapshot 精確差異 0/12,774、segment 集合差異 0/12,784（3,931 碼行序變化＝修掉舊 `syllables.size` 分檔 bug）。A/B 合併為一個提交：中間態沒有獨立可回退價值。 |
| `9557044` feat(panel) | 步驟 C | `INITIAL_COMBO` 接入 + `ASSOC` 改分桶輪轉；新增 `PanelGoldenTest` 與 golden JSON。 |
| 本提交 docs(panel) | 步驟 D | 常量審計、docs/17、docs/19、AGENTS 更新；裝機。 |

### 11.2 最終常量值（`TwelveKeyTuning`）

| 常量 | 值 | 備註 |
|---|---|---|
| `INITIAL_COMBO_MAX_ROWS` | 8 | 首字母組合行總數 |
| `INITIAL_COMBO_ROWS_PER_PATTERN` | 1 | 每組合每輪 1 行 |
| `INITIAL_COMBO_PATTERN_LIMIT` | 64 | **組合數**上限（`5i4a57` 6 鍵恰 64 組） |
| `VOWEL_LETTERS` / `KEY_LETTERS` | `aeiou` / 12 鍵字母表 | 首字母集合取自詞典音節集合 |
| `ASSOC_BUCKET_ROW_BUDGET` | 12 | `= ASSOC_MAX_ROWS × 3`（用戶 2026-11-11 拍板） |
| `ASSOC_MAX_ROWS` | 4 | 公式基底（不再直接封頂） |
| `ASSOC_EXPAND_LIMIT` | 1_000_000 | 聯想分桶展開「全部候選音節」 |
| `ASSOC_PREFIX_READINGS` / `ASSOC_WORDS` | 4 / 6 | 整串詞行 / 行內詞數 |
| `MAX_SYLLABLES_TOTAL` | 16（= `SEGMENT_SYLLABLE_LIMIT`） | 由 `TwelveKeyPreedit` 搬入 Tuning |
| `MAX_RESULT_GROUPS` | — | **刪除**：無引用死常量（原計畫是搬進 Tuning，審計後直接移除） |

### 11.3 與計畫的偏差（均已有用戶/驗證依據）

1. **A/B 合併為一個提交**（見 11.1）；**ASSOC 行數上限 4 → 12**（用戶 2026-11-11 明確拍板：
   「保留 App 富聯想 + 照腳本字母序，行數放開到 12」）。AGENTS §2.4 已同步。
2. **聯想索引口徑**：腳本原本只收「詞表自帶讀音」的詞，App 會用字表反推純詞表（sougou 等）。
   為讓 golden 可對拍，腳本補上與 App 一致的反推、詞長、詞頻、排序規則；兩邊從此同口徑。
3. **`ASSOC` 選行**：由舊「熱度優先 + 每輪 ≤4」改為「(首字母,首字母) 分桶 + 跨桶輪轉」；
   `we` 這類剩餘碼展開不到音節時退回「該第一音節全部後續音節」，與舊 `kiung th` 場景一致。
4. **排序確定性**：音節桶外層/內層、桶內詞條全部改成顯式排序，消除對 `HashMap` 迭代順序的依賴
   （否則腳本無法鏡像、golden 會漂）。
5. **`BLANK_READING("…")` 實際落 0 檔**（§4.3 原寫「→2 档」），按重構前行為保留；docs/17 §8 已更正。
6. **golden 對拍範圍**：`PanelGoldenTest` 逐源比對「讀音集合 + 行內文字集合 + 行序」，
   merged 另比讀音/檔位順序；32 個碼串（含用戶樣例 `ia8`/`ia8i3`/`5i4a57`/`744ii`/`we`/`798ea`）。
   重新生成命令見測試類頭部註釋。

### 11.4 驗收

- `testDebugUnitTest`：147 個全綠（含新增 `PanelGoldenTest`）。
- `python3 tools/panel_probe.py selftest`：0 失敗。
- 真機只裝機、不代測（用戶要求）；APK 與裝機命令見 §8。
