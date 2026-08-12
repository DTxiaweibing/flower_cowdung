// LobbyActivity.java
// 游戏大厅（真人联网对战）：
//   竖屏一排 3 桌，向下错位斜放滚动查看，每桌显示椅凳 + 小人/观众。
//   当前数据为 mock（本地随机生成），后端接口接入点已预留。
// 说明：行与行之间错位半桌，形成斜向摆放的视觉效果，充分利用窄屏宽度。
package com.example.cowdunggame;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.Random;

public class LobbyActivity extends Activity {

    private static final int TABLES_PER_ROW = 3;
    private static final int TABLE_ROWS = 20; // mock：20 行 × 3 桌 = 60 桌

    private final Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // 顶部标题栏 + 返回
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#1C1C1C"));

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("游戏大厅");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(
            0, dp(48), 1f));
        topBar.addView(title);

        android.widget.Button back = new android.widget.Button(this);
        back.setText("返回");
        back.setTextSize(14);
        back.setTextColor(Color.WHITE);
        back.setAllCaps(false);
        back.setBackground(roundedBg(0xFFB71C1C));
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(64), dp(36));
        backParams.setMargins(dp(8), 0, dp(8), 0);
        back.setLayoutParams(backParams);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        topBar.addView(back);
        root.addView(topBar);

        // 地面 + 桌子滚动区
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout floorWrap = new LinearLayout(this);
        floorWrap.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(floorWrap);

        // 地板砖：铺满整个滚动区背景（背景 View 高度跟随内容）
        final FloorView floor = new FloorView(this);
        floor.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, TABLE_ROWS * (dp(150))));
        floorWrap.addView(floor);

        // 桌面层：用 FrameLayout 把桌子叠在地板上
        android.widget.FrameLayout tableLayer = new android.widget.FrameLayout(this);
        tableLayer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, TABLE_ROWS * dp(150)));
        tableLayer.setBackgroundColor(Color.TRANSPARENT);

        int cellW = getResources().getDisplayMetrics().widthPixels / TABLES_PER_ROW;

        for (int row = 0; row < TABLE_ROWS; row++) {
            int rowOffset = (row % 2 == 0) ? 0 : (int) (cellW * 0.5f); // 错位斜放
            for (int col = 0; col < TABLES_PER_ROW; col++) {
                TableDishView table = new TableDishView(this);
                // mock 状态：随机是否有人/观众
                boolean left = random.nextBoolean();
                boolean right = random.nextBoolean();
                boolean spec = random.nextInt(4) == 0; // 约 1/4 桌有观众
                table.setOccupancy(left, right, spec);

                int size = (int) (cellW * 0.86f);
                android.widget.FrameLayout.LayoutParams tp =
                    new android.widget.FrameLayout.LayoutParams(size, size);
                tp.leftMargin = rowOffset + col * cellW + (cellW - size) / 2;
                tp.topMargin = row * dp(150) + (dp(150) - size) / 2;
                table.setLayoutParams(tp);
                tableLayer.addView(table);
            }
        }
        floorWrap.addView(tableLayer);

        root.addView(scrollView);

        setContentView(root);
    }

    private android.graphics.drawable.GradientDrawable roundedBg(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(8));
        gd.setColor(color);
        return gd;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
