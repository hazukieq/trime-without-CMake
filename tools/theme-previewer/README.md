# 主题预览器

浏览器工具用于读取 YAML、检查按键定义、近似预览布局与色键回退。
当前随包主题只有 `hakka_theme.trime.yaml`；工具默认加载和其它预设仍指向不存在的
`trime.yaml` / `tongwenfeng.trime.yaml`，启动后请选择“客家主题”或打开本地 YAML。

## 使用

在 App 仓库执行 `./tools/theme-previewer/serve.sh`，通过
`http://127.0.0.1:8765/tools/theme-previewer/` 打开。
必须使用 HTTP；页面需要 fetch 仓库资源，不能直接双击 HTML 代替服务器。
编辑 YAML 后自动重渲染，Ctrl+Enter 立即渲染；也支持打开文件或拖入 YAML。

可选择配色、键盘、横竖屏、屏宽、缩放和显示开关；点击按键检查事件/颜色/尺寸，
点击候选区域检查几何和配色。右侧面板显示解析问题、色键来源、键盘和 style。
patch 叠加仅为简化的路径覆盖/追加，不等同于完整 librime 配置编译。

## 边界

layoutKeyboard、keyLabel 和 resolveColor 近似复刻 Android 布局、标签和回退色链。
它不运行引擎、不呈现真实 12 键快照面板、不模拟全部长按/滑动、字体文件、
九宫格背景合成或液态键盘，因此预览通过后仍需真机验证。
YAML 十六进制颜色在解析前加引号以保留位数；App 的最终结果以实际 Config 解析为准。

文件包括 index.html、style.css、app.js、serve.sh 及本地 js-yaml 库。
修改主题的设备步骤见 [主题文档](../../docs/08-theme-trime-yaml.md)。
