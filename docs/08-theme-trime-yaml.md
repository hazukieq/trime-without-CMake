# trime.yaml 主题详解与可视化预览

> 适用版本：**hkrime 3.2.7**（本仓库 `dev` 分支，基于 osfans/trime v3.2.7）
> 对应文件：`app/src/main/assets/rime/trime.yaml`（1650 行）与 `tongwenfeng.trime.yaml`（4133 行）
> 配套工具：`tools/theme-previewer/`（浏览器内实时渲染，见 §12）
> 外部资料：`trime-workspace/theme-research/upstream-theme-notes.md`（上游 wiki/源码/社区三条线，逐条标来源）

本文所有结论都以**本仓库代码**为准（`data/Config.java`、`ime/keyboard/*.java`、`ime/view/KeyboardView.java` 等）；
上游文档与代码不一致时，文中会显式写「上游 wiki 如此，但本 fork 实际是…」。

---

## 1. 先建立心智模型

`trime.yaml` 不是「样式表」，而是**主题 + 键盘布局 + 按键定义 + 配色方案 + 悬浮键盘**五件事塞进一个 YAML。
一个主题文件里同时定义了：

```
trime.yaml
├── style:                 全局样式（尺寸、字体、配色方案选择、键盘高度锁定、悬浮窗旧参数…）
├── fallback_colors:       颜色回退链（没有直接定义的色键沿这条链取父键的值）
├── preset_color_schemes:  37 套配色方案（每套 5–33 个色键）
├── preset_keys:           100 个具名按键（点击/长按/滑动 的行为描述）
├── preset_keyboards:      17 套键盘布局（keys 列表 + 键盘级属性）
├── liquid_keyboard:       悬浮键盘/剪贴板/emoji 等「液态键盘」配置
└── android_keys:          历史遗留，仅供查阅，基本不生效
```

主题的生命周期（重要）：

1. APK 内 `assets/rime/trime.yaml` 只是**出厂默认值**。
2. 首次启动（或版本变化）时，`Config.prepareRime()` 把它**拷贝**到
   `/storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime/`（`sharedDataDir`）。
3. librime 的 `build/*.bin`、`userdb`、`installation.yaml` 落在 **`/storage/emulated/0/rime`**（`userDataDir`），
   与主题文件不是同一个目录 —— 这是本 fork 最容易搞混的一点（详见 `docs/07-opencc-and-rime-data.md`）。
4. 用户在设置里换主题/换方案时，`AppPrefs` 记下名字，`Config.get()` 用 `Rime.config_get_map(themeName)` 读取；
   主题文件不存在时回退到 `default`。
5. **改 assets 里的 trime.yaml 后必须重新部署**，否则设备上仍是旧的 `sharedDataDir/rime/trime.yaml`。

> 想验证主题改动：改 assets → 重装 APK（或直接往设备 `sharedDataDir/rime/` 推文件）→ 触发部署
> （本机用 `script/verify-rime-assets.py` 静态校验，真机用 androidTest `RimeDataTest`）。

---

## 2. 顶层结构（含行号，trime.yaml 1650 行）

| 行号 | 顶层键 | 内容 | 说明 |
|---|---|---|---|
| 4 | `config_version` | `"3.0"` | 主题格式版本；本 fork 不校验 |
| 5 | `name` | `預設` | 主题名（设置里显示） |
| 6 | `author` | `osfans` | 作者 |
| 8–101 | `style` | 全局样式 | 见 §3 |
| 102–138 | `fallback_colors` | 37 条颜色回退链 | 见 §4 |
| 139–684 | `preset_color_schemes` | 37 套方案 | 见 §5 |
| 685–809 | `liquid_keyboard` | 悬浮键盘 | 见 §9 |
| 810–846 | `android_keys` | 历史遗留 | 见 §10 |
| 847–955 | `preset_keys` | 100 个具名按键 | 见 §7 |
| 956–1650 | `preset_keyboards` | 17 套键盘 | 见 §8 |

`tongwenfeng.trime.yaml`（4133 行）与 `hakka_theme.trime.yaml`（3923 行）结构相同，只是方案/键盘更丰富，
可作为「大型主题怎么写」的参考；把任何一个文件路径喂给预览器即可直接看效果。

---

## 3. style：全局样式

### 3.1 单位：除 `key_width` 外都是 sp

代码依据：`Config.getPixel(float)` → `TypedValue.applyDimension(COMPLEX_UNIT_SP, f, metrics)`。
所以 `key_height: 44` 不是 44 像素，而是 **44sp**（随系统字体缩放变化）；预览器按 1sp≈1dp 的 CSS px 渲染。

**唯一例外**：

- `key_width`：**屏幕可用宽度的百分比**（`10.0` = 10%）。
- 键盘里的 `width:`（单键宽度）也是百分比；`height:`（单键高度）是 sp。

### 3.2 布局与尺寸键

| 键 | 默认值 | 作用 | 代码位置 |
|---|---|---|---|
| `key_width` | `10.0` | 单键默认宽度（屏宽百分比） | `Keyboard` 构造 |
| `key_height` | `44` | 单键默认高度（sp） | `Keyboard` 构造 |
| `horizontal_gap` | `1` | 键水平间距 | `Keyboard` 构造 |
| `vertical_gap` | `1` | 行间距 | `Keyboard` 构造 |
| `round_corner` | `8` | 按键圆角 | `Keyboard`/`KeyboardView` |
| `keyboard_height` | `250` | **锁定键盘总高**（切换键盘不闪烁的关键） | `Keyboard` 构造 |
| `keyboard_height_land` | `200` | 横屏锁定高度 | `Keyboard` 构造 |
| `keyboard_padding` | `0` | 竖屏左右内缩（值同时作用于左右） | `Config.getKeyboardPadding` |
| `keyboard_padding_left` / `_right` | `0` / `40` | 一手模式左右分别内缩 | 同上 |
| `keyboard_padding_bottom` | `0` | 底部内缩（避开手势条） | 同上 |
| `keyboard_padding_land` / `_land_bottom` | `40` / `0` | 横屏左右/底部内缩 | 同上 |
| `horizontal` | `true` | 横向候选/编码布局 | `Candidate`/`Composition` |

