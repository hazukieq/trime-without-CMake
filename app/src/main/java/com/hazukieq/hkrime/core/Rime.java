/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hazukieq.hkrime.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hazukieq.hkrime.data.AppPrefs;
import com.hazukieq.hkrime.data.DataManager;
import com.hazukieq.hkrime.data.opencc.OpenCCDictManager;
import com.hazukieq.hkrime.ime.core.Trime;
import java.io.BufferedReader;
import java.io.CharArrayWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import timber.log.Timber;

/**
 * Rime與OpenCC的Java實現
 *
 * @see <a href="https://github.com/rime/librime">Rime</a> <a
 *     href="https://github.com/BYVoid/OpenCC">OpenCC</a>
 */
public class Rime {
  /** Rime編碼區 */
  public static class RimeComposition {
    int length;
    int cursor_pos;
    int sel_start;
    int sel_end;
    String preedit;
    byte[] bytes;

    public String getText() {
      if (length == 0) return "";
      bytes = preedit.getBytes();
      return preedit;
    }

    public int getLength() { return length; }
    public int getCursorPos() { return cursor_pos; }
    public int getSelStart() { return sel_start; }
    public int getSelEnd() { return sel_end; }

    public int getStart() {
      if (length == 0) return 0;
      byte[] b = preedit == null ? bytes : preedit.getBytes();
      return new String(b, 0, safeUtf8Length(b, sel_start)).length();
    }

    public int getEnd() {
      if (length == 0) return 0;
      byte[] b = preedit == null ? bytes : preedit.getBytes();
      return new String(b, 0, safeUtf8Length(b, sel_end)).length();
    }

    /** 把字節偏移夾到 [0, len] 且不回退到 UTF-8 多字節字符中間。 */
    private static int safeUtf8Length(byte[] b, int offset) {
      if (b == null) return 0;
      int n = Math.max(0, Math.min(offset, b.length));
      while (n > 0 && n < b.length && (b[n] & 0xC0) == 0x80) n--;
      return n;
    }

    /**
     * preedit 被 12 鍵方案改寫後，librime 原始的 length/cursor_pos/sel_* 是舊 preedit 的字節偏移，
     * 必須重新按新文本歸一化，否則 {@link #getStart()}/{@link #getEnd()} 會越界。
     */
    void normalizeAfterRewrite() {
      if (preedit == null) return;
      bytes = preedit.getBytes();
      length = bytes.length;
      cursor_pos = Math.max(0, Math.min(cursor_pos, bytes.length));
      sel_start = Math.max(0, Math.min(sel_start, bytes.length));
      sel_end = Math.max(sel_start, Math.min(sel_end, bytes.length));
    }
  }

  /** Rime候選項 */
  public static class RimeCandidate {
    public String text;
    public String comment;
  }

  /** Rime候選區，包含多個{@link RimeCandidate 候選項} */
  public static class RimeMenu {
    int page_size;
    int page_no;
    boolean is_last_page;
    int highlighted_candidate_index;
    int num_candidates;
    RimeCandidate[] candidates;
    String select_keys;
  }

  /** Rime上屏的字符串 */
  public static class RimeCommit {
    int data_size;
    // v0.9
    String text;
  }

  /** Rime環境，包括 {@link RimeComposition 編碼區} 、{@link RimeMenu 候選區} */
  public static class RimeContext {
    int data_size;
    // v0.9
    RimeComposition composition;
    RimeMenu menu;
    // v0.9.2
    String commit_text_preview;
    String[] select_labels;

    public int size() {
      if (menu == null) return 0;
      return menu.num_candidates;
    }

    public RimeCandidate[] getCandidates() {
      Timber.d("setWindow getCandidates() size()=%s", size());
      return size() == 0 ? null : menu.candidates;
    }
  }

  /** Rime狀態 */
  public static class RimeStatus {
    int data_size;
    // v0.9
    String schema_id;
    String schema_name;
    boolean is_disabled;
    boolean is_composing;
    boolean is_ascii_mode;
    boolean is_full_shape;
    boolean is_simplified;
    boolean is_traditional;
    boolean is_ascii_punct;
  }

  /** Rime方案 */
  public static class RimeSchema {
    private final String kRadioSelected = " ✓";

    Map<String, Object> schema = new HashMap<String, Object>();
    List<Map<String, Object>> switches = new ArrayList<Map<String, Object>>();

