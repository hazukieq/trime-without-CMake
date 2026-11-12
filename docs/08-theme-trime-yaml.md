# 主题、键盘布局与预览

## 当前主题

随包主题是 `app/src/main/assets/rime/hakka_theme.trime.yaml`（客家同文风），
`Config` 的默认主题名为 `hakka_theme.trime`。assets 不包含 `trime.yaml` 或 `tongwenfeng.trime.yaml`。
实际主题从配置目录加载，修改 assets 不会自动替换用户目录已有的同版本文件。

## 配置结构

| 顶层键 | 用途 |
|---|---|
| `style` | 全局尺寸、字体、候选栏、键盘列表和配色选择 |
| `fallback_colors` | 色键回退链，缺值时沿链解析 |
| `preset_color_schemes` | 具名配色集 |
| `preset_keys` | 具名事件：标签、发送码、长按、状态切换等 |
| `preset_keyboards` | 键盘行、单键宽高、点击/滑动/长按映射 |
| `liquid_keyboard` | 符号、剪贴板、草稿等液态键盘分页 |

`style.keyboards` 控制可选择布局；`.default` 与方案/键盘名共同决定实际布局。
常用布局包括 `hakka_jiugong`、`hakka_qwerty`、`ipa_yunlong`、letter、number、symbols。
按键名先解析 preset，再按 Event/Key 的事件规则发送，不把界面标签直接当引擎码。

## 尺寸与颜色

- `Config.getPixel` 按 sp 转像素，随字体缩放；单键 `width` / `key_width` 是可用宽度百分比。
- `keyboard_height` / `keyboard_height_land` 控制总高，当前主题为 250/200。
- `horizontal_gap`、`vertical_gap`、keyboard padding 参与可用空间和布局计算。
- 候选相关键：`candidate_view_height`、`candidate_text_size`、`candidate_padding`、
  `candidate_spacing`、`comment_height`、`comment_on_top`。12 键固定候选条独立关闭读音提示。
- 主题 color_scheme 的缺失色键沿 fallback_colors 回退；缺字体使用系统回退字体。
- 颜色以字符串保存位数与 alpha 语义；布局最终以 Config、Keyboard、KeyboardView 和 Candidate 代码为准。

修改候选条/面板尺寸时同时验证两者与键盘高度、滚动和系统输入框的关系。
12 键的候选读音/排序规则属于 App 逻辑，不能只靠主题修改。

## 更新设备

```bash
adb -s <serial> push app/src/main/assets/rime/hakka_theme.trime.yaml /sdcard/hkrime/
adb -s <serial> shell am force-stop com.hazukieq.hkrime
adb -s <serial> shell ime set com.hazukieq.hkrime/.TrimeImeService
```

路径不同则使用实际 sharedDataDir。配置缓存刷新由 Config 加载/部署处理，
改方案与词典按 [07](07-opencc-and-rime-data.md) 部署。
截图前确认 IME 窗口属于本包；验证横竖屏、按键事件、候选、面板及字体缩放。

## 预览器

启动 `./tools/theme-previewer/serve.sh`，通过 HTTP 打开
`http://127.0.0.1:8765/tools/theme-previewer/`，点击“客家主题”或打开本地 YAML。
预览器默认载入与其它预设按钮仍指向未随包提供的主题，默认加载失败不代表客家主题有错。
它近似复刻布局、色键回退和标签解析，不模拟引擎、真实候选面板、字体文件、背景合成与全部手势。
具体操作见 [工具说明](../tools/theme-previewer/README.md)。