### 3.3 候选栏与编码区

| 键 | 默认值 | 作用 |
|---|---|---|
| `candidate_view_height` | `28` | 候选栏高度（sp） |
| `candidate_text_size` | `22` | 候选字号 |
| `candidate_padding` | `5` | 候选内边距 |
| `candidate_spacing` | `0.5` | 候选之间额外间距 |
| `candidate_use_cursor` | `true` | 高亮首候选 |
| `comment_on_top` | `true` | 注释在候选上方（否则下方） |
| `comment_height` | `12` | 注释区高度 |
| `comment_text_size` | `12` | 注释字号 |
| `text_size` | `16` | 编码区字号 |
| `text_height` | 注释掉 | 编码区高度（`#text_height: 22`，本主题未启用） |

> **注释在上时会自动加高**：`Candidate.updateCandidateWidth()` 里
> `params.height = (shouldShowComment && isCommentOnTop) ? candidate_view_height + comment_height : candidate_view_height`，
> 默认主题即 28 + 12 = 40sp；文字画在下半区（中心 = comment_height + candidate_view_height/2），
> 注释画在上方 comment_height 带内。只有 `comment_on_top: false` 时才把注释塞进 `candidate_view_height` 内（此时 word 左移、注释贴右缘）。
> 所以「28sp 栏塞 22sp 字必裁切」是误读——真正会裁的是注释在下的配置。

### 3.4 字体

| 键 | 默认值 | 说明 |
|---|---|---|
| `key_font` | `symbol.ttf` | 按键主标签字体 |
| `key_long_text_size` | `14` | 长标签（≥2 字符）字号 |
| `key_text_size` | `22` | 单字符标签字号 |
| `label_font` / `label_text_size` | `label.ttf` / `22` | 键盘标签（如「中文」） |
| `symbol_font` / `symbol_text_size` | `symbol.ttf` / `10` | 按键角标（长按符号） |
| `candidate_font` / `comment_font` | `han.ttf` / `comment.ttf` | 候选/注释 |
| `text_font` | `latin.ttf` | 编码区 |
| `latin_font` / `latin_locale` | `latin.ttf` / `en_US` | 西文与字型回退 |
| `hanb_font` | `hanb.ttf` | 扩展汉字（GB18030 外字） |
| `long_text_font` | `comment.ttf` | 剪贴板等长文本 |
| `preview_font` / `preview_text_size` / `preview_height` / `preview_offset` | `latin.ttf` / `40` / `60` / `-12` | 按键放大提示 |
| `locale` | `zh_TW` | `zh_TW`/`zh_CN`/`zh_HK`/`""` |

字体文件必须放在 `assets/rime/fonts/`（本仓库该目录为空，字体缺失时系统回退默认字体，不影响功能）。

### 3.5 配色与背景

| 键 | 默认值 | 说明 |
|---|---|---|
| `color_scheme` | `default` | 默认配色方案名（用户在设置里选过则以用户为准） |
| `background_dim_amount` | `0.5` | 背景图变暗程度 |
| `round_corner` | `8` | 按键圆角 |
| `shadow_radius` | `0.0` | 按键阴影半径（>0 才有阴影） |
| `background_folder` | 注释掉 | 背景图子目录（`#background_folder:`） |

### 3.6 行为开关

| 键 | 默认值 | 说明 |
|---|---|---|
| `auto_caps` | `false` | 句首自动大写：`true`/`false`/`ascii` |
| `proximity_correction` | `true` | 按键纠错 |
| `reset_ascii_mode` | `false` | 每次显示键盘是否重置为中文 |
| `speech_opencc_config` | `s2t.json` | 语音输入简繁转换配置 |
| `enter_label_mode` | `0` | Enter 文本来源：0 不用 ActionLabel / 1 只用 / 2 优先 / 3 兜底 |
| `vertical_correction` | `-10` | 浮窗垂直修正 |
| `key_long_text_border` | `1` | 长文本按键的边框 |
| `keyboards` | `[.default, letter, number, symbols, mini]` | **键盘选择候补列表**（见 §8.4，缺失会 NPE） |

`enter_labels`（Enter 键文本表）：

```yaml
enter_labels:
  go: 前往
  done: 完成
  next: 下个
  pre: 上个
  search: 搜索
  send: 发送
  default: Enter
```

按键里写 `label: enter_labels` 就会在运行时替换成当前 EditorInfo 对应的那条（`Key.getLabel` → `mLabelEnter`）。

### 3.7 旧悬浮窗参数（已废弃，仅存档）

`style.layout`（`position/min_length/max_length/sticky_lines/max_entries/min_check/all_phrases/border/max_width/...`）
与 `style.window`（3 条旧悬浮窗行模板）属于 **4.x 之前的旧悬浮窗**；本 fork 的悬浮窗已经换成
`liquid_keyboard` + 新主题配置 `window`。这两个键现在**写了也不会生效**，保留只为兼容旧主题。

上游 2025-09 之后新增的 `preedit` / `window` / `tool_bar` 是**新**配置，但本 fork 同样不识别
（`Config` 里没有对应解析），写了会被静默忽略。

---

## 4. 颜色系统：fallback_colors

主题里任何色键都可以「不写」——`Config.getColorObject(key)` 会先查当前方案，取不到就沿
`fallback_colors` 链一路向上找：

```java
while (o == null && fallbackColors.containsKey(fallbackKey)) {
  fallbackKey = fallbackColors.get(fallbackKey);
  o = map.get(fallbackKey);
}
```

本主题的完整回退链（37 条）：

