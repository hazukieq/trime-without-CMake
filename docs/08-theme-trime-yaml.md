# 主题、键盘布局与预览（v5.1）

## 当前主题架构

随包主题文件为 `app/src/main/assets/rime/hakka.trime.yaml`（`config_version: "5.1"`），
配色回退链独立存放在 `app/src/main/assets/rime/fallback_colors.yaml`。
`Config` 固定使用默认主题 `hakka.trime`，不再提供“切换主题”入口。
实际文件从配置目录加载，修改 assets 不会自动替换用户目录已有的同版本文件。

### 职责分离

| 来源 | 职责 |
|---|---|
| `hakka.trime.yaml` | **仅负责键盘与候选窗外观 + 配色方案**（`keyboard`、`candidate`、`color_scheme`、`colors`），可选高级 `keyboards` / `preset_keys` 覆盖 |
| `fallback_colors.yaml` | 独立配色回退链，未在配色方案中指定的细粒度色键沿链自动推导 |
| `ThemeDefaults.kt` | 所有系统行为默认值（`locale`、`auto_caps`、`reset_ascii_mode`、`enter_labels`、悬浮窗 `layout`/`window` 等） |
| `BuiltinKeyboards.kt` | 6 套内置打字布局（`12key` / `qwerty` / `9key` / `wubi` / `stroke` / `bopomofo`）与内置工具键盘（`english`、`number`），按方案按需懒加载 |
| `SymbolPanelController` / `EditPanelController` | 固定原生符号/表情面板与文字编辑面板，自动跟随主题配色，不接受主题改布局 |

## 强类型键盘与高级自定义

- 方案 `.schema.yaml` 声明 `keyboard: { type: 12key | qwerty | 9key | wubi | stroke | bopomofo | <自定义type>, default: <可选布局ID> }`。
- `KeyboardLayoutResolver` 按 `用户设置 → 方案推荐 → 主题 keyboard.defaults[type] → 同类型首项` 强类型解析默认布局，严禁跨类型混选。
- **拒绝无 `type` 的杂乱键盘**：主题 `keyboards:` 中仅允许两种合法自定义：
  1. 覆盖已有类型布局（或覆盖内置 `english` / `number`）；
  2. 新建自定义 `type` 并设计对应布局（需在对应方案中声明相同 `keyboard/type`）。
- 符号扩展词库仍通过 `/sdcard/hkrime/symbols/*.txt` 纯文本维护（新增 `.txt` 即自动新增分类标签）。

## 多键位主题书写规范（Token-Set 原生规范）

对于 `12key`、`9key` 或自定义多键位布局，主题与内置键盘统一遵循以下书写规范（由 `KeyboardLayoutResolver.validateAndFilterTheme`、`Key`/`Event` 与 `SchemaSpecValidator` 强制校验）：

1. **必须使用 `atoms` 声明语义音素集合**：
   - 多键字母/声母按键必须配置 `atoms`（支持列表 `atoms: [a, b, c]` 或逗号/空格分隔字符串 `atoms: "a,b,c"`），多字母组合声母作为单个整体原子声明（如 `atoms: [th]`、`atoms: [ng]`、`atoms: [zh, ch, sh]`）。
   - 支持两种等价写法：
     - **内联写法**：直接在 `keyboards.<id>.keys` 的按键项写 `{ label: DEF, atoms: [d, e, f], long_click: "3" }`（`Key` 与 `Keyboard` 原生识别 `atoms`，无需强制先写 `preset_keys`）。
     - **预设键引用**：在 `preset_keys` 中声明 `atoms` 与 `functional: false`，再由 `keys` 中的 `click` 引用。
2. **禁止旧版数字/占位编码写法（拒绝加载）**：
   - 严禁覆盖内置 `letter_*` 或 `t9_2..t9_9` 时去掉 `atoms` 改写为 `send: "2"` 等占位发送。
   - 严禁在 `12key` / `9key` 布局中写裸数字键（`click: "2"`）或引用无 `atoms` 且 `send` 为单数字/`";"` 的旧式 `preset_keys`（对应 `preset_keys` 覆盖与整套旧布局会被拒绝加载并弹出提示）。
3. **分词键与数字输入规范**：
   - 组合输入态分词键统一使用 `composing: delimiter`（或 `click: delimiter`），闲置态可配置 `text: "@"` 或切换功能键。
   - 副标签长按数字可用 `long_click: "2"`，独立数字盘请使用内置 `num_0`..`num_9`（走 `commit` 直出，不被多键引擎截获）。
4. **配套方案要求**：多键方案（`12key`/`9key`）必须在 `.schema.yaml` 中显式声明 `keyboard/type` 与 `speller/multi_key: true`，禁止使用 `xlit` 或 `derive/[abc]/2/`，详见 `SchemaSpecValidator`。
5. **完整可拷贝示例**：`app/src/main/assets/rime/hakka.trime.yaml` 文末提供了**完整 4 行**的 `my_custom_12key`（全内联 `atoms` 写法，含 `nz/qt/abc/def/ng/ghi/kl/mno/th/ps/vur/zj` 全部 12 键）与 `my_custom_9key`（完整 `preset_keys` `custom_t9_1..custom_t9_9` 引用写法）示例，可直接取消注释启用或作为定制模板。

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