    public RimeSchema(String schema_id) {
      Timber.d("RimeSchema() start");
      Object o;
      o = schema_get_value(schema_id, "schema");
      if (o == null || !(o instanceof Map)) return;
      Timber.d("RimeSchema() switch");
      schema = (Map<String, Object>) o;
      o = schema_get_value(schema_id, "switches");
      if (o == null || !(o instanceof List)) return;
      switches = (List<Map<String, Object>>) o;
      check(); // 檢查不在選單中顯示的選項
      Timber.d("RimeSchema() menu");
      o = schema_get_value(schema_id, "menu");
      if (o == null || !(o instanceof HashMap)) return;
      Timber.d("RimeSchema() menu.page_size=" + ((Map<Object, Object>) o).get("page_size"));
    }

    public void check() {
      if (switches.isEmpty()) return;
      for (Iterator<?> it = switches.iterator(); it.hasNext(); ) {
        Map<?, ?> o = (Map<?, ?>) it.next();
        if (!o.containsKey("states")) it.remove();
      }
    }

    public RimeCandidate[] getCandidates() {
      if (switches.isEmpty()) return null;
      RimeCandidate[] candidates = new RimeCandidate[switches.size()];
      int i = 0;
      for (Map<String, Object> o : switches) {
        candidates[i] = new RimeCandidate();
        final List<?> states = (List<?>) o.get("states");
        Integer value = (Integer) o.get("value");
        if (value == null) value = 0;
        candidates[i].text = states.get(value).toString();

        String kRightArrow = "→ ";
        if (showSwitchArrow)
          candidates[i].comment =
              o.containsKey("options") ? "" : kRightArrow + states.get(1 - value).toString();
        else
          candidates[i].comment = o.containsKey("options") ? "" : states.get(1 - value).toString();
        i++;
      }
      return candidates;
    }

    /** 開關的易懂名稱：librime 的 option name 對普通用戶沒意義。 */
    private static String switchTitle(String name, List<?> states) {
      switch (name) {
        case "ywz":
          return "颜文字/表情";
        case "lenth":
          return "联想短语";
        case "simplification":
          return "简繁转换";
        case "ascii_mode":
          return "中英文";
        case "half_shape":
        case "full_shape":
          return "全半角";
        default:
          if (states != null && states.size() >= 2) {
            String a = states.get(0).toString();
            String b = states.get(1).toString();
            // 像 [关, 情] 這種：用後一個狀態當名稱（情 / 联）
            if (("关".equals(a) || "關".equals(a)) && !"开".equals(b) && !"開".equals(b)) {
              return b;
            }
          }
          return name;
      }
    }

    /**
     * 方案開關對話框的行標籤：`✓ 颜文字/表情：当前 关（点按切到 情）`。
     *
     * 舊版是「關 → 情」，用戶完全看不出哪邊是開哪邊是關（2026-11-09 反饋）。
     */
    public String[] getSwitchDialogLabels() {
      if (switches.isEmpty()) return new String[0];
      String[] out = new String[switches.size()];
      for (int i = 0; i < switches.size(); i++) {
        Map<String, Object> o = switches.get(i);
        List<?> states = (List<?>) o.get("states");
        String name = String.valueOf(o.get("name"));
        String title = switchTitle(name, states);
        Integer value = (Integer) o.get("value");
        if (value == null) value = 0;
        if (states == null || states.size() < 2) {
          out[i] = title;
          continue;
        }
        int cur = Math.floorMod(value, states.size());
        int next = (cur + 1) % states.size();
        String curLabel = states.get(cur).toString().trim();
        String nextLabel = states.get(next).toString().trim();
        boolean on = value != 0;
        out[i] =
            (on ? "✓ " : "○ ")
                + title
                + "：当前 "
                + curLabel
                + "（点按切到 "
                + nextLabel
                + "）";
      }
      return out;
    }

    /** 方案開關的「當前 → 下一個」標籤，供 ☰ 菜單列表使用。 */
    public String[] getSwitchLabels() {
      if (switches.isEmpty()) return new String[0];
      String[] out = new String[switches.size()];
      for (int i = 0; i < switches.size(); i++) {
        Map<String, Object> o = switches.get(i);
        List<?> states = (List<?>) o.get("states");
        if (states == null || states.isEmpty()) {
          out[i] = "";
          continue;
        }
        Integer value = (Integer) o.get("value");
        if (value == null) value = 0;
        String cur = states.get(value % states.size()).toString().trim();
        String next = states.get((value + 1) % states.size()).toString().trim();
        out[i] = cur + "  →  " + next;
      }
      return out;
    }

    public void getValue() {
      if (switches.isEmpty()) return; // 無方案
      for (int j = 0; j < switches.size(); j++) {
        final Map<String, Object> o = switches.get(j);
        if (o.containsKey("options")) {
          List<?> options = (List<?>) o.get("options");
          for (int i = 0; i < options.size(); i++) {
            final String s = (String) options.get(i);
            if (Rime.get_option(s)) {
              o.put("value", i);
              break;
            }
          }
        } else {
          o.put("value", Rime.get_option(o.get("name").toString()) ? 1 : 0);
        }
        switches.set(j, o);
      }
    }