| 色键 | 回退到 | 用途 |
|---|---|---|
| `candidate_text_color` | `text_color` | 候选文字 |
| `comment_text_color` | `candidate_text_color` | 候选注释 |
| `border_color` | `back_color` | 边框/分割 |
| `candidate_separator_color` | `border_color` | 候选分隔线 |
| `hilited_text_color` | `text_color` | 高亮编码 |
| `hilited_back_color` | `back_color` | 高亮编码背景 |
| `hilited_candidate_text_color` | `hilited_text_color` | 高亮候选文字 |
| `hilited_candidate_back_color` | `hilited_back_color` | 高亮候选背景 |
| `hilited_label_color` | `hilited_candidate_text_color` | 高亮标签 |
| `hilited_comment_text_color` | `comment_text_color` | 高亮注释 |
| `hilited_key_back_color` | `hilited_candidate_back_color` | 高亮键背景 |
| `hilited_key_text_color` | `hilited_candidate_text_color` | 高亮键文字 |
| `hilited_key_symbol_color` | `hilited_comment_text_color` | 高亮键角标 |
| `hilited_off_key_back_color` / `hilited_off_key_text_color` | `hilited_key_back_color` / `hilited_key_text_color` | 关闭态键（高亮） |
| `hilited_on_key_back_color` / `hilited_on_key_text_color` | `hilited_key_back_color` / `hilited_key_text_color` | 打开态键（高亮） |
| `key_back_color` | `back_color` | 普通键背景 |
| `key_border_color` | `border_color` | 普通键边框 |
| `key_text_color` | `candidate_text_color` | 普通键文字 |
| `key_symbol_color` | `comment_text_color` | 普通键角标 |
| `label_color` | `candidate_text_color` | 键盘标签 |
| `off_key_back_color` / `off_key_text_color` | `key_back_color` / `key_text_color` | 关闭态键 |
| `on_key_back_color` / `on_key_text_color` | `hilited_key_back_color` / `hilited_key_text_color` | 打开态键 |
| `preview_back_color` / `preview_text_color` | `key_back_color` / `key_text_color` | 按键提示弹窗 |
| `shadow_color` | `border_color` | 阴影 |
| `root_background` | `back_color` | 根背景/皮肤 |
| `candidate_background` | `back_color` | 候选栏皮肤 |
| `keyboard_back_color` | `border_color` | 键盘背景 |
| `liquid_keyboard_background` | `keyboard_back_color` | 悬浮键盘背景 |
| `text_back_color` | `back_color` | 编码区背景 |
| `long_text_back_color` | `key_back_color` | 长文本背景 |

**最小可用方案**：`back_color` + `text_color` 两个就够（其余全部沿链回退）。

### 4.1 颜色写法

| 写法 | 含义 |
|---|---|
| `0xAARRGGBB` | 含 alpha，如 `0xCCD3D7DA`（8 位） |
| `0xRRGGBB` | 不含 alpha，如 `0xECEFF1`（6 位） |
| `"#AARRGGBB"` / `"#RRGGBB"` | 同上，**引号必须有**，否则 `#` 后内容会被 YAML 当注释 |
| `red` 等颜色名 | Android `Color.parseColor` 支持的名字 |
| `xxx.png` / `xxx.9.png` | 带 `.` 的字符串视为**图片名**，按图片背景绘制 |

**注意 YAML 1.1 的坑**：`0xE4E7E9` 会被 YAML 解析成整数 `0x00E4E7E9`，前导 `00` 变成 alpha=0 → 全透明。
librime 侧拿到的是字符串所以没问题；但**预览器/第三方解析器**必须先给 `0x…` 加引号（见 §12.3）。

### 4.2 图片背景与皮肤三层

- 色键值写成图片名（如 `background: keyboard_bg.9.png`）时，Android 按 `.9.png` 九宫格拉伸。
- 皮肤是三层叠加：`root_background` → `candidate_background` → `keyboard_back_color`。
- 图片放 `assets/rime/background/`（`background_folder` 可指定子目录）。

---

## 5. preset_color_schemes：配色方案

### 5.1 方案里的键

| 键 | 必需 | 说明 |
|---|---|---|
| `name` / `author` | 是 | 显示名与作者（设置里展示） |
| `back_color` | 是 | 候选区背景 |
| `border_color` | 是 | 边框 |
| `text_color` | 是 | 编码文字 |
| `hilited_text_color` | 是 | 编码高亮 |
| `hilited_back_color` | 是 | 编码高亮背景 |
| `hilited_candidate_text_color` / `_back_color` | 是 | 高亮候选 |
| `candidate_text_color` | 否 | 候选文字（缺省回退 `text_color`） |
| `comment_text_color` | 否 | 注释 |
| `label_color` / `hilited_label_color` | 否 | 标签 |
| `key_back_color` / `key_text_color` / `key_symbol_color` | 否 | 键盘键（本主题只有 default 方案写全） |
| `keyboard_back_color` | 否 | 键盘背景 |
| `candidate_separator_color` | 否 | 分隔线 |
| `preview_back_color` / `preview_text_color` / `shadow_color` | 否 | 预览弹窗/阴影 |
| `text_back_color` | 否 | 编码区背景 |
| `dark_scheme` / `light_scheme` | 否 | 深/浅色模式时自动切换到另一套方案 |

本文件 37 套方案：`default, aqua, azure, luna, ink, lost_temple, dark_temple, starcraft, google, solarized_rock,
tintin, dota_2, brasil, doraemon, espagna, gholabok, kuma_shuzboz, kuon, macau, nba, ps4, skype, xbox_silver,
youtube, so_young, smurfs, wii, android, cool_breeze, google_plus, modern_warfare, brisk, starcraft_ii,
steam, flypy, tantsing, yygc`。

