// TableDishView.java
// 游戏大厅/私房「一张桌子」：俯视图
//   布局比例（严格按设计）：
//     桌面边长  = 头像边长 × 2.5
//     头像到桌面间距 = 固定 8dp
//     组件宽度 = 2×头像 + 2×8dp + 桌面 = 4.5×头像 + 16dp（正方形）
//   四边座位：左右玩家、上下观众，空位半透明占位（始终可见 = 预留位置）。
//   可选：桌面中央显示房号/桌号（setRoomLabel）。
package com.example.cowdunggame;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public class TableDishView extends FrameLayout {

    // 设计规格常量
    public static final float HEAD_TO_TABLE_DP = 8f;   // 头像-桌面间距（dp）
    public static final float TABLE_RATIO = 2.5f;      // 桌面边长 / 头像边长
    public static final float ROW_GAP_DP = 12f;        // 行间距（dp）

    private ImageView tableImg;
    private ImageView leftSeat;
    private ImageView rightSeat;
    private ImageView topSeat;
    private ImageView bottomSeat;
    private TextView labelView;

    // 状态
    private boolean playing = false;
    private boolean leftPlayer = false;
    private boolean rightPlayer = false;
    private boolean spectator = false;
    private boolean leftMale = true;
    private boolean rightMale = true;

    private final float density;

    public TableDishView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.TRANSPARENT);

        // 中心桌面图
        tableImg = new ImageView(context);
        tableImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tableImg.setImageResource(R.drawable.table_idle);
        FrameLayout.LayoutParams tableParams = new FrameLayout.LayoutParams(0, 0);
        tableImg.setLayoutParams(tableParams);
        addView(tableImg);

        // 桌面中央文字：房号/桌号（初始 GONE）
        labelView = new TextView(context);
        labelView.setTextColor(Color.WHITE);
        labelView.setTextSize(14);
        labelView.setGravity(Gravity.CENTER);
        labelView.setShadowLayer(4, 2, 2, Color.BLACK);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        labelView.setLayoutParams(labelParams);
        labelView.setVisibility(GONE);
        addView(labelView);

        // 四边座位
        leftSeat = makeSeat();
        rightSeat = makeSeat();
        topSeat = makeSeat();
        bottomSeat = makeSeat();
        addView(leftSeat);
        addView(rightSeat);
        addView(topSeat);
        addView(bottomSeat);

        refresh();
    }

    // 保持正方形：宽 = 父布局给宽，高自动等于宽
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int w = getMeasuredWidth();
        setMeasuredDimension(w, w);
    }

    private ImageView makeSeat() {
        ImageView seat = new ImageView(getContext());
        seat.setScaleType(ImageView.ScaleType.FIT_CENTER);
        seat.setVisibility(VISIBLE); // 始终可见（预留位置）
        seat.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        return seat;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) return;
        // 由组件宽反推头像边长：w = 4.5×A + 16dp
        float headDp = (w / density - 4 * HEAD_TO_TABLE_DP) / 4.5f;
        if (headDp <= 0) return;
        int head = (int) (headDp * density);
        int headGap = (int) (HEAD_TO_TABLE_DP * density);
        int desk = (int) (headDp * TABLE_RATIO * density);
        int mid = w / 2;

        // 桌面居中
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) tableImg.getLayoutParams();
        tp.width = desk;
        tp.height = desk;
        tp.leftMargin = mid - desk / 2;
        tp.topMargin = mid - desk / 2;
        tableImg.setLayoutParams(tp);

        // 左玩家头像
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) leftSeat.getLayoutParams();
        lp.width = head;
        lp.height = head;
        lp.leftMargin = 0;
        lp.topMargin = mid - head / 2;
        leftSeat.setLayoutParams(lp);

        // 右玩家头像
        FrameLayout.LayoutParams rp = (FrameLayout.LayoutParams) rightSeat.getLayoutParams();
        rp.width = head;
        rp.height = head;
        rp.leftMargin = w - head;
        rp.topMargin = mid - head / 2;
        rightSeat.setLayoutParams(rp);

        // 上观众头像
        FrameLayout.LayoutParams tp2 = (FrameLayout.LayoutParams) topSeat.getLayoutParams();
        tp2.width = head;
        tp2.height = head;
        tp2.leftMargin = mid - head / 2;
        tp2.topMargin = 0;
        topSeat.setLayoutParams(tp2);

        // 下观众头像
        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bottomSeat.getLayoutParams();
        bp.width = head;
        bp.height = head;
        bp.leftMargin = mid - head / 2;
        bp.topMargin = h - head;
        bottomSeat.setLayoutParams(bp);
    }

    // 设置桌子状态与座位
    public void setState(boolean playing, boolean left, boolean right, boolean spectators,
                         boolean leftMale, boolean rightMale) {
        this.playing = playing;
        this.leftPlayer = left;
        this.rightPlayer = right;
        this.spectator = spectators;
        this.leftMale = leftMale;
        this.rightMale = rightMale;
        refresh();
    }

    // 便捷：仅设置占用（默认男性玩家）
    public void setOccupancy(boolean left, boolean right, boolean spectators) {
        setState(playing, left, right, spectators, true, true);
    }

    // 桌面中央显示房号/桌号
    public void setRoomLabel(String text) {
        labelView.setText(text);
        labelView.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
    }

    private void refresh() {
        if (tableImg != null) {
            tableImg.setImageResource(playing ? R.drawable.table_playing : R.drawable.table_idle);
        }
        if (leftSeat != null) {
            leftSeat.setImageResource(leftMale ? R.drawable.man : R.drawable.women);
            leftSeat.setAlpha(leftPlayer ? 1.0f : 0.25f); // 空位半透明预留
        }
        if (rightSeat != null) {
            rightSeat.setImageResource(rightMale ? R.drawable.man : R.drawable.women);
            rightSeat.setAlpha(rightPlayer ? 1.0f : 0.25f);
        }
        if (topSeat != null) {
            topSeat.setImageResource(R.drawable.viewers);
            topSeat.setAlpha(spectator ? 1.0f : 0.25f);
        }
        if (bottomSeat != null) {
            bottomSeat.setImageResource(R.drawable.viewers);
            bottomSeat.setAlpha(spectator ? 1.0f : 0.25f);
        }
    }

    // ============================================================
    // 横向一排桌子布局计算（手机 2 桌 / 平板 3 桌，固定行数）
    //   tableSide = 4.5×head + 16dp  组件边长（桌面 2.5head、两侧头像 2×head、间距 2×8dp）
    //   gap = head/2                  人与人 / 人与屏幕边距
    //   一排总宽 = n×tableSide + (n+1)×gap
    //           = head×(10n+1)/2 + 16n×dp  → 反解 head
    // ============================================================
    public static class RowLayout {
        public int tablesPerRow;   // 一排放几张（手机 2 / 平板 3）
        public int headSizePx;     // 头像边长（px）
        public int tableSidePx;    // 桌子组件边长（px）= 4.5A + 16dp（正方形）
        public int gapPx;          // 人与人 / 人与屏幕边距 = A/2
        public int deskSidePx;     // 桌面边长 = 2.5A
    }

    public static RowLayout computeRowLayout(int screenWidthPx, float density,
                                             int tablesPerRow, int minHeadDp, int maxDeskDp) {
        RowLayout r = new RowLayout();
        float headGapDp = HEAD_TO_TABLE_DP;

        float headDp = (screenWidthPx / density) - 16f * tablesPerRow;
        headDp = headDp * 2f / (10f * tablesPerRow + 1f);
        if (headDp < minHeadDp) headDp = minHeadDp;
        if (headDp * TABLE_RATIO > maxDeskDp) headDp = maxDeskDp / TABLE_RATIO;

        r.tablesPerRow = tablesPerRow;
        r.headSizePx = (int) (headDp * density);
        r.gapPx = r.headSizePx / 2;
        r.tableSidePx = (int) ((4.5f * headDp + 4 * headGapDp) * density);
        r.deskSidePx = (int) (headDp * TABLE_RATIO * density);
        return r;
    }
}