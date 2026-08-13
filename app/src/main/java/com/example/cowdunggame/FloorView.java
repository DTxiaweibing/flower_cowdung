// FloorView.java
// 游戏大厅地面：地板砖背景
// 以固定格宽的棋盘式地砖铺满，深色地面配浅色砖缝线，
// 相邻砖块用轻微色差模拟真实地板效果。纯代码绘制，不依赖图片。
package com.example.cowdunggame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class FloorView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 地砖边宽（px）
    private final int tileSize;

    private static final int BASE = 0xFF9FC3D8;      // 浅蓝地面
    private static final int BASE_ALT = 0xFFB4D4E6;  // 相邻砖微亮
    private static final int LINE = 0xFF7DA7BE;      // 砖缝

    public FloorView(Context context) {
        super(context);
        tileSize = (int) (56 * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 每块砖：整砖铺底色（半偏移棋盘布局），再交错填补空隙砖
        // 先铺满基准色
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BASE);
        canvas.drawRect(0, 0, w, h, paint);

        // 交错棋盘：每行砖缝错开半格，形成斜向交错的真实地板
        int rows = h / tileSize + 1;
        int cols = w / tileSize + 1;
        for (int r = 0; r < rows; r++) {
            int offset = (r % 2 == 0) ? 0 : -(tileSize / 2);
            for (int c = 0; c < cols + 1; c++) {
                int x = c * tileSize + offset;
                int y = r * tileSize;
                paint.setColor(((r + c) % 2 == 0) ? BASE_ALT : BASE);
                canvas.drawRect(x, y, x + tileSize, y + tileSize, paint);
            }
        }

        // 砖缝线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(LINE);
        for (int r = 0; r <= rows; r++) {
            int offset = (r % 2 == 0) ? 0 : -(tileSize / 2);
            canvas.drawLine(offset, r * tileSize, w, r * tileSize, paint);
            for (int c = 0; c <= cols + 1; c++) {
                int x = c * tileSize + offset;
                canvas.drawLine(x, r * tileSize, x, Math.min(r * tileSize + tileSize, h), paint);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
    }
}