> 只有 `default` 写全了 33 个色键（含键盘三态），其他方案大多只定义了 8–10 个色键，其余全部靠回退链。
> 这就是「主题看起来乱」的根源：**同一个颜色在不同方案里来自不同的键**，用预览器的「颜色」tab
> 可以看到每个色键最终解析到的值以及命中的来源键。

### 5.2 三态：普通 / 高亮 / 关闭·打开

按键颜色按状态取：

- 普通：`key_back_color` / `key_text_color` / `key_symbol_color`
- 高亮（按下/选中）：`hilited_key_back_color` / `hilited_key_text_color` / `hilited_key_symbol_color`
- 开关键关闭：`off_key_back_color` / `off_key_text_color`
- 开关键打开：`on_key_back_color` / `on_key_text_color`
- 开关键在「高亮」状态下的关闭/打开：`hilited_off_*` / `hilited_on_*`

实际取值逻辑见 `Key#getKeyBackground` / `KeyboardView#setKeyBackground`（预览器复刻在 `keyColorsForState()`）。

---

## 6. 编码区与候选区渲染细节

| 元素 | 关键代码 | 要点 |
|---|---|---|
| 编码文字 | `Composition` | `text_size`、`text_color`、`hilited_text_color`、`hilited_back_color`、`text_back_color` |
| 候选文字 | `Candidate` | `candidate_text_size`、`candidate_text_color`、`hilited_candidate_*` |
| 候选注释 | `Candidate` | `comment_text_size`、`comment_on_top`、`comment_height` |
| 分隔线 | `Candidate#draw` | `candidate_separator_color`，在候选项右侧画 1px |
| 光标高亮 | `candidate_use_cursor` | 为 true 时首候选使用 `hilited_*` 颜色 |

注释布局（`Candidate`）：`commentHeight = getPixel("comment_height")`；若 `comment_on_top`，
word 的基线再下移 `commentHeight/2`，注释画在上方；否则注释在下方。

---

## 7. preset_keys：具名按键

### 7.1 按键可写的属性

| 属性 | 类型 | 说明 |
|---|---|---|
| `label` | string | 显示文字；`enter_labels` 为特殊值 |
| `label_symbol` | string | 角标文字（优先于长按标签） |
| `hint` | string | 底部提示文字 |
| `send` | 组合键串 | 如 `"Control+a"`、`"Shift+space"` |
| `commit` | string | 直接上屏的内容 |
| `text` | string | 输入文本（与 commit 的区别见下） |
| `command` | string | 功能命令（见 §7.3） |
| `option` | string | command 的参数 |
| `select` | string | 切换键盘：键盘名 / `.default` / `.next` |
| `toggle` | string | 切换 Rime 选项，如 `ascii_mode` |
| `states` | [string,string] | toggle 的 [关,开] 两态标签 |
| `preview` | string | 按键放大提示文字 |
| `shift_lock` | `ascii_long` / `ascii` / `long` | 长按锁定行为 |
| `sticky` | bool | 粘滞 |
| `repeatable` | bool | 长按连发 |
| `functional` | bool | 是否参与功能键逻辑（默认 true；space 设为 false） |
| `__include` | path | 复用另一个键定义 |

click / long_click / swipe_up / swipe_down / swipe_left / swipe_right / composing / has_menu / paging / combo / ascii
这些**事件槽位**里放的是「按键名」或内联定义（`{click: 'q', long_click: '_'}`）。

### 7.2 `send` / `commit` / `text` 的区别

| 字段 | 行为 | 例子 |
|---|---|---|
| `send` | 模拟按键事件（走 InputConnection 的按键路径） | `send: "Control+a"` |
| `commit` | 直接把字符串上屏 | `commit: "，"` |
| `text` | 输入文本，会经过 Rime 的输入流程/大小写处理 | `text: "abc"` |

`Key.getLabel()` 优先用 key 自己的 `label`；否则 `Event.getLabel()`：
`toggle` → `states[当前是否开启 ? 1 : 0]`；否则 `adjustCase(label)`；`space` → 当前方案名。

### 7.3 command 全集（以 `ShortcutUtils` 为准）

| command | option | 作用 |
|---|---|---|
| `date` | 格式串，如 `yyyy-MM-dd` | 上屏当前日期 |
| `run` | 包名/组件 | 启动应用（如 `com.hazukieq.hkrime`） |
| `broadcast` | action | 发广播（如 `com.hazukieq.hkrime.deploy`） |
| `clipboard` | — | 剪贴板 |
| `share_text` | — | 分享文本 |
| `liquid_keyboard` | `特殊`/`emoji`/`clipboard` 等 | 打开悬浮键盘 |
| 其他任意值 | — | 当作 Intent action 处理 |

> 上游 wiki 提到的 `set_color_scheme` / `set_theme` 在本 fork **没有实现**；配色切换走设置界面/`Color_switch`。

### 7.4 100 个预设键（按用途分组）

| 分组 | 键名 |
|---|---|
| 修饰/功能 | `Shift_L` `Return` `Return1` `Hide` `BackSpace` `space` `Escape` `Menu` `Mode_switch` `Zenkaku_Hankaku` `Henkan` `Charset_switch` `Punct_switch` |
| 光标/编辑 | `Home` `End` `Insert` `Delete` `Page_Up` `Page_Down` `Left` `Right` `Up` `Down` `Clear` `select_all` `cut` `cut_all` `copy` `copy_all` `paste` `paste_text` `share_text` `redo` `undo` `F4` |
| Rime 行为 | `BackToPreviousSyllable` `CommitRawInput` `CommitScriptText` `CommitComment` `DeleteCandidate` `Schema_switch` `Candidate_switch` `Comment_switch` `Hint_switch` |
| 键盘切换 | `Keyboard_symbols` `Keyboard_number` `Keyboard_mini` `Keyboard_letter` `Keyboard_default` `Keyboard_switch` `liquid_keyboard_switch` `liquid_keyboard_switch2` `liquid_keyboard_emoji` `liquid_keyboard_clipboard` `one_hand_switch_1/2/3` |
| 输入法/应用 | `IME_switch` `IME_last` `IME_next` `TrimeApp` `TrimeCmp` `Homepage` `CommitHomepage` `Wiki` `Help` `Info` `Settings` `Color_settings` `Theme_settings` `Schema_settings` `Deploy` `Sync` `Search` `WebSearch` `Share` `Color_switch` |
| 系统 | `BRIGHTNESS_UP/DOWN` `VOLUME_UP/DOWN/MUTE` `CALCULATOR` `CALENDAR` `CONTACTS` `ENVELOPE` `EXPLORER` `MUSIC` `POWER` `SEARCH` `SLEEP` `VOICE_ASSIST` |
| 日期/时间 | `Date` `ChineseDate` `Time` |
| 词典/搜索 | `MoeDict` `Baidu` `Zdic` `Zdic2` `Google` |

