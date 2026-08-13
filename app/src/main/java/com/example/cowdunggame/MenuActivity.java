// MenuActivity.java
// 启动页(主菜单)：打开即游戏大厅画面
//   背景：地板砖 + 铺满厅内此刻的桌子（有对战的、空的和带观众的），
//   画面中央悬浮三个大按钮：人机对战 / 游戏大厅 / 私人房间
//   人机对战 -> MainActivity（直接进棋盘，不画桌子）
//   游戏大厅 -> LobbyActivity（真人联网，一排 3 桌滚动）
//   私人房间 -> PrivateRoomActivity（创建房间等朋友）
package com.example.cowdunggame;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MenuActivity extends Activity {

    // 初始大厅画面的桌子布局：3 行 × 3 桌
    private static final int COLS = 3;
    private static final int ROWS = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int screenH = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this);

        // 背景：地板砖
        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

        // 大厅桌子：铺满整个屏，部分有人对战、部分空、部分带观众
        int cellW = screenW / COLS;
        int cellH = screenH / (ROWS + 1); // 顶部留标题空间
        int size = (int) (cellW * 0.86f);
        int idx = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                idx++;
                TableDishView table = new TableDishView(this);
                // 交错：部分桌有人在下棋，部分空桌，偶尔有观众
                if (idx % 3 == 0) {
                    table.setOccupancy(true, true, true);      // 对战 + 观众
                } else if (idx % 3 == 2) {
                    table.setOccupancy(true, true, false);     // 对战无观众
                } else {
                    table.setOccupancy(false, false, false);   // 空桌
                }
                FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(size, size);
                tp.leftMargin = col * cellW + (cellW - size) / 2;
                tp.topMargin = (int) (cellH * 0.25f) + row * cellH - size / 2;
                table.setLayoutParams(tp);
                root.addView(table);
            }
        }

        // 顶部标题
        TextView title = new TextView(this);
        title.setText("鲜花与牛粪");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(4), 2, 2, Color.BLACK);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(48));
        titleParams.topMargin = dp(16);
        title.setLayoutParams(titleParams);
        root.addView(title);

        // 半透明面板 + 三个大按钮：悬浮在整个大厅画面之上
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(panelBg());
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));

        final int btnH = dp(54);
        final int btnGap = dp(14);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
            dp(230), LinearLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER;
        panelParams.topMargin = dp(30);
        panel.setLayoutParams(panelParams);

        String[] labels = {"人机对战", "游戏大厅", "私人房间"};
        for (String label : labels) {
            Button b = new Button(this);
            b.setText(label);
            b.setTextSize(20);
            b.setTextColor(Color.WHITE);
            b.setAllCaps(false);
            b.setBackground(btnBg());
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, btnH);
            if (!label.equals(labels[0])) {
                bp.topMargin = btnGap;
            }
            b.setLayoutParams(bp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String label = ((Button) b).getText().toString();
                    Intent intent;
                    if ("人机对战".equals(label)) {
                        intent = new Intent(MenuActivity.this, MainActivity.class);
                        intent.putExtra("source", "pve");
                    } else if ("游戏大厅".equals(label)) {
                        intent = new Intent(MenuActivity.this, LobbyActivity.class);
                    } else {
                        intent = new Intent(MenuActivity.this, PrivateRoomActivity.class);
                    }
                    startActivity(intent);
                }
            });
            panel.addView(b);
        }
        root.addView(panel);

        setContentView(root);
    }

    // 半透明黑色面板，让按钮悬浮于大厅之上且能看到后面的桌子
    private GradientDrawable panelBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(16));
        gd.setColor(0xCC1C1C1C);
        gd.setStroke(2, Color.parseColor("#FFD700"));
        return gd;
    }

    private GradientDrawable btnBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(12));
        gd.setColor(0xFF2D2D2D);
        gd.setStroke(2, Color.parseColor("#FFD700"));
        return gd;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}