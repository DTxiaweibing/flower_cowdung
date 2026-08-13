// LobbyActivity.java
// 游戏大厅（真人联网对战）：
//   手机竖屏一排 2 张桌子，平板/宽屏一排 3 张；向下滚动查看。
//   每桌：中心桌面图（空闲/对战中）+ 四边座位头像（玩家 man/women、观众 viewers）。
//   点击桌子弹出选择：坐下玩游戏 / 当观众。
//   当前数据为 mock（本地随机生成），后端接口接入点已预留。
package com.example.cowdunggame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;

public class LobbyActivity extends Activity {

    private static final int TABLE_ROWS = 15; // mock：15 行 × 每行桌数

    private final Random random = new Random();
    private int tablesPerRow = 2; // 手机默认 2，平板 3

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 平板判断：最小边 >= 600dp 视为平板/宽屏
        float minDp = Math.min(getResources().getDisplayMetrics().widthPixels,
            getResources().getDisplayMetrics().heightPixels) / getResources().getDisplayMetrics().density;
        tablesPerRow = (minDp >= 600f) ? 3 : 2;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // 顶部标题栏 + 返回
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#1C1C1C"));

        TextView title = new TextView(this);
        title.setText("游戏大厅");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(
            0, dp(48), 1f));
        topBar.addView(title);

        Button back = new Button(this);
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

        // 地面 + 桌子滚动区：地板铺底，桌子层用 FrameLayout 重叠盖在地板上（立即可见）
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 计算每桌尺寸与行高：屏宽减去两侧留边与桌间间隙后均分
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int edgeGap = dp(12);          // 屏幕左右留边
        int tableGap = dp(16);         // 桌与桌之间间隙
        int tableSize = (screenW - 2 * edgeGap - (tablesPerRow - 1) * tableGap) / tablesPerRow;
        int rowH = tableSize + dp(12); // 行高 = 桌高 + 行间距

        final int totalH = TABLE_ROWS * rowH + dp(12);

        FrameLayout scene = new FrameLayout(this);
        scene.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, totalH));
        scrollView.addView(scene);

        // 地板砖：铺满整个场景高度，只作背景
        final FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scene.addView(floor);

        // 桌面层：透明 FrameLayout，桌子绝对定位叠在地板上
        FrameLayout tableLayer = new FrameLayout(this);
        tableLayer.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tableLayer.setBackgroundColor(Color.TRANSPARENT);

        for (int row = 0; row < TABLE_ROWS; row++) {
            for (int col = 0; col < tablesPerRow; col++) {
                final int tableIndex = row * tablesPerRow + col;
                TableDishView table = new TableDishView(this);
                // mock 状态：随机对战中/空闲、左右玩家、观众
                boolean playing = random.nextInt(3) == 0;      // 约 1/3 对战中
                boolean left = random.nextBoolean();
                boolean right = random.nextBoolean();
                boolean spec = random.nextInt(4) == 0;          // 约 1/4 桌有观众
                boolean leftMale = random.nextBoolean();
                boolean rightMale = random.nextBoolean();
                table.setState(playing, left, right, spec, leftMale, rightMale);

                FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(tableSize, tableSize);
                tp.leftMargin = edgeGap + col * (tableSize + tableGap);
                tp.topMargin = row * rowH + dp(6);
                table.setLayoutParams(tp);
                table.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTableChoice(tableIndex);
                    }
                });
                tableLayer.addView(table);
            }
        }
        scene.addView(tableLayer);

        root.addView(scrollView);
        setContentView(root);
    }

    // 点击桌子：选择坐下玩游戏 / 当观众
    private void showTableChoice(final int tableIndex) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("桌 " + (tableIndex + 1));
        builder.setItems(new String[]{"坐下玩游戏", "当观众"},
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        Intent intent = new Intent(LobbyActivity.this, MainActivity.class);
                        intent.putExtra("source", "lobby");
                        startActivity(intent);
                    } else {
                        Toast.makeText(LobbyActivity.this,
                            "观战模式即将上线，敬请期待", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        builder.setNegativeButton("取消", null);
        builder.show();
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