几个值得抄的写法：

```yaml
Shift_L:       {label: Shift, send: Shift_L, shift_lock: ascii_long}
Return:        {label: enter_labels, send: Return}
BackSpace:     {label: 退格, repeatable: true, send: BackSpace}
space:         {functional: false}
Mode_switch:   {toggle: ascii_mode, states: [中文, 西文]}
Keyboard_default: {label: 默认, select: .default}
Date:          {label: 日期, command: date, option: "yyyy-MM-dd"}
TrimeApp:      {label: 配置, command: run, option: "com.hazukieq.hkrime"}
Deploy:        {command: broadcast, option: "com.hazukieq.hkrime.deploy"}
```

> `select` 的特殊值（`KeyboardSwitcher.switchToKeyboard(name)`，`name` 为空时若当前键盘 lock 则保持，否则回 `lastLockId`）：

| 值 | 行为 |
|---|---|
| `.default` | 回到 `keyboardNames[0]` |
| `.prior` | 当前索引 −1 |
| `.next` | 当前索引 +1（轮换 `style.keyboards`） |
| `.last` | 上一次用过的键盘索引 |
| `.last_lock` | 上一次 lock 的键盘索引 |
| `.ascii` | 切到当前键盘的 `ascii_keyboard`（未配置则不变） |
| 其他 | 按名字在 `keyboardNames`（= `style.keyboards` 顺序）里查索引，找不到返回 -1 → 不切换 |

> `startKeyboard()` 还多一条：若目标是 `keyboardNames[0]` 且列表里有 `mini`、用户开启「精简键盘」、
> 且系统检测到实体键盘（`Configuration.keyboard != KEYBOARD_NOKEYS`），则改开 `mini`。

---

## 8. preset_keyboards：键盘布局

### 8.1 键盘级属性

| 属性 | 默认 | 说明 |
|---|---|---|
| `name` / `author` | — | 键盘名/作者 |
| `ascii_mode` | `1` | 该键盘的初始中英文状态（0 中文 1 西文） |
| `ascii_keyboard` | — | 西文模式下改用的键盘 |
| `reset_ascii_mode` | — | 显示时重置中英文 |
| `lock` | `false` | 锁定 = 切换 App 时记住这个键盘（`KeyboardSwitcher` 的 `lastLockId`）；不是「禁止切换」 |
| `columns` | `30` | 一行最多多少键 |
| `width` | `10` | 默认键宽（屏宽%） |
| `height` | — | 默认键高（sp）；行高只读**行首键** |
| `label_transform` | `none` | `uppercase`：中文模式下把单字符标签转大写 |
| `auto_height_index` | `-1` | 高度缩放时「吸收余数」的行（负数从末行倒数） |
| `horizontal_gap` / `vertical_gap` / `round_corner` | 继承 style | 键盘级覆盖 |
| `keyboard_height` / `keyboard_height_land` | 继承 style | 键盘级覆盖 |
| `key_text_offset_x/y`、`key_symbol_offset_x/y`、`key_hint_offset_x/y`、`key_press_offset*` | `0` | 各类文字偏移 |
| `__include` | 路径 | 继承另一个键盘（如 `/preset_keyboards/default`） |

### 8.2 keys 里的元素

```yaml
keys:
  - {click: 'q', long_click: '_'}          # 普通键：点击/长按
  - {click: 'a', swipe_up: 'A'}            # 加滑动
  - {click: Shift_L}                       # 引用 preset_keys
  - {click: space, width: 30}              # 宽度 30% 屏宽
  - {click: Return, composing: Return1, width: 15}
  - {width: 5}                             # 空位（没有 click）
```

- 每个元素天生带 `width`（%）与 `height`（sp）两个尺寸属性。
- **没有 `click` 的元素 = 空位**，只占位不画键。
- 空位/键宽在右缘会自动「填满到边」（见 §8.3）。

### 8.3 布局算法（`Keyboard#layout`，预览器已逐行复刻）

**可用宽度**：`displayWidth = 屏幕宽 - 键盘左右 padding`。

**单键宽度**：

```
w = width% * displayWidth / 100
if (w == 0 && 该元素有 click) w = displayWidth * key_width / 100
w -= horizontal_gap                  // 键宽扣掉间距
```

**换行**：`column >= columns || x + w > displayWidth` 时换行
（`x = gap/2`，`y += verticalGap + rowHeight`，上一键标 `EDGE_RIGHT`）。

**行高**：`column == 0` 时确定本行 `rowHeight = 行首键的 height`（没有则键盘/样式的 `height`）。

**空位**：`x += w + gap` 后直接 `continue`，不产生键。

**右缘填满**：`right_gap = |displayWidth - x - w - gap/2|`，
若 `right_gap <= displayWidth/100`（即「贴着右边」）则 `width = displayWidth - x - gap/2`。

**高度锁定（`keyboard_height > 0`）**：

