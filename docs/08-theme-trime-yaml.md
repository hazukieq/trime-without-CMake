# 主题、键盘布局与预览

## 当前主题

随包主题是 `app/src/main/assets/rime/hakka.trime.yaml`（客语简约主题，`config_version: "4.0"`），
`Config` 固定使用默认主题 `hakka.trime`，不再提供“切换主题”入口。
实际主题从配置目录加载，修改 assets 不会自动替换用户目录已有的同版本文件。

## 配置结构

| 顶层键 | 用途 |
|---|---|
| `style` | 全局尺寸、候选栏、默认配色、`keyboard_defaults` 与主键盘列表 |
| `utility_keyboards` | App 内置工具键盘的轻量自定义（高度、宽度、字号等，可选） |
| `fallback_colors` | 色键回退链，缺值时沿链解析 |
| `preset_color_schemes` | 具名配色集：`default`（浅色）、`dark`（深色）、`classic`（暖纸护眼） |
| `preset_keyboards` | 主打字键盘布局（`jiugong_simp`、`letter`、`default`、`qwerty_cn2en`） |
| `preset_keys` | 核心功能键与 12 键码位键（工具键盘切换键与符号触发键由 App 自动注册） |
| `android_keys` | Android 按键码映射（引擎必需） |

### 主键盘强类型匹配（`keyboard/type`）

- 方案 `.schema.yaml` 声明 `keyboard: { type: 12key | qwerty, default: <可选布局ID> }`。
- 主题 `preset_keyboards` 中的主打字键盘声明 `type: 12key` 或 `type: qwerty`。
- `KeyboardLayoutResolver` 按 `用户设置 → 方案推荐 → style.keyboard_defaults[type] → 同类型首项` 解析 `.default`，严禁跨类型混选（如 12 键方案套用 26 键布局）。

### App 原生内置工具键盘与符号/表情面板

- **数字与编辑键盘（`BuiltinKeyboards`）**：`number`（123 数字）与 `edit`（编辑，兼容别名 `numberb`）由 `BuiltinKeyboards.kt` 原生生成，并在 `KeyboardSwitcher.kt` 中**懒加载**；主题侧仅需在 `utility_keyboards` 中按需微调 `height` / `width`。
- **固定原生符号/表情面板（`SymbolPanelController`）**：符号、表情、颜文字采用 App 固定原生 4×4 网格 + 右侧功能列（返回 / ▲ / ▼ / 锁定）+ 底部横向分类标签栏，**不再接受主题布局编辑**。
- **纯文本 `.txt` 扩展词库（`SymbolRepository`）**：
  - 随包默认文件在 `assets/rime/symbols/*.txt`，首次打开自动释放到 `/sdcard/hkrime/symbols/`（不覆盖已有修改）。
  - 扩充词库：直接向对应 `.txt` 追加符号/表情（默认按空白分隔；含空格的颜文字在文件头写 `# split: line` 按行分隔）。
  - 新增分类标签：直接在 `/sdcard/hkrime/symbols/` 新增一个 `.txt` 文件（如 `10_我的.txt`，数字前缀控制排序并自动去掉显示为 `10.我的`），下次打开符号面板即生效。

### 深色模式

`default` 与 `classic` 均配置了 `dark_scheme: dark`，`dark` 配置了 `light_scheme: default`。
开启设置中的「跟随系统深色」后，`Config.getColorSchemeName(darkMode)` 自动在浅色与深色之间切换。

## 尺寸与颜色

- `Config.getPixel` 按 sp 转像素，随字体缩放；单键 `width` / `key_width` 是可用宽度百分比。
- `keyboard_height` / `keyboard_height_land` 控制总高，当前主题为 250/200。
- `horizontal_gap`、`vertical_gap`、keyboard padding 参与可用空间和布局计算。
- 候选相关键：`candidate_view_height`、`candidate_text_size`、`candidate_padding`、
  `candidate_spacing`、`comment_height`、`comment_on_top`。12 键固定候选条独立关闭读音提示。
- 主题 `color_scheme` 的缺失色键沿 `fallback_colors` 回退；字体直接使用系统默认字体。
- 布局最终以 `Config`、`Keyboard`、`KeyboardView` 和 `Candidate` 代码为准。

## 更新设备

```bash
adb -s <serial> push app/src/main/assets/rime/hakka.trime.yaml /sdcard/hkrime/
adb -s <serial> shell am force-stop com.hazukieq.hkrime
adb -s <serial> shell ime set com.hazukieq.hkrime/.TrimeImeService
```

路径不同则使用实际 `sharedDataDir`。配置缓存刷新由 `Config` 加载/部署处理，
改方案与词典按 [07](07-opencc-and-rime-data.md) 部署。
截图前确认 IME 窗口属于本包；验证横竖屏、按键事件、候选、面板及字体缩放。
