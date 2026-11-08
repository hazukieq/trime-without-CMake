# 候选区改造评估：单行 + 展开面板 vs 九宫格

> 本文对应可交互 mock：`tools/candidate-mock/index.html`（纯静态，直接双击打开）。
> 结论先行：**推荐方案 B（单行候选 + 「▼」展开面板：左拼音组、右候选网格）**，
> 分三期落地；方案 C（九宫格候选页）可以视为 B 的「无分组」降级形态，复用同一套面板；
> 方案 A（现状单行 Canvas 横滚）保留为默认形态，不改也能用。

---

## 1. 现状（本仓库代码事实）

### 1.1 单行候选栏

| 位置 | 事实 |
| --- | --- |
| `app/src/main/java/com/hazukieq/hkrime/ime/text/Candidate.java` | 一个自绘 `View`：把**整页候选**全部画到一块 Canvas 上（`onDraw`），`MAX_CANDIDATE_COUNT = 30` |
| 同上 `updateCandidateWidth()` | 逐个候选算宽度 `measureText(text) + 2 * candidatePadding`（有 comment 且 `comment_on_top` 时取 `max(wordW, commentW)`，否则相加），累加得到总宽，最后 `LayoutParams.width = x` |
| 同上 | 宽度超出时插入 `PAGE_UP_BUTTON="◀"` / `PAGE_DOWN_BUTTON="▶"`；还能放下不整页时插入 `PAGE_EX_BUTTON="▼"` 并 `Trime.getService().setCandidateExPage(true)` |
| `app/src/main/res/layout/candidate_bar.xml` | `ScrollView(fillViewport)` → `LinearLayout` → `Candidate(width=1000dp,height=120dp)`：**横向滚动是 ScrollView 给的**，候选本身是一整块 |
| `ime/text/ScrollView.java:124` | 右滑到底且 `hasCandidateExPage()` 时触发 `pageExAction`（= 打开展开页） |

### 1.2 已有的「展开候选页」

- `TextInputManager.kt:465-472`：`onCandidateSymbolPressed(PAGE_EX_BUTTON) -> Trime.getService().selectLiquidKeyboard(SymbolKeyboardType.CANDIDATE)`
- `ime/symbol/LiquidKeyboard.java:339-372` `initCandidate()`：`FlexboxLayoutManager`（`ROW` + `WRAP`）+ `CandidateAdapter`，即**自动换行的网格**，点候选 → `onCandidatePressed(position)`
- `ime/symbol/CandidateAdapter.java`：数据源 `Rime.getCandidatesWithoutSwitch()`，样式读主题的 `candidate_text_size / comment_* / liquidKeyboard` 参数

也就是说：**「九宫格」形态其实已经存在**，只是藏在 liquid keyboard 里、没有针对性打磨（无分组、无翻页器、样式不统一）。

### 1.3 Rime 能提供什么

| API | 说明 |
| --- | --- |
| `Rime.getCandidates()`（`Rime.java:416`） | 当前页候选（`RimeMenu.candidates`） |
| `Rime.getCandidatesWithoutSwitch()`（`:421`） | 组字中时同上（不带 schema 开关） |
| `Rime.getComposition()`（`:288`） | `RimeComposition{ length, cursor_pos, sel_start, sel_end, preedit }` |
| `Rime.RimeGetCaretPos()` / `RimeSetCaretPos()`（`:628-634`） | 原始输入串上的光标位置；`setCaretPos` 后自动 `getContexts()` |
| librime 声明 | `rime_api.h:99-105`（RimeComposition）、`:518`（get_caret_pos）、`:527`（set_caret_pos）、`:521`（select_candidate）、`:540`（candidate_list_from_index） |
| JNI 现状 | `rime_jni.cc:598-617` 已导出 `get_caret_pos / set_caret_pos / select_candidate / select_candidate_on_current_page`；**没有**导出 `candidate_list_from_index`（如果要「一次性取全量候选」需补一个 JNI 函数） |

---

## 2. 参考产品（其他输入法怎么做的）

| 产品 | 单行候选 | 展开方式 | 展开后的结构 |
| --- | --- | --- | --- |
| 搜狗手机输入法（九键） | 是，键盘上方一行 | 点【更多】 | **候选词界面**：下方是**音节选择区**（列出该输入对应的所有拼音组合），右侧是翻页/返回按钮；不进候选界面时，数字键盘左侧也给 4 个常用音节，选音节后候选随之变化（[官方帮助](https://shouji.sogou.com/bangzhu.php?pid=5&cid=53)） |
| 搜狗手机输入法（26 键） | 是 | 点候选区右侧【更多】 | 「查看全部候选词并选择」；专利 CN101071338B 描述「候选框右侧下拉图标 → 几乎整个键盘区域变成候选词显示区」（[报道](https://m.techweb.com.cn/article/2015-11-02/2220123.shtml)） |
| 百度输入法 / 微信键盘 | 是 | 展开 / 「更多」 | 候选网格页（与搜狗同型），九键下同样有「音节」概念 |
| Gboard 中文 | 是，单行 | 左右滑 / 箭头翻页 | 不展开为网格（Google 系偏「滑动 + 分页」） |
| Google 日本語 / ATOK（日文） | 是（文節ごと） | 候補一覧 / 次候補一覧 | **文節選択**：左右键在文節间移动，候选只作用于当前文節；另有「候选一览」整页（[ATOK 功能说明](https://docs.oracle.com/cd/E19253-01/819-7844/819-7844.pdf#21#28#21)） |
| 本 fork / 上游 Trime | 是 | ▼ / 右滑 | liquid keyboard 的 CANDIDATE 网格页（见 §1.2） |

**共同规律**（也是用户要求的依据）：

1. 首屏永远是**单行**（视线不动、候选在键盘正上方）；
2. 「看不到/要翻很多页」时才升级为**覆盖键盘区的面板**（不是弹窗、不遮输入框）；
3. 面板里通常分两块：**左边选输入单元（音节/拼音组/文節），右边是该单元的候选**；
4. 点候选 = 上屏该单元，自动跳到下一个单元，整个面板可以连续选完一句话。

---

## 3. 三种方案

### 方案 A：现状单行（不动）

- 交互：单行 + `◀ ▶ ▼`，横滑看更多。
- 优点：零改动；主题的 `candidate_*` 参数完全生效；单行时性能最好。
- 缺点：候选一多就必须翻页；用户反馈的「看不全、想展开」无解；▼ 打开的网格页样式割裂。
- 工作量：0。

### 方案 B：单行 + 「▼」展开面板（推荐）

**收起态**：单行、不再出现 `◀ ▶` 翻页箭头；候选数量超过可用宽度时**直接截断**（最右侧做渐隐提示），因为点「▼」就能看到全部；右侧**有候选就固定显示「▼」**（无论是否截断、是否有下一页）。

**候选为空时**：候选栏只剩这个按钮，它的行为变成**收起键盘**（与搜狗/百度一致：无候选时按钮即「隐藏输入法」）。

**展开态**（覆盖键盘区，高度 ≈ 键盘高度；**没有标题栏、没有底栏**，两栏直接铺满）：

```
┌──────────┬───────────────────────────────┐
│ ni       │  你   尼   泥   呢            │
│ hao      │  逆   拟   腻   妮            │  ← 纵向滚动
│ ma       │  倪   怩   伲   旎            │
│          │  ……（滚到底有渐隐）           │
└──────────┴───────────────────────────────┘
↑ 候选栏的 ▼ 打开时变成 ▲，点它收起
```

- 只有「▼」一个入口；单行态不翻页、不横滑，超宽即截断；
- 面板占满键盘区（高度 = `keyboard_height`），顶部圆角 + 阴影 + 0.18s 滑入；
- 右侧候选是**独立纵向滚动的网格**（对应实现的 `GridLayoutManager` 4 列，即 RecyclerView），滚到底部有渐隐提示；
- 面板骨架 = 左列 92px（拼音组）/ 右列滚动网格，**不设标题栏与底栏**（键盘区每一像素都用来放候选）；
- **左侧拼音组只显示拼音本身，不显示候选数量**；选中组高亮左边条 + 白底；
- 左侧点一个拼音组 → 右侧候选立刻切到该组（光标同时定位到该组，符合 Rime caret 语义）；
- 点右侧候选 → 上屏该组，自动切到下一组（等价于「分步选字」）；
- 不翻页：右侧网格一次铺满当前页候选，纵向滚动查看（对应实现用 `GridLayoutManager`，滚到底再按需加载下一页；`RimePageUp/Down` 作为滚动触底时的数据源）；
- 收起：再点候选栏的 `▲`，或按系统返回键；键盘重新出现。

**优点**：与搜狗/百度主流形态一致；解决「候选多」与「整句分组」两个痛点；单行态不变，主题兼容；已有 liquid CANDIDATE 页可复用。
**缺点**：需要做「拼音组」推导和光标联动；面板需要自己画（不能直接复用 `Candidate.java`）。

### 方案 C：九宫格候选页（B 的降级版）

**收起态**同 B；**展开态**直接把在售的全部候选铺成 4 列网格 + 翻页，不分拼音组。

- 优点：实现最简单（现有 `CandidateAdapter` + `FlexboxLayoutManager` 基本就是它）；「一眼看到全部候选」。
- 缺点：整句输入时无法按组选字，长句体验不如 B；候选很多时要翻页。
- 工作量：约 B 的 ⅓。

> 结论：C 作为 B 的第一期（先把面板做出来、只铺网格），第二期再加左侧拼音组。

---

## 4. 技术可行性与关键实现点

### 4.1 拼音组怎么来

librime 没有直接暴露「分段列表」，但可以用 **caret 遍历**推出来：

```text
raw = Rime.RimeGetInput()            // 例如 "nihao"
orig = Rime.RimeGetCaretPos()
for pos in 0..raw.length:
    Rime.RimeSetCaretPos(pos)        // 内部会 getContexts()
    c = Rime.getComposition()        // { cursor_pos, sel_start, sel_end }
    用 (sel_start, sel_end) 去重 → 得到若干段
Rime.RimeSetCaretPos(orig)           // 还原，避免影响输入状态
```

- 每段再 `Rime.getCandidates()` 就是该段的候选页；
- 段与段之间用 `select_candidate(index)` 上屏（索引是**当前页内的序号**，配合 `setCaretPos` 定位到该段）；
- 同一段内翻页：`Rime.getService().onKey(KEYCODE_PAGE_DOWN/UP, 0)`（现有 `onCandidateSymbolPressed` 就是这么做的）。

**成本**：一次展开 = O(音节数) 次 `getContexts`（JNI 往返），九键双拼最多 4~6 段，可接受；在面板打开时算一次并缓存，输入变化再重算。

**风险**：

- `setCaretPos` 的位置语义是「原始输入串的字符位置」，双拼/九键序列下切分点不一定等于音节边界 → 需要「试探 + 去重」，不能假设等距；
- 某些方案（形码、整句引擎）在 caret 模式下候选行为不同 → 需要灰度 / 设置项；
- `RimeSetCaretPos` 会重算 context，频繁调用要防抖。

### 4.2 面板本身

- 新增 `ime/text/CandidatePanel`（或复用 liquid keyboard 容器），内部 `RecyclerView`（左 `LinearLayoutManager` + 右 `GridLayoutManager(4)`，或单 `RecyclerView` + 自定义 `ItemDecoration` 做分组头）；
- 层级：挂在 `main_input_layout` 的 `FrameLayout` 里，覆盖 `KeyboardView`，高度 = `keyboard_height`（读主题），动画用 `translationY`；
- 样式全部读主题现有键：`candidate_text_size`、`comment_*`、`back_color`、`hilited_candidate_*`、`candidate_padding/spacing`、`layout/round_corner`；新增少量可选键（如 `candidate_panel_columns`，默认 4），不填则走默认值，保证老主题可用。

### 4.3 与现有交互的冲突

| 冲突点 | 处理 |
| --- | --- |
| `ScrollView.java:124` 右滑已绑「打开展开页」 | 保留；面板打开时禁用横滑 |
| `Candidate.java` 的 `▼` 只在放不下时出现 | **已改**：只要有候选就常驻 `▼`（空组字时是 `⌄` 收起键盘）；无后续页时面板退化为展示当前页全部候选 |
| `onCandidatePressed` 有 `hookCandidate` 分支（模拟数字键） | 面板复用同一个回调，不另写一套上屏逻辑 |
| liquid keyboard 的 CANDIDATE tab | 面板上线后该 tab 可隐藏（或作为「无分组」模式保留） |

---

## 5. 分期与工作量（粗估，1 人）

| 期 | 内容 | 交付 | 估时 |
| --- | --- | --- | --- |
| P0 | 面板骨架（覆盖键盘、网格铺候选、滚动、收起），复用现有 `CandidateAdapter` | 可用但无分组（=方案 C） | 2–3 天 |
| P1 | 拼音组推导（caret 遍历 + 缓存 + 去重），左侧组列表 + 右侧联动，点候选上屏该组并跳下一组 | 方案 B 完整形态 | 3–4 天 |
| P2 | 打磨：动画、边距/圆角/字号全走主题、暗色方案、无障碍 contentDescription、边界（零候选/单段/超长输入）、设置项开关 | 可发布 | 2 天 |

**验收标准**（P1 后）：

1. 输入 3 段以上拼音（如 `ni hao ma`），候选行超宽时截断且右侧渐隐，点「▼」展开；
2. 展开面板左列出现 3 组，点第 2 组右侧候选变化；
3. 点候选上屏后自动定位到下一组，连续点完 3 组能上屏整句；
4. 候选为空时候选栏只剩「▼」，点它收起键盘；收起面板后键盘恢复；
5. 主题切换（37 套方案）颜色/字号正确；`home` 等极简键盘不出现面板按钮；
6. 单行模式无回归（长按删词、数字选词；横滑翻页被移除属预期）。

---

## 6. Mock 怎么用

```bash
# 直接打开（无依赖、无构建）
xdg-open trime-workspace/hkrime/tools/candidate-mock/index.html
```

- 左侧三个卡片切换 A / B / C；
- 顶部「输入串」用空格模拟拼音组（`ni hao ma`）；
- 点预编辑行里的 `ni | hao | ma` 也可切换当前组；
- 「模拟上屏一段」按顺序选首选词：连点三次可看到「组字结束 → 候选为空 → 点 ⌄ 收起键盘」的完整状态。

Mock 只验证**交互形态**；像素级细节（字号/间距/圆角/阴影）以 `trime.yaml` + `Candidate.java` / `CandidateAdapter.java` 为准。

---

## 7. 建议

1. **先做 B 的 P0**（单行截断 + ▼ + 面板网格 + 滚动 + 空态收起键盘），两三天就能看到实物，也能立刻解决「候选看不全」；
2. P1 再加拼音组；如果 caret 遍历在双拼/九键上不稳定，就降级为「按空格切分 preedit」的启发式，并保留 C 作为回退；
3. 单行态只加「固定 ▼」和「左右滑翻页」，其余不动，避免回归；
4. 主题零破坏：新增键一律可选、有默认值；面板颜色全部走 `fallback_colors` 链。

参考：搜狗帮助 <https://shouji.sogou.com/bangzhu.php?pid=5&cid=53>；搜狗候选展开专利报道 <https://m.techweb.com.cn/article/2015-11-02/2220123.shtml>；ATOK 文節/候補一覧 <https://docs.oracle.com/cd/E19253-01/819-7844/819-7844.pdf>。
## 九、左列音節的最終語義（librime 原生分段）

點左列不是「自己切、自己查字典」，而是借 librime 自己的光標分段：

1. `TextInputManager.selectPinyinGroup()`：`moveCaretTo(該音節末尾)`（用 `XK_Left/Right` 走 librime 的 `Navigator::Rewind`）+ `Rime.getContexts()`；
   - `librime/src/rime/engine.cc` 的 `ConcreteEngine::Compose()` 只把 `input.substr(0, caret_pos)` 放進 `Composition` 再分段翻譯，
     所以 caret 停在 `ngin^hau` 時，菜單就是 `ngin` 這一節的候選（實測 `凝|垠`）；
2. 點右側候選時 `Rime.selectCandidate(index)`：`ConcreteEngine::OnSelect()` 會 `Forward()` 到下一段、把 caret 移回末尾，
   所以 **只轉換前一節，剩下的 `hau` 仍在組字**（實測 preedit `凝hau`）；
3. 左列只顯示「還沒轉換的音節」：轉換過的 segment（`status >= 2`）不再列出，靠 `getCompositionSegments()` 的原生 start/end/status 判斷；
   沒有轉換段時才用光標探測（`XK_Left` 逐個跳）。

真機驗證（Keep 記事，`nginhau`）：
- 點 `ngin` → 選「凝」→ 預編輯 `凝hau`、左列只剩 `hau`、候選欄變 `好/号/豪/毫/孝`（`hau` 的多種切分都由 librime 給）；
- 再點「好」→ 上屏 `凝好`、組字結束、面板自動收起。

截圖：`artifacts/candidate-syllable-1.png`（初始 ngin/hau）、`candidate-syllable-2.png`（選完凝、只剩 hau）、`candidate-syllable-3.png`（上屏凝好）。
## 十、左列列出「下一個音節」的所有可行切分

`hau` 在客家話音節表裡有兩種切法：`hau`（好/号/豪…）與 `ha + u`（哈 + u）。librime 本身允許用 caret 選切點，
所以左列現在不是「一個音節一格」，而是**列出所有可行的下一音節切分**：

- `rebuildPinyinGroups()`：先由 `RimeCompositionSegments` 求已轉換前綴的 `startByte`；
  再逐字節 `p = startByte+1..len` 做 `RimeSetCaretPos(p)` + `getContexts()`，
  當「最後一段 end == p」且該段候選非空、且 `input[startByte..p)` 是 `SyllableLexicon` 裡的合法音節時，就記為一個切分點（最多 6 個）；
  探完 `RimeSetCaretPos(savedCaret)` + `getContexts()` 還原。
- 點某個切分（例：`ha`）＝ 把 caret 移到該切片末尾 → `getContexts()` → 候選欄/面板就是 `ha` 的候選；
  選字後 librime 的 `OnSelect` 會 `Forward()`，剩下的 `u` 成為新的待處理音節，左列只剩 `u`。

真機驗證（Keep 記事，輸入 `hau`）：
1. 左列 `ha` / `hau`；
2. 點 `ha` → 預編輯 `ha u`、候選 `下/哈/夏/霞/蝦`；
3. 選「下」→ 預編輯 `下u`、左列只剩 `u`。

截圖：`artifacts/candidate-splits-1.png`、`-2.png`、`-3.png`。