    public void toggleOption(int i) {
      if (switches.isEmpty()) return;
      Map<String, Object> o = switches.get(i);
      Integer value = (Integer) o.get("value");
      if (value == null) value = 0;
      if (o.containsKey("options")) {
        List<String> options = (List<String>) o.get("options");
        Rime.setOption(options.get(value), false);
        value = (value + 1) % options.size();
        Rime.setOption(options.get(value), true);
      } else {
        value = 1 - value;
        Rime.setOption(o.get("name").toString(), value == 1);
      }
      o.put("value", value);
      switches.set(i, o);
    }
  }

  private static Rime self;

  /**
   * 主線程啟動時不在主線程 join 維護：大方案（霧凇拼音 60MB table、melt_eng 8.5MB prism…）
   * 部署要 1~2 分鐘，主線程 join 會讓 splash/界面整段白屏假死（2026-11-09 用戶反饋）。
   * 這裡改成：維護丟到背景線程，init 先不建 session；維護完成後在主線程補建 session、
   * 重讀方案並讓輸入法重綁鍵盤。
   */
  private static volatile boolean maintenancePending = false;
  private static Context appContext;

  private static final RimeCommit mCommit = new RimeCommit();
  private static final RimeContext mContext = new RimeContext();
  /**
   * librime session 非線程安全：後台枚舉、UI 按鍵、release 事件都必須串行。
   * Kotlin 側 TwelveKeyController.ENGINE_LOCK 直接引用這一顆鎖。
   */
  public static final Object ENGINE_LOCK = new Object();

  private static final RimeStatus mStatus = new RimeStatus();
  private static RimeSchema mSchema;

  /**
   * 引擎是否可用于 UI 交互。
   *
   * 維護被延後（{@code maintenancePending}）時 init() 直接 return，session/schema 都還沒建；
   * 這期間輸入法 UI 已經顯示、用戶可能已經在敲鍵，native 調用會拿到 null schema。
   * 所有入口先看這個，未就緒就當作「無輸入」丟棄，等後台維護完成 initSchema() 後自然恢復。
   */
  private static boolean isEngineReady() {
    return mSchema != null && mSchemaList != null;
  }
  private static List<?> mSchemaList;
  private static boolean mOnMessage;

  static {
    System.loadLibrary("rime_jni");
  }

  /*
  Android SDK包含了如下6个修饰键的状态，其中function键会被trime消费掉，因此只处理5个键
  Android和librime对按键命名并不一致。读取可能有误。librime按键命名见如下链接，
  https://github.com/rime/librime/blob/master/src/rime/key_table.cc
   */
  public static int META_SHIFT_ON = get_modifier_by_name("Shift");
  public static int META_CTRL_ON = get_modifier_by_name("Control");
  public static int META_ALT_ON = get_modifier_by_name("Alt");
  public static int META_SYM_ON = get_modifier_by_name("Super");
  public static int META_META_ON = get_modifier_by_name("Meta");

  public static int META_RELEASE_ON = get_modifier_by_name("Release");
  private static boolean showSwitches = true;
  private static boolean showSwitchArrow = false;

  public static void setShowSwitches(boolean show) {
    showSwitches = show;
  }

  public static void setShowSwitchArrow(boolean show) {
    showSwitchArrow = show;
  }

  public static boolean hasMenu() {
    return isComposing() && mContext.menu.num_candidates != 0;
  }

  public static boolean hasLeft() {
    return hasMenu() && mContext.menu.page_no != 0;
  }

  public static boolean hasRight() {
    return hasMenu() && !mContext.menu.is_last_page;
  }

  public static boolean isPaging() {
    return hasLeft();
  }

  public static boolean isComposing() {
    return mStatus.is_composing;
  }

  public static boolean isAsciiMode() {
    return mStatus.is_ascii_mode;
  }

