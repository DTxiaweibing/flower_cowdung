// PrivateRoomActivity.java
// 私人房间：约朋友对战
//   进入后生成房间号并等待朋友加入；房间号未来由服务端分配，
//   当前为 mock（本地随机生成）。朋友加入后即可开局（后端接入点已预留）。
package com.example.cowdunggame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;

public class PrivateRoomActivity extends Activity {

    private final Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 背景：地板砖（房间内同样铺地砖，营造统一氛围）
        FrameLayout root = new FrameLayout(this);
        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

        // 中央浅色面板
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(roundedBg(0xE61C1C1C));
        panel.setPadding(dp(24), dp(28), dp(24), dp(28));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
            dp(280), LinearLayout.LayoutParams.WRAP_CONTENT);
        panelParams.topMargin = dp(120);
        panel.setLayoutParams(panelParams);

        TextView panelTitle = new TextView(this);
        panelTitle.setText("私人房间");
        panelTitle.setTextSize(22);
        panelTitle.setTextColor(Color.parseColor("#FFD700"));
        panelTitle.setGravity(Gravity.CENTER);
        panel.addView(panelTitle);

        // 房间号
        int roomId = 10000 + random.nextInt(90000);
        TextView roomNo = new TextView(this);
        roomNo.setText(String.format(Locale.US, "房间号 %05d", roomId));
        roomNo.setTextSize(34);
        roomNo.setTextColor(Color.WHITE);
        roomNo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams roomParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        roomParams.topMargin = dp(24);
        roomNo.setLayoutParams(roomParams);
        panel.addView(roomNo);

        // 房间内的桌子：空桌，左右可坐两位玩家（本人已就座）
        TableDishView table = new TableDishView(this);
        android.content.SharedPreferences sp =
            getSharedPreferences("CowDungPrefs", android.content.Context.MODE_PRIVATE);
        boolean selfMale = "male".equals(sp.getString("PlayerGender", "male"));
        table.setState(false, true, false, false, selfMale, true);
        table.setClickable(true);
        LinearLayout.LayoutParams tableParams = new LinearLayout.LayoutParams(dp(160), dp(160));
        tableParams.gravity = Gravity.CENTER_HORIZONTAL;
        tableParams.topMargin = dp(16);
        table.setLayoutParams(tableParams);
        table.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRoomTableChoice();
            }
        });
        panel.addView(table);

        // 等待提示 + 转圈
        ProgressBar waiting = new ProgressBar(this);
        waiting.setIndeterminate(true);
        LinearLayout.LayoutParams waitParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        waitParams.topMargin = dp(20);
        waitParams.gravity = Gravity.CENTER_HORIZONTAL;
        waiting.setLayoutParams(waitParams);
        panel.addView(waiting);

        TextView waitText = new TextView(this);
        waitText.setText("等待朋友加入...");
        waitText.setTextSize(16);
        waitText.setTextColor(Color.WHITE);
        waitText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams txtParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        txtParams.topMargin = dp(8);
        waitText.setLayoutParams(txtParams);
        panel.addView(waitText);

        // 操作按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        rowParams.topMargin = dp(20);
        btnRow.setLayoutParams(rowParams);

        Button leave = new Button(this);
        leave.setText("退出房间");
        leave.setTextSize(15);
        leave.setTextColor(Color.WHITE);
        leave.setAllCaps(false);
        leave.setBackground(roundedBg(0xFFB71C1C));
        LinearLayout.LayoutParams lParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lParams.setMargins(dp(4), 0, dp(4), 0);
        leave.setLayoutParams(lParams);
        leave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnRow.addView(leave);

        Button invite = new Button(this);
        invite.setText("邀请朋友");
        invite.setTextSize(15);
        invite.setTextColor(Color.WHITE);
        invite.setAllCaps(false);
        invite.setBackground(roundedBg(0xFF1E88E5));
        LinearLayout.LayoutParams iParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        iParams.setMargins(dp(4), 0, dp(4), 0);
        invite.setLayoutParams(iParams);
        invite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.widget.Toast.makeText(PrivateRoomActivity.this,
                    "复制房间号发给朋友即可加入", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(invite);

        panel.addView(btnRow);

        root.addView(panel);
        setContentView(root);
    }

    private void showRoomTableChoice() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择");
        builder.setItems(new String[]{"坐下玩游戏", "当观众"},
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        Intent intent = new Intent(PrivateRoomActivity.this, MainActivity.class);
                        intent.putExtra("source", "private");
                        startActivity(intent);
                    } else {
                        android.widget.Toast.makeText(PrivateRoomActivity.this,
                            "观战模式即将上线，敬请期待", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private GradientDrawable roundedBg(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(14));
        gd.setColor(color);
        gd.setStroke(2, Color.parseColor("#FFD700"));
        return gd;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}