```
① 先扫一遍：rawHeight[row]（每行行高）、rawSumHeight = Σ rawHeight
② scale = keyboardHeight / (rawSumHeight + verticalGap * (rows + 1))
③ verticalGap = ceil(verticalGap * scale)
④ autoHeight = keyboardHeight - verticalGap * (rows + 1)
⑤ scale = autoHeight / rawSumHeight
⑥ 除 autoHeightIndex 行外：rowH = (int)(rawHeight[row] * scale)
   autoHeightIndex 行吸收余数（保证总高恰好 = keyboardHeight）
⑦ autoHeightIndex < 0 时从末行倒数；越界则夹到最后一行
```

这就是「换键盘时键盘高度不跳」的机制：所有键盘被强制压到同一个总高。

### 8.3.1 按键内部的三段文字位置（`KeyboardView#onBufferDraw`）

一个键里最多画三段文字，**位置完全不同**，这是主题排查时最容易看错的地方：

| 文字 | 来源 | 水平 | 垂直 | 字号 |
| --- | --- | --- | --- | --- |
| 主标签 `label` | `Key.getLabel()`（键自身 `label`，或 preset/事件标签，`enter_labels` 会替换） | 居中：`键宽/2 + key_text_offset_x`（Paint 为 `CENTER` 对齐） | `(h - padT - padB)/2 + (textSize - descent)/2 + padT + key_text_offset_y` | `key_text_size`；否则 `label.length() > 1 ? key_long_text_size : key_text_size` |
| 角标 `symbol` | `Key.getSymbolLabel()`：`label_symbol`，否则 `long_click` 的标签 | 居中：`键宽/2 + key_symbol_offset_x` | **顶部**：baseline = `padding.top - fontMetrics.top + key_symbol_offset_y` | `symbol_text_size`（键级优先，否则 `style/symbol_text_size`，默认 10） |
| 提示 `hint` | 键的 `hint` 字段 | 居中：`键宽/2 + key_hint_offset_x` | **底部**：baseline = `键高 - padding.bottom - fontMetrics.bottom + key_hint_offset_y` | 同 `symbol_text_size`（复用 symbol Paint） |

> 记忆口诀：**主标签居中，长按角标在顶，hint 在底**。
> 所以 `long_click: Keyboard_edit` 的「编辑」小字是画在键的**顶部居中**（不是左下角）；
> 想挪位置用 `key_symbol_offset_x / key_symbol_offset_y`（正值向右/向下），
> 想换角标文字用 `label_symbol`（优先级高于 `long_click` 的标签）。

### 8.4 键盘选择链（`style.keyboards` + `Config.getKeyboardNames`）

`.default` 的解析顺序（上游行为，本 fork 简化）：

1. 方案全名（如 `luna_pinyin`）
2. 方案名前段（`luna`）
3. `speller/alphabet` 同名布局（含 `,;` → `qwerty_`；含 `01` → `qwerty0`）
4. `qwerty`
5. `default`

`style.keyboards` 是「可轮换的键盘列表」，`Keyboard_switch` / `select: .next` 在其中轮换；
**这个键不能删**：`Config.getKeyboardNames()` 直接遍历它，缺失会 NPE。

### 8.5 17 套键盘一览

| 名称 | 显示名 | 键数 | 宽/高 | 特点 |
|---|---|---|---|---|
| `default` | 預設40鍵 | 47 | 10 / 44 | 数字行 + qwerty + 底部功能行，锁定高度 |
| `mini` | 精简键盘 | 20 | 10 / 44, `keyboard_height: 120` | 矮版 |
| `letter` | — | 0 | 继承 default | `ascii_mode: 1, reset_ascii_mode: true, lock: false` |
| `qwerty0` | 預設36鍵 | 47 | 10 / 44 | `label_transform: uppercase` |
| `qwerty_` | 預設30鍵 | 36 | 10 / 55 | 带四向滑动 |
| `qwerty` | 預設26鍵 | 37 | 10 / 55, `columns: 10` | 26 键 + 偏移参数 |
| `us_intl` | 美式國際 | 47 | 10 / 44 | ascii_mode 1 |
| `number` | 預設數字 | 25 | 20 / 44 | 九宫格数字 |
| `symbols` | 符號 | 47 | 10 / 44 | 符号页 |
| `bopomofo` | 註音鍵盤 | 48 | 10 / 44 | `ascii_keyboard: letter` |
| `cangjie5` | 倉頡五代 | 37 | 10 / 55 | |
| `cangjie6` | 倉頡六代 | 51 | 9.7 / 44 | |
| `scj6` | — | 0 | 继承 cangjie5 | |
| `stroke` | 五筆畫 | 16 | 20 / 75 | |
| `telegraph` | 電碼 | 23 | 20 / — | |
| `terra_pinyin` | 地球拼音 | 37 | 10 / 55 | |
| `array30` | 行列30 | 47 | 10 / 44 | 美式國際布局 |

---

## 9. liquid_keyboard：悬浮键盘

结构（`trime.yaml:685-809`）：

```yaml
liquid_keyboard:
  row: 6                # 一行几个
  row_land: 5           # 横屏一行几个
  key_height: 40
  single_width: 60
  vertical_gap: 1
  margin_x: 0.5
  keyboards:            # tab 列表
    - {name: 表情, type: SINGLE, keys: [...]}
    - {name: 符号, type: SINGLE, keys: [...]}
    ...
```

### 9.1 type 取值（代码比 wiki 多几种）

| type | 说明 |
|---|---|
| `SINGLE` | 普通按键网格；`keys` 可以是字符串（每个字符一键）或混合列表；`{click: ""}` 强制换行 |
| `CANDIDATE` | 显示候选 |
| `HISTORY` | 历史输入 |
| `CLIPBOARD` | 剪贴板 |
| `DRAFT` | 草稿 |
| `TABS` | tab 页 |
| `NEW_ROW` | 新起一行 |
| `NO_KEY` | 占位不显示 |
| `SHORT` / `LONG` / `MULTI_LINE` / `PAIR` | 代码支持、wiki 未载 |

