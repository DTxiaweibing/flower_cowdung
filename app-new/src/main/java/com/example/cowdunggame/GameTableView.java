// GameTableView.java
// 一张桌子：整桌图形渲染，含桌面 + 四周座位头像 + 桌号。可复用于：人机大厅 / 人人大厅 / 私人房间。
//
// 布局几何（唯一变量 = 头像边长 head，其余由规格常量派生，不自洽则不可能显示正确）：
//   规格：head = 头像边长；GAP = 头像到桌面间距 8dp；RATIO = 桌面/头像 = 2.5
//   一张卡片边长 CARD = 2*head + 2*GAP + RATIO*head = 4.5*head + 16dp   （正方形）
//   空(留白) = 0.5*head
//   卡内落位（桌面居中，间隙恰 8dp，互不覆盖）：
//     左座位 0..head；桌面左缘 = (CARD - desk)/2 = head + 8dp
//     右座位 贴右缘；桌面右缘 = CARD - (head + 8dp)
//     上下座位贴上下缘，水平居中于卡
package com.example.cowdunggame;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public class GameTableView extends FrameLayout {

    // 规格常量
    public static final float HEAD_GAP_DP = 8f;    // 头像到桌面间距（dp）
    public static final float TABLE_RATIO = 2.5f;  // 桌面边长 / 头像边长
    public static final float BLANK_RATIO = 0.5f;  // 空(留白) = 0.5*head

    private final LayoutInfo layout;

    private ImageView deskView;
    private ImageView leftSeat;
    private ImageView rightSeat;
    private ImageView topSeat;
    private ImageView bottomSeat;
    private TextView labelView;
    private TextView leftNickView;

    // 状态
    private boolean playing = false;
    private boolean leftOccupied = false;
    private boolean rightOccupied = false;
    private boolean spectators = false;
    private boolean leftMale = true;
    private boolean rightMale = true;
    private boolean botRight = false; // 右侧固定 AIBOT 头像
    private String leftNick = "";   // 左座玩家昵称（头像下方显示）

    // ============================================================
    // 行布局计算：一行 perRow 桌横向铺满屏宽，反解唯一变量 head
    //   一行总宽 = perRow*CARD + (perRow+1)*BLANK
    //           = perRow*(4.5h+16dp) + (perRow+1)*0.5h
    //           = h*(5*perRow + 0.5) + perRow*16dp
    //    -> head = (screenW - perRow*16dp) / (5*perRow + 0.5)
    // ============================================================
    public static class LayoutInfo {
        public int headPx;      // 头像边长
        public int deskPx;      // 桌面边长 = 2.5*head
        public int cardSidePx;  // 卡片边长 = 4.5*head + 16dp
        public int blankPx;     // 空 = 0.5*head
        public int roomPadTop;  // 网格顶部留白
        public int roomPadBottom; // 网格底部留白
    }

    public static LayoutInfo computeLayout(int screenW, int screenH, float density, int perRow) {
        LayoutInfo li = new LayoutInfo();
        // head = (screenWdp - perRow*2*GAP) / (5*perRow + 0.5)
        float headDp = (screenW / density - perRow * 2 * HEAD_GAP_DP) / (5f * perRow + 0.5f);
        li.headPx = (int) (headDp * density);
        li.cardSidePx = (int) ((4.5f * headDp + 2 * HEAD_GAP_DP) * density); // 4.5h + 2*8dp
        li.deskPx = (int) (li.headPx * TABLE_RATIO);
        li.blankPx = (int) (li.headPx * BLANK_RATIO);
        li.roomPadTop = (int) (screenH * 0.02f);
        li.roomPadBottom = (int) (screenH * 0.10f);
        return li;
    }

    public GameTableView(Context context, LayoutInfo layout) {
        super(context);
        this.layout = layout;
        setBackgroundColor(Color.TRANSPARENT);

        // 桌卡组件尺寸：正方形
        FrameLayout.LayoutParams self = new FrameLayout.LayoutParams(
            layout.cardSidePx, layout.cardSidePx);
        setLayoutParams(self);

        int head = layout.headPx;
        int desk = layout.deskPx;
        int mid = layout.cardSidePx / 2;

        // 桌面图（中央）
        deskView = new ImageView(context);
        deskView.setScaleType(ImageView.ScaleType.FIT_XY);
        deskView.setImageResource(R.drawable.table_idle);
        FrameLayout.LayoutParams deskP = new FrameLayout.LayoutParams(desk, desk);
        deskP.leftMargin = (layout.cardSidePx - desk) / 2; // = head + 8dp
        deskP.topMargin = (layout.cardSidePx - desk) / 2;
        deskView.setLayoutParams(deskP);
        addView(deskView);

        // 桌号（桌面中央，默认隐藏）
        labelView = new TextView(context);
        labelView.setTextColor(Color.WHITE);
        labelView.setTextSize(14);
        labelView.setGravity(Gravity.CENTER);
        labelView.setShadowLayer(4, 2, 2, Color.BLACK);
        FrameLayout.LayoutParams labelP = new FrameLayout.LayoutParams(
            layout.cardSidePx, layout.cardSidePx);
        labelView.setLayoutParams(labelP);
        labelView.setVisibility(GONE);
        addView(labelView);

        // 左座位（贴左缘，垂直居中）
        leftSeat = makeSeat();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(head, head);
        lp.leftMargin = 0;
        lp.topMargin = mid - head / 2;
        leftSeat.setLayoutParams(lp);
        addView(leftSeat);

        // 左座位昵称（头像正下方，仅玩家显示，默认隐藏）
        leftNickView = new TextView(context);
        leftNickView.setTextColor(Color.BLACK);
        leftNickView.setTextSize(10);
        leftNickView.setGravity(Gravity.CENTER);
        leftNickView.setSingleLine(true);
        leftNickView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        leftNickView.setShadowLayer(2, 1, 1, Color.WHITE);
        FrameLayout.LayoutParams nickP = new FrameLayout.LayoutParams(
            head, (int) (14 * context.getResources().getDisplayMetrics().density));
        nickP.leftMargin = 0;
        nickP.topMargin = mid + head / 2;
        leftNickView.setLayoutParams(nickP);
        leftNickView.setVisibility(GONE);
        addView(leftNickView);

        // 右座位（贴右缘，垂直居中）
        rightSeat = makeSeat();
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(head, head);
        rp.leftMargin = layout.cardSidePx - head;
        rp.topMargin = mid - head / 2;
        rightSeat.setLayoutParams(rp);
        addView(rightSeat);

        // 上座位（贴上缘，水平居中）
        topSeat = makeSeat();
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(head, head);
        tp.leftMargin = mid - head / 2;
        tp.topMargin = 0;
        topSeat.setLayoutParams(tp);
        addView(topSeat);

        // 下座位（贴下缘，水平居中）
        bottomSeat = makeSeat();
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(head, head);
        bp.leftMargin = mid - head / 2;
        bp.topMargin = layout.cardSidePx - head;
        bottomSeat.setLayoutParams(bp);
        addView(bottomSeat);

        refresh();
    }

    private ImageView makeSeat() {
        ImageView seat = new ImageView(getContext());
        seat.setScaleType(ImageView.ScaleType.FIT_CENTER);
        seat.setVisibility(VISIBLE); // 占位始终可见（预留位置）
        return seat;
    }

    // ============================================================
    // 状态设置（可复用接口）
    // ============================================================
    public void setPlaying(boolean p) {
        this.playing = p;
        refresh();
    }

    // 左侧是否有玩家（影响是否点亮头像）
    public void setLeftOccupancy(boolean occupied, boolean male) {
        this.leftOccupied = occupied;
        this.leftMale = male;
        refresh();
    }

    // 右侧是否有玩家；bot=true 时固定显示 AIBOT 头像
    public void setRightOccupancy(boolean occupied, boolean bot, boolean male) {
        this.rightOccupied = occupied;
        this.rightMale = male;
        this.botRight = bot;
        refresh();
    }

    // 上下观众位
    public void setSpectators(boolean occupied) {
        this.spectators = occupied;
        refresh();
    }

    // 便捷：整桌状态一次设置
    public void setState(boolean playing, boolean left, boolean right, boolean spectators,
                         boolean leftMale, boolean rightMale, boolean botRight) {
        this.playing = playing;
        this.leftOccupied = left;
        this.rightOccupied = right;
        this.spectators = spectators;
        this.leftMale = leftMale;
        this.rightMale = rightMale;
        this.botRight = botRight;
        refresh();
    }

    // 桌面中央显示桌号（null/空则隐藏）
    public void setTableNo(String text) {
        labelView.setText(text);
        labelView.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
    }

    // 左座玩家昵称（头像正下方；只有该座有玩家时才显示，观众位不做昵称）
    public void setPlayerLabel(String nick) {
        this.leftNick = nick;
        refresh();
    }

    private void refresh() {
        if (deskView != null) {
            deskView.setImageResource(playing ? R.drawable.table_playing : R.drawable.table_idle);
        }
        if (leftNickView != null) {
            if (leftOccupied && leftNick != null && !leftNick.isEmpty()) {
                leftNickView.setText(leftNick);
                leftNickView.setVisibility(VISIBLE);
            } else {
                leftNickView.setVisibility(GONE);
            }
        }
        if (leftSeat != null) {
            if (leftOccupied) {
                leftSeat.setImageResource(leftMale ? R.drawable.man : R.drawable.women);
                leftSeat.setAlpha(1.0f);
            } else {
                leftSeat.setImageResource(R.drawable.avatar_player_empty);
            }
        }
        if (rightSeat != null) {
            if (botRight) {
                rightSeat.setImageResource(R.drawable.avatar_bot);
                rightSeat.setAlpha(1.0f);
            } else if (rightOccupied) {
                rightSeat.setImageResource(rightMale ? R.drawable.man : R.drawable.women);
                rightSeat.setAlpha(1.0f);
            } else {
                rightSeat.setImageResource(R.drawable.avatar_player_empty);
            }
        }
        if (topSeat != null) {
            if (spectators) {
                topSeat.setImageResource(R.drawable.viewers);
                topSeat.setAlpha(1.0f);
            } else {
                topSeat.setImageResource(R.drawable.avatar_player_empty);
            }
        }
        if (bottomSeat != null) {
            if (spectators) {
                bottomSeat.setImageResource(R.drawable.viewers);
                bottomSeat.setAlpha(1.0f);
            } else {
                bottomSeat.setImageResource(R.drawable.avatar_player_empty);
            }
        }
    }
}