package com.hazukieq.hkrime.ime.text;

import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.HorizontalScrollView;

/**
 * 固定最大寬度的橫向滾動 preedit 容器：
 * 短編碼隨內容收緊，過長編碼鎖死在系統固定寬度內並裁切溢出部分，支持用戶觸摸左右滑動查看。
 * 寬度上限由系統統一鎖定，不對主題配置開放。
 */
public class PreeditScrollView extends HorizontalScrollView {

  /** 系統鎖定的 preedit 區域最大寬度（dp），不再對主題開放配置。 */
  public static final int FIXED_MAX_WIDTH_DP = 280;

  private static final int MIN_AVAILABLE_WIDTH_DP = 160;
  private static final int EDGE_MARGIN_DP = 12;

  public PreeditScrollView(Context context) {
    super(context);
    init();
  }

  public PreeditScrollView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public PreeditScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    setHorizontalScrollBarEnabled(false);
    setOverScrollMode(OVER_SCROLL_NEVER);
    setFillViewport(false);
    setClipChildren(true);
    setClipToPadding(true);
    setFocusable(false);
    setFocusableInTouchMode(false);
  }

  /** 計算系統固定的 preedit 最大像素寬度（不讀取主題 layout/max_width）。 */
  public static int resolveFixedMaxWidthPx(Context context) {
    final DisplayMetrics dm = context.getResources().getDisplayMetrics();
    final float density = dm.density > 0f ? dm.density : 1f;
    final int fixedCapPx = Math.round(FIXED_MAX_WIDTH_DP * density);
    final int marginPx = Math.round(EDGE_MARGIN_DP * density);
    final int minAvailPx = Math.round(MIN_AVAILABLE_WIDTH_DP * density);
    final int screenAvailPx = Math.max(minAvailPx, dm.widthPixels - marginPx * 2);
    return Math.min(fixedCapPx, screenAvailPx);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int cap = resolveFixedMaxWidthPx(getContext());
    final int specMode = MeasureSpec.getMode(widthMeasureSpec);
    final int specSize = MeasureSpec.getSize(widthMeasureSpec);
    final int effectiveCap =
        specMode == MeasureSpec.UNSPECIFIED ? cap : Math.min(cap, Math.max(0, specSize));
    super.onMeasure(
        MeasureSpec.makeMeasureSpec(effectiveCap, MeasureSpec.AT_MOST), heightMeasureSpec);
    if (getMeasuredWidth() > effectiveCap) {
      setMeasuredDimension(effectiveCap, getMeasuredHeight());
    }
  }

  /** 將可視窗口滾動至編碼尾端（不搶焦點）。 */
  public void scrollToTail() {
    if (getChildCount() == 0) return;
    final View child = getChildAt(0);
    final int contentWidth = child.getWidth();
    final int visibleWidth = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
    final int maxScrollX = Math.max(0, contentWidth - visibleWidth);
    scrollTo(maxScrollX, 0);
  }

  /** 復位至最左側，供隱藏或重新輸入時調用。 */
  public void resetScroll() {
    scrollTo(0, 0);
  }
}