  public static RimeComposition getComposition() {
    if (mContext == null) return null;
    RimeComposition composition = mContext.composition;
    // 12 鍵方案的 preedit 是「鍵碼」，schema 的 preedit_format 反推拼音不可靠
    // （一個碼對應多個字母），這裡改用字典反查音節，切不出來就維持原樣。
    if (composition != null
        && composition.preedit != null
        && "hakka_jiugong".equals(getSchemaId())) {
      String hint = null;
      RimeCandidate[] cs = mContext.getCandidates();
      if (cs != null && cs.length > 0) {
        hint = cs[0].text;
      }
      // 保留 librime preedit 裡「已選中的前綴」（如 `咧5e` 的 `咧`），只把尾部碼段轉拼音；
      // 否則選完一段後前綴被整串替換掉，用戶看到「漢字消失了」。
      String decoded = TwelveKeyPreedit.INSTANCE.rewrite(composition.preedit, hint);
      Timber.d("dbgpre: raw=[%s] hint=[%s] decoded=[%s]", composition.preedit, hint, decoded);
      if (decoded != null) {
        composition.preedit = decoded;
        composition.normalizeAfterRewrite();
      }
    }
    return composition;
  }

  /**
   * 12 鍵：librime preedit 裡「已選中的前綴」（如 `咧le` 的 `咧`）。
   *
   * 12 鍵多音節輸入時，選中一段的字不會立刻 commit，而是留在 librime 的 context 裡，
   * preedit 呈 `已選字 + 剩餘碼`。嵌入式預覽要把這段前綴也顯示出來，否則選中的字會「消失」。
   */
  public static String getCompositionPrefix() {
    if (mContext == null || mContext.composition == null) return "";
    String preedit = mContext.composition.preedit;
    if (preedit == null || preedit.isEmpty()) return "";
    int i = preedit.length();
    while (i > 0 && preedit.charAt(i - 1) < 128) i--;
    return preedit.substring(0, i);
  }

  public static String getCompositionText() {
    RimeComposition composition = getComposition();
    return (composition == null || composition.preedit == null) ? "" : composition.preedit;
  }

  public static String getComposingText() {
    if (mContext.commit_text_preview == null) return "";
    return mContext.commit_text_preview;
  }

  public Rime(Context context, boolean full_check) {
    appContext = context == null ? null : context.getApplicationContext();
    init(full_check);
    self = this;
  }

  private static void initSchema() {
    mSchemaList = get_schema_list();
    String schema_id = getSchemaId();
    Timber.d("initSchema() RimeSchema");
    mSchema = new RimeSchema(schema_id);
    Timber.d("initSchema() getStatus");
    getStatus();
    Timber.d("initSchema() done");
  }

  @SuppressWarnings("UnusedReturnValue")
  private static boolean getStatus() {
    if (!isEngineReady()) return false;
    mSchema.getValue();
    return get_status(mStatus);
  }

  private static void init(boolean full_check) {
    String methodName =
        "\t<TrimeInit>\t" + Thread.currentThread().getStackTrace()[2].getMethodName() + "\t";
    Timber.d(methodName);
    mOnMessage = false;
    final AppPrefs appPrefs = AppPrefs.defaultInstance();
    final String sharedDataDir = appPrefs.getConf().getSharedDataDir();
    final String userDataDir = appPrefs.getConf().getUserDataDir();

    Timber.d(methodName + "setup");
    // Initialize librime APIs
    setup(sharedDataDir, userDataDir);
    Timber.d(methodName + "initlialize");
    initialize(sharedDataDir, userDataDir);

    Timber.d(methodName + "check");
    check(full_check);
    Timber.d(methodName + "set_notification_handler");
    set_notification_handler();
    if (maintenancePending) {
      // 維護在背景跑：先不建 session，避免 librime 在維護模式下建 session 失敗。
      // UI 側對「無方案」的調用都做了 null/空值的保護（getSchemaId 返回 .default 等）。
      mSchemaList = java.util.Collections.emptyList();
      Timber.w("Rime.init: maintenance pending, deferred session creation");
      return;
    }
    if (!find_session()) {
      if (create_session() == 0) {
        Timber.wtf("Error creating rime session");
        return;
      }
    }
    Timber.d(methodName + "initSchema");
    initSchema();

    Timber.d(methodName + "finish");
  }

  /** 背景執行維護，完成後回主線程補建 session / 重讀方案 / 讓輸入法重綁鍵盤。 */
  private static void runMaintenanceInBackground() {
    final long t0 = System.nanoTime();
    try {
      join_maintenance_thread();
      Timber.i("Rime: background maintenance finished in %dms",
          (System.nanoTime() - t0) / 1_000_000);
    } catch (Throwable e) {
      Timber.e(e, "Rime: background maintenance failed");
    }
    final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    handler.post(() -> {
      synchronized (ENGINE_LOCK) {
        try {
          if (self == null) return;
          if (!find_session() && create_session() == 0) {
            Timber.wtf("Rime: create session failed after maintenance");
            return;
          }
          initSchema();
          maintenancePending = false;
          Timber.i("Rime: re-initialized after background maintenance (schema=%s)", getSchemaId());
        } catch (Throwable e) {
          Timber.e(e, "Rime: re-init after maintenance failed");
          return;
        }
      }
      final Trime trime = Trime.getServiceOrNull();
      if (trime != null) trime.initKeyboard();
    });
  }

  public static void destroy() {
    destroy_session();
    finalize1();
    self = null;
  }

  public static String getCommitText() {
    return mCommit.text;
  }

  public static boolean getCommit() {
    return get_commit(mCommit);
  }

  public static void getContexts() {
    // librime session 非線程安全：所有讀寫都要串到同一顆鎖上，否則「後台枚舉候選」
    // 與「UI 線程 getContexts」並發會 SIGSEGV（RimeGetContext 空指針，2026-11-08 真機 crash）。
    synchronized (ENGINE_LOCK) {
      Timber.i("\t<TrimeInput>\tgetContexts() get_context");
      // get_context() 是耗时操作
      get_context(mContext);
      Timber.i("\t<TrimeInput>\tgetContexts() getStatus");
      getStatus();
      Timber.i("\t<TrimeInput>\tgetContexts() finish");
    }
  }

  public static boolean isVoidKeycode(int keycode) {
    int XK_VoidSymbol = 0xffffff;
    return keycode <= 0 || keycode == XK_VoidSymbol;
  }

  // KeyProcess 调用JNI方法发送keycode和mask
  private static boolean onKey(int keycode, int mask) {
    synchronized (ENGINE_LOCK) {
      if (!isEngineReady()) return false; // 延後維護期間丟棄按鍵
      Timber.i("\t<TrimeInput>\tonkey()\tkeycode=%s, mask=%s", keycode, mask);
      if (isVoidKeycode(keycode)) return false;
      // 此处调用native方法是耗时操作
      final boolean b = process_key(keycode, mask);
      Timber.i(
          "\t<TrimeInput>\tonkey()\tkeycode=%s, mask=%s, process_key result=%s",
          keycode, mask, b);
      getContexts();
      Timber.i("\t<TrimeInput>\tonkey()\tfinish");
      return b;
    }
  }

  // KeyProcess 调用JNI方法发送keycode和mask
  public static boolean onKey(int[] event) {
    if (event != null && event.length == 2) return onKey(event[0], event[1]);
    return false;
  }

  public static boolean isValidText(CharSequence text) {
    if (text == null || text.length() == 0) return false;
    int ch = text.toString().codePointAt(0);
    return ch >= 0x20 && ch < 0x80;
  }

  public static boolean onText(CharSequence text) {
    if (!isEngineReady()) return false; // 延後維護期間丟棄
    if (!isValidText(text)) return false;
    boolean b = simulate_key_sequence(text.toString().replace("{}", "{braceleft}{braceright}"));
    Timber.i("simulate key sequence = %s, input = %s", b, text);
    getContexts();
    return b;
  }

  /**
   * 候選條數據源。方案開關不再渲染到候選區（改由候選條左側 ☰ 菜單提供），
   * 因此這裡永遠返回真正的候選；未組字時為空，候選條顯示 ☰ / 配色 / ⌄。
   */
  public static RimeCandidate[] getCandidates() {
    return mContext.getCandidates();
  }

  public static RimeCandidate[] getCandidatesWithoutSwitch() {
    if (isComposing()) return mContext.getCandidates();
    return null;
  }

  public static String[] getSelectLabels() {
    if (mContext != null && mContext.size() > 0) {
      if (mContext.select_labels != null) return mContext.select_labels;
      if (mContext.menu.select_keys != null) return mContext.menu.select_keys.split("\\B");
      int n = mContext.size();
      String[] labels = new String[n];
      for (int i = 0; i < n; i++) {
        labels[i] = String.valueOf((i + 1) % 10);
      }
      return labels;
    }
    return null;
  }

  public static int getCandHighlightIndex() {
    return isComposing() ? mContext.menu.highlighted_candidate_index : -1;
  }

  public static boolean commitComposition() {
    boolean b = commit_composition();
    getContexts();
    return b;
  }

  public static void clearComposition() {
    synchronized (ENGINE_LOCK) {
      clear_composition();
      getContexts();
    }
  }

  public static boolean selectCandidate(int index) {
    synchronized (ENGINE_LOCK) {
      boolean b = select_candidate_on_current_page(index);
      getContexts();
      return b;
    }
  }

  public static boolean deleteCandidate(int index) {
    boolean b = delete_candidate_on_current_page(index);
    getContexts();
    return b;
  }

  public static void setOption(String option, boolean value) {
    if (mOnMessage) return;
    set_option(option, value);
  }

  public static boolean getOption(String option) {
    return get_option(option);
  }

  public static void toggleOption(String option) {
    boolean b = getOption(option);
    setOption(option, !b);
  }

  public static void toggleOption(int i) {
    if (mSchema == null) return; // 延後維護期間沒有 schema
    mSchema.toggleOption(i);
  }

  /** ☰ 菜單：方案開關的「當前 → 下一個」列表；沒有開關時返回空數組。 */
  public static String[] getSchemaSwitchLabels() {
    return mSchema == null ? new String[0] : mSchema.getSwitchLabels();
  }

  /** 方案開關對話框的行標籤（明確顯示「當前/切到」，見 Schema#getSwitchDialogLabels）。 */
  public static String[] getSchemaSwitchDialogLabels() {
    return mSchema == null ? new String[0] : mSchema.getSwitchDialogLabels();
  }

  public static void setProperty(String prop, String value) {
    if (mOnMessage) return;
    set_property(prop, value);
  }

  public static String getProperty(String prop) {
    return get_property(prop);
  }

  public static String getSchemaId() {
    final String schema = get_current_schema();
    // 維護被延後時還沒有 session，native 返回 null：統一成 .default（= 無方案），
    // 讓 isEmpty()/UI 走「無方案」分支而不是 NPE。
    return schema == null ? ".default" : schema;
  }

  private static boolean isEmpty(@NonNull String s) {
    return s.contentEquals(".default"); // 無方案
  }

  public static boolean isEmpty() {
    return isEmpty(getSchemaId());
  }

  public static String[] getSchemaNames() {
    if (mSchemaList == null) return new String[0];
    int n = mSchemaList.size();
    String[] names = new String[n];
    int i = 0;
    for (Object o : mSchemaList) {
      Map<?, ?> m = (Map<?, ?>) o;
      names[i++] = (String) m.get("name");
    }
    return names;
  }

  public static int getSchemaIndex() {
    if (mSchemaList == null) return 0;
    String schema_id = getSchemaId();
    int i = 0;
    for (Object o : mSchemaList) {
      Map<?, ?> m = (Map<?, ?>) o;
      if (m.get("schema_id").toString().contentEquals(schema_id)) return i;
      i++;
    }
    return 0;
  }

  public static String getSchemaName() {
    return mStatus.schema_name;
  }

  private static boolean selectSchema(String schema_id) {
    Timber.d("selectSchema() schema_id=" + schema_id);
    overWriteSchema(schema_id);
    boolean b = select_schema(schema_id);
    getContexts();
    return b;
  }

  // 刷新当前输入方案
  public static void applySchemaChange() {
    String schema_id = getSchemaId();
    // 实测直接select_schema(schema_id)方案没有重新载入，切换到不存在的方案，再切回去（会产生1秒的额外耗时）.需要找到更好的方法
    // 不发生覆盖则不生效
    if (overWriteSchema(schema_id)) {
      select_schema("nill");
      select_schema(schema_id);
    }
    getContexts();
  }
  // 临时修改scheme文件参数
  // 临时修改build后的scheme可以避免build过程的耗时
  // 另外实际上jni读入yaml、修改、导出的效率并不高
  private static boolean overWriteSchema(String schema_id) {
    Map<String, String> map = new HashMap<>();
    String page_size = "0";
    Timber.d("overWriteSchema() page_size=" + page_size);
    if (!page_size.equals("0")) {
      map.put("page_size", page_size);
    }
    if (map.isEmpty()) return false;
    return overWriteSchema(schema_id, map);
  }

  private static boolean overWriteSchema(String schema_id, Map<String, String> map) {
    if (schema_id == null) schema_id = getSchemaId();
    File file =
        new File(Rime.get_user_data_dir() + File.separator + "build", schema_id + ".schema.yaml");
    try {
      FileReader in = new FileReader(file);
      BufferedReader bufIn = new BufferedReader(in);
      CharArrayWriter tempStream = new CharArrayWriter();
      String line = null;
      read:
      while ((line = bufIn.readLine()) != null) {
        for (String k : map.keySet()) {
          String key = k + ": ";
          if (line.contains(key)) {
            String value = ": " + map.get(k) + System.getProperty("line.separator");
            tempStream.write(line.replaceFirst(":.+", value));
            map.remove(k);
            continue read;
          }
        }
        tempStream.write(line);
        tempStream.append(System.getProperty("line.separator"));
      }
      bufIn.close();
      FileWriter out = new FileWriter(file);
      tempStream.writeTo(out);
      out.close();
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
    return map.isEmpty();
  }

  public static boolean selectSchema(int id) {
    int n = mSchemaList.size();
    if (id < 0 || id >= n) return false;
    final String schema_id = getSchemaId();
    Map<String, String> m = (Map<String, String>) mSchemaList.get(id);
    final String target = m.get("schema_id");
    if (target.contentEquals(schema_id)) return false;
    return selectSchema(target);
  }

  public static Rime get(Context context, boolean full_check) {
    if (self == null) {
      if (full_check) {
        OpenCCDictManager.internalDeploy();
      }
      self = new Rime(context, full_check);
    }
    return self;
  }

  public static Rime get(Context context) {
    return get(context, false);
  }

  public static String RimeGetInput() {
    synchronized (ENGINE_LOCK) {
      String s = get_input();
      return s == null ? "" : s;
    }
  }

  /**
   * 取 librime Composition 的分段（比 caret 猜測可靠）。
   *
   * @return 每個 segment 三個 int：[start, end, status]；start/end 是 input 的字節偏移，
   * status 0..3 = void/guess/selected/confirmed
   */
  public static int[] getRimeCompositionSegments() {
    synchronized (ENGINE_LOCK) {
      int[] arr = getCompositionSegments();
      return arr == null ? new int[0] : arr;
    }
  }

  /**
   * 全量候選枚舉（不受 menu/page_size 限制，走 RimeCandidateListBegin/Next/End）。
   *
   * @param maxCount 上限（建議 600；單鍵最多可達 2000+ 條）
   * @return 扁平數組 [text0, comment0, text1, comment1, ...]；未組字或無候選返回空數組。
   *     下標 i 即 {@link #select_candidate(int)} 使用的全量下標（與本數組順序一致）。
   */
  public static String[] enumerateCandidates(int maxCount) {
    String[] arr = enumerate_candidates(maxCount);
    return arr == null ? new String[0] : arr;
  }

  /** 同上，解析爲 (text, comment) 對；comment 可能爲空串。 */
  public static List<String[]> candidatesSnapshot(int maxCount) {
    String[] flat = enumerateCandidates(maxCount);
    List<String[]> out = new ArrayList<>(flat.length / 2);
    for (int i = 0; i + 1 < flat.length; i += 2) {
      out.add(new String[] {flat[i], flat[i + 1]});
    }
    return out;
  }

  public static int RimeGetCaretPos() {
    synchronized (ENGINE_LOCK) {
      return get_caret_pos();
    }
  }

  public static void RimeSetCaretPos(int caret_pos) {
    set_caret_pos(caret_pos);
    getContexts();
  }

  public static void handleRimeNotification(String message_type, String message_value) {
    mOnMessage = true;
    final RimeEvent event = RimeEvent.create(message_type, message_value);
    // Timber.i("message: [%s] %s", message_type, message_value);
    Timber.i("Notification: %s", event);
    final Trime trime = Trime.getService();
    Timber.i("Notification: getService done, before det SchemaEvent");
    if (event instanceof RimeEvent.SchemaEvent) {
      initSchema();
      trime.initKeyboard();
      Timber.i("Notification: solve SchemaEvent");
    } else if (event instanceof RimeEvent.OptionEvent) {
      getStatus();
      getContexts(); // 切換中英文、簡繁體時更新候選
      final boolean value = !message_value.startsWith("!");
      final String option = message_value.substring(value ? 0 : 1);
      trime.textInputManager.onOptionChanged(option, value);
    }
    mOnMessage = false;
  }

  public static String openccConvert(String line, String name) {
    if (!TextUtils.isEmpty(name)) {
      final File f = new File(DataManager.getDataDir("opencc"), name);
      if (f.exists()) return opencc_convert(line, f.getAbsolutePath());
    }
    return line;
  }

  public static void check(boolean full_check) {
    final long t0 = System.nanoTime();
    final boolean started = start_maintenance(full_check);
    final long t1 = System.nanoTime();
    final boolean maint = is_maintenance_mode();
    Timber.i(
        "Rime.check(%b): start_maintenance=%b(%dms) maintenance_mode=%b",
        full_check, started, (t1 - t0) / 1_000_000, maint);
    if (started && maint) {
      if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        // 主線程不能 join：大方案維護 1~2 分鐘，會讓 UI 直接白屏假死。
        // 丟到背景線程，等它完成再補初始化（見 runMaintenanceInBackground()）。
        maintenancePending = true;
        Timber.w("Rime.check: maintenance pending -> run in background, UI keeps responsive");
        final Thread worker = new Thread(Rime::runMaintenanceInBackground, "rime-maintenance");
        worker.setDaemon(true);
        worker.start();
        return;
      }
      join_maintenance_thread();
      Timber.i("Rime.check: joined maintenance in %dms", (System.nanoTime() - t1) / 1_000_000);
    }
  }

  public static boolean syncUserData(Context context) {
    boolean b = sync_user_data();
    destroy();
    get(context, true);
    return b;
  }

  // init
  public static native void setup(String shared_data_dir, String user_data_dir);

  public static native void set_notification_handler();

  // entry and exit
  public static native void initialize(String shared_data_dir, String user_data_dir);

  public static native void finalize1();

  public static native boolean start_maintenance(boolean full_check);

  public static native boolean is_maintenance_mode();

  public static native void join_maintenance_thread();

  // deployment
  public static native void deployer_initialize(String shared_data_dir, String user_data_dir);

  public static native boolean prebuild();

  public static native boolean deploy();

  public static native boolean deploy_schema(String schema_file);

  public static native boolean deploy_config_file(String file_name, String version_key);

  /**
   * 部署config文件到build目录
   *
   * @param name 配置名称，不含yaml后缀
   * @param skipIfExists 启用此模式时，如build目录已经存在对应名称的文件，且大小超过10k，则不重新部署，从而节约时间
   * @return
   */
  public static boolean deploy_config_file(String name, boolean skipIfExists) {
    String file_name = name + ".yaml";
    if (skipIfExists) {
      File f = new File(Rime.get_user_data_dir() + File.separator + "build", file_name);
      if (f.exists()) {
        if (f.length() > 10000) {
          Timber.d("deploy_config_file() skip");
          return true;
        }
      } else {
        return Rime.deploy_config_file(file_name, "config_version");
      }
    }
    return Rime.deploy_config_file(file_name, "config_version");
  }

  public static native boolean sync_user_data();

  // session management
  public static native int create_session();

  public static native boolean find_session();

  public static native boolean destroy_session();

  public static native void cleanup_stale_sessions();

  public static native void cleanup_all_sessions();

  // input
  public static native boolean process_key(int keycode, int mask);

  public static native boolean commit_composition();

  public static native void clear_composition();

  // output
  public static native boolean get_commit(RimeCommit commit);

  public static native boolean get_context(RimeContext context);

  public static native boolean get_status(RimeStatus status);

  // runtime options
  public static native void set_option(String option, boolean value);

  public static native boolean get_option(String option);

  public static native void set_property(String prop, String value);

  public static native String get_property(String prop);

  public static native List get_schema_list();

  public static native String get_current_schema();

  public static native boolean select_schema(String schema_id);

  // configuration
  public static native Boolean config_get_bool(String name, String key);

  public static native boolean config_set_bool(String name, String key, boolean value);

  public static native Integer config_get_int(String name, String key);

  public static native boolean config_set_int(String name, String key, int value);

  public static native Double config_get_double(String name, String key);

  public static native boolean config_set_double(String name, String key, double value);

  public static native String config_get_string(String name, String key);

  public static native boolean config_set_string(String name, String key, String value);

  public static native int config_list_size(String name, String key);

  public static native List config_get_list(String name, String key);

  @Nullable
  public static native Map<String, Map<String, ?>> config_get_map(String name, String key);

  public static native Object config_get_value(String name, String key);

  public static native Object schema_get_value(String name, String key);

  // testing
  public static native boolean simulate_key_sequence(String key_sequence);

  public static native String get_input();

  public static native int get_caret_pos();

  public static native int[] getCompositionSegments();

  /** @see #enumerateCandidates(int) */
  public static native String[] enumerate_candidates(int maxCount);

  public static native void set_caret_pos(int caret_pos);

  public static native boolean select_candidate(int index);

  public static native boolean select_candidate_on_current_page(int index);

  public static native boolean delete_candidate(int index);

  public static native boolean delete_candidate_on_current_page(int index);

  public static native String get_version();

  public static native String get_librime_version();

  // module
  public static native boolean run_task(String task_name);

  public static native String get_shared_data_dir();

  public static native String get_user_data_dir();

  public static native String get_sync_dir();

  public static native String get_user_id();

  // key_table
  public static native int get_modifier_by_name(String name);

  public static native int get_keycode_by_name(String name);

  // customize setting
  public static native boolean customize_bool(String name, String key, boolean value);

  public static native boolean customize_int(String name, String key, int value);

  public static native boolean customize_double(String name, String key, double value);

  public static native boolean customize_string(String name, String key, String value);

  public static native List<Map<String, String>> get_available_schema_list();

  public static native List<Map<String, String>> get_selected_schema_list();

  public static native boolean select_schemas(String[] schema_id_list);

  // opencc
  public static native String get_opencc_version();

  public static native String opencc_convert(String line, String name);

  public static native void opencc_convert_dictionary(
      String inputFileName, String outputFileName, String formatFrom, String formatTo);

  public static native String get_trime_version();
}