尺寸：`key_height`、`single_width`、`vertical_gap`、`margin_x`；tab 名支持 `name:` 标签。

---

## 10. android_keys（历史遗留）

`android_keys` 是早期「按 Android 输入类型自动换键」的配置（`when`/`property`/`action`），
本 fork 只在设置界面里当**参考文档**展示，实际不参与键盘生成。新主题不要再写它。

---

## 11. 主题改动落到真机的路径

```bash
# 1) 静态校验（不用设备）
python3 script/verify-rime-assets.py            # schema/dict/opencc 引用完整性
# 主题本身可用预览器目检（§12）

# 2) 构建并装到设备
JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9 ./gradlew --no-daemon clean assembleDebug
adb install -r -t app/build/outputs/apk/debug/trime-3.2.7-debug.apk

# 3) 真机部署验证（会清空 sharedDataDir 强制重拷 assets）
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
```

> 重装 APK 后 `MANAGE_EXTERNAL_STORAGE` 会被重置，务必先 `appops set`，否则部署会因权限失败进入递归。

---

## 12. 主题预览器（`tools/theme-previewer/`）

### 12.1 打开方式

```bash
cd <repo 根目录>
python3 -m http.server 8765           # 或 ./tools/theme-previewer/serve.sh
# 浏览器打开 http://127.0.0.1:8765/tools/theme-previewer/
```

必须走 HTTP：页面要 `fetch()` 仓库里的 `app/src/main/assets/rime/*.yaml`，`file://` 会被浏览器拒绝。

### 12.2 功能

| 区域 | 能力 |
|---|---|
| 左栏 | 实时编辑 YAML（250ms 防抖），显示行/列，Ctrl+Enter 立即渲染，「还原」回磁盘版本 |
| 中栏 | 配色方案（37）/ 键盘（17）/ 竖横屏 / 屏宽 / 缩放 / **适应宽度** / 西文 / 深色 / 符号 / hint / 候选栏 / **候选区结构标注** / 方案名（空格键标签） |
| 设备预览 | 只渲染键盘本体（无 App 内容区），水平居中并按「适应宽度」自动缩放到中间列宽度（默认开，约等于屏宽占比的真实观感）；按 `Keyboard#layout` 复刻的绝对坐标渲染键盘，候选栏/编码区按真实样式渲染 |
| 候选区结构 | 勾选后在候选栏上画虚线外框、给每个候选画独立框、给 comment 区画斜纹带，并在下方列出构成：背景/高亮底/文字/注释字/分隔线的实际颜色 + 高度/字号/内边距/间距/注释位置 |
| 候选区点击探查 | 点候选字 / 注释（拼音）/ 分隔线 / 候选栏空白，右侧「候选区」tab 会显示该部分的几何计算（外框 left/width、measureText 结果、文字与注释中心 X、颜色命中键、高度公式）；再点同一个候选取消，点候选区之外（键盘、面板、页面空白）也会清除 |
| 右栏-问题 | YAML 解析错误（含行列）、缺 style/方案/键盘、未知按键名、style 缺键、键盘高度锁定提示 |
| 右栏-颜色 | 渲染用到的每个色键：解析值 + 命中的来源键（即 fallback 链走到了哪） |
| 右栏-键盘 | 每个键盘的键数/行数/高度 |
| 右栏-按键 | 点击任意键 → 该键的全部事件槽位、preset 解析结果、颜色/尺寸 |
| 右栏-style | style 段最终取值表 |
| 预设按钮 | 一键载入 `trime.yaml` / 客家主题 / 同文风主题 |
| patch 叠加 | 把编辑区当 `trime.custom.yaml` 叠加到默认主题（简化 patch：`/a/b` 覆盖、`+/a` 追加） |

### 12.3 已知实现要点（改工具必读）

1. **0x 颜色必须先加引号**：`quoteHexNumbers()` 在解析前给所有 `0x…` 值加引号；否则 YAML 1.1 把它变 int，
   6 位色会被当成「alpha=0」全透明。
2. **布局两遍法**：`layoutKeyboard()` 与 `Keyboard.java` 同构（含 `keyboard_height` 缩放、右缘填满、空位）。
3. **label 解析**：`keyLabel()` 复刻 `Key.getLabel`：key.label → Event.getLabel（toggle states / adjustCase /
   enter_labels / space→方案名 / `label_transform: uppercase` 只在非 ascii 模式生效）。
4. **颜色解析**：`parseColor()` 复刻 `Config.parseColor`（int 按 AARRGGBB、6/8 位 hex、`#hex`、颜色名、含 `.` 视为图片）。

### 12.4 与真机的差异（不要当成精确还原）

- 字体：真机用 `assets/rime/fonts/*.ttf`，预览器用系统 sans-serif（字体缺失时真机也会回退）。
- 图片背景（`.9.png` 九宫格、皮肤三层）不做合成。
- 候选栏：**完全照抄真机几何**——`comment_on_top` 且有注释时 `高度 = candidate_view_height + comment_height`，文字画在下半区、注释带在上方；注释在下方时高度仍为 `candidate_view_height`，文字左移 `commentWidth/2`、注释贴右缘；候选框宽度按 `measureText(word) + 2×candidate_padding`（有注释时再与注释宽取大 / 相加），间距 `candidate_spacing` 走 `x += width + spacing`。
- 预览器不再为了可读性撑高候选栏（旧版本那处刻意偏离已删除）。
- `{Left}`、`[]{Left}`、`{}{Left}` 这类花括号 send/text 串在预览器里统一显示成 `{}`（不展示内部内容，避免长符号名糊在键角上）。
- 交互：**点击按键**即选中，按 `hilited_key_back_color` / `hilited_key_text_color` / `hilited_key_symbol_color` 高亮（即真机按下时的样子），右侧「按键」tab 展示事件解析；再点一次同一键、或点键盘布局外的任何位置（含键间空白）即取消。
- 按键放大气泡（真机 `preview_*`）不再渲染：它是按下瞬间的浮层，静态预览里意义不大，去掉后视觉更干净。
- 滑动/长按的交互不做，仅在「按键」tab 里列出所有事件槽位。
- 悬浮键盘（liquid_keyboard）不渲染，只展示配置。

