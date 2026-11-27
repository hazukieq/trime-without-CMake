# 主题、键盘布局与预览（v5.0）

## 当前主题架构

随包主题文件为 `app/src/main/assets/rime/hakka.trime.yaml`（`config_version: "5.0"`），
配色回退链独立存放在 `app/src/main/assets/rime/fallback_colors.yaml`。
`Config` 固定使用默认主题 `hakka.trime`，不再提供“切换主题”入口。
实际文件从配置目录加载，修改 assets 不会自动替换用户目录已有的同版本文件。

### 职责分离

| 来源 | 职责 |
|---|---|
| `hakka.trime.yaml` | **仅负责键盘与候选窗外观 + 配色方案**（`keyboard`、`candidate`、`color_scheme`、`colors`），可选高级 `keyboards` / `preset_keys` 覆盖 |
| `fallback_colors.yaml` | 独立配色回退链，未在配色方案中指定的细粒度色键沿链自动推导 |
| `ThemeDefaults.kt` | 所有系统行为默认值（`locale`、`auto_caps`、`reset_ascii_mode`、`enter_labels`、悬浮窗 `layout`/`window` 等） |
| `BuiltinKeyboards.kt` | 5 套标准打字布局（`12key` / `qwerty` / `9key` / `wubi` / `stroke`）与内置工具键盘（`english`、`number`），按方案按需懒加载 |
| `SymbolPanelController` / `EditPanelController` | 固定原生符号/表情面板与文字编辑面板，自动跟随主题配色，不接受主题改布局 |

## 强类型键盘与高级自定义

- 方案 `.schema.yaml` 声明 `keyboard: { type: 12key | qwerty | 9key | wubi | stroke | <自定义type>, default: <可选布局ID> }`。
- `KeyboardLayoutResolver` 按 `用户设置 → 方案推荐 → 主题 keyboard.defaults[type] → 同类型首项` 强类型解析默认布局，严禁跨类型混选。
- **拒绝无 `type` 的杂乱键盘**：主题 `keyboards:` 中仅允许两种合法自定义：
  1. 覆盖已有类型布局（或覆盖内置 `english` / `number`）；
  2. 新建自定义 `type` 并设计对应布局（需在对应方案中声明相同 `keyboard/type`）。
- 符号扩展词库仍通过 `/sdcard/hkrime/symbols/*.txt` 纯文本维护（新增 `.txt` 即自动新增分类标签）。

## 深色模式与配色

`default` 与 `classic` 均配置了 `dark_scheme: dark`，`dark` 配置了 `light_scheme: default`。
每套配色只需定义 13 个核心语义色（背景、普通键、功能键、按下高亮、候选文字），其余细粒度色键由 `fallback_colors.yaml` 派生；高级用户也可在配色中直接写出任意细粒度色键覆盖回退值。

## 更新设备

```bash
adb -s <serial> push app/src/main/assets/rime/hakka.trime.yaml /sdcard/hkrime/
adb -s <serial> push app/src/main/assets/rime/fallback_colors.yaml /sdcard/hkrime/
adb -s <serial> shell am force-stop com.hazukieq.hkrime
adb -s <serial> shell ime set com.hazukieq.hkrime/.TrimeImeService
```
