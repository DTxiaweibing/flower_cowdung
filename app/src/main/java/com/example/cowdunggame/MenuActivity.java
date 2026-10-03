// MenuActivity.java (app-new 精简版)
// 登录后的主菜单。已实现入口：人机游戏大厅 / 人人游戏大厅 / 私密房间。
//   底部：注销登录。
package com.example.cowdunggame;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MenuActivity extends Activity {

    private SupabaseClient client;
    private SeatManager seatManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int screenH = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this);

        // 背景：用图片铺满全屏（替换原代码绘制的地板 FloorView）。
        ImageView bg = new ImageView(this);
        bg.setImageResource(R.drawable.background);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bg);

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
        title.setClickable(true);
        // 点击标题弹出鲜花下拉抽屉：每项「名称 + emoji」，当前选中的行尾打勾。
        title.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFlowerPicker(v);
            }
        });
        root.addView(title);

        // 声音设置入口（右上角）：弹出"音效 / 音乐"两个独立开关
        Button btnSound = new Button(this);
        btnSound.setText("🔊");
        btnSound.setTextSize(18);
        btnSound.setAllCaps(false);
        btnSound.setTextColor(Color.WHITE);
        btnSound.setBackground(btnBg());
        FrameLayout.LayoutParams soundParams = new FrameLayout.LayoutParams(
                (int) (screenW * 0.12f), (int) (screenW * 0.12f));
        soundParams.gravity = Gravity.TOP | Gravity.RIGHT;
        soundParams.topMargin = (int) (screenH * 0.03f);
        soundParams.rightMargin = (int) (screenW * 0.03f);
        btnSound.setLayoutParams(soundParams);
        btnSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SoundSettingsDialog.show(MenuActivity.this, null);
            }
        });
        root.addView(btnSound);

        // 排行榜入口（标题正下方，无边框透明按钮）
        Button btnRank = new Button(this);
        btnRank.setText("排行榜");
        btnRank.setTextSize(16);
        btnRank.setAllCaps(false);
        btnRank.setTextColor(Color.BLACK);
        btnRank.setBackground(null);
        FrameLayout.LayoutParams rankParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        rankParams.gravity = Gravity.CENTER_HORIZONTAL;
        rankParams.topMargin = (int) (screenH * 0.14f);
        btnRank.setLayoutParams(rankParams);
        btnRank.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RankingBoard.show(MenuActivity.this, client);
            }
        });
        root.addView(btnRank);

        // 底部按钮：2×2 网格，尺寸/间距全部按屏幕比例用权重分配，保证各设备位置基本一致
        final int menuW = (int) (screenW * 0.92f);
        final int menuH = (int) (screenH * 0.20f);   // 两行按钮约占屏高 20%
        final int gap = (int) (screenW * 0.03f);     // 行/列间距随屏宽等比

        // 底部网格与屏幕底边的基础距离；导航栏高度在下面 insets 回调里叠加上去
        final int baseBottomMargin = (int) (screenH * 0.03f);
        LinearLayout bottomMenu = new LinearLayout(this);
        bottomMenu.setOrientation(LinearLayout.VERTICAL);
        bottomMenu.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomMenu.setWeightSum(2f);
        FrameLayout.LayoutParams bmParams = new FrameLayout.LayoutParams(menuW, menuH);
        bmParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bmParams.bottomMargin = baseBottomMargin;
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

        // targetSdk 36 起 Android 强制 edge-to-edge，内容默认会画到导航栏底下。
        // 底部这排按钮是 Gravity.BOTTOM 锚定的，于是整整一排压在导航栏上，
        // 系统给导航栏画的那层半透明遮罩直接盖在按钮上，看着就像「导航栏有颜色」。
        //
        // 这里只把导航栏高度让给 bottomMenu，不给 root 加 padding：root 一旦
        // 加 padding，里面铺满全屏的背景图会被裁到 padding 边界以内，
        // 让出的那条就会露出主题的浅色 window 背景 —— 那反而更像「导航栏有颜色」。
        // 只叠 bottomMargin 的话背景图仍然画到屏幕最底边，导航栏那条露出来
        // 的是背景图自己的深色，和 LocalGameActivity 的处理方式一致。
        ViewCompat.setOnApplyWindowInsetsListener(root, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                ViewGroup.LayoutParams lp = bottomMenu.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) lp;
                    fp.bottomMargin = baseBottomMargin + bars.bottom;
                    bottomMenu.setLayoutParams(fp);
                }
                return WindowInsetsCompat.CONSUMED;
            }
        });

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
        // 主题给 Button 的默认内边距要手动清掉：setBackground 只换掉了背景
        // drawable，主题 buttonStyle 上的 paddingLeft/Right/Top/Bottom 还在，
        // 于是文字被挤在中间、四周留出一圈空白，按钮显得比实际格子小一圈。
        b.setPadding(0, 0, 0, 0);
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

    // 点击标题弹出的鲜花选择抽屉（PopupWindow 锚在标题下方）。
    // 每行「名称 + emoji」，当前选中的行尾加 ✓ 并高亮；点选即存并收起。
    private void showFlowerPicker(View anchor) {
        final int current = FlowerTheme.getIndex(this);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(popupBg());
        int pad = dp(6);
        list.setPadding(pad, pad, pad, pad);

        final PopupWindow popup = new PopupWindow(list,
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));

        for (int i = 0; i < FlowerTheme.NAMES.length; i++) {
            final int index = i;
            boolean selected = (i == current);
            TextView row = new TextView(this);
            row.setText(FlowerTheme.NAMES[i] + "  " + FlowerTheme.EMOJIS[i] + (selected ? "  ✓" : ""));
            row.setTextSize(18);
            row.setTextColor(Color.WHITE);
            row.setSingleLine(true);
            row.setPadding(dp(18), dp(12), dp(18), dp(12));
            // 每行同宽，抽屉整体才好居中。
            row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            if (selected) row.setBackgroundColor(0x33FFD700);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FlowerTheme.setIndex(MenuActivity.this, index);
                    popup.dismiss();
                }
            });
            list.addView(row);
        }

        // 先量出抽屉实际宽度，再相对标题水平居中；垂直方向贴在标题正下方。
        list.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int xoff = (anchor.getWidth() - list.getMeasuredWidth()) / 2;
        popup.showAsDropDown(anchor, xoff, dp(4));
    }

    private GradientDrawable popupBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(12));
        gd.setColor(0xF0202020);
        gd.setStroke(1, Color.parseColor("#88FFD700"));
        return gd;
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