---

## 13. 15 个常见坑

| # | 现象 | 原因 | 修法 |
|---|---|---|---|
| 1 | 颜色全透明/键看不见 | `0xE4E7E9` 被解析成 int，alpha=0 | 写成 8 位 `0xFFE4E7E9`，或加引号 `"0xE4E7E9"` |
| 2 | 改主题不生效 | assets 只是出厂值，设备上已有 `sharedDataDir/rime/trime.yaml` | 重新安装/清数据，或删设备上的主题文件再部署 |
| 3 | 换键盘时高度跳动 | 没设 `keyboard_height` | 设 `keyboard_height`（本主题 250） |
| 4 | 各键盘总高不一致 | 各键盘 `height`/行数不同 | 同上，靠 `keyboard_height` 统一 + `auto_height_index` 吸收余数 |
| 5 | 某键没有显示 | 元素没有 `click`（=空位） | 加 `click`，或当成有意的占位 |
| 6 | 键盘右侧留白 | 最后一键没贴到右缘 | 让 `x + w` 落在 `displayWidth` 的 1% 内，或调 `width` |
| 7 | `label` 不显示 | 当前是 ascii 模式；`label` 只在非 ascii 模式优先 | 用 `send`/`text` 触发时 label 会走 Event 解析 |
| 8 | `label_transform: uppercase` 无效 | 只在中文模式（`ascii_mode: 0`）生效 | 确认键盘 `ascii_mode` |
| 9 | `space` 显示很奇怪 | `space.functional: false`，标签取当前方案名 | 改 `inpSchema`（真机是当前 Rime 方案名） |
| 10 | `enter_labels` 不生效 | `enter_label_mode` 为 0 | 调 `enter_label_mode`（0-3） |
| 11 | 写了新配置 `window`/`preedit` 无效 | 本 fork 不识别上游新参数 | 不用写；本 fork 用 liquid_keyboard |
| 12 | `style.keyboards` 删了会崩 | `getKeyboardNames()` 遍历它 | 保留（至少 `.default`） |
| 13 | 十六进制色带 `#` 报错 | YAML 把 `#` 当注释 | 加引号：`"#FF000000"` |
| 14 | `__include` 报错 | 路径必须 `/preset_keyboards/xxx` 形式 | 检查拼写与层级 |
| 15 | 注释里写 `0x…` 被改 | 预览器/脚本预处理会扫描全文（但跳过引号与注释后的内容） | 注释内不受影响；值内请加引号 |

---

## 14. 最小可用主题

```yaml
config_version: "3.0"
name: 我的主题
author: me

style:
  key_height: 44
  keyboard_height: 250
  keyboard_padding: 0

fallback_colors:
  candidate_text_color: text_color
  key_back_color: back_color
  key_text_color: text_color

preset_color_schemes:
  default:
    name: 我的配色
    author: me
    back_color: 0xFFEEEEEE
    border_color: 0xFFCCCCCC
    text_color: 0xFF333333
    hilited_text_color: 0xFF000000
    hilited_back_color: 0xFFDDDDDD
    hilited_candidate_text_color: 0xFF000000
    hilited_candidate_back_color: 0xFFCCCCCC
    key_text_color: 0xFF222222

preset_keyboards:
  default:
    name: 简化键盘
    width: 10
    height: 44
    keys:
      - {click: 'q'}
      - {click: 'w'}
      - {click: space, width: 60}
      - {click: BackSpace, width: 20}
      - {click: Return, width: 20}
```

把这个文件另存为 `trime.yaml` 或作为 `.custom.yaml` 的 patch 都能被预览器直接加载。

---

## 15. 本 fork 与上游的差异（写主题时最容易踩）

| 项目 | 上游 develop（3.3.x+） | 本 fork（3.2.7） |
|---|---|---|
| 事件槽位 | 还有 `popup` / `double_click` / `lazy_double_click` | 只有 12 个（composing/has_menu/paging/combo/ascii/click/swipe_up/long_click/swipe_down/swipe_left/swipe_right/extra） |
| command | 含 `set_color_scheme` / `set_theme` | 无；配色走设置 |
| 新主题配置 | `preedit` / `window` / `tool_bar` | **不识别**，写了静默忽略 |
| `liquid_keyboard.type` | wiki 列举 | 代码多 SHORT/LONG/MULTI_LINE/PAIR/NEW_ROW |
| `enter_label_mode` | wiki 描述含糊 | 0 不用 / 1 只用 / 2 优先 / 3 兜底 |
| `style.keyboards` | 上游新默认主题已删 | 本 fork 仍必需（否则 NPE） |
| 主题源 | `trime.yaml` + 可能的 `trime.custom.yaml` | 同左；本 fork 的数据文件在 `assets/rime/` |

---

## 16. 参考

- 上游 wiki：<https://github.com/osfans/trime/wiki>
- 上游默认主题：<https://github.com/osfans/trime/blob/develop/app/src/main/assets/rime/trime.yaml>
- 本项目代码索引：`data/Config.java`、`ime/keyboard/Keyboard.java`、`ime/keyboard/Key.java`、
  `ime/keyboard/Event.java`、`ime/view/KeyboardView.java`、`ime/view/Candidate.java`
- 研究笔记（含逐条 URL 与【官】/【码】/【社】/【推】标记）：
  `trime-workspace/theme-research/upstream-theme-notes.md`
- 相关文档：`docs/07-opencc-and-rime-data.md`（数据目录/OpenCC）、`docs/03-build.md`（构建）、
  `docs/06-troubleshooting-roadmap.md`（排错）
