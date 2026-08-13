// TableDishView.java
// 游戏大厅「一张桌子」：俯视图
//   中心：桌面图片（空闲桌 table_idle / 对战中 table_playing）
//   四边座位：左右两张椅子（玩家头像 man/women）、上下两条凳子（观众头像 viewers）
//   头像直接显示，空位不显示；状态由数据驱动。
package com.example.cowdunggame;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class TableDishView extends FrameLayout {

    private ImageView tableImg;
    private ImageView leftSeat;
    private ImageView rightSeat;
    private ImageView topSeat;
    private ImageView bottomSeat;

    // 状态
    private boolean playing = false;
    private boolean leftPlayer = false;
    private boolean rightPlayer = false;
    private boolean spectator = false;
    private boolean leftMale = true;
    private boolean rightMale = true;

    public TableDishView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);

        // 中心桌面图：占满整个组件（桌面图本身含四边空间）
        tableImg = new ImageView(context);
        tableImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tableImg.setImageResource(R.drawable.table_idle);
        FrameLayout.LayoutParams tableParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        tableImg.setLayoutParams(tableParams);
        addView(tableImg);

        // 四边头像：左右(玩家) / 上下(观众)，桌面留出边缘给座位
        leftSeat = makeSeat(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        rightSeat = makeSeat(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        topSeat = makeSeat(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bottomSeat = makeSeat(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);

        addView(leftSeat);
        addView(rightSeat);
        addView(topSeat);
        addView(bottomSeat);

        refresh();
    }

    private ImageView makeSeat(int gravity) {
        ImageView seat = new ImageView(getContext());
        seat.setScaleType(ImageView.ScaleType.FIT_CENTER);
        seat.setVisibility(GONE);
        // 头像大小：占桌面宽度的 18%，贴边放置
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, 0, gravity);
        // 具体尺寸在 onSizeChanged 后按比例设置
        seat.setTag(gravity);
        seat.setLayoutParams(lp);
        return seat;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int seatSize = (int) (Math.min(w, h) * 0.20f);
        FrameLayout.LayoutParams lpLeft = (FrameLayout.LayoutParams) leftSeat.getLayoutParams();
        lpLeft.width = seatSize;
        lpLeft.height = seatSize;
        lpLeft.leftMargin = (int) (w * 0.02f);
        leftSeat.setLayoutParams(lpLeft);

        FrameLayout.LayoutParams lpRight = (FrameLayout.LayoutParams) rightSeat.getLayoutParams();
        lpRight.width = seatSize;
        lpRight.height = seatSize;
        lpRight.rightMargin = (int) (w * 0.02f);
        rightSeat.setLayoutParams(lpRight);

        FrameLayout.LayoutParams lpTop = (FrameLayout.LayoutParams) topSeat.getLayoutParams();
        lpTop.width = seatSize;
        lpTop.height = seatSize;
        lpTop.topMargin = (int) (h * 0.02f);
        topSeat.setLayoutParams(lpTop);

        FrameLayout.LayoutParams lpBottom = (FrameLayout.LayoutParams) bottomSeat.getLayoutParams();
        lpBottom.width = seatSize;
        lpBottom.height = seatSize;
        lpBottom.bottomMargin = (int) (h * 0.02f);
        bottomSeat.setLayoutParams(lpBottom);
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

    private void refresh() {
        if (tableImg != null) {
            tableImg.setImageResource(playing ? R.drawable.table_playing : R.drawable.table_idle);
        }
        if (leftSeat != null) {
            leftSeat.setImageResource(leftMale ? R.drawable.man : R.drawable.women);
            leftSeat.setVisibility(leftPlayer ? VISIBLE : GONE);
        }
        if (rightSeat != null) {
            rightSeat.setImageResource(rightMale ? R.drawable.man : R.drawable.women);
            rightSeat.setVisibility(rightPlayer ? VISIBLE : GONE);
        }
        if (topSeat != null) {
            topSeat.setImageResource(R.drawable.viewers);
            topSeat.setVisibility(spectator ? VISIBLE : GONE);
        }
        if (bottomSeat != null) {
            bottomSeat.setImageResource(R.drawable.viewers);
            bottomSeat.setVisibility(spectator ? VISIBLE : GONE);
        }
    }
}
