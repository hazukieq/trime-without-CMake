/* =====================================================================
 * Trime 主题预览器（trime.yaml live previewer）
 * ---------------------------------------------------------------------
 * 目标：把 trime.yaml 里的 style / preset_color_schemes / preset_keys /
 * preset_keyboards 还原成「和真机上尽量一致」的界面，方便主题作者实时调参。
 *
 * 布局算法逐行对照 app/src/main/java/com/hazukieq/hkrime/ime/keyboard/
 * Keyboard.java（含键盘高度锁定后的行高缩放、右侧填满、空位缩进），
 * 颜色解析对照 data/Config.java 的 getColor() + fallback_colors 链，
 * 按键文字对照 Key.java / Event.java 的 getLabel() 逻辑。
 *
 * 不依赖构建、不需要 Android；纯静态页面。用仓库根目录下的
 *   python3 -m http.server 8000
 * 打开 http://localhost:8000/tools/theme-previewer/ 即可。
 * ===================================================================== */
(() => {
  'use strict';

  /* ----------------------------- 常量与默认值 ----------------------------- */
  // style 缺省值：与 assets/rime/trime.yaml 的默认主题保持一致，取不到的键用这些兜底
  const STYLE_DEFAULTS = {
    auto_caps: false,
    background_dim_amount: 0.5,
    candidate_padding: 5,
    candidate_spacing: 0.5,
    candidate_text_size: 22,
    candidate_use_cursor: false,
    candidate_view_height: 28,
    color_scheme: 'default',
    comment_height: 20,
    comment_on_top: false,
    comment_text_size: 12,
    horizontal: true,
    horizontal_gap: 1,
    keyboard_padding: 0,
    keyboard_padding_left: 0,
    keyboard_padding_right: 0,
    keyboard_padding_bottom: 0,
    keyboard_padding_land: 40,
    keyboard_padding_land_bottom: 0,
    keyboard_height: 250,
    keyboard_height_land: 200,
    key_height: 44,
    key_long_text_size: 14,
    key_text_size: 22,
    key_width: 10,
    label_text_size: 22,
    preview_height: 60,
    preview_offset: 0,
    preview_text_size: 40,
    proximity_correction: true,
    round_corner: 8,
    shadow_radius: 0.0,
    symbol_text_size: 10,
    text_size: 22,
    vertical_correction: -10,
    vertical_gap: 1,
    key_text_offset_x: 0,
    key_text_offset_y: 0,
    key_symbol_offset_x: 0,
    key_symbol_offset_y: 0,
    key_hint_offset_x: 0,
    key_hint_offset_y: 0,
    key_press_offset_x: 0,
    key_press_offset_y: 0,
    enter_label_mode: 0,
  };

  // 渲染时必定会取的颜色键：即使 fallback_colors / 方案里都没写，也在「颜色」面板列出来
  const RENDER_COLOR_KEYS = [
    'back_color', 'text_color', 'hilited_text_color', 'hilited_back_color',
    'candidate_background', 'candidate_text_color', 'hilited_candidate_text_color',
    'comment_text_color', 'hilited_comment_text_color', 'hilited_candidate_back_color',
    'candidate_separator_color', 'label_color', 'hilited_label_color',
    'keyboard_back_color', 'key_back_color', 'key_text_color',
    'hilited_key_back_color', 'hilited_key_text_color',
    'key_symbol_color', 'hilited_key_symbol_color',
    'on_key_back_color', 'on_key_text_color', 'off_key_back_color', 'off_key_text_color',
    'preview_back_color', 'preview_text_color', 'shadow_color', 'border_color',
    'text_back_color', 'long_text_back_color', 'root_background',
  ];

  // 常用于 preset_keys 里 send: 的键名 -> 预览标签（Event.getDisplayLabel 的近似）
  const KEYCODE_LABELS = {
    Return: 'Return', BackSpace: 'BackSpace', Tab: 'Tab', Escape: 'Esc',
    Delete: 'Del', Insert: 'Ins', Menu: 'Menu',
    Shift_L: 'Shift', Shift_R: 'Shift', Control_L: 'Ctrl', Control_R: 'Ctrl',
    Alt_L: 'Alt', Alt_R: 'Alt', Meta_L: 'Meta', Meta_R: 'Meta',
    Left: '←', Right: '→', Up: '↑', Down: '↓',
    Page_Up: 'PgUp', Page_Down: 'PgDn', MoveHome: 'Home', MoveEnd: 'End',
    Home: 'Home', End: 'End', Caps_Lock: 'Caps', Num_Lock: 'Num',
    KP_Enter: 'Enter', KP_Delete: 'Del',
  };

  // 常见 Keycode 名（Event.getDisplayLabel 走 keyName 的那些），用于减少「未知按键名」噪音
  const KNOWN_KEYCODES = new Set([
    'F1','F2','F3','F4','F5','F6','F7','F8','F9','F10','F11','F12',
    'KP_0','KP_1','KP_2','KP_3','KP_4','KP_5','KP_6','KP_7','KP_8','KP_9',
    'KP_Enter','KP_Plus','KP_Minus','KP_Multiply','KP_Divide','KP_Decimal','KP_Equal',
    'Print','Sys_Req','Scroll_Lock','Pause','Break','Clear','Select','Print_Screen',
    'AudioPlay','AudioStop','AudioPrev','AudioNext','AudioRewind','AudioForward',
    'AudioRepeat','AudioRandom','AudioVolumeUp','AudioVolumeDown','AudioMute',
    'Back','Forward','Refresh','Stop','Find','Cancel','Launch_0','Launch_1',
    'Sleep','Wake_Up','Power','Eject','Explorer','Calculator','Mail','Music',
    'Search','HomePage','BackSpace','Return','Escape','Tab','Delete','Insert',
    'Menu','Caps_Lock','Num_Lock','Left','Right','Up','Down','Page_Up','Page_Down',
    'MoveHome','MoveEnd','Shift_L','Shift_R','Control_L','Control_R','Alt_L','Alt_R',
    'Super_L','Super_R','Multi_key','VoidSymbol','space','BackSpace','ISO_Left_Tab',
  ]);

  const EVENTS = ['click', 'long_click', 'swipe_up', 'swipe_down', 'swipe_left', 'swipe_right', 'composing', 'has_menu', 'paging', 'ascii', 'combo', 'extra'];

  /* ------------------------------- 状态 ------------------------------- */
  const S = {
    editorText: '',        // 编辑框里的内容
    baseText: '',          // 「还原」用的原始文件内容
    fileName: '',
    theme: null,           // 解析后的主题对象（已处理 __include / patch）
    baseTheme: null,       // patch 模式下叠加的基底主题
    schemeName: '',
    schemeObj: null,
    keyboardName: '',
    land: false,
    width: 360,
    zoom: 1,
    ascii: false,
    dark: false,
    showSymbol: true,
    showHint: true,
    showPreview: true,
    showCandidates: true,
    showCandStruct: true,
    schemaName: '客语12键',
    composition: 'ngin',
    candidates: [],
    patchMode: false,
    issues: [],
    selectedKey: null,
    layout: null,
    sideTab: 'issues',
  };

  const $ = (id) => document.getElementById(id);
  const el = {
    editor: $('editor'), fileInput: $('fileInput'),
    status: $('status'), themeMeta: $('themeMeta'), cursorInfo: $('cursorInfo'),
    selScheme: $('selScheme'), selKeyboard: $('selKeyboard'),
    selOrientation: $('selOrientation'), inpWidth: $('inpWidth'), inpZoom: $('inpZoom'),
    chkAscii: $('chkAscii'), chkDark: $('chkDark'), chkSymbol: $('chkSymbol'),
    chkHint: $('chkHint'), chkPreview: $('chkPreview'), chkCandidates: $('chkCandidates'), chkCandStruct: $('chkCandStruct'),
    candLegend: $('candLegend'),
    inpSchema: $('inpSchema'), inpComposition: $('inpComposition'), inpCandidates: $('inpCandidates'),
    device: $('device'), composeBar: $('composeBar'), candidateBar: $('candidateBar'),
    keyboard: $('keyboard'), stageHint: $('stageHint'), patchMode: $('patchMode'),
    issueList: $('issueList'), issuesEmpty: $('issuesEmpty'), badgeIssues: $('badgeIssues'),
    colorTable: $('colorTable').querySelector('tbody'), curScheme: $('curScheme'),
    kbTable: $('kbTable').querySelector('tbody'), keyDetail: $('keyDetail'),
    styleTable: $('styleTable').querySelector('tbody'),
  };

  /* ------------------------------ 小工具 ------------------------------ */
  const isPlain = (o) => o !== null && typeof o === 'object' && !Array.isArray(o);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const num = (v, d) => (v === undefined || v === null || v === '' ? d : Number(v));
  const px = (v, d) => Math.round(num(v, d));
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

  function clone(o) {
    if (o === undefined) return undefined;
    return JSON.parse(JSON.stringify(o));
  }

  // 深合并：map 递归合并，数组/标量直接覆盖
  function merge(base, over) {
    if (!isPlain(base) || !isPlain(over)) return over === undefined ? clone(base) : clone(over);
    const out = clone(base);
    for (const k of Object.keys(over)) {
      out[k] = isPlain(out[k]) && isPlain(over[k]) ? merge(out[k], over[k]) : clone(over[k]);
    }
    return out;
  }

  function getPath(root, path) {
    if (typeof path !== 'string') return undefined;
    if (path.startsWith('/')) path = path.slice(1);
    if (!path) return root;
    let cur = root;
    for (const part of path.split('/')) {
      if (cur == null) return undefined;
      cur = cur[part];
    }
    return cur;
  }

  function setPath(root, path, value) {
    const parts = path.replace(/^\//, '').split('/').filter(Boolean);
    let cur = root;
    for (let i = 0; i < parts.length - 1; i++) {
      const p = parts[i];
      if (!isPlain(cur[p]) && !Array.isArray(cur[p])) cur[p] = {};
      cur = cur[p];
    }
    cur[parts[parts.length - 1]] = value;
  }

  /* ------------------------------ YAML 解析 ------------------------------ */
  // 解析 __include（支持 "/preset_keyboards/default" 这种同文件路径，可多级）
  function resolveIncludes(node, root, depth = 0) {
    if (depth > 24) return node;
    if (Array.isArray(node)) return node.map((n) => resolveIncludes(n, root, depth + 1));
    if (!isPlain(node)) return node;
    let out = {};
    if (node.__include !== undefined) {
      let base = {};
      const inc = node.__include;
      if (typeof inc === 'string') {
        base = getPath(root, inc) || {};
      } else if (Array.isArray(inc)) {
        for (const p of inc) base = merge(base, getPath(root, p) || {});
      }
      out = isPlain(base) ? resolveIncludes(clone(base), root, depth + 1) : {};
    }
    for (const k of Object.keys(node)) {
      if (k === '__include') continue;
      if (k === 'patch') { out[k] = clone(node[k]); continue; } // patch 段不展开
      out[k] = resolveIncludes(node[k], root, depth + 1);
    }
    return out;
  }

  // 应用 trime.custom.yaml 的 patch 段（简化实现：/a/b 覆盖，+/a 追加，支持数组下标）
  function applyPatch(root, patch) {
    if (!isPlain(patch)) return root;
    const out = clone(root);
    for (const rawPath of Object.keys(patch)) {
      let path = rawPath;
      let mode = 'set';
      if (path.startsWith('+')) { mode = 'add'; path = path.slice(1); }
      const val = clone(patch[rawPath]);
      const parts = path.replace(/^\//, '').split('/').filter(Boolean);
      if (!parts.length) continue;
      // 定位父节点
      let cur = out;
      let ok = true;
      for (let i = 0; i < parts.length - 1; i++) {
        const p = parts[i];
        if (cur[p] === undefined) cur[p] = /^\d+$/.test(parts[i + 1]) ? [] : {};
        cur = cur[p];
        if (!isPlain(cur) && !Array.isArray(cur)) { ok = false; break; }
      }
      if (!ok) continue;
      const last = parts[parts.length - 1];
      if (mode === 'add') {
        if (Array.isArray(cur[last])) cur[last] = Array.isArray(val) ? val.concat(cur[last]) : [val].concat(cur[last]);
        else if (isPlain(cur[last]) && isPlain(val)) cur[last] = merge(cur[last], val);
        else cur[last] = val;
      } else {
        if (/^\d+$/.test(last) && Array.isArray(cur)) cur[Number(last)] = val;
        else cur[last] = val;
      }
    }
    return out;
  }

  // YAML 1.1 会把 0xE4E7E9 解析成整数（丢掉前导 00），而 Rime 是按字符串
  // "0xRRGGBB"/"0xAARRGGBB" 解释的。这里在解析前把 0x... 统一加上引号保真。
  function quoteHexNumbers(text) {
    let out = '', i = 0, quote = null, comment = false;
    while (i < text.length) {
      const c = text[i];
      if (comment) { out += c; if (c === '\n') comment = false; i++; continue; }
      if (quote) {
        out += c;
        if (c === quote && text[i - 1] !== '\\') quote = null;
        i++; continue;
      }
      if (c === '"' || c === "'") { quote = c; out += c; i++; continue; }
      if (c === '#') { comment = true; out += c; i++; continue; }
      if (c === '0' && text[i + 1] === 'x' && /[0-9a-fA-F]/.test(text[i + 2] || '')) {
        let j = i + 2;
        while (j < text.length && /[0-9a-fA-F]/.test(text[j])) j++;
        out += '"' + text.slice(i, j) + '"';
        i = j; continue;
      }
      out += c; i++;
    }
    return out;
  }

  function parseYaml(text, what) {
    try {
      const doc = jsyaml.load(quoteHexNumbers(text), { schema: jsyaml.DEFAULT_SCHEMA });
      if (doc === null || doc === undefined) throw new Error('文件是空的');
      if (!isPlain(doc)) throw new Error('顶层必须是映射（mapping），当前是 ' + (Array.isArray(doc) ? '列表' : typeof doc));
      return { doc };
    } catch (e) {
      const mark = e.mark || {};
      return {
        error: {
          message: String(e.reason || e.message || e).split('\n')[0],
          line: typeof mark.line === 'number' ? mark.line + 1 : null,
          column: typeof mark.column === 'number' ? mark.column + 1 : null,
          what,
        },
      };
    }
  }

  /* ------------------------------ 颜色解析 ------------------------------ */
  function intToCss(n) {
    const u = n >>> 0;
    const a = ((u >>> 24) & 0xff) / 255;
    const r = (u >>> 16) & 0xff;
    const g = (u >>> 8) & 0xff;
    const b = u & 0xff;
    if (a >= 0.999) return 'rgb(' + r + ',' + g + ',' + b + ')';
    return 'rgba(' + r + ',' + g + ',' + b + ',' + (Math.round(a * 1000) / 1000) + ')';
  }

  // 对照 Config.parseColor：0xRRGGBB / 0xAARRGGBB / #hex；带 "." 的字符串视为图片
  function parseColor(v) {
    if (v === null || v === undefined || v === '') return null;
    if (typeof v === 'number') return { css: intToCss(v), int: v };
    if (typeof v !== 'string') return null;
    const s = v.trim();
    if (/^-?\d+$/.test(s)) return { css: intToCss(parseInt(s, 10)), int: parseInt(s, 10) };
    let m = /^0x([0-9a-fA-F]+)$/.exec(s);
    if (m) {
      let h = m[1];
      if (h.length === 8) {
        const n = parseInt(h, 16) >>> 0;
        return { css: intToCss(n), int: n };
      }
      if (h.length === 6) return { css: '#' + h, int: parseInt(h, 16) };
      if (h.length === 2) return { css: 'rgba(0,0,0,' + (parseInt(h, 16) / 255) + ')', int: parseInt(h, 16) << 24 };
      if (h.length < 6) return { css: '#' + h.padStart(6, '0'), int: parseInt(h.padStart(6, '0'), 16) };
      return null;
    }
    if (/^#[0-9a-fA-F]{3,8}$/.test(s)) return { css: s, int: null };
    if (s.includes('.')) return { css: null, int: null, image: s };
    // 常见颜色名（Rime 里少数主题会写 red/green 之类）
    if (/^[a-zA-Z]+$/.test(s)) return { css: s.toLowerCase(), int: null };
    return null;
  }

  function resolveColor(key) {
    const theme = S.theme || {};
    const fb = isPlain(theme.fallback_colors) ? theme.fallback_colors : {};
    const scheme = S.schemeObj || {};
    const chain = [];
    const seen = new Set();
    let k = key;
    while (k && !seen.has(k)) {
      seen.add(k);
      chain.push(k);
      const raw = scheme[k];
      if (raw !== undefined && raw !== null && raw !== '') {
        const c = parseColor(raw);
        return { key, chain, from: k, raw, ...(c || {}) };
      }
      k = fb[k];
    }
    return { key, chain, from: null, raw: null, css: null };
  }

  function color(key, dflt) {
    const r = resolveColor(key);
    return r.css || dflt || null;
  }

  /* ------------------------------ style 取值 ------------------------------ */
  function st(key) {
    const style = (S.theme && S.theme.style) || {};
    return style[key] !== undefined && style[key] !== null ? style[key] : STYLE_DEFAULTS[key];
  }
  function stpx(key) { return px(st(key), 0); }
  function fontCss(name, fallback) {
    if (!name || typeof name !== 'string') return fallback || '';
    if (name.includes('.') && !/\s/.test(name)) return fallback || ''; // trime 里 xxx.ttf 这种自定义字体预览器拿不到
    return '"' + name + '", ' + (fallback || 'sans-serif');
  }

  /* ------------------------------ 事件解析 ------------------------------ */
  function presetKey(name) {
    const keys = (S.theme && S.theme.preset_keys) || {};
    return isPlain(keys[name]) ? keys[name] : null;
  }

  function optionOn(name) {
    if (name === 'ascii_mode') return S.ascii;
    return false;
  }

  function enterLabel() {
    const labels = (S.theme && S.theme.style && S.theme.style.enter_labels) || {};
    return labels.default || 'Enter';
  }

  // `{Left}`、`[]{Left}`、`{}{Left}` 这类「花括号 send/text 串」不显示内部内容，
  // 统一缩成 `{}`（用户要求：这类提示不需要，占位即可）
  function hasBraceToken(s) {
    return typeof s === 'string' && /\{[^}]*\}/.test(s);
  }
  function bracePlaceholder(s) {
    return hasBraceToken(s) ? '{}' : s;
  }

  function displayLabel(token) {
    if (token === null || token === undefined) return '';
    const s = String(token);
    if (hasBraceToken(s)) return '{}';
    if (s === 'space' || s === 'Space') return S.schemaName || '空格';
    if (s.length === 1) return s;
    return KEYCODE_LABELS[s] || s;
  }

  // 按键实际显示文字：Key.getLabel() 的近似（label 优先、preset 的 label/states、keycode 名）
  function keyLabel(entry) {
    const kb = S.layout && S.layout.kb;
    if (entry.label !== undefined && entry.label !== null && entry.label !== '' && !S.ascii && entry.ascii === undefined) {
      return applyTransform(String(entry.label), kb);
    }
    const token = entry.click;
    if (token === undefined || token === null || token === '') return '';
    let t = String(token);
    if (hasBraceToken(t)) return '{}';
    const p = presetKey(t);
    let lab = null;
    if (p) {
      if (p.toggle && Array.isArray(p.states) && p.states.length) lab = String(p.states[optionOn(p.toggle) ? 1 : 0] ?? p.states[0]);
      if (lab === null && p.label !== undefined && p.label !== null && p.label !== '') lab = String(p.label);
      if (lab === 'enter_labels') lab = enterLabel();
      if (lab === null) lab = displayLabel(p.send || p.text || t);
    } else {
      lab = displayLabel(t);
    }
    return applyTransform(lab, kb);
  }

  function applyTransform(label, kb) {
    if (!label) return label;
    if (kb && kb.label_transform === 'uppercase' && !S.ascii && label.length === 1) return label.toUpperCase();
    return label;
  }

  // 给右侧「按键」面板用：把每个事件槽解析成人能读的字段
  function parseEventToken(token) {
    const out = { token, kind: '', fields: {} };
    if (token === undefined || token === null || token === '') { out.kind = 'empty'; return out; }
    const t = String(token);
    if (t.startsWith('{') && t.endsWith('}')) {
      const inner = t.slice(1, -1);
      out.kind = 'send-chord';
      out.fields.label = inner;
      out.fields.send = inner;
      return out;
    }
    const p = presetKey(t);
    if (p) {
      out.kind = 'preset';
      out.preset = t;
      for (const f of ['label', 'send', 'text', 'command', 'option', 'select', 'toggle', 'shift_lock', 'preview']) {
        if (p[f] !== undefined) out.fields[f] = p[f];
      }
      if (p.states) out.fields.states = p.states;
      if (p.repeatable) out.fields.repeatable = true;
      if (p.functional === false) out.fields.functional = false;
      return out;
    }
    out.kind = t.length === 1 ? 'char' : 'keycode';
    out.fields.label = displayLabel(t);
    if (t.length === 1) out.fields.text = t;
    return out;
  }

  /* ------------------------------ 键盘布局 ------------------------------ */
  // 逐行对照 Keyboard.java 的构造函数（竖屏/横屏 padding、键盘级覆盖、高度缩放、右侧填满）
  function keyboardPadding(land) {
    const out = [0, 0, 0];
    if (land) {
      out[0] = stpx('keyboard_padding_land');
      out[1] = out[0];
      out[2] = stpx('keyboard_padding_land_bottom');
    } else {
      out[0] = stpx('keyboard_padding');
      out[1] = out[0];
      out[2] = stpx('keyboard_padding_bottom');
    }
    return out;
  }

  function layoutKeyboard(kbName, opts) {
    const silent = opts && opts.silent;
    const theme = S.theme || {};
    const kbs = theme.preset_keyboards || {};
    const kb = kbs[kbName];
    if (!isPlain(kb)) {
      addIssue('error', 'keyboard', '找不到键盘「' + kbName + '」', 'preset_keyboards 里没有这个键。检查 style.keyboards 或按钮选择。');
      return null;
    }
    const pad = keyboardPadding(S.land);
    const displayWidth = S.width - pad[0] - pad[1];
    let gap = px(kb.horizontal_gap !== undefined ? kb.horizontal_gap : st('horizontal_gap'), 3);
    let vgap = px(kb.vertical_gap !== undefined ? kb.vertical_gap : st('vertical_gap'), 5);
    const round = kb.round_corner !== undefined ? Number(kb.round_corner) : Number(st('round_corner'));
    const defaultWidthStyle = Math.round((displayWidth * num(st('key_width'), 10)) / 100);
    const kbWidthPct = num(kb.width, 0);
    let defaultWidth = Math.round((kbWidthPct * displayWidth) / 100);
    if (defaultWidth === 0) defaultWidth = defaultWidthStyle;

    const kbHeightRaw = px(kb.height, 0);
    const defaultHeight = kbHeightRaw > 0 ? kbHeightRaw : stpx('key_height');
    let keyboardHeight = stpx('keyboard_height');
    if (S.land) {
      const l = stpx('keyboard_height_land');
      if (l > 0) keyboardHeight = l;
    }
    if (kb.keyboard_height !== undefined) {
      const h = px(kb.keyboard_height, 0);
      if (S.land && kb.keyboard_height_land !== undefined) {
        const hl = px(kb.keyboard_height_land, 0);
        if (hl > 0) keyboardHeight = hl;
        else if (h > 0) keyboardHeight = h;
      } else if (h > 0) keyboardHeight = h;
    }

    const columns = num(kb.columns, 30);
    const maxColumns = columns === -1 ? Number.MAX_SAFE_INTEGER : columns;
    let autoHeightIndex = num(kb.auto_height_index, -1);
    const keys = Array.isArray(kb.keys) ? kb.keys : [];
    if (!keys.length) {
      addIssue('warn', 'keyboard', '键盘「' + kbName + '」没有 keys 列表', '这个键盘在真机上也没有按键可显示。');
    }

    const rows = [];
    let newHeight = null;

    if (keyboardHeight > 0) {
      // ---- 第一遍：按风格原始高度模拟，统计每行高度 ----
      let x = Math.floor(gap / 2), y = vgap, row = 0, column = 0, rowHeight = defaultHeight;
      let rawSum = 0;
      const rawHeight = [];
      for (const mk of keys) {
        let w = Math.round(num(mk.width, 0) * displayWidth / 100);
        if (w === 0 && mk.click !== undefined) w = defaultWidth;
        w -= gap;
        if (column >= maxColumns || x + w > displayWidth) {
          x = Math.floor(gap / 2);
          y += vgap + rowHeight;
          column = 0; row++;
          rawSum += rowHeight;
          rawHeight.push(rowHeight);
        }
        if (column === 0) {
          const hk = px(mk.height, 0);
          rowHeight = hk > 0 ? hk : defaultHeight;
        }
        if (mk.click === undefined) { x += w + gap; continue; }
        column++;
        const rightGap = Math.abs(displayWidth - x - w - gap / 2);
        x += (rightGap <= displayWidth / 100 ? displayWidth - x - gap / 2 : w) + gap;
      }
      rawSum += rowHeight;
      rawHeight.push(rowHeight);

      let scale = keyboardHeight / (rawSum + vgap * (rawHeight.length + 1));
      vgap = Math.ceil(vgap * scale);
      let autoHeight = keyboardHeight - vgap * (rawHeight.length + 1);
      scale = autoHeight / rawSum;
      if (autoHeightIndex < 0) {
        autoHeightIndex = rawHeight.length + autoHeightIndex;
        if (autoHeightIndex < 0) autoHeightIndex = 0;
      } else if (autoHeightIndex >= rawHeight.length) {
        autoHeightIndex = rawHeight.length - 1;
      }
      newHeight = new Array(rawHeight.length).fill(0);
      for (let i = 0; i < rawHeight.length; i++) {
        if (i !== autoHeightIndex) {
          const h = Math.trunc(rawHeight[i] * scale);
          newHeight[i] = h;
          autoHeight -= h;
        }
      }
      if (autoHeight < 1 && rawHeight[autoHeightIndex] > 0) autoHeight = 1;
      newHeight[autoHeightIndex] = autoHeight;
      if (!silent) {
        addIssue('info', 'layout', '键盘「' + kbName + '」高度锁定为 ' + keyboardHeight + 'px',
          '原始行高 [' + rawHeight.join(', ') + '] 缩放为 [' + newHeight.join(', ') + ']（auto_height_index=' + autoHeightIndex + '）。');
      }
    }

    // ---- 第二遍：正式落位 ----
    let x = Math.floor(gap / 2), y = vgap, row = 0, column = 0, rowHeight = defaultHeight;
    const outKeys = [];
    for (const mk of keys) {
      let w = Math.round(num(mk.width, 0) * displayWidth / 100);
      if (w === 0 && mk.click !== undefined) w = defaultWidth;
      w -= gap;
      if (column >= maxColumns || x + w > displayWidth) {
        x = Math.floor(gap / 2);
        y += vgap + rowHeight;
        column = 0; row++;
        if (outKeys.length) outKeys[outKeys.length - 1].edgeRight = true;
      }
      if (column === 0) {
        if (newHeight) rowHeight = newHeight[row] || 1;
        else rowHeight = px(mk.height, 0) > 0 ? px(mk.height, 0) : defaultHeight;
      }
      if (mk.click === undefined) { x += w + gap; continue; } // 空位缩进
      const rightGap = Math.abs(displayWidth - x - w - gap / 2);
      const width = rightGap <= displayWidth / 100 ? displayWidth - x - gap / 2 : w;
      outKeys.push({
        entry: mk, x, y, width, height: rowHeight, gap, row, column,
        left: pad[0] + x,
      });
      column++;
      x += width + gap;
    }
    const totalHeight = y + rowHeight + vgap;
    return {
      kb, name: kbName, pad, displayWidth, keys: outKeys, totalHeight, keyboardHeight,
      gap, vgap, round, rowsCount: row + 1,
    };
  }

  /* ------------------------------ 问题面板 ------------------------------ */
  function addIssue(level, tag, message, detail) {
    S.issues.push({ level, tag, message, detail });
  }

  function clearIssues() { S.issues = []; }

  function renderIssues() {
    const list = el.issueList;
    list.innerHTML = '';
    const errs = S.issues.filter((i) => i.level === 'error').length;
    const warns = S.issues.filter((i) => i.level === 'warn').length;
    const n = errs + warns;
    el.badgeIssues.textContent = String(n);
    el.badgeIssues.classList.toggle('zero', n === 0);
    el.issuesEmpty.hidden = S.issues.length > 0;
    for (const it of S.issues) {
      const li = document.createElement('li');
      li.className = 'issue ' + it.level;
      li.innerHTML = '<span class="tag">' + esc(it.level) + '</span>' + esc(it.message) +
        (it.detail ? '<div class="detail">' + esc(it.detail) + '</div>' : '');
      list.appendChild(li);
    }
  }

  /* ------------------------------ 主题装载 ------------------------------ */
  function selectScheme(theme) {
    const schemes = theme.preset_color_schemes || {};
    const style = theme.style || {};
    let name = S.schemeName && schemes[S.schemeName] ? S.schemeName : (style.color_scheme || 'default');
    if (!schemes[name]) {
      const first = Object.keys(schemes)[0];
      if (first) {
        addIssue('warn', 'color', '配色方案「' + name + '」不存在', '退回到「' + first + '」。style.color_scheme 或选择框引用了未定义的方案。');
        name = first;
      } else {
        addIssue('error', 'color', '主题里没有任何 preset_color_schemes', '所有颜色都会用 fallback 或透明。');
        name = '';
      }
    }
    // dark_scheme / light_scheme：Config.getColorSchemeName 的行为
    let s = schemes[name];
    if (isPlain(s)) {
      if (S.dark && s.dark_scheme && schemes[s.dark_scheme]) name = s.dark_scheme;
      else if (!S.dark && s.light_scheme && schemes[s.light_scheme]) name = s.light_scheme;
    }
    S.schemeName = name;
    S.schemeObj = schemes[name] || null;
    return name;
  }

  function themeToRender(theme) {
    // 展开 __include：以解析后的 doc 作为根
    return resolveIncludes(theme, theme);
  }

  function rebuildTheme() {
    clearIssues();
    S.layout = null;
    S.selectedKey = null;
    const parseBase = parseYaml(el.editor.value, '主题');
    if (parseBase.error) {
      S.theme = null;
      S.issues.push({
        level: 'error', tag: 'yaml',
        message: 'YAML 解析失败' + (parseBase.error.line ? '（第 ' + parseBase.error.line + ' 行，第 ' + parseBase.error.column + ' 列）' : ''),
        detail: parseBase.error.message,
      });
      setStatus('YAML 解析失败', 'err');
      renderAll();
      return;
    }
    let theme = parseBase.doc;
    if (S.patchMode) {
      if (!S.baseTheme) {
        S.issues.push({ level: 'error', tag: 'patch', message: 'patch 叠加模式需要基底主题', detail: '先点「默认主题」载入基底，再勾选 patch 叠加。' });
        S.theme = null;
        renderAll();
        return;
      }
      if (!isPlain(theme.patch)) {
        S.issues.push({ level: 'warn', tag: 'patch', message: '当前内容的顶层没有 patch: 段', detail: 'patch 模式会把它当作空的补丁，界面与基底主题相同。' });
      }
      theme = applyPatch(S.baseTheme, theme.patch || {});
    }
    S.theme = themeToRender(theme);
    checkThemeSanity(S.theme);
    selectScheme(S.theme);
    chooseKeyboard(S.theme);
    S.layout = layoutKeyboard(S.keyboardName);
    setStatus('已渲染 · ' + S.keyboardName + ' · ' + (S.schemeName || '无配色'), 'ok');
    renderAll();
  }

  function checkThemeSanity(theme) {
    const style = theme.style;
    if (!isPlain(style)) addIssue('warn', 'theme', '缺少 style 段', '没有 style，预览会全部用内置默认值；真机上同样会失去主题外观。');
    if (theme.preset_keyboards && !Object.keys(theme.preset_keyboards).length) {
      addIssue('warn', 'theme', 'preset_keyboards 为空', '没有可预览的键盘。');
    }
    // 常用 style 键缺失提示（真机有默认值，这里仅提示）
    for (const k of ['key_width', 'key_height', 'key_text_size', 'horizontal_gap', 'vertical_gap', 'round_corner']) {
      if (isPlain(style) && style[k] === undefined) addIssue('info', 'style', 'style 缺少 ' + k, '预览器使用默认值 ' + STYLE_DEFAULTS[k] + '。');
    }
    // preset_keys 引用检查：扫描所有键盘的按键事件
    const kbs = theme.preset_keyboards || {};
    const presets = theme.preset_keys || {};
    const unknown = new Map();
    for (const kbName of Object.keys(kbs)) {
      const kb = kbs[kbName];
      if (!isPlain(kb) || !Array.isArray(kb.keys)) continue;
      for (const entry of kb.keys) {
        if (!isPlain(entry)) continue;
        for (const slot of EVENTS) {
          const token = entry[slot];
          if (typeof token !== 'string' || !token || token.startsWith('{')) continue;
          if (hasBraceToken(token)) continue;
          if (presets[token] || token.length === 1 || KEYCODE_LABELS[token] || KNOWN_KEYCODES.has(token) || token === 'space') continue;
          const key = kbName + ' → ' + token;
          if (!unknown.has(token)) unknown.set(token, new Set());
          unknown.get(token).add(kbName);
        }
      }
    }
    if (unknown.size) {
      const names = [...unknown.keys()];
      addIssue('info', 'preset', '有 ' + names.length + ' 个按键名既不在 preset_keys、也不是单字符/常见键码',
        '预览会直接把名字当作标签显示：' + names.slice(0, 12).join('、') + (names.length > 12 ? ' …' : ''));
    }
  }

  function chooseKeyboard(theme) {
    const kbs = theme.preset_keyboards || {};
    const names = Object.keys(kbs);
    let name = S.keyboardName;
    if (!name || !kbs[name]) {
      // style.keyboards 是键盘映射表（.default/letter/...），默认取第一个可用的
      const styleList = Array.isArray((theme.style || {}).keyboards) ? theme.style.keyboards : [];
      const cand = styleList.find((n) => kbs[n] || kbs[String(n).replace(/^\./, '')]);
      name = cand ? (kbs[cand] ? cand : String(cand).replace(/^\./, '')) : (names.includes('default') ? 'default' : names[0]);
    }
    if (!kbs[name]) {
      addIssue('error', 'keyboard', '没有可用的 preset_keyboards', '主题里没有任何键盘定义。');
      S.keyboardName = '';
      return;
    }
    S.keyboardName = name;
  }

  /* ------------------------------ 渲染：候选栏 ------------------------------ */
  function parseCandidates() {
    const raw = el.inpCandidates.value || '';
    return raw.split(/\n|,/).map((s) => s.trim()).filter(Boolean).map((line, i) => {
      const parts = line.split('|');
      return { word: parts[0].trim(), comment: (parts[1] || '').trim(), label: String(i + 1) };
    });
  }

  function renderCandidates() {
    const bar = el.candidateBar;
    bar.innerHTML = '';
    const style = (S.theme && S.theme.style) || {};
    const h = px(style.candidate_view_height, STYLE_DEFAULTS.candidate_view_height);
    const padding = num(style.candidate_padding, 5);
    const spacing = num(style.candidate_spacing, 0.5);
    const fontSize = px(style.candidate_text_size, 22);
    const commentSize = px(style.comment_text_size, 12);
    const commentOnTop = !!style.comment_on_top;
    const bg = color('candidate_background') || color('back_color') || 'transparent';
    // 真机按 candidate_view_height 硬裁；预览器优先「看得清」，26sp 的栏里放 22+12 会把注释切掉半截，
    // 这里按内容撑高（仅预览，不改变主题语义），并在 docs/08 §12 里注明这处差异。
    const hasComment = (S.candidates.length ? S.candidates : parseCandidates()).some((c) => c.comment);
    const barH = hasComment ? Math.max(h, fontSize + commentSize + 10) : h;
    bar.style.height = barH + 'px';
    bar.style.background = bg;
    if (!S.showCandidates) { bar.style.display = 'none'; return; }
    bar.style.display = 'flex';
    const cands = S.candidates.length ? S.candidates : parseCandidates();
    cands.forEach((c, i) => {
      const d = document.createElement('div');
      d.className = 'candidate' + (i === 0 ? ' hl' : '');
      d.style.padding = '0 ' + (padding + spacing) + 'px';
      d.style.height = '100%';
      if (i === 0) {
        const hlBg = color('hilited_candidate_back_color');
        if (hlBg) d.style.background = hlBg;
      }
      const wordColor = i === 0 ? (color('hilited_candidate_text_color') || color('candidate_text_color')) : color('candidate_text_color');
      const commentColor = i === 0 ? (color('hilited_comment_text_color') || color('comment_text_color')) : color('comment_text_color');
      const w = document.createElement('div');
      w.className = 'word';
      w.textContent = c.word;
      w.style.fontSize = fontSize + 'px';
      w.style.lineHeight = '1.02';
      w.style.color = wordColor || 'inherit';
      w.style.fontFamily = fontCss((S.theme.style || {}).candidate_font, 'sans-serif');
      let cm = null;
      if (c.comment) {
        cm = document.createElement('div');
        cm.className = 'comment';
        cm.textContent = c.comment;
        cm.style.fontSize = commentSize + 'px';
        cm.style.lineHeight = '1.02';
        cm.style.color = commentColor || 'inherit';
        cm.style.fontFamily = fontCss((S.theme.style || {}).comment_font, 'sans-serif');
      }
      // 真机 Candidate.java：comment_on_top=true 时 comment 画在 word 上方（word 下移）
      if (commentOnTop) { if (cm) d.appendChild(cm); d.appendChild(w); }
      else { d.appendChild(w); if (cm) d.appendChild(cm); }
      d.style.maxHeight = h + 'px';
      if (i < cands.length - 1) {
        const sep = document.createElement('div');
        sep.className = 'sep';
        sep.style.background = color('candidate_separator_color') || 'transparent';
        d.appendChild(sep);
      }
      bar.appendChild(d);
    });

    // ---- 候选区结构标注：虚线外框 + 注释带 + 高度徽标 + 图例 ----
    bar.classList.toggle('annotate', !!S.showCandStruct);
    if (S.showCandStruct) {
      const g = document.createElement('div');
      g.className = 'cand-guide';
      if (hasComment) {
        const band = document.createElement('div');
        band.className = 'cand-comment-band';
        band.style.height = commentSize + 'px';
        if (commentOnTop) band.style.top = '0px';
        else band.style.bottom = '0px';
        band.title = 'comment 区（' + commentSize + 'px，' + (commentOnTop ? '在上方' : '在下方') + '）';
        g.appendChild(band);
      }
      bar.appendChild(g);
      renderCandLegend({
        barH, cfgH: h, fontSize, commentSize, padding, spacing, commentOnTop, hasComment,
        candCount: cands.length,
      });
    } else if (el.candLegend) {
      el.candLegend.hidden = true;
    }
  }

  function renderCandLegend(info) {
    const L = el.candLegend;
    if (!L) return;
    L.hidden = false;
    const chip = (label, key) => {
      const r = resolveColor(key);
      const sw = r.css ? '<span class="sw" style="background:' + esc(r.css) + '"></span>'
                       : '<span class="sw" style="background:repeating-linear-gradient(45deg,#333,#333 2px,#222 2px,#222 4px)"></span>';
      return '<span class="chip">' + sw + esc(label) + '</span>';
    };
    L.innerHTML =
      '<span class="title">候选区构成</span>' +
      chip('背景', 'candidate_background') +
      chip('高亮底', 'hilited_candidate_back_color') +
      chip('文字', 'candidate_text_color') +
      chip('高亮字', 'hilited_candidate_text_color') +
      chip('注释字', 'comment_text_color') +
      chip('分隔线', 'candidate_separator_color') +
      '<span class="chip"><b>高 ' + info.barH + 'px</b></span>' +
      '<span class="chip">候选 <b>' + info.fontSize + 'px</b></span>' +
      '<span class="chip">注释 <b>' + info.commentSize + 'px</b></span>' +
      '<span class="chip">内边距 <b>' + info.padding + '</b></span>' +
      '<span class="chip">间距 <b>' + info.spacing + '</b></span>' +
      '<span class="chip">斜纹 = comment ' + info.commentSize + 'px，<b>' + (info.commentOnTop ? '在上' : '在下') + '</b></span>' +
      '<span class="chip">共 <b>' + info.candCount + '</b> 个候选</span>';
  }

  function renderComposition() {
    const bar = el.composeBar;
    const text = (el.inpComposition.value || '').trim();
    if (!text || !S.showCandidates) { bar.hidden = true; return; }
    const style = (S.theme && S.theme.style) || {};
    bar.hidden = false;
    bar.textContent = text;
    bar.style.background = color('text_back_color') || color('back_color') || 'transparent';
    bar.style.color = color('text_color') || 'inherit';
    bar.style.fontSize = px(style.text_size, STYLE_DEFAULTS.text_size) + 'px';
    bar.style.fontFamily = fontCss(style.text_font, 'sans-serif');
  }

  /* ------------------------------ 渲染：键盘 ------------------------------ */
  function keyColorsForState(entry, hovered, on) {
    const base = {
      bg: entry.key_back_color !== undefined ? (parseColor(entry.key_back_color) || {}).css : null,
      fg: entry.key_text_color !== undefined ? (parseColor(entry.key_text_color) || {}).css : null,
      sym: entry.key_symbol_color !== undefined ? (parseColor(entry.key_symbol_color) || {}).css : null,
    };
    if (hovered) {
      return {
        bg: (entry.hilited_key_back_color !== undefined && (parseColor(entry.hilited_key_back_color) || {}).css) || color('hilited_key_back_color') || base.bg,
        fg: (entry.hilited_key_text_color !== undefined && (parseColor(entry.hilited_key_text_color) || {}).css) || color('hilited_key_text_color') || base.fg,
        sym: (entry.hilited_key_symbol_color !== undefined && (parseColor(entry.hilited_key_symbol_color) || {}).css) || color('hilited_key_symbol_color') || base.sym,
      };
    }
    if (on) {
      return {
        bg: color('on_key_back_color') || base.bg,
        fg: color('on_key_text_color') || base.fg,
        sym: base.sym,
      };
    }
    return {
      bg: base.bg || color('key_back_color'),
      fg: base.fg || color('key_text_color'),
      sym: base.sym || color('key_symbol_color'),
    };
  }

  function renderKeyboard() {
    const kbEl = el.keyboard;
    kbEl.innerHTML = '';
    if (!S.layout) return;
    const L = S.layout;
    const style = (S.theme && S.theme.style) || {};
    kbEl.style.width = S.width + 'px';
    kbEl.style.height = (L.totalHeight + L.pad[2]) + 'px';
    kbEl.style.background = color('keyboard_back_color') || 'transparent';
    kbEl.style.paddingBottom = L.pad[2] + 'px';
    kbEl.style.boxSizing = 'border-box';

    const keyTextSize = px(style.key_text_size, STYLE_DEFAULTS.key_text_size);
    const keyLongSize = px(style.key_long_text_size, STYLE_DEFAULTS.key_long_text_size);
    const symbolSize = px(style.symbol_text_size, STYLE_DEFAULTS.symbol_text_size);
    const keyFont = fontCss(style.key_font, 'sans-serif');
    const symbolFont = fontCss(style.symbol_font, 'sans-serif');
    const shadowRadius = Number(style.shadow_radius || 0);
    const shadowColor = color('shadow_color') || 'rgba(0,0,0,.5)';

    L.keys.forEach((k, idx) => {
      const entry = k.entry;
      const d = document.createElement('div');
      d.className = 'key';
      d.dataset.idx = String(idx);
      const round = entry.round_corner !== undefined ? Number(entry.round_corner) : L.round;
      d.style.left = k.left + 'px';
      d.style.top = k.y + 'px';
      d.style.width = Math.max(1, k.width) + 'px';
      d.style.height = Math.max(1, k.height) + 'px';
      d.style.borderRadius = Math.max(0, round) + 'px';
      const cols = keyColorsForState(entry, false, false);
      d.style.background = cols.bg || 'transparent';

      const label = keyLabel(entry);
      const lbl = document.createElement('span');
      lbl.className = 'lbl';
      lbl.textContent = label;
      const lsize = entry.key_text_size !== undefined && entry.key_text_size > 0 ? Number(entry.key_text_size) : (label.length > 1 ? keyLongSize : keyTextSize);
      lbl.style.fontSize = lsize + 'px';
      lbl.style.color = cols.fg || '#fff';
      lbl.style.fontFamily = keyFont;
      if (shadowRadius > 0) lbl.style.textShadow = '0 0 ' + shadowRadius + 'px ' + shadowColor;
      const offX = num(entry.key_text_offset_x, num(style.key_text_offset_x, 0));
      const offY = num(entry.key_text_offset_y, num(style.key_text_offset_y, 0));
      if (offX || offY) lbl.style.transform = 'translate(' + offX + 'px,' + offY + 'px)';
      d.appendChild(lbl);

      let symLabel = entry.label_symbol !== undefined ? String(entry.label_symbol) : '';
      if (!symLabel && entry.long_click !== undefined && entry.long_click !== '') {
        symLabel = keySymbolLabelFrom(entry.long_click);
      }
      if (S.showSymbol && symLabel) {
        const sym = document.createElement('span');
        sym.className = 'sym';
        sym.textContent = symLabel;
        const ssize = entry.symbol_text_size !== undefined && entry.symbol_text_size > 0 ? Number(entry.symbol_text_size) : symbolSize;
        sym.style.fontSize = ssize + 'px';
        sym.style.color = cols.sym || '#aaa';
        sym.style.fontFamily = symbolFont;
        const sx = num(entry.key_symbol_offset_x, num(style.key_symbol_offset_x, 0));
        const sy = num(entry.key_symbol_offset_y, num(style.key_symbol_offset_y, 0));
        sym.style.left = (6 + sx) + 'px';
        sym.style.bottom = (1 - sy) + 'px';
        d.appendChild(sym);
      }
      if (S.showHint && entry.hint !== undefined && entry.hint !== '') {
        const hint = document.createElement('span');
        hint.className = 'hint';
        hint.textContent = String(entry.hint);
        hint.style.fontSize = symbolSize + 'px';
        hint.style.color = cols.sym || '#aaa';
        hint.style.fontFamily = symbolFont;
        const hx = num(entry.key_hint_offset_x, num(style.key_hint_offset_x, 0));
        const hy = num(entry.key_hint_offset_y, num(style.key_hint_offset_y, 0));
        hint.style.transform = 'translateX(calc(-50% + ' + hx + 'px))';
        hint.style.bottom = (0 - hy) + 'px';
        d.appendChild(hint);
      }
      if (S.selectedKey === idx) d.classList.add('sel');

      d.addEventListener('mouseenter', () => {
        const hoverCols = keyColorsForState(entry, true, false);
        d.style.background = hoverCols.bg || 'transparent';
        lbl.style.color = hoverCols.fg || '#fff';
        if (S.showPreview) showKeyPreview(k, label);
      });
      d.addEventListener('mouseleave', () => {
        const normal = keyColorsForState(entry, false, false);
        d.style.background = normal.bg || 'transparent';
        lbl.style.color = normal.fg || '#fff';
        hideKeyPreview();
      });
      d.addEventListener('click', () => selectKey(idx));
      kbEl.appendChild(d);
    });
  }

  function keySymbolLabelFrom(token) {
    if (typeof token !== 'string' || !token) return '';
    if (hasBraceToken(token)) return '{}';
    const p = presetKey(token);
    if (p) {
      if (p.label !== undefined && p.label !== null && p.label !== '') return String(p.label);
      return displayLabel(p.send || p.text || token);
    }
    return displayLabel(token);
  }

  let popEl = null;
  const measureCanvas = document.createElement('canvas');
  function measureText(text, font) {
    const ctx = measureCanvas.getContext('2d');
    if (!ctx) return text.length * 20;
    ctx.font = font;
    return ctx.measureText(text).width;
  }
  function showKeyPreview(k, label) {
    hideKeyPreview();
    if (!label) return;
    const style = (S.theme && S.theme.style) || {};
    const fs = px(style.preview_text_size, 40);
    const font = fs + 'px ' + fontCss(style.preview_font, 'sans-serif');
    const h = Math.max(px(style.preview_height, 60), fs + 16);
    const pad = Math.round(Math.max(10, h * 0.28));
    const textW = Math.ceil(measureText(label, font));
    const w = Math.min(Math.max(k.width, textW + pad * 2), Math.round(S.width * 0.94));
    const off = num(style.preview_offset, 0);
    const vcorr = num(style.vertical_correction, 0);
    const pop = document.createElement('div');
    pop.className = 'key-pop';
    pop.textContent = label;
    pop.style.height = h + 'px';
    pop.style.width = w + 'px';
    // 键坐标是相对 #keyboard 的，气泡挂在 #device 下，必须加键盘偏移
    const baseX = el.keyboard.offsetLeft || 0;
    const baseY = el.keyboard.offsetTop || 0;
    const devH = el.device.clientHeight;
    // 水平：对准键中心，再夹在设备可视区内
    const left = Math.min(Math.max(0, baseX + k.left + k.width / 2 - w / 2), Math.max(0, S.width - w));
    pop.style.left = left + 'px';
    // 垂直：默认在键上方（preview_offset 为负值表示再往上）；上方放不下就落到键下方
    let top = baseY + k.y - h - off + vcorr;
    if (top < 2) top = baseY + k.y + k.height + 6;
    top = Math.min(Math.max(0, top), Math.max(0, devH - h - 2));
    pop.style.top = top + 'px';
    pop.style.background = color('preview_back_color') || 'rgba(40,44,52,.92)';
    pop.style.color = color('preview_text_color') || '#fff';
    pop.style.fontSize = fs + 'px';
    pop.style.fontFamily = fontCss(style.preview_font, 'sans-serif');
    pop.style.borderRadius = Math.min(14, Math.round(h * 0.22)) + 'px';
    pop.style.padding = '0 ' + pad + 'px';
    pop.style.boxSizing = 'border-box';
    el.device.appendChild(pop);
    popEl = pop;
  }
  function hideKeyPreview() { if (popEl && popEl.parentNode) popEl.parentNode.removeChild(popEl); popEl = null; }

  /* ------------------------------ 渲染：检查器 ------------------------------ */
  function renderColors() {
    const scheme = S.schemeObj || {};
    const fb = (S.theme && S.theme.fallback_colors) || {};
    const keys = new Set([...RENDER_COLOR_KEYS, ...Object.keys(fb), ...Object.keys(scheme)]);
    const tbody = el.colorTable;
    tbody.innerHTML = '';
    el.curScheme.textContent = S.schemeName || '（无）';
    for (const key of [...keys].sort()) {
      const r = resolveColor(key);
      const tr = document.createElement('tr');
      const sw = r.css ? '<span class="swatch" style="background:' + esc(r.css) + '"></span>' : '<span class="swatch" style="background:repeating-linear-gradient(45deg,#333,#333 3px,#222 3px,#222 6px)"></span>';
      const val = r.css || (r.image ? '图片 ' + esc(r.image) : '<span class="missing">未解析</span>');
      const from = r.from ? '<span class="hit">' + esc(r.from === key ? '直接' : r.from) + '</span>' : '<span class="dim">—</span>';
      tr.innerHTML = '<td>' + sw + '</td><td><code>' + esc(key) + '</code></td><td class="mono">' + val + '</td><td class="mono">' + from + '</td>';
      tr.title = (r.chain && r.chain.length > 1) ? 'fallback 链：' + r.chain.join(' → ') : '';
      tbody.appendChild(tr);
    }
  }

  function renderKeyboardTable() {
    const tbody = el.kbTable;
    tbody.innerHTML = '';
    const kbs = (S.theme && S.theme.preset_keyboards) || {};
    for (const name of Object.keys(kbs)) {
      const lay = layoutKeyboard(name, { silent: true });
      const tr = document.createElement('tr');
      tr.style.cursor = 'pointer';
      const nKeys = lay ? lay.keys.length : 0;
      const nRows = lay ? lay.rowsCount : 0;
      const h = lay ? Math.round(lay.totalHeight) : 0;
      tr.innerHTML = '<td><code>' + esc(name) + '</code>' + (name === S.keyboardName ? ' <span class="badge-mini">当前</span>' : '') + '</td><td>' + nKeys + '</td><td>' + nRows + '</td><td class="mono">' + h + 'px</td>';
      tr.addEventListener('click', () => {
        S.keyboardName = name;
        S.selectedKey = null;
        el.selKeyboard.value = name;
        renderAll();
      });
      tbody.appendChild(tr);
    }
  }

  function renderStyleTable() {
    const tbody = el.styleTable;
    tbody.innerHTML = '';
    const style = (S.theme && S.theme.style) || {};
    const keys = new Set([...Object.keys(style), ...Object.keys(STYLE_DEFAULTS)]);
    for (const k of [...keys].sort()) {
      const tr = document.createElement('tr');
      const own = style[k] !== undefined;
      const val = own ? JSON.stringify(style[k]) : JSON.stringify(STYLE_DEFAULTS[k]);
      tr.innerHTML = '<td><code>' + esc(k) + '</code>' + (own ? '' : ' <span class="dim">默认</span>') + '</td><td class="mono">' + esc(val) + '</td>';
      tbody.appendChild(tr);
    }
  }

  function selectKey(idx) {
    S.selectedKey = idx;
    S.sideTab = 'key';
    switchSideTab('key');
    renderKeyboard();
    renderKeyDetail(idx);
  }

  function renderKeyDetail(idx) {
    const box = el.keyDetail;
    if (!S.layout || idx === null || !S.layout.keys[idx]) {
      box.innerHTML = '<div class="empty">点击预览里的任意按键查看它的事件解析与原始 YAML。</div>';
      return;
    }
    const k = S.layout.keys[idx];
    const entry = k.entry;
    const rows = [];
    rows.push(['位置', '第 ' + (k.row + 1) + ' 行 / 第 ' + (k.column + 1) + ' 列']);
    rows.push(['尺寸', Math.round(k.width) + ' × ' + Math.round(k.height) + ' px（间距 ' + k.gap + '）']);
    rows.push(['显示标签', keyLabel(entry) || '（空）']);
    rows.push(['符号标签', entry.label_symbol !== undefined ? String(entry.label_symbol) : (entry.long_click !== undefined ? keySymbolLabelFrom(entry.long_click) : '—')]);
    rows.push(['hint', entry.hint !== undefined ? String(entry.hint) : '—']);
    rows.push(['字号', (entry.key_text_size !== undefined ? entry.key_text_size + 'px（键级）' : 'style: ' + px(st('key_text_size'), 22) + 'px / 长文本 ' + px(st('key_long_text_size'), 14) + 'px')]);
    const evs = [];
    for (const slot of EVENTS) {
      if (entry[slot] === undefined) continue;
      const p = parseEventToken(entry[slot]);
      const bits = [];
      if (p.fields.label !== undefined) bits.push('label=' + JSON.stringify(p.fields.label));
      if (p.fields.text !== undefined) bits.push('text=' + JSON.stringify(p.fields.text));
      if (p.fields.send !== undefined) bits.push('send=' + JSON.stringify(p.fields.send));
      if (p.fields.command !== undefined) bits.push('command=' + JSON.stringify(p.fields.command));
      if (p.fields.option !== undefined) bits.push('option=' + JSON.stringify(p.fields.option));
      if (p.fields.select !== undefined) bits.push('select=' + JSON.stringify(p.fields.select));
      if (p.fields.toggle !== undefined) bits.push('toggle=' + JSON.stringify(p.fields.toggle) + ' states=' + JSON.stringify(p.fields.states || []));
      if (p.fields.functional === false) bits.push('functional=false');
      evs.push('<div class="ev"><span class="k">' + esc(slot) + '</span><span>' +
        '<span class="badge-mini">' + esc(p.kind) + '</span> ' + esc(bits.join('  ') || String(entry[slot])) + '</span></div>');
    }
    box.innerHTML =
      '<h3>按键详情</h3>' +
      '<dl class="kv">' + rows.map(([a, b]) => '<dt>' + esc(a) + '</dt><dd>' + esc(b) + '</dd>').join('') + '</dl>' +
      '<h3>事件解析</h3><div class="event-list">' + (evs.join('') || '<div class="empty">这个键没有任何事件字段。</div>') + '</div>' +
      '<h3>原始 YAML</h3><pre class="yaml">' + esc(jsyaml.dump(entry, { lineWidth: 200 })) + '</pre>';
  }

  /* ------------------------------ 渲染总入口 ------------------------------ */
  function renderAll() {
    renderIssues();
    if (!S.theme) {
      el.candidateBar.innerHTML = '';
      el.keyboard.innerHTML = '';
      el.stageHint.hidden = false;
      el.stageHint.textContent = '主题解析失败，见右侧「问题」面板。';
      el.colorTable.innerHTML = '';
      el.kbTable.innerHTML = '';
      el.styleTable.innerHTML = '';
      return;
    }
    el.stageHint.hidden = true;
    el.themeMeta.textContent = (S.theme.name || '未命名') + ' · v' + (S.theme.config_version || '?') +
      (S.fileName ? ' · ' + S.fileName : '');
    // 只展示键盘本体（不要 App 内容区）
    el.device.style.width = S.width + 'px';
    el.device.style.height = 'auto';
    S.candidates = parseCandidates();
    renderComposition();
    renderCandidates();
    renderKeyboard();
    renderColors();
    renderKeyboardTable();
    renderStyleTable();
    if (S.selectedKey !== null) renderKeyDetail(S.selectedKey);
    applyZoom();
  }

  function applyZoom() {
    const z = S.zoom;
    el.device.style.transform = 'scale(' + z + ')';
    el.device.style.marginBottom = (el.device.offsetHeight * (z - 1)) + 'px';
  }

  function setStatus(text, cls) {
    el.status.textContent = text;
    el.status.className = 'status' + (cls ? ' ' + cls : '');
  }

  /* ------------------------------ 下拉框同步 ------------------------------ */
  function syncSelects() {
    const theme = S.theme || {};
    const schemes = Object.keys(theme.preset_color_schemes || {});
    const fill = (sel, items, cur) => {
      sel.innerHTML = '';
      for (const it of items) {
        const o = document.createElement('option');
        o.value = it; o.textContent = it;
        sel.appendChild(o);
      }
      if (items.includes(cur)) sel.value = cur;
      sel.disabled = items.length === 0;
    };
    fill(el.selScheme, schemes, S.schemeName);
    fill(el.selKeyboard, Object.keys(theme.preset_keyboards || {}), S.keyboardName);
  }

  /* ------------------------------ 侧栏 tab ------------------------------ */
  function switchSideTab(name) {
    S.sideTab = name;
    document.querySelectorAll('.side-tabs .tab').forEach((t) => t.classList.toggle('active', t.dataset.stab === name));
    document.querySelectorAll('.side-tab').forEach((p) => p.classList.toggle('active', p.dataset.panel === name));
  }

  /* ------------------------------ 文件载入 ------------------------------ */
  async function loadPreset(name) {
    setStatus('载入 ' + name + '…');
    try {
      const res = await fetch('../../app/src/main/assets/rime/' + name, { cache: 'no-cache' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const text = await res.text();
      S.fileName = name;
      S.editorText = text;
      S.baseText = text;
      S.baseTheme = resolveIncludes(parseYaml(text, '基底').doc || {}, {});
      el.editor.value = text;
      S.patchMode = el.patchMode.checked;
      rebuildTheme();
      syncSelects();
      updateCursorInfo();
    } catch (e) {
      setStatus('载入失败：' + e.message + '（请用仓库根目录的 python3 -m http.server 打开）', 'err');
      el.stageHint.hidden = false;
      el.stageHint.textContent = '无法通过 fetch 读取仓库里的 trime.yaml；请点「打开 YAML…」选择本地文件。';
    }
  }

  function loadText(text, name) {
    S.fileName = name || '';
    S.editorText = text;
    S.baseText = text;
    el.editor.value = text;
    const parsed = parseYaml(text, '基底');
    S.baseTheme = parsed.doc ? resolveIncludes(parsed.doc, parsed.doc) : null;
    rebuildTheme();
    syncSelects();
    updateCursorInfo();
  }

  /* ------------------------------ 事件绑定 ------------------------------ */
  function updateCursorInfo() {
    const ta = el.editor;
    const upto = ta.value.slice(0, ta.selectionStart);
    const lines = upto.split('\n');
    el.cursorInfo.textContent = '行 ' + lines.length + '，列 ' + (lines[lines.length - 1].length + 1);
  }

  let debounceTimer = null;
  function scheduleRebuild() {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      rebuildTheme();
      syncSelects();
    }, 250);
  }

  function bind() {
    el.editor.addEventListener('input', () => { updateCursorInfo(); scheduleRebuild(); });
    el.editor.addEventListener('click', updateCursorInfo);
    el.editor.addEventListener('keyup', updateCursorInfo);
    el.editor.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { clearTimeout(debounceTimer); rebuildTheme(); syncSelects(); }
      if (e.key === 'Tab') {
        e.preventDefault();
        const ta = el.editor;
        const start = ta.selectionStart, end = ta.selectionEnd;
        ta.value = ta.value.slice(0, start) + '  ' + ta.value.slice(end);
        ta.selectionStart = ta.selectionEnd = start + 2;
      }
    });

    $('btnOpen').addEventListener('click', () => el.fileInput.click());
    el.fileInput.addEventListener('change', () => {
      const f = el.fileInput.files && el.fileInput.files[0];
      if (!f) return;
      const r = new FileReader();
      r.onload = () => loadText(String(r.result), f.name);
      r.readAsText(f, 'utf-8');
    });
    $('btnReset').addEventListener('click', () => {
      if (!S.baseText) return;
      el.editor.value = S.baseText;
      rebuildTheme();
      syncSelects();
      setStatus('已还原到原始文件', 'ok');
    });
    document.querySelectorAll('.preset').forEach((b) => b.addEventListener('click', () => loadPreset(b.dataset.preset)));
    el.patchMode.addEventListener('change', () => { S.patchMode = el.patchMode.checked; rebuildTheme(); syncSelects(); });

    el.selScheme.addEventListener('change', () => { S.schemeName = el.selScheme.value; selectScheme(S.theme); renderAll(); });
    el.selKeyboard.addEventListener('change', () => {
      S.keyboardName = el.selKeyboard.value;
      S.selectedKey = null;
      S.layout = layoutKeyboard(S.keyboardName);
      renderAll();
    });
    el.selOrientation.addEventListener('change', () => {
      S.land = el.selOrientation.value === 'landscape';
      S.layout = layoutKeyboard(S.keyboardName);
      renderAll();
    });
    el.inpWidth.addEventListener('input', () => {
      S.width = clamp(Number(el.inpWidth.value) || 360, 240, 1200);
      S.layout = layoutKeyboard(S.keyboardName);
      renderAll();
    });
    el.inpZoom.addEventListener('input', () => { S.zoom = Number(el.inpZoom.value) / 100; applyZoom(); });
    el.chkAscii.addEventListener('change', () => { S.ascii = el.chkAscii.checked; renderAll(); });
    el.chkDark.addEventListener('change', () => {
      S.dark = el.chkDark.checked;
      if (S.theme) selectScheme(S.theme);
      renderAll();
    });
    el.chkSymbol.addEventListener('change', () => { S.showSymbol = el.chkSymbol.checked; renderKeyboard(); });
    el.chkHint.addEventListener('change', () => { S.showHint = el.chkHint.checked; renderKeyboard(); });
    el.chkPreview.addEventListener('change', () => { S.showPreview = el.chkPreview.checked; hideKeyPreview(); });
    el.chkCandidates.addEventListener('change', () => { S.showCandidates = el.chkCandidates.checked; renderComposition(); renderCandidates(); });
    el.chkCandStruct.addEventListener('change', () => { S.showCandStruct = el.chkCandStruct.checked; renderCandidates(); });
    el.inpSchema.addEventListener('input', () => { S.schemaName = el.inpSchema.value; renderKeyboard(); });
    el.inpComposition.addEventListener('input', renderComposition);
    el.inpCandidates.addEventListener('input', () => { S.candidates = parseCandidates(); renderCandidates(); });

    document.querySelectorAll('.side-tabs .tab').forEach((t) => t.addEventListener('click', () => switchSideTab(t.dataset.stab)));

    // 拖拽 YAML 到页面
    document.addEventListener('dragover', (e) => { e.preventDefault(); });
    document.addEventListener('drop', (e) => {
      e.preventDefault();
      const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
      if (!f) return;
      const r = new FileReader();
      r.onload = () => loadText(String(r.result), f.name);
      r.readAsText(f, 'utf-8');
    });
  }

  /* ------------------------------ 启动 ------------------------------ */
  async function init() {
    bind();
    S.candidates = parseCandidates();
    switchSideTab('issues');
    await loadPreset('trime.yaml');
  }

  init();
})();
