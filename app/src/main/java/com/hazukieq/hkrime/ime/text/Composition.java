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
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 純 preedit 文本繪製視圖：只負責單行編碼顯示與高亮配色，
 * 不再讀取主題尺寸參數、不查詢引擎狀態、不管理 PopupWindow。
 * 寬度上限與橫向觸摸滾動由外層 {@link PreeditScrollView} 與 {@link TouchPreeditPopupWindow} 集中控制。
 */
public class Composition extends AppCompatTextView {

  private int textColor;
  private int hilitedTextColor;
  private int hilitedBackColor;
  private int backColor;
  private CharSequence lastRenderedText = "";

  public Composition(Context context, AttributeSet attrs) {
    super(context, attrs);
    setSingleLine(true);
    setHorizontallyScrolling(true);
    setEllipsize(null);
    setMinWidth(0);
    setMaxWidth(Integer.MAX_VALUE);
    setMinHeight(0);
    setMaxHeight(Integer.MAX_VALUE);
  }

  /** 由 {@link TouchPreeditPopupWindow} 注入系統固定的字號、字距與內邊距。 */
  public void applyFixedMetrics(int textSizePx, float letterSpacing, int paddingH, int paddingV) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      setLetterSpacing(letterSpacing);
    }
    setPadding(paddingH, paddingV, paddingH, paddingV);
  }

  /** 由 {@link TouchPreeditPopupWindow} 注入主題配色與字體。 */
  public void applyThemeStyle(
      int textColor,
      int hilitedTextColor,
      int hilitedBackColor,
      int backColor,
      @Nullable Typeface typeface) {
    this.textColor = textColor;
    this.hilitedTextColor = hilitedTextColor;
    this.hilitedBackColor = hilitedBackColor;
    this.backColor = backColor;
    setTextColor(textColor);
    if (typeface != null) {
      setTypeface(typeface);
    }
  }

  /** 僅渲染已算好的 {@link TouchPreeditDisplayState}；僅在文本變更時滾至尾端。 */
  public void render(@NonNull TouchPreeditDisplayState state) {
    final String text = state.getText();
    if (text.isEmpty()) {
      resetScrollState();
      setText("");
      return;
    }
    final boolean textChanged = !TextUtils.equals(lastRenderedText, text);
    if (textChanged) {
      lastRenderedText = text;
    }
    final int len = text.length();
    final int hiStart = Math.max(0, Math.min(state.getHighlightStart(), len));
    final int hiEnd = Math.max(hiStart, Math.min(state.getHighlightEnd(), len));

    final SpannableStringBuilder ss = new SpannableStringBuilder(text);
    ss.setSpan(new ForegroundColorSpan(textColor), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (backColor != 0) {
      ss.setSpan(new BackgroundColorSpan(backColor), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (hiEnd > hiStart) {
      ss.setSpan(
          new ForegroundColorSpan(hilitedTextColor), hiStart, hiEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if (hilitedBackColor != 0) {
        ss.setSpan(
            new BackgroundColorSpan(hilitedBackColor),
            hiStart,
            hiEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    setText(ss);
    if (textChanged && getParent() instanceof PreeditScrollView) {
      final PreeditScrollView scroller = (PreeditScrollView) getParent();
      post(scroller::scrollToTail);
    }
  }

  public void resetScrollState() {
    lastRenderedText = "";
    if (getParent() instanceof PreeditScrollView) {
      ((PreeditScrollView) getParent()).resetScroll();
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(@NonNull MotionEvent event) {
    // 禁用點按跳光標，將觸摸事件完整讓給外層 PreeditScrollView 處理左右滑動查看。
    return false;
  }
}
