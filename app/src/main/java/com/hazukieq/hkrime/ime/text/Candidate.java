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

package com.hazukieq.hkrime.ime.text;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.PaintDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import com.hazukieq.hkrime.core.Rime;
import com.hazukieq.hkrime.data.AppPrefs;
import com.hazukieq.hkrime.data.Config;
import com.hazukieq.hkrime.ime.core.Trime;
import com.hazukieq.hkrime.util.GraphicUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** 顯示候選字詞 */
public class Candidate extends View {

  /**
   * 候選條的一項（12 鍵路徑由 App 的快照直接餵入，不再回讀 Rime.getCandidates()）。
   */
  public static class BarEntry {
    public final String text;
    public final String comment;
    public final boolean openccModified;

    public BarEntry(String text, String comment) {
      this(text, comment, false);
    }

    public BarEntry(String text, String comment, boolean openccModified) {
      this.text = text;
      this.comment = comment;
      this.openccModified = openccModified;
    }
  }

  /** 處理候選條選字事件 */
  public interface EventListener {
    void onCandidatePressed(int index);

    void onCandidateSymbolPressed(String arrow);

    void onCandidateLongClicked(int index);
  }

  private static final int MAX_CANDIDATE_COUNT = 30;
  public static final String PAGE_UP_BUTTON = "◀";
  public static final String PAGE_DOWN_BUTTON = "▶";
  public static final String PAGE_EX_BUTTON = "▼";
  /** 候選為空時的按鈕：點它收起輸入法鍵盤 */
  public static final String HIDE_KEYBOARD_BUTTON = "⌄";
  /** 候選為空時的剪切板按鈕：點它開/關原生剪切板面板 */
  public static final String CLIPBOARD_BUTTON = "📋";

  /** 面板展開時，▼ 反向繪製成 ▲（同一個路徑，僅方向不同），保證展開/收起圖標同尺寸。 */
  private static final String PANEL_OPEN_ARROW = "▲";

  /** 左側菜單鍵（Gboard 式）：列出方案開關。 */
  public static final String MENU_BUTTON = "☰";

  /** 右側配色鍵（展開箭頭左邊）：打開配色對話框。 */
  public static final String THEME_BUTTON = "◑";
  private int expectWidth = 0;

  private WeakReference<EventListener> listener = new WeakReference<>(null);
  private final GraphicUtils graphicUtils;
  private int highlightIndex = -1;
  private final int touchSlop =
      ViewConfiguration.get(getContext()).getScaledTouchSlop();
  private float touchDownX, touchDownY;
  private boolean dragged = false;
  /** 展開面板是否處於打開狀態（影響 ▼ 的繪製方向）。 */
  private boolean panelOpen = false;
  private int symbolBoxSize;
  private final Path chevronPath = new Path();
  private BarEntry[] candidates;
  /** 非 null 時候選條只讀這份外部快照（12 鍵路徑），不再回讀 Rime。 */
  private BarEntry[] externalCandidates;
  private boolean useExternalCandidates = false;
  private final ArrayList<ComputedCandidate> computedCandidates =
      new ArrayList<>(MAX_CANDIDATE_COUNT);
  private int numCandidates;
  private int startNum = 0;
  private long timeDown = 0, timeMove = 0;

  private PaintDrawable candidateHighlight;
  private final Paint separatorPaint;
  private final Paint candidatePaint;
  private final TextPaint ellipsizePaint = new TextPaint();
  private final Paint symbolPaint;
  private final Paint commentPaint;
  private final Paint openccDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private Typeface candidateFont, symbolFont, commentFont;
  private int candidateTextColor, hilitedCandidateTextColor;
  private int commentTextColor, hilitedCommentTextColor;
  private int candidateViewHeight, commentHeight, candidateSpacing, candidatePadding;
  private boolean shouldShowComment = true, isCommentOnTop, candidateUseCursor;
  /**
   * 只在當前候選確實含 comment 時預留註釋行，避免空註釋增加候選條高度。
   */
  private boolean showCommentRow = false;

