# Trime 主题预览器

在浏览器里**实时渲染** Trime 主题（`trime.yaml`）的真实键盘布局与配色，不用装 APK、不用连手机。
配套文字说明见 [`docs/08-theme-trime-yaml.md`](../../docs/08-theme-trime-yaml.md)。

## 快速开始

```bash
# 在仓库根目录执行
./tools/theme-previewer/serve.sh          # 默认 8765 端口
# 浏览器打开 http://127.0.0.1:8765/tools/theme-previewer/
```

必须通过 HTTP 打开：页面要 `fetch()` 仓库里的 `app/src/main/assets/rime/trime.yaml`，
直接双击 `index.html`（`file://`）会被浏览器同源策略拦截。

## 能做什么

- **左侧**：YAML 源码编辑框。改完 250ms 自动重渲染；`Ctrl+Enter` 立即渲染；「还原」回到磁盘上的原始文件。
  也可以点「打开 YAML…」选本地文件，或直接把 `.yaml` 拖进窗口。
- **顶部工具栏**：配色方案（37 套）、键盘（17 套）、竖屏/横屏、屏宽（dp）、缩放、西文模式、深色外观、
  显示符号 / 提示、候选栏开关、**候选区结构标注**、方案名（决定空格键显示什么）。
- **中间**：只渲染键盘本体（不含 App 内容区）。键盘按 `Keyboard.java` 的布局算法计算绝对坐标
  （含 `keyboard_height` 高度锁定、右侧填满、空位占位），候选栏/编码区按 `Candidate`/`Composition` 的样式渲染。
- **候选区结构**（默认开）：候选栏虚线外框、每个候选独立框、comment 区斜纹带，下方图例给出
  实际用到的颜色（背景/高亮底/文字/高亮字/注释字/分隔线）与高度/字号/内边距/间距/注释位置。
- **按键三段文字**：主标签居中、长按角标（`label_symbol` / `long_click` 的标签）顶部居中、`hint` 底部居中，
  与 `KeyboardView#onBufferDraw` 一致（旧版本把角标画在左下角，已修正）。
- **候选区点击探查**：点候选字 / 注释（拼音）/ 分隔线 / 候选栏空白，右侧「候选区」tab 显示该部分的
  几何与颜色计算（外框 left/width、measureText、文字与注释中心 X、颜色命中键、高度公式）。
  候选栏几何与 `Candidate.updateCandidateWidth()` / `onDraw()` 逐项对齐：`comment_on_top` 时高度为
  `candidate_view_height + comment_height`，文字居中在下半区、注释在顶部 band。
- **右侧 tabs**
  - 问题：YAML 解析错误（带行列）、缺 style / 方案 / 键盘、未知按键名、style 缺键、键盘高度锁定提示；
  - 颜色：当前渲染用到的每个色键 → 最终颜色 + 命中的来源键（即 fallback 链走了几跳）；
  - 键盘：每套键盘的键数 / 行数 / 高度；
  - 按键：点设备上的任意键，查看它全部事件槽位与 preset 解析结果、颜色、尺寸；
  - style：style 段最终取值表。
- **预设按钮**：默认主题 / 客家主题 / 同文风主题 一键切换。
- **patch 叠加**：把编辑区内容当作 `trime.custom.yaml` 叠加到默认主题上（简化实现：`/a/b` 覆盖、`+/a` 追加）。

## 与真机的差异（重要）

| 项 | 说明 |
|---|---|
| 字体 | 真机读 `assets/rime/fonts/*.ttf`，这里用系统 sans-serif；字体缺失时真机同样回退 |
| 图片背景 | `.9.png` 九宫格、皮肤三层（root/candidate/keyboard）不做合成 |
| 候选栏溢出 | 按 `candidate_view_height` 固定高度，文字溢出的部分被裁掉（与真机一致） |
| 交互 | 点击按键 = 选中（按 `hilited_*` 配色高亮），右侧「按键」tab 显示事件解析；再点一次或点布局外空白 = 取消。不渲染按下瞬间的放大气泡（`preview_*`），也不模拟长按/滑动手势 |
| 花括号串 | `{Left}` / `[]{Left}` / `{}{Left}` 统一显示成 `{}`（不展开内部内容） |
| 悬浮键盘 | `liquid_keyboard` 不渲染，仅在配置里可见 |

它的定位是**读主题、调布局、验配色**，不是像素级还原器。

## 已知实现要点

1. **十六进制颜色要先加引号**：YAML 1.1 会把 `0xE4E7E9` 解析成整数 `0x00E4E7E9`（alpha=0，全透明）。
   `app.js` 在解析前用 `quoteHexNumbers()` 给所有 `0x…` 加引号，保留原始位数。
2. **布局与真机同构**：`layoutKeyboard()` 复刻 `Keyboard#layout` 的两遍法。
3. **label 同构**：`keyLabel()` 复刻 `Key#getLabel` / `Event#getLabel`（toggle states、adjustCase、
   `enter_labels`、space→方案名、`label_transform: uppercase` 只在非 ascii 模式生效）。
4. **配色同构**：`resolveColor()` 沿 `fallback_colors` 链解析，与 `Config.getColorObject` 一致。

## 文件

```
tools/theme-previewer/
├── index.html            页面结构
├── style.css             深色工具界面
├── app.js                解析 / 布局 / 渲染 / 校验（约 1300 行）
├── vendor/js-yaml.min.js js-yaml 4.1.0（本地化，无需联网）
├── serve.sh              起本地静态服务器
└── README.md             本文件
```
