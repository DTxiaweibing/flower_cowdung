// MenuActivity.java
// 启动页(主菜单)：背景地板砖 + 斜放装饰桌子，画面中央浮三个大选项
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public class MenuActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);

        // 背景：地板砖
        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

        // 斜放装饰桌子：背景铺几张，营造大厅氛围（纯装饰，不可点击）
        int[] offsets = {35, 62, 12};
        for (int i = 0; i < 3; i++) {
            TableDishView table = new TableDishView(this);
            table.setOccupancy(true, true, true);
            int size = dp(110);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(size, size);
            tp.gravity = Gravity.CENTER;
            tp.topMargin = dp(offsets[i]) - size / 2;
            table.setLayoutParams(tp);
            root.addView(table);
        }

        // 顶部标题
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("鲜花与牛粪");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(4), 2, 2, Color.BLACK);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(56));
        titleParams.topMargin = dp(80);
        title.setLayoutParams(titleParams);
        root.addView(title);

        // 三个大按钮：垂直排列于中央偏下
        LinearLayout menuList = new LinearLayout(this);
        menuList.setOrientation(LinearLayout.VERTICAL);
        menuList.setGravity(Gravity.CENTER);


        int btnH = dp(58);
        int btnGap = dp(24);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            dp(240), LinearLayout.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(170);
        menuList.setLayoutParams(listParams);

        String[] labels = {"人机对战", "游戏大厅", "私人房间"};
        for (String label : labels) {
            Button b = new Button(this);
            b.setText(label);
            b.setTextSize(20);
            b.setTextColor(Color.WHITE);
            b.setAllCaps(false);
            b.setBackground(goldBg());
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, btnH);
            if (label.equals(labels[0])) {
                bp.topMargin = 0;
            } else {
                bp.topMargin = btnGap;
            }
            b.setLayoutParams(bp);
            b.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    String label = ((Button) v).getText().toString();
                    Class<?> target = "人机对战".equals(label) ? MainActivity.class
                        : "游戏大厅".equals(label) ? LobbyActivity.class : PrivateRoomActivity.class;
                    startActivity(new Intent(MenuActivity.this, target));
                }
            });
            menuList.addView(b);
        }
        root.addView(menuList);

        setContentView(root);
    }

    private GradientDrawable goldBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(12));
        gd.setColor(0xCC1C1C1C);
        gd.setStroke(2, Color.parseColor("#FFD700"));
        return gd;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}