  public void reset(Context context) {
    Config config = Config.get(context);
    candidateHighlight = new PaintDrawable(config.getColor("hilited_candidate_back_color"));
    candidateHighlight.setCornerRadius(0f);
    separatorPaint.setColor(config.getColor("candidate_separator_color"));
    candidateSpacing = config.getPixel("candidate_spacing");
    candidatePadding = config.getPixel("candidate_padding");

    candidateTextColor = config.getColor("candidate_text_color");
    commentTextColor = config.getColor("comment_text_color");
    hilitedCandidateTextColor = config.getColor("hilited_candidate_text_color");
    hilitedCommentTextColor = config.getColor("hilited_comment_text_color");

    int candidate_text_size = config.getPixel("candidate_text_size");
    int comment_text_size = config.getPixel("comment_text_size");
    candidateViewHeight = config.getPixel("candidate_view_height");
    commentHeight = config.getPixel("comment_height");

    candidateFont = config.getFont("candidate_font");
    commentFont = config.getFont("comment_font");
    symbolFont = config.getFont("symbol_font");
    symbolBoxSize = Math.round(48 * getResources().getDisplayMetrics().density);

    candidatePaint.setTextSize(candidate_text_size);
    candidatePaint.setTypeface(candidateFont);
    ellipsizePaint.set(candidatePaint);
    ellipsizePaint.setTypeface(candidateFont);
    symbolPaint.setTextSize(candidate_text_size);
    symbolPaint.setTypeface(symbolFont);
    commentPaint.setTextSize(comment_text_size);
    commentPaint.setTypeface(commentFont);

    isCommentOnTop = config.getBoolean("comment_on_top");
    candidateUseCursor = config.getBoolean("candidate_use_cursor");
    // 主題可能改了 candidate_view_height / comment_height：讓 onMeasure 重新算高度。
    requestLayout();
    invalidate();
  }

  public void setShowComment(boolean value) {
    shouldShowComment = value;
  }

  public Candidate(Context context, AttributeSet attrs) {
    super(context, attrs);
    candidatePaint = new Paint();
    candidatePaint.setAntiAlias(true);
    candidatePaint.setStrokeWidth(0);
    symbolPaint = new Paint();
    symbolPaint.setAntiAlias(true);
    symbolPaint.setStrokeWidth(0);
    commentPaint = new Paint();
    commentPaint.setAntiAlias(true);
    commentPaint.setStrokeWidth(0);

    separatorPaint = new Paint();
    separatorPaint.setColor(Color.BLACK);
    openccDotPaint.setStyle(Paint.Style.FILL);

    graphicUtils = new GraphicUtils(context);

    // reset(context);

    setWillNotDraw(false);
  }

  public static int getMaxCandidateCount() {
    return MAX_CANDIDATE_COUNT;
  }

  public void setCandidateListener(@Nullable EventListener listener) {
    this.listener = new WeakReference<>(listener);
  }

