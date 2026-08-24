// MenuActivity.java (app-new 精简版)
// 登录后的主菜单。已实现入口：人机游戏大厅 / 人人游戏大厅 / 私密房间。
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
    private SeatManager seatManager;

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

        // 初始场景：与私密房间同款大桌卡，复用 GameTableView。
        // 游戏中的桌面（table_playing），左座男、右座女、上下观众满员，纯装饰不可点。
        float density = getResources().getDisplayMetrics().density;
        GameTableView.LayoutInfo sceneLayout =
            GameTableView.computeLayout(screenW, screenH, density, 1);
        GameTableView sceneTable = new GameTableView(this, sceneLayout);
        FrameLayout.LayoutParams sceneParams = new FrameLayout.LayoutParams(
            sceneLayout.cardSidePx, sceneLayout.cardSidePx);
        sceneParams.gravity = Gravity.CENTER;
        sceneTable.setLayoutParams(sceneParams);
        sceneTable.setState(true, true, true, true, true, false, false);
        root.addView(sceneTable);

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

        // 底部按钮：2×2 网格，尺寸/间距全部按屏幕比例用权重分配，保证各设备位置基本一致
        final int menuW = (int) (screenW * 0.92f);
        final int menuH = (int) (screenH * 0.20f);   // 两行按钮约占屏高 20%
        final int gap = (int) (screenW * 0.03f);     // 行/列间距随屏宽等比

        LinearLayout bottomMenu = new LinearLayout(this);
        bottomMenu.setOrientation(LinearLayout.VERTICAL);
        bottomMenu.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomMenu.setWeightSum(2f);
        FrameLayout.LayoutParams bmParams = new FrameLayout.LayoutParams(menuW, menuH);
        bmParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bmParams.bottomMargin = (int) (screenH * 0.03f);
        bottomMenu.setLayoutParams(bmParams);

        LinearLayout row1 = newMenuRow(gap, false);
        LinearLayout row2 = newMenuRow(gap, true);

        addMenuButton(row1, "人机游戏大厅", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MenuActivity.this, PvELobbyActivity.class));
            }
        }, gap);

        addMenuButton(row1, "人人游戏大厅", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MenuActivity.this, PvPLobbyActivity.class));
            }
        }, gap);

        addMenuButton(row2, "私密房间", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPrivateRoomDialog();
            }
        }, gap);

        addMenuButton(row2, "注销登录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmLogout();
            }
        }, gap);

        bottomMenu.addView(row1);
        bottomMenu.addView(row2);
        root.addView(bottomMenu);

        setContentView(root);

        client = new SupabaseClient(this);
        seatManager = new SeatManager(client);
        if (!client.hasSession()) {
            Intent intent = new Intent(MenuActivity.this, AuthActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }
    }

    // 一行（横向）按钮容器：两列等权（weightSum=2），行高由父容器权重分配
    private LinearLayout newMenuRow(int gap, boolean withTopGap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setWeightSum(2f);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        if (withTopGap) rp.topMargin = gap;
        row.setLayoutParams(rp);
        return row;
    }

    // 单个按钮：宽度 0dp + weight=1（占行宽一半），高度 MATCH_PARENT（撑满行高），
    // 尺寸完全由权重和屏幕比例推导，不写死 dp。
    private void addMenuButton(LinearLayout container, final String label,
                               View.OnClickListener click, int gap) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(btnBg());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (container.getChildCount() > 0) bp.leftMargin = gap;
        b.setLayoutParams(bp);
        b.setOnClickListener(click);
        container.addView(b);
    }

    // ============================================================
    // 私密房间：创建 / 加入
    // ============================================================
    private void showPrivateRoomDialog() {
        AppDialog.confirm(this, "私密房间",
            "创建一个新房间，或输入好友的 4 位房间号加入。\n\n创建后房间号会显示在房间页面，把号码发给好友即可对战。",
            "创建房间", "加入房间",
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    doCreateRoom();
                }
            },
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    showJoinRoomDialog();
                }
            }).backCancelable().show();
    }

    // 创建房间：服务端返回 4 位房间号，直接进入房间页
    private void doCreateRoom() {
        Toast.makeText(this, "正在创建房间...", Toast.LENGTH_SHORT).show();
        seatManager.roomCreate(new SeatManager.ResultCodeCallback() {
            @Override
            public void onResult(final String code) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (code == null) {
                            Toast.makeText(MenuActivity.this, "创建失败，请重试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(MenuActivity.this, "房间号：" + code, Toast.LENGTH_LONG).show();
                        enterPrivateRoom(code);
                    }
                });
            }
        });
    }

    // 加入房间：输入 4 位房间号校验后进入房间页
    private void showJoinRoomDialog() {
        AppDialog.input(this, "加入房间", "请输入 4 位房间号",
            "加入", "取消",
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    final String code = dialog.getInputText();
                    if (code == null || code.length() != 4 || !code.matches("\\d{4}")) {
                        Toast.makeText(MenuActivity.this, "房间号必须是 4 位数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    seatManager.roomJoin(code, new SeatManager.ResultCallback() {
                        @Override
                        public void onResult(boolean ok, String message) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (ok) {
                                        enterPrivateRoom(code);
                                    } else {
                                        Toast.makeText(MenuActivity.this, message, Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    });
                }
            },
            null).show();
    }

    private void enterPrivateRoom(String code) {
        Intent intent = new Intent(MenuActivity.this, PrivateRoomActivity.class);
        intent.putExtra("room_code", code);
        startActivity(intent);
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