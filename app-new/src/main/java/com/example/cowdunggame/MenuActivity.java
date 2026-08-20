// MenuActivity.java (app-new 精简版)
// 登录后的主菜单。已实现入口：人机游戏大厅 / 人人游戏大厅。
//   （私人房间 后续重写，未实现前不留占位入口）
//   底部：注销登录。
package com.example.cowdunggame;

import android.app.Activity;
import android.content.Context;
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
import android.widget.Toast;

public class MenuActivity extends Activity {

    private SupabaseClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int screenH = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this);

        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

        TextView title = new TextView(this);
        title.setText("鲜花与牛粪");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(4), 2, 2, Color.BLACK);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, (int) (screenH * 0.08f));
        titleParams.topMargin = (int) (screenH * 0.05f);
        title.setLayoutParams(titleParams);
        root.addView(title);

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.CENTER_HORIZONTAL);
        int menuW = (int) (screenW * 0.72f);
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
            menuW, LinearLayout.LayoutParams.WRAP_CONTENT);
        menuParams.gravity = Gravity.CENTER;
        menu.setLayoutParams(menuParams);

        addMenuButton(menu, "人机游戏大厅", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MenuActivity.this, PvELobbyActivity.class));
            }
        });

        addMenuButton(menu, "人人游戏大厅", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MenuActivity.this, PvPLobbyActivity.class));
            }
        });
        root.addView(menu);

        Button btnLogout = new Button(this);
        btnLogout.setText("注销登录");
        btnLogout.setTextSize(14);
        btnLogout.setTextColor(Color.WHITE);
        btnLogout.setAllCaps(false);
        btnLogout.setBackground(btnBg());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            (int) (screenW * 0.4f), dp(44));
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = (int) (screenH * 0.06f);
        btnLogout.setLayoutParams(lp);
        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmLogout();
            }
        });
        root.addView(btnLogout);

        setContentView(root);

        client = new SupabaseClient(this);
        if (!client.hasSession()) {
            Intent intent = new Intent(MenuActivity.this, AuthActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }
    }

    private void addMenuButton(LinearLayout container, final String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(btnBg());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        if (container.getChildCount() > 0) bp.topMargin = dp(16);
        b.setLayoutParams(bp);
        b.setOnClickListener(click);
        container.addView(b);
    }

    private void confirmLogout() {
        AppDialog.confirm(this, "注销登录", "确定要退出当前账号吗？",
            "注销", "取消",
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (client != null) client.signOut();
                            getSharedPreferences("CowDungPrefs", Context.MODE_PRIVATE)
                                .edit().putBoolean("LoggedIn", false).apply();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Intent intent = new Intent(MenuActivity.this, AuthActivity.class);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    startActivity(intent);
                                    finish();
                                }
                            });
                        }
                    }).start();
                }
            },
            null).show();
    }

    private GradientDrawable btnBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(12));
        gd.setColor(0xAA202020);
        gd.setStroke(1, Color.parseColor("#88FFD700"));
        return gd;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}