  /**
   * 12 鍵路徑：候選條只讀 App 快照（與展開面板同源），不調 Rime.getCandidates()。
   *
   * <p>空列表時會顯示右側「⌄」收起鍵盤按鈕，行為與舊的空候選一致。
   */
  public void setCandidates(@Nullable List<BarEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      externalCandidates = new BarEntry[0];
    } else {
      externalCandidates = entries.toArray(new BarEntry[0]);
    }
    useExternalCandidates = true;
    setText(0);
  }

  /** 離開 12 鍵路徑：恢復「候選條自己回讀 Rime.getCandidates()」的舊行為。 */
  public void clearExternalCandidates() {
    useExternalCandidates = false;
    externalCandidates = null;
    setText(0);
  }

  /** 展開面板開/關：讓右側箭頭在 ▼ / ▲ 之間切換（尺寸不變）。 */
  public void setPanelOpen(boolean open) {
    if (panelOpen == open) return;
    panelOpen = open;
    invalidate();
  }

  /**
   * 刷新候選列表
   *
   * @param start 候選的起始編號
   */
  public void setText(int start) {
    startNum = start;
    removeHighlight();
    updateCandidateWidth();
    // 候選為空時也要重繪，否則「收起鍵盤」按鈕不會出現（舊狀態會殘留在 Canvas 上）
    invalidate();
  }

  public void setExpectWidth(int expectWidth) {
    if (this.expectWidth == expectWidth) return;
    this.expectWidth = expectWidth;
    requestLayout();
  }

  /** 候選條高度：只有「真的有註釋且註釋在上方」時才多留一行。 */
  private int desiredBarHeight() {
    return (showCommentRow && isCommentOnTop)
        ? candidateViewHeight + commentHeight
        : candidateViewHeight;
  }

  /**
   * 官方對自定義 View 的契約：寬高必須在 onMeasure() 內自行算出並呼叫 setMeasuredDimension()，
   * 而不是由外部反覆改寫 LayoutParams —— 後者每次都會觸發父容器 requestLayout，連帶整棵
   * 視圖樹重新測量。這裡寬度尊重父容器給的 MeasureSpec，高度由內容（是否顯示註釋行）決定。
   */
  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int specSize = MeasureSpec.getSize(widthMeasureSpec);
    final int available =
        MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
            ? (expectWidth > 0 ? expectWidth : getResources().getDisplayMetrics().widthPixels)
            : specSize;
    final int desiredWidth = expectWidth > 0 ? Math.min(expectWidth, available) : available;
    final int width = resolveSize(desiredWidth, widthMeasureSpec);
    final int height =
        resolveSize(
            Math.max(desiredBarHeight(), getSuggestedMinimumHeight()), heightMeasureSpec);
    setMeasuredDimension(width, height);
  }

  /**
   * 選取候選項
   *
   * @param index 候選項序號（從0開始），{@code -1}表示選擇當前高亮候選項
   * @return 是否成功選字
   */
  private void onCandidateClick(int index, boolean isLongClick) {
    ComputedCandidate candidate = null;
    if (index >= 0 && index < computedCandidates.size()) {
      candidate = computedCandidates.get(index);
      if (candidate != null) {
        if (candidate instanceof ComputedCandidate.Word) {
          if (listener.get() != null) {
            if (isLongClick && Config.getShouldLongClickDeleteCandidate()) {
              listener.get().onCandidateLongClicked(index + startNum);
            } else {
              listener.get().onCandidatePressed(index + startNum);
            }
          }
        }
        if (candidate instanceof ComputedCandidate.Symbol) {
          String arrow = ((ComputedCandidate.Symbol) candidate).getArrow();
          if (listener.get() != null) {
            listener.get().onCandidateSymbolPressed(arrow);
          }
        }
      }
    }
  }

  private void removeHighlight() {
    // 每次刷新都 requestLayout 會讓候選條整條重新 measure/layout；沒有高亮時沒必要。
    if (highlightIndex == -1) return;
    highlightIndex = -1;
    invalidate();
    requestLayout();
  }

  private boolean isHighlighted(int i) {
    return candidateUseCursor && i == highlightIndex;
  }

  public int getHighlightLeft() {
    if (highlightIndex < computedCandidates.size() && highlightIndex >= 0)
      return computedCandidates.get(highlightIndex).getGeometry().left;
    return 0;
  }

  public int getHighlightRight() {
    if (highlightIndex < computedCandidates.size() && highlightIndex >= 0)
      return computedCandidates.get(highlightIndex).getGeometry().right;
    return 0;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (canvas == null) return;
    // 先画背景：候選為空時也要擦掉舊像素，並畫出「收起鍵盤」按鈕
    super.onDraw(canvas);
    if (computedCandidates.isEmpty()) return;

    for (ComputedCandidate computedCandidate : computedCandidates) {
      int i = computedCandidates.indexOf(computedCandidate);
      // Draw highlight
      if (candidateUseCursor && i == highlightIndex) {
        candidateHighlight.setBounds(computedCandidates.get(i).getGeometry());
        candidateHighlight.draw(canvas);
      }
      // Draw candidates
      if (computedCandidate instanceof ComputedCandidate.Word) {
        final Rect geom = computedCandidate.getGeometry();
        canvas.save();
        canvas.clipRect(geom);
        float wordX = geom.centerX();
        float wordY =
            computedCandidates.get(0).getGeometry().centerY()
                - (candidatePaint.ascent() + candidatePaint.descent()) / 2;
        // 有 comment 時整排候選共同預留註釋高度，保持文字基線一致。
        if (showCommentRow) {
          wordY += isCommentOnTop ? commentHeight / 2.0f : -commentHeight / 2.0f;
        }
        // 候選區：只有第一個候選標拼音（其餘候選共用同一讀音，重複標註很難看）
        if (showCommentRow && i == 0) {
          String comment = ((ComputedCandidate.Word) computedCandidate).getComment();
          if (comment != null && !comment.isEmpty()) {
            float commentX = geom.centerX();
            float commentY =
                commentHeight / 2.0f - (commentPaint.ascent() + commentPaint.descent()) / 2;
            if (!isCommentOnTop) {
              commentY += computedCandidates.get(0).getGeometry().bottom - commentHeight;
            }
            commentPaint.setColor(isHighlighted(i) ? hilitedCommentTextColor : commentTextColor);
            graphicUtils.drawText(canvas, comment, commentX, commentY, commentPaint, commentFont);
          }
        }
        String word = ((ComputedCandidate.Word) computedCandidate).getWord();
        final boolean openccModified = ((ComputedCandidate.Word) computedCandidate).getOpenccModified();
        final float maxWordW = Math.max(1f, geom.width() - 2f * candidatePadding);
        if (graphicUtils.measureText(candidatePaint, word, candidateFont) > maxWordW) {
          word =
              TextUtils.ellipsize(word, ellipsizePaint, maxWordW, TextUtils.TruncateAt.END)
                  .toString();
        }
        final boolean highlighted = isHighlighted(i);
        candidatePaint.setColor(highlighted ? hilitedCandidateTextColor : candidateTextColor);
        graphicUtils.drawText(canvas, word, wordX, wordY, candidatePaint, candidateFont);
        if (openccModified) {
          drawOpenccMarker(canvas, geom, wordY, highlighted);
        }
        canvas.restore();
      } else if (computedCandidate instanceof ComputedCandidate.Symbol) {
        String arrow = ((ComputedCandidate.Symbol) computedCandidate).getArrow();
        // 圖標與候選文字同色（不再用拼音註釋的藍色），視覺重量才一致
        symbolPaint.setColor(isHighlighted(i) ? hilitedCandidateTextColor : candidateTextColor);
        if (PAGE_EX_BUTTON.equals(arrow) || HIDE_KEYBOARD_BUTTON.equals(arrow)) {
          // ▼ / ⌄ 統一用路徑繪製：同尺寸、同線寬；面板打開時 ▼ 反向成 ▲
          boolean up = PAGE_EX_BUTTON.equals(arrow) && panelOpen;
          drawChevron(canvas, computedCandidate.getGeometry(), up);
        } else if (MENU_BUTTON.equals(arrow)) {
          drawMenuIcon(canvas, computedCandidate.getGeometry());
        } else if (CLIPBOARD_BUTTON.equals(arrow)) {
          drawClipboardIcon(canvas, computedCandidate.getGeometry());
        } else if (THEME_BUTTON.equals(arrow)) {
          drawPaletteIcon(canvas, computedCandidate.getGeometry());
        } else {
          float arrowX =
              computedCandidate.getGeometry().centerX()
                  - graphicUtils.measureText(symbolPaint, arrow, symbolFont) / 2;
          float arrowY =
              computedCandidates.get(0).getGeometry().centerY()
                  - (candidatePaint.ascent() + candidatePaint.descent()) / 2;
          canvas.drawText(arrow, arrowX, arrowY, symbolPaint);
        }
      }
      // Draw separators
      if (i + 1 < computedCandidates.size()) {
        canvas.drawRect(
            computedCandidate.getGeometry().right - candidateSpacing,
            computedCandidate.getGeometry().height() * 0.2f,
            computedCandidate.getGeometry().right + candidateSpacing,
            computedCandidate.getGeometry().height() * 0.8f,
            separatorPaint);
      }
    }
  }

  /** OpenCC 改寫候選的輕標記：固定候選欄僅保留右上角微角標圓點（無橫向佔位）。 */
  private void drawOpenccMarker(Canvas canvas, Rect geom, float wordY, boolean highlighted) {
    final float d = dp();
    final int refInk = highlighted ? hilitedCandidateTextColor : candidateTextColor;
    final int markColor =
        ColorUtils.calculateLuminance(refInk) > 0.5 ? 0xFFFBBF24 : 0xFFD97706;
    openccDotPaint.setColor(ColorUtils.setAlphaComponent(markColor, 235));

    final float dotR = 2.3f * d;
    final float dotX = geom.right - Math.max(5.5f * d, candidatePadding * 0.6f);
    final float dotY =
        Math.max(geom.top + 5.5f * d, wordY + candidatePaint.ascent() + 2.0f * d);
    canvas.drawCircle(dotX, dotY, dotR, openccDotPaint);
  }

  /**
   * 圖標的點擊/繪製區域：只覆蓋「候選詞那一行」，
   * 這樣 comment_on_top 時候選上方有拼音注釋時，圖標仍與漢字對齊（而不是在整條中間）。
   */
  private Rect symbolRect(int left, int width) {
    // 觸摸區域整條高（48dp 級別），繪製時再對齊到候選詞那一行
    return new Rect(left, 0, left + width, getMeasuredHeight());
  }

  /** 圖標垂直中心 = 候選詞那一行的中心（comment_on_top 且真的有注釋時在拼音注釋下方）。 */
  private float iconCenterY(Rect rect) {
    final float offset = (showCommentRow && isCommentOnTop) ? commentHeight / 2f : 0f;
    return rect.centerY() + offset;
  }

  private void addMenuSymbol(int left) {
    computedCandidates.add(
        new ComputedCandidate.Symbol(MENU_BUTTON, symbolRect(left, symbolBoxSize)));
  }

  private void addClipboardSymbol(int left) {
    computedCandidates.add(
        new ComputedCandidate.Symbol(CLIPBOARD_BUTTON, symbolRect(left, symbolBoxSize)));
  }

  private void addThemeSymbol(int left) {
    computedCandidates.add(
        new ComputedCandidate.Symbol(THEME_BUTTON, symbolRect(left, symbolBoxSize)));
  }

  /** 圖標統一：線寬 2dp、圓頭、光學尺寸 ~16dp，顏色跟候選文字一致。 */
  private static final float ICON_STROKE_DP = 2.2f;

  private float dp() {
    return getResources().getDisplayMetrics().density;
  }

  /** 菜單：三條等長橫線。 */
  private void drawMenuIcon(Canvas canvas, Rect rect) {
    final float halfW = 9.5f * dp();
    final float gap = 5.4f * dp();
    final float cx = rect.centerX();
    final float cy = iconCenterY(rect);
    symbolPaint.setStyle(Paint.Style.STROKE);
    symbolPaint.setStrokeWidth(ICON_STROKE_DP * dp());
    symbolPaint.setStrokeCap(Paint.Cap.ROUND);
    canvas.drawLine(cx - halfW, cy - gap, cx + halfW, cy - gap, symbolPaint);
    canvas.drawLine(cx - halfW, cy, cx + halfW, cy, symbolPaint);
    canvas.drawLine(cx - halfW, cy + gap, cx + halfW, cy + gap, symbolPaint);
    symbolPaint.setStrokeCap(Paint.Cap.BUTT);
    symbolPaint.setStyle(Paint.Style.FILL);
  }

  /** 剪切板：與菜單/配色同線寬（2.2dp 圓角描邊）的簡潔剪貼板圖標。 */
  private void drawClipboardIcon(Canvas canvas, Rect rect) {
    final float d = dp();
    final float cx = rect.centerX();
    final float cy = iconCenterY(rect);
    final float halfW = 7.4f * d;
    final float top = cy - 7.8f * d;
    final float bottom = cy + 9.2f * d;
    final float r = 2.4f * d;
    final float clipHalfW = 3.6f * d;
    final float clipTop = cy - 9.8f * d;
    final float clipBottom = cy - 5.8f * d;

    symbolPaint.setStyle(Paint.Style.STROKE);
    symbolPaint.setStrokeWidth(ICON_STROKE_DP * d);
    symbolPaint.setStrokeCap(Paint.Cap.ROUND);
    symbolPaint.setStrokeJoin(Paint.Join.ROUND);

    // 頂部夾子（小圓角矩形）
    canvas.drawRoundRect(
        cx - clipHalfW, clipTop, cx + clipHalfW, clipBottom, 1.4f * d, 1.4f * d, symbolPaint);
    // 主板外框
    canvas.drawRoundRect(cx - halfW, top, cx + halfW, bottom, r, r, symbolPaint);
    // 兩條簡短文字線條
    canvas.drawLine(cx - 3.6f * d, cy + 0.6f * d, cx + 3.6f * d, cy + 0.6f * d, symbolPaint);
    canvas.drawLine(cx - 3.6f * d, cy + 4.4f * d, cx + 1.8f * d, cy + 4.4f * d, symbolPaint);

    symbolPaint.setStrokeCap(Paint.Cap.BUTT);
    symbolPaint.setStyle(Paint.Style.FILL);
  }

  /** 配色：半填充圓（明暗/主題的通用符號），乾淨且與文字同色。 */
  private void drawPaletteIcon(Canvas canvas, Rect rect) {
    final float r = 9.6f * dp();
    final float cx = rect.centerX();
    final float cy = iconCenterY(rect);
    symbolPaint.setStyle(Paint.Style.FILL);
    symbolPaint.setStrokeWidth(ICON_STROKE_DP * dp());
    // 左半實心 + 整體描邊
    canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 90f, 180f, true, symbolPaint);
    symbolPaint.setStyle(Paint.Style.STROKE);
    canvas.drawCircle(cx, cy, r, symbolPaint);
    symbolPaint.setStyle(Paint.Style.FILL);
  }

  /** 展開/收起/收起鍵盤共用同一個「線條箭頭」，與其它圖標同線寬。 */
  private void drawChevron(Canvas canvas, Rect rect, boolean up) {
    final float halfW = 8.0f * dp();
    final float halfH = 4.8f * dp();
    final float cx = rect.centerX();
    final float cy = iconCenterY(rect);
    chevronPath.reset();
    if (up) {
      chevronPath.moveTo(cx - halfW, cy + halfH);
      chevronPath.lineTo(cx, cy - halfH);
      chevronPath.lineTo(cx + halfW, cy + halfH);
    } else {
      chevronPath.moveTo(cx - halfW, cy - halfH);
      chevronPath.lineTo(cx, cy + halfH);
      chevronPath.lineTo(cx + halfW, cy - halfH);
    }
    symbolPaint.setStyle(Paint.Style.STROKE);
    symbolPaint.setStrokeWidth(ICON_STROKE_DP * dp());
    symbolPaint.setStrokeCap(Paint.Cap.ROUND);
    symbolPaint.setStrokeJoin(Paint.Join.ROUND);
    canvas.drawPath(chevronPath, symbolPaint);
    symbolPaint.setStrokeCap(Paint.Cap.BUTT);
    symbolPaint.setStyle(Paint.Style.FILL);
  }

  /**
   * 單行候選欄：超寬直接截斷，右側固定一個「▼」；候選為空時右側變成「收起鍵盤」按鈕。
   * 翻頁箭頭（◀ ▶）已移除，上一頁/下一頁統一在展開面板裡操作。
   */
  private void updateCandidateWidth() {
    computedCandidates.clear();
    updateCandidates();

    // 有實際 comment 時才預留註釋高度。
    showCommentRow =
        shouldShowComment
            && numCandidates > 0
            && candidates != null
            && !TextUtils.isEmpty(candidates[startNum].comment);

    int avail = expectWidth > 0 ? expectWidth : getMeasuredWidth();
    if (avail <= 0) avail = getResources().getDisplayMetrics().widthPixels;

    // 圖標只在「沒有候選」時出現：菜單（左）／配色 / 收起鍵盤（右）。
    // Gboard 工具欄規格：24dp 圖標 + 48dp 觸摸盒 + 8dp 頁邊距，盒相鄰即 24dp 視覺間隔。
    final float density = getResources().getDisplayMetrics().density;
    final int edge = Math.round(8 * density);

    if (numCandidates <= 0 || candidates == null) {
      final int rightMost = avail - edge - symbolBoxSize;
      final int themeLeft = rightMost - symbolBoxSize;
      addMenuSymbol(edge);
      addClipboardSymbol(edge + symbolBoxSize);
      addThemeSymbol(themeLeft);
      computedCandidates.add(
          new ComputedCandidate.Symbol(HIDE_KEYBOARD_BUTTON, symbolRect(rightMost, symbolBoxSize)));
      ensureBarHeight();
      Trime.getService().setCandidateExPage(false);
      return;
    }

    // 有候選時只留右側展開箭頭的位置，其餘寬度全部給候選詞
    final int arrowLeft = avail - edge - symbolBoxSize;
    int limit = arrowLeft - candidateSpacing;
    int x = edge / 2;
    boolean truncated = false;
    for (int i = 0; i < numCandidates; i++) {
      int n = i + startNum;
      String text = candidates[n].text;
      String comment = null;
      float candidateWidth =
          graphicUtils.measureText(candidatePaint, text, candidateFont) + 2 * candidatePadding;
      if (shouldShowComment) {
        comment = candidates[n].comment;
        if (!TextUtils.isEmpty(comment)) {
          float commentWidth = graphicUtils.measureText(commentPaint, comment, commentFont);
          candidateWidth =
              isCommentOnTop
                  ? Math.max(candidateWidth, commentWidth)
                  : candidateWidth + commentWidth;
        }
      }
      final boolean openccModified = candidates[n].openccModified;
      if (x + candidateWidth > limit) {
        if (computedCandidates.isEmpty() && limit > x + 2 * candidatePadding) {
          computedCandidates.add(
              new ComputedCandidate.Word(
                  text, comment, new Rect(x, 0, limit, getMeasuredHeight()), openccModified));
        }
        truncated = true;
        break;
      }
      computedCandidates.add(
          new ComputedCandidate.Word(
              text, comment, new Rect(x, 0, (int) (x + candidateWidth), getMeasuredHeight()),
              openccModified));
      x += candidateWidth + candidateSpacing;
    }

    // 只要還有候選就常駐展開箭頭（▼/▲）；截斷/翻頁只是「面板裡會有更多」的附加資訊
    computedCandidates.add(
        new ComputedCandidate.Symbol(PAGE_EX_BUTTON, symbolRect(arrowLeft, symbolBoxSize)));

    ensureBarHeight();
    Trime.getService().setCandidateExPage(true);
  }

  /** 內容高度（是否預留註釋行）變了才請求重新測量；高度由 onMeasure 決定。 */
  private void ensureBarHeight() {
    if (getMeasuredHeight() == desiredBarHeight()) return;
    requestLayout();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    updateCandidateWidth();
  }

  /**
   * 固定候選條只接受點擊；移動超過 touch slop 視為拖動，抬手不選字。
   */
  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(@NonNull MotionEvent me) {
    int x = (int) me.getX();
    int y = (int) me.getY();

    switch (me.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        timeDown = System.currentTimeMillis();
        touchDownX = me.getX();
        touchDownY = me.getY();
        dragged = false;
      // fall through
      case MotionEvent.ACTION_MOVE:
        setPressed(true);
        if (!dragged) {
          float dx = Math.abs(me.getX() - touchDownX);
          float dy = Math.abs(me.getY() - touchDownY);
          if (dx > touchSlop || dy > touchSlop) {
            dragged = true;
            highlightIndex = -1;
          }
        }
        if (!dragged) {
          highlightIndex = getCandidateIndex(x, y);
        }
        invalidate();
        break;
        // updateHighlight(x, y);
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        timeMove = System.currentTimeMillis();
        long durationMs = timeMove - timeDown;
        setPressed(false);
        if (me.getActionMasked() == MotionEvent.ACTION_UP && !dragged) {
          onCandidateClick(highlightIndex, durationMs >= Config.getDeleteCandidateTimeout());
        }
        highlightIndex = -1;
        dragged = false;
        invalidate();
        break;
    }
    return true;
  }

  /**
   * 獲得觸摸處候選項序號
   *
   * @param x 觸摸點橫座標
   * @param y 觸摸點縱座標
   * @return {@code >=0}: 觸摸點 (x, y) 處候選項序號，從0開始編號； {@code -1}: 觸摸點 (x, y) 處無候選項； {@code -4}: 觸摸點
   *     (x, y) 處爲{@code Page_Up}； {@code -5}: 觸摸點 (x, y) 處爲{@code Page_Down}
   */
  private int getCandidateIndex(int x, int y) {
    // Rect r = new Rect();
    int retIndex = -1;
    for (ComputedCandidate computedCandidate : computedCandidates) {
      /*
       Enlarge the rectangle to be more responsive to user clicks.
      r.set(candidateRect[j++]);
      r.inset(0, CANDIDATE_TOUCH_OFFSET);
      */
      if (computedCandidate.getGeometry().contains(x, y)) {
        retIndex = computedCandidates.indexOf(computedCandidate);
        break;
      }
    }
    return retIndex;
  }

  private int updateCandidates() {
    if (useExternalCandidates) {
      candidates = externalCandidates;
      startNum = 0;
      highlightIndex = -1;
      numCandidates = candidates == null ? 0 : candidates.length;
      return numCandidates;
    }
    Rime.RimeCandidate[] rimeCandidates = Rime.getCandidates();
    if (rimeCandidates == null) {
      candidates = null;
      numCandidates = 0;
      return 0;
    }
    BarEntry[] entries = new BarEntry[rimeCandidates.length];
    for (int i = 0; i < rimeCandidates.length; i++) {
      Rime.RimeCandidate candidate = rimeCandidates[i];
      entries[i] =
          new BarEntry(
              candidate == null ? "" : candidate.text,
              candidate == null ? "" : candidate.comment,
              candidate != null && candidate.opencc_modified);
    }
    candidates = entries;
    highlightIndex = Rime.getCandHighlightIndex() - startNum;
    numCandidates = candidates.length - startNum;
    return numCandidates;
  }
}
