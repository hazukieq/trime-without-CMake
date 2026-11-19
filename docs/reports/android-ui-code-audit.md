# Hakka Rime (hkrime) Android UI 代码全面审计报告

- **审计范围**：Android 客户端 UI 展现层（绘制管线、View 测量与布局、Activity/Fragment/Dialog 交互、XML 资源、主题与样式、无障碍）
- **审计原则**：仅聚焦 UI 体系代码，不涉及底层 Rime / JNI / 拼音切分等业务逻辑算法；检视并产出建议，不直接变更源码
- **生成日期**：2026-11-13
- **报告版本**：v1.0.0

---

## 目录
1. [审计概述与健康度评级](#一审计概述与健康度评级)
2. [高危缺陷与潜在崩溃风险 (Critical / P0)](#二高危缺陷与潜在崩溃风险-critical--p0)
3. [UI 渲染管线与资源复用瓶颈 (Performance / P1)](#三ui-渲染管线与资源复用瓶颈-performance--p1)
4. [交互体验、无障碍与夜间模式失范 (UX & Accessibility / P2)](#四交互体验无障碍与夜间模式失范-ux--accessibility--p2)
5. [XML 资源层级、冗余与规范问题 (Resources & Layouts / P2)](#五xml-资源层级冗余与规范问题-resources--layouts--p2)
6. [分阶段系统化重构与修改建议方案](#六分阶段系统化重构与修改建议方案)

---

## 一、审计概述与健康度评级

| 维度 | 评分 (1-5) | 核心现状评估 |
| :--- | :---: | :--- |
| **稳定性与生命周期** | ⭐⭐ | 存在多处 WindowToken 越权绑定、缺失滚动容器截断按钮、Fragment View 泄漏 |
| **渲染管线与性能** | ⭐⭐ | 键盘采用已过时的 CPU 软件双缓冲绘制；流式面板自创 LayoutManager 失去复用 |
| **无障碍与多端适配** | ⭐ | TalkBack 支持基本注释/屏蔽；部分可点击控件触控热区低于 48dp |
| **主题与暗色规范** | ⭐⭐⭐ | 引入了现代设计 Token，但暗色下波纹透明度不可见，代码中存在多处硬编码色值 |
| **XML 与资源组织** | ⭐⭐⭐ | 存在多处单一子控件嵌套、僵尸冗余布局及单位误用（sp 作宽高） |

---

## 二、高危缺陷与潜在崩溃风险 (Critical / P0)

### 1. 全局配置对话框 Window Token 与权限劫持反模式
- **涉及文件**：
  - [ThemePickerDialog.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/settings/components/ThemePickerDialog.kt#L56-L65)
  - [SchemaPickerDialog.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/settings/components/SchemaPickerDialog.kt#L102-L111)
  - [ColorPickerDialog.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/settings/components/ColorPickerDialog.kt#L55-L63)
  - [ResetAssetsDialog.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/settings/components/ResetAssetsDialog.kt#L47-L55)
  - [SoundPickerDialog.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/settings/components/SoundPickerDialog.kt#L43-L51)
- **代码分析**：
  上述 5 个配置弹窗全部在 `appendDialogParams` 中强行设置：
  ```kotlin
  dialog.window?.let { window ->
      window.attributes.token = Trime.getServiceOrNull()?.window?.window?.decorView?.windowToken
      window.attributes.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
          WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
      }
      window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
  }
  ```
- **危害与风险**：
  1. **运行时崩溃 (BadTokenException)**：这些弹窗不仅由输入法呼出，还被设置页 `LooksFragment`、`PrefFragment` 等常规 Activity 页面直接调用（传入 `requireContext()`）。如果输入法服务未运行或输入法窗口未展现，`windowToken` 为 `null`，设置窗体类型为系统级悬浮窗会直接引起应用闪退。
  2. **权限滥用**：为此实现，应用在 `AndroidManifest.xml` 中引入了敏感高危权限 `SYSTEM_ALERT_WINDOW`，并在启动引导页向用户强索悬浮窗权限。实际上，常规 Activity 中的对话框天然由该 Activity Window 承载，完全不需要劫持输入法 Token，更无需任何系统悬浮窗权限。

### 2. `ModernListDialog` 缺乏滚动容器致界面截断
- **涉及文件**：
  - [ModernListDialog.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/dialog/ModernListDialog.kt#L111-L135)
- **代码分析**：
  在对话框构建时，代码直接通过 `items.forEachIndexed { card.addView(buildRow(index)) }` 把所有列表项线性塞入垂直 `LinearLayout`，外层也是不可滚动的普通 `FrameLayout`。
- **危害与风险**：
  - 当用户的输入方案（Schema）或主题（Theme）超过 6~8 项时，弹窗高度会超出物理屏幕可视区域。由于没有任何滚动机制，排在下方的选项以及底部的“确定/取消”操作按钮完全被推出屏幕之外，用户无法滚动查看，也无法完成确认提交或取消退出。

### 3. `LauchAct.kt` 权限拒绝引发递归死循环弹窗
- **涉及文件**：
  - [LauchAct.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/setup/LauchAct.kt#L130-L134)
- **代码分析**：
  ```kotlin
  } else {
      ToastUtils.showShort(R.string.external_storage_permission_not_available)
      ToastUtils.showShort(" $deniedList")
      requestPermission() // 递归调用自身
  }
  ```
- **危害与风险**：
  若用户拒绝授权，回调进入 `else` 分支立刻再次调用 `requestPermission()`，形成死循环弹窗，剥夺用户的选择权利，引起死锁甚至触发系统 ANR 拦截。

---

## 三、UI 渲染管线与资源复用瓶颈 (Performance / P1)

### 1. `KeyboardView` 软件双缓冲禁用硬件加速与渲染热点开销
- **涉及文件**：
  - [KeyboardView.java](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/keyboard/KeyboardView.java#L843-L950)
- **代码分析**：
  1. **古旧的双缓冲方案**：维护 CPU 端位图 `mBuffer = Bitmap.createBitmap(...)` 与私有 `mCanvas`，在 `onBufferDraw()` 遍历所有按键软件绘制后，再由硬件 Canvas 执行 `canvas.drawBitmap(mBuffer, 0, 0, null)`。这彻底放弃了现代 Android 的 DisplayList 硬件加速绘制能力，每次局部按键重绘都会引发全分辨率位图的 CPU 渲染与纹理 GPU 上传。
  2. **高频渲染热点中反射与对象创建**：
     - 在 `onBufferDraw()` 的循环体内部（几十个按键，每帧/每次按键按下），调用了 `mPaintSymbol.getFontMetrics()`，每次都会分配堆对象。
     - 若键位未缓存状态背景，每次遍历都会通过反射调用 `findStateDrawableIndex.invoke` 和 `getStateDrawable.invoke`。
     - 在绘制主循环内高频输出 `Timber.i("onBufferDraw() keyCount=%d, ...")`，频繁产生字符串拼接与 GC 压力。
  3. **Canvas 变换状态缺失保护**：按键绘制中执行 `canvas.translate(dx, dy)` 和 `canvas.translate(-dx, -dy)`，未使用 `canvas.save()` 和 `canvas.restore()` 块保护。一旦循环体内抛出异常，整个 Canvas 矩阵将错位失效。

### 2. `FlowLayoutManager` 破坏 RecyclerView 视图复用机制
- **涉及文件**：
  - [FlowLayoutManager.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/text/FlowLayoutManager.kt#L43-L56)
- **代码分析**：
  - 自实现的 `FlowLayoutManager` 在 `onLayoutChildren` 中：
    ```kotlin
    val tiles = ArrayList<Tile>(state.itemCount)
    for (i in 0 until state.itemCount) {
        val view = recycler.getViewForPosition(i)
        addView(view)
        measureChildWithMargins(view, 0, 0)
        // ...
    }
    ```
  - 一次性将 `itemCount` 全部条目的 View 全量实例化并附加至视图树；在 `scrollVerticallyBy` 中仅调用 `offsetChildrenVertical` 进行纵向位移，**完全不回收移出屏幕的 View**。
- **危害与风险**：
  - 若候选词列表有上百条，会导致一次性生成上百个 View，失去 `RecyclerView` 懒加载复用的根本价值，造成内存与视图层级瞬间暴涨、滑动严重卡顿。
  - 实际上，项目已引入 Google 官方的 `com.google.android.flexbox:flexbox:3.0.0`，完全具备高性能复用能力。

### 3. `Candidate.java` 与 `TabView.java` 测量机制反模式
- **涉及文件**：
  - [Candidate.java](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/text/Candidate.java#L530-L541)
  - [TabView.java](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/symbol/TabView.java#L166-L175)
- **代码分析**：
  两个核心自定义 View 均未重写 `onMeasure()`，而在 XML 中预设占位 `layout_width="1000dp"`，并在代码中通过 `applyLayoutParams()` 调用 `setLayoutParams(params)` 修改宽高。
- **危害与风险**：
  外部改写 `LayoutParams` 会触发父视图的 `requestLayout()` 并引发整树重新测量。规范做法应在自定义 View 的 `onMeasure` 阶段自主测算所需宽高并调用 `setMeasuredDimension(w, h)`。

### 4. 典型生命周期 View 与 Context 内存泄漏
1. **`SetupFragment.kt` 视图泄漏**：
   [SetupFragment.kt#L14](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/setup/SetupFragment.kt#L14) 的 `lateinit var binding: FragmentSetupBinding` 未在 `onDestroyView()` 中清空。Fragment 被替换压栈时，其 View 树将被永久驻留内存。
2. **`AboutActivity.kt` 的 WebView 泄漏**：
   [AboutActivity.kt#L101-L125](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/settings/AboutActivity.kt#L101-L125) 中每次点击均 `new WebView(requireContext())` 装入对话框展示，弹窗 dismiss 后未调用 `webView.destroy()`，WebView 内部线程与静态资源泄露。
3. **`Trime.java` WindowContext 泄漏**：
   [Trime.java#L761](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/core/Trime.java#L761) 在 Android 12+ 每次 `onCreateInputView()` 都会分配 `createWindowContext(...)`，但从未在输入法销毁时释放，导致 WMS Client Token 泄漏。
4. **`LogView.kt` 文本无限追加 OOM 风险**：
   [LogView.kt#L91](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/settings/components/LogView.kt#L91) 使用单只 `TextView.append(colored)` 流式展示日志，未设行数上限。日志量过大时会导致 TextView 内部布局缓存爆炸并引发 OOM。

---

## 四、交互体验、无障碍与夜间模式失范 (UX & Accessibility / P2)

### 1. 无障碍（Accessibility / TalkBack）全面缺失与抑制
- **键盘区不可触达**：
  [KeyboardView.java#L229](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/keyboard/KeyboardView.java#L229) 中 `mAccessibilityManager` 及其触摸探索逻辑全部被注释掉。由于全键盘为一个单一自定义 View，未实现 `AccessibilityNodeProvider` 或 `ExploreByTouchHelper`，开启 TalkBack 的视障用户触摸键盘时无任何按键焦点与语音朗读。
- **候选栏忽略 Lint 检查**：
  [Candidate.java#L48](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/text/Candidate.java#L48) 简单添加 `@SuppressLint("ClickableViewAccessibility")` 压制检查，未向系统报告候选条目结构。
- **触控点击滥发**：
  [KeyboardView.java#L1506](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/keyboard/KeyboardView.java#L1506) 在 `onTouchEvent` 内部无条件执行 `performClick()`。即在每次微小的 `ACTION_MOVE`（滑动每秒几十次）都会强行调用 `performClick()`，造成辅助功能无序轰炸。

### 2. 暗色模式（Dark Theme）触摸反馈失效
- **`ModernListDialog` 波纹黑白失衡**：
  [ModernListDialog.kt#L285](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/dialog/ModernListDialog.kt#L285) 中生成波纹固定为 `Color.argb(alpha, 0, 0, 0)`（黑色）。在暗色卡片底色（`#1C1C1E`）上点击时，黑色波纹物理不可见。
- **`ModernListDialog` 背景覆盖 Bug**：
  [ModernListDialog.kt#L177](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/dialog/ModernListDialog.kt#L177) 设置了 `background = ripple(0.06f)`，但在 [第 222 行](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/dialog/ModernListDialog.kt#L222) 又设置了 `row.background = GradientDrawable(...)`，直接将先前的 Ripple 覆盖抹除，导致行项完全失去水波纹反馈。
- **`pref_row_bg.xml` 同样存在暗色隐形波纹**：
  [pref_row_bg.xml#L3](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/drawable/pref_row_bg.xml#L3) 采用了固定的 `android:color="#14000000"`，暗色模式下毫无触控响应感知。

### 3. 关键按钮触控热区未达标
- **涉及文件**：
  - [pref_activity.xml#L19-L72](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/pref_activity.xml#L19-L72)
- **缺陷**：
  头部操作按钮（`header_back`、`header_deploy`、`header_about`）的尺寸均硬编码为 `44dp x 44dp`。根据 Material Design 与 Android 官方可访问性规范，高频操作的触摸目标尺寸不得小于 **`48dp x 48dp`**。

### 4. `LogActivity` 标题栏风格割裂与返回按键失效
- **涉及文件**：
  - [LogActivity.kt](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/settings/LogActivity.kt#L66)
  - [toolbar.xml](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/toolbar.xml#L8)
- **缺陷**：
  1. `LogActivity` 引入了带有 `#3F4144` 深灰底色的旧 `toolbar.xml`，与主设置页面的 Modern 扁平大标题卡片风格严重不统一。
  2. 虽然调用了 `setDisplayHomeAsUpEnabled(true)`，但**未重写 `onOptionsItemSelected` 或设置 `setNavigationOnClickListener`**，导致用户点击左上角返回箭头无响应。

---

## 五、XML 资源层级、冗余与规范问题 (Resources & Layouts / P2)

### 1. 冗余层级严重（Redundant View Nesting）
- **`candidate_bar.xml`**：
  [candidate_bar.xml#L24-L38](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/candidate_bar.xml#L24-L38)：`FrameLayout -> LinearLayout -> Candidate`。包裹单一控件的 `LinearLayout` 纯属冗余。
- **`tab_bar.xml`**：
  [tab_bar.xml#L2-L20](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/tab_bar.xml#L2-L20)：`ScrollView -> LinearLayout -> TabView`，多余嵌套；且 `ScrollView`（水平滚动）标记了 `android:orientation="vertical"` 冲突属性。
- **`symbol_input_layout.xml`**：
  [symbol_input_layout.xml#L10-L21](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/symbol_input_layout.xml#L10-L21)：垂直 `LinearLayout` 下嵌套了一个 `layout_weight="1"` 的垂直 `LinearLayout`，且内部只装了一个 `RecyclerView`。
- **`main_input_layout.xml`**：
  [main_input_layout.xml#L21-L85](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/main_input_layout.xml#L21-L85)：`candidate_panel_root` (FrameLayout) 嵌套单个 LinearLayout；底部退格键处又使用一个 FrameLayout 包裹单只 TextView。

### 2. 颜色与文字硬编码、国际化与 RTL 缺陷
- **硬编码色值破坏主题**：
  - [main_input_layout.xml](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/main_input_layout.xml#L25)：`#FFFFFFFF`、`#FFEDF0F2`、`#FF3C4043`。
  - [liquid_key_item.xml](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/liquid_key_item.xml#L22)：`#333333`。
- **硬编码尺寸单位错误**：
  - [keyboard_key_preview.xml#L26-L27](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/keyboard_key_preview.xml#L26-L27)：使用 `minWidth="40sp"` 和 `minHeight="60sp"`。控件尺寸应为 `dp`，若使用 `sp` 会随着系统字体放大而异常膨胀。
- **非语义化边距**：
  - [simple_key_item.xml](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/simple_key_item.xml#L6-L8) 与 `liquid_key_item.xml` 依旧使用 `layout_marginLeft` / `layout_marginRight`，而非现代标准的 `layout_marginStart` / `layout_marginEnd`。
- **未提取文案**：
  - [main_input_layout.xml#L76](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/main_input_layout.xml#L76)：`android:contentDescription="退格"`。
  - [activity_lauch.xml#L44](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/res/layout/activity_lauch.xml#L44)：`android:text="Hakka Rime · 客語輸入"`。

### 3. 冗余废弃资源（Dead Code / Assets）
- **完全未使用的 Layout 废弃代码**：
  - `res/layout/about_activity.xml`（`AboutActivity` 实际使用的是 `pref_activity`）
  - `res/layout/help_activity.xml`（项目中无 `HelpActivity` 引用）
  - `res/layout/licensing.xml`（许可界面已改用 WebView 动态注入）
  - `res/layout/dialog_loading.xml`（系统使用了废弃的 `ProgressDialog`，该优秀布局被弃用）
  - `res/layout-land/input_root.xml`（内容与 `res/layout/input_root.xml` 100% 逐字相同，无存在必要）
- **废弃的控件与逻辑**：
  - [ModernListDialog.kt#L296](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/dialog/ModernListDialog.kt#L296) 的 `CheckMark` 绘制类未被使用。
  - [LandscapeInputUIMode.kt#L12](file:///home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main/java/com/hazukieq/hkrime/ime/landscapeinput/LandscapeInputUIMode.kt#L12) 的 `convertMap` 为未填充的空 Map，转换逻辑名存实亡。

---

## 六、分阶段系统化重构与修改建议方案

### 阶段一：稳定性与致命缺陷加固 (P0)
1. **重构 `ModernListDialog` 容器**：
   - 将 `card` 内部的 `items` 改为放入 `NestedScrollView`（最大高度限制为屏幕 70%）或轻量级 `RecyclerView`，确保长列表无论有 5 项还是 50 项，底部按钮均能固定呈现且列表流畅滚动。
2. **规范弹窗 WindowToken 绑定逻辑**：
   - 移除在常规 Activity 环境下对 `TYPE_APPLICATION_OVERLAY` 和 `Trime.getServiceOrNull()` 的强行依赖。
   - 仅当 `context !is Activity` 且当前确定在 InputMethodService 内部呼起时，才使用 `TYPE_APPLICATION_ATTACHED_DIALOG` 并绑定当前 `mInputView.windowToken`；普通 Activity 呼起时保持默认 Window 属性即可。
3. **修复 `LauchAct.kt` 交互死循环与命名规范**：
   - 移除权限回调中的递归自调用，改为弹出解释性 Material 对话框，允许用户退出或去系统设置页授权。
   - 纠正命名错别字（重命名为 `LaunchActivity` 及 `activity_launch.xml`）。

### 阶段二：UI 性能优化与测量重构 (P1)
1. **候选面板切回 `FlexboxLayoutManager`**：
   - 废除无复用能力的 `FlowLayoutManager`，使用项目已有依赖 `FlexboxLayoutManager`，设置 `flexWrap = FlexWrap.WRAP`，配合标准 `RecyclerView.ViewHolder` 实现真正的视图懒加载与回收复用。
2. **重构 `Candidate.java` 与 `TabView.java` 测量生命周期**：
   - 重写 `onMeasure(int widthMeasureSpec, int heightMeasureSpec)`，在测量阶段测算自身所需宽高并调用 `setMeasuredDimension(w, h)`，彻底移除外部 `applyLayoutParams()` 对 `setLayoutParams()` 的不当调用。
3. **清理 `KeyboardView` 绘制热路径**：
   - 移除 `onBufferDraw()` 内部的 `Timber.i` 字符串格式化日志。
   - 缓存 `FontMetrics`，避免重复 `new` 对象；提前解析按键 Drawable 状态映射，避免热路径反射。
   - 为按键绘制加上标准的 `canvas.save()` 和 `canvas.restore()` 保护。

### 阶段三：规范化交互、无障碍与主题 (P2)
1. **实现基本的 Accessibility 节点树**：
   - 在 `KeyboardView` 和 `Candidate` 中接入 `ExploreByTouchHelper`，向系统暴露各按键与候选词的真实边界（Rect）及文字描述，恢复盲人无障碍输入能力。
   - 移除非 ACTION_UP 阶段在 `onTouchEvent` 中对 `performClick()` 的调用。
2. **重构触摸波纹与深色模式**：
   - 将 `pref_row_bg.xml` 与 `ModernListDialog` 中的波纹颜色统一改为使用 `?attr/colorControlHighlight` 或根据亮/暗模式动态选择波纹透明度，并在 Dialog 设置圆角背景时通过 LayerDrawable 保留 Ripple。
3. **触控热区与统一样式修复**：
   - 将 `pref_activity.xml` 的头部按钮扩大到 `48dp x 48dp`。
   - 统一 `LogActivity` 的风格，替换旧 `Toolbar` 为现代卡片式头部，并补全返回按钮点击事件。

### 阶段四：资源清理与现代 Android API 演进 (P3)
1. **清理废弃 XML 资源**：
   - 物理删除 `about_activity.xml`、`help_activity.xml`、`licensing.xml`，删除完全重复的 `layout-land/input_root.xml`。
   - 启用并接入预先定义好的 `dialog_loading.xml`，废除已弃用的 `ProgressDialog`。
2. **全面适配 Edge-to-Edge 与系统栏**：
   - 移除已废弃的 `systemUiVisibility` 和 `SYSTEM_UI_FLAG_*`，在设置页及启动页使用 AndroidX 推荐的 `WindowCompat.setDecorFitsSystemWindows(window, false)` 与 `ViewCompat.setOnApplyWindowInsetsListener`，为 Android 15+ 强制的无边框与避让手势导航条做好准备。
