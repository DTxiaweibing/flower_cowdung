// TableDishView.java
// 游戏大厅「一张桌子」：俯视图
//   中心木桌（斜 45 度矩形）
//   左、右两张椅子面对面：下棋的人（代码绘制小人，区分有人/空位）
//   上、下两条凳子面对面：观众（有观众时两条凳子各坐两个小人）
// 纯代码绘制，不依赖图片；是否有人由数据驱动。
package com.example.cowdunggame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

public class TableDishView extends View {

    // 座位状态：true = 有人，false = 空位
    private boolean leftPlayer = false;
    private boolean rightPlayer = false;
    private boolean spectator = false;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TableDishView(Context context) {
        super(context);
    }

    // 数据驱动：设置左右下棋人、观众是否有人
    public void setOccupancy(boolean left, boolean right, boolean spectators) {
        this.leftPlayer = left;
        this.rightPlayer = right;
        this.spectator = spectators;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;

        // 中心木桌：旋转 45 度的正方形（菱形），棕木色
        float tableSize = Math.min(w, h) * 0.52f;
        paint.setColor(Color.parseColor("#8B5A2B"));
        paint.setStyle(Paint.Style.FILL);
        Path table = new Path();
        table.moveTo(cx, cy - tableSize / 2f);          // 上角
        table.lineTo(cx + tableSize / 2f, cy);           // 右角
        table.lineTo(cx, cy + tableSize / 2f);           // 下角
        table.lineTo(cx - tableSize / 2f, cy);           // 左角
        table.close();
        canvas.drawPath(table, paint);
        // 桌面描边
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(Color.parseColor("#5D3A1A"));
        canvas.drawPath(table, paint);

        // 凳子（上/下，观众）：长条矩形，浅木色
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#A9825B"));
        float benchW = w * 0.7f;
        float benchH = h * 0.13f;
        // 上凳
        RectF topBench = new RectF(cx - benchW / 2f, h * 0.02f, cx + benchW / 2f, h * 0.02f + benchH);
        canvas.drawRoundRect(topBench, dp(3), dp(3), paint);
        // 下凳
        RectF bottomBench = new RectF(cx - benchW / 2f, h - h * 0.02f - benchH, cx + benchW / 2f, h - h * 0.02f);
        canvas.drawRoundRect(bottomBench, dp(3), dp(3), paint);

        // 椅子（左/右，下棋人）：靠桌子两侧，深棕
        paint.setColor(Color.parseColor("#6B4226"));
        float chairW = w * 0.15f;
        float chairH = h * 0.22f;
        RectF leftChair = new RectF(w * 0.02f, cy - chairH / 2f, w * 0.02f + chairW, cy + chairH / 2f);
        RectF rightChair = new RectF(w - w * 0.02f - chairW, cy - chairH / 2f, w - w * 0.02f, cy + chairH / 2f);
        canvas.drawRoundRect(leftChair, dp(3), dp(3), paint);
        canvas.drawRoundRect(rightChair, dp(3), dp(3), paint);

        // 观众（上/下凳子上，各坐两个小人）
        if (spectator) {
            drawPerson(canvas, cx - benchW * 0.22f, topBench.centerY(), dp(7), Color.parseColor("#E53935"));
            drawPerson(canvas, cx + benchW * 0.22f, topBench.centerY(), dp(7), Color.parseColor("#FB8C00"));
            drawPerson(canvas, cx - benchW * 0.22f, bottomBench.centerY(), dp(7), Color.parseColor("#E53935"));
            drawPerson(canvas, cx + benchW * 0.22f, bottomBench.centerY(), dp(7), Color.parseColor("#FB8C00"));
        }

        // 下棋人（左/右椅子上）
        if (leftPlayer) {
            drawPerson(canvas, leftChair.centerX(), leftChair.top - dp(8), dp(8), Color.parseColor("#1E88E5"));
        }
        if (rightPlayer) {
            drawPerson(canvas, rightChair.centerX(), rightChair.top - dp(8), dp(8), Color.parseColor("#43A047"));
        }
    }

    // 代码绘制小人：圆头 + 圆角矩形身体
    private void drawPerson(Canvas canvas, float x, float y, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        // 头
        canvas.drawCircle(x, y, size, paint);
        // 身体
        RectF body = new RectF(x - size * 0.8f, y + size * 0.7f, x + size * 0.8f, y + size * 2.1f);
        canvas.drawRoundRect(body, size * 0.4f, size * 0.4f, paint);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
