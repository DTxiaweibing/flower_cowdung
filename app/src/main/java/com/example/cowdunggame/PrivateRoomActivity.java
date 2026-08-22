// PrivateRoomActivity.java
// 私密房间（单桌页面）：一个房间号对应数据库中一张专用的桌子（A 左先手 / B 右后手）。
//   入口来自主菜单「私人房间」-> 创建或加入，进入时带上 4 位 room_code。
//   中央一张大桌卡显示 A/B 真人座位 + 对局状态 + 观众数；点击桌卡 -> 坐下玩游戏 / 坐下当观众；
//   底部红色按钮离开房间（玩家走 room_leave，观众走 room_unwatch）。
//   数据源：supabase/private_rooms.sql 的 RPC（room_sit / room_watch / room_leave ...）。
package com.example.cowdunggame;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class PrivateRoomActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

    private SupabaseClient client;
    private SeatManager seatManager;

    private String roomCode;
    private TextView tvTitle;
    private TextView tvStatus;
    private GameTableView table;
    private GameTableView.LayoutInfo layout;
    private int screenW, screenH;
    private float density;

    private JSONObject roomState;
    private String mySide; // 'a' / 'b' / null（轮询解析）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        density = getResources().getDisplayMetrics().density;

        roomCode = getIntent() != null ? getIntent().getStringExtra("room_code") : null;
        if (roomCode == null || roomCode.length() != 4) {
            Toast.makeText(this, "房间号无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        client = new SupabaseClient(this);
        seatManager = new SeatManager(client);

        FrameLayout root = new FrameLayout(this);

        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

        // 标题：房间号
        tvTitle = new TextView(this);
        tvTitle.setText("私密房间 #" + roomCode);
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(android.graphics.Color.parseColor("#FFD700"));
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setShadowLayer(4, 2, 2, android.graphics.Color.BLACK);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, (int) (screenH * 0.07f));
        titleParams.topMargin = (int) (screenH * 0.03f);
        tvTitle.setLayoutParams(titleParams);
        root.addView(tvTitle);

        // 状态文字
        tvStatus = new TextView(this);
        tvStatus.setText("加载中...");
        tvStatus.setTextSize(15);
        tvStatus.setTextColor(android.graphics.Color.WHITE);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setShadowLayer(2, 1, 1, android.graphics.Color.BLACK);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(34));
        statusParams.topMargin = (int) (screenH * 0.07f) + dp(8);
        tvStatus.setLayoutParams(statusParams);
        root.addView(tvStatus);

        // 中央单一桌卡（perRow=1 -> 大卡）
        layout = GameTableView.computeLayout(screenW, screenH, density, 1);
        table = new GameTableView(this, layout);
        FrameLayout.LayoutParams tableParams = new FrameLayout.LayoutParams(
            layout.cardSidePx, layout.cardSidePx);
        tableParams.gravity = Gravity.CENTER;
        table.setLayoutParams(tableParams);
        table.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSeatDialog();
            }
        });
        root.addView(table);

        // 底部操作列：退出房间（回初始页；双方都离开后服务端关闭房间并踢出观众）
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        int barW = (int) (screenW * 0.72f);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
            barW, dp(48));
        barParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barParams.bottomMargin = (int) (screenH * 0.05f);
        bottomBar.setLayoutParams(barParams);

        Button btnLeave = new Button(this);
        btnLeave.setText("退出房间");
        btnLeave.setTextSize(15);
        btnLeave.setTextColor(android.graphics.Color.WHITE);
        btnLeave.setAllCaps(false);
        btnLeave.setBackground(roundedStrokeBg(0xFFB71C1C));
        btnLeave.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        btnLeave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 直接 finish，退回初始页（房间状态由服务端管理）
                finish();
            }
        });
        bottomBar.addView(btnLeave);
        root.addView(bottomBar);

        setContentView(root);

        startPolling();
    }

    // ============================================================
    // 轮询房间状态（每 2s）
    // ============================================================
    private void startPolling() {
        ui.removeCallbacks(pollRunnable);
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                pollOnce();
                ui.postDelayed(pollRunnable, 2000);
            }
        };
        ui.post(pollRunnable);
    }

    private void pollOnce() {
        if (client == null || roomCode == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject state = client.fetchRoom(roomCode);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (state == null) {
                            tvStatus.setText("房间不存在，可能已失效");
                            return;
                        }
                        roomState = state;
                        render();
                    }
                });
            }
        }).start();
    }

    // ============================================================
    // 渲染桌卡与状态
    // ============================================================
    private void render() {
        if (roomState == null) return;
        final String myId = client != null ? client.getUserId() : null;
        mySide = SeatManager.mySide(roomState, myId);

        boolean playing = SeatManager.isPvpPlaying(roomState);
        boolean hasA = SeatManager.hasPlayerA(roomState);
        boolean hasB = SeatManager.hasPlayerB(roomState);
        int watchers = roomState.optInt("watcher_count", 0);

        // A（左）资料
        boolean leftMale = true;
        String aNick = "";
        JSONObject aObj = roomState.optJSONObject("player_a");
        if (aObj != null) {
            if (aObj.has("gender")) leftMale = "male".equals(aObj.optString("gender"));
            aNick = aObj.optString("nickname", "");
        } else if (!hasA) {
            leftMale = !"female".equals(getSharedPreferences("CowDungPrefs",
                MODE_PRIVATE).getString("PlayerGender", "male"));
        }
        // B（右）资料
        boolean rightMale = true;
        String bNick = "";
        JSONObject bObj = roomState.optJSONObject("player_b");
        if (bObj != null) {
            if (bObj.has("gender")) rightMale = "male".equals(bObj.optString("gender"));
            bNick = bObj.optString("nickname", "");
        }

        String label = "#" + roomCode;
        if ("a".equals(mySide)) label += "·A你";
        else if ("b".equals(mySide)) label += "·B你";
        if (playing) label += "·对局中";
        else if (hasA && hasB) label += "·满座";
        else if (hasA || hasB) label += "·有人";
        else label += "·空闲";
        table.setTableNo(label);

        table.setState(playing, hasA, hasB, watchers > 0,
            leftMale, rightMale, false);
        if (hasA) table.setPlayerLabel(aNick.isEmpty() ? "先入座" : aNick);
        if (hasB) table.setRightPlayerLabel(bNick.isEmpty() ? "后入座" : bNick);

        // 状态文字
        String status;
        if (playing) {
            status = "对局中，点击桌卡进入";
        } else if (hasA && hasB) {
            status = "满座，等待开局";
        } else if (hasA || hasB) {
            status = "已有人入座（" + (hasA ? "左" : "") + (hasA && hasB ? "、" : "")
                + (hasB ? "右" : "") + "），可入座对战";
        } else {
            status = "空闲，点击桌卡坐下当先手";
        }
        if ("a".equals(mySide)) status += "　你=左·先手";
        else if ("b".equals(mySide)) status += "　你=右·后手";
        else if (watchers > 0) status += "　观众 " + watchers + " 人";
        tvStatus.setText(status);
    }

    // ============================================================
    // 点击桌卡 -> 坐下玩游戏 / 坐下当观众
    // ============================================================
    private void showSeatDialog() {
        if (roomState == null) return;
        final boolean hasA = SeatManager.hasPlayerA(roomState);
        final boolean hasB = SeatManager.hasPlayerB(roomState);
        final boolean playing = SeatManager.isPvpPlaying(roomState);
        final boolean full = hasA && hasB;

        String message = "房间 #" + roomCode + "\n";
        if (full) {
            message += playing ? "状态：对局中（左右已有人）" : "状态：满座，等开局";
        } else if (hasA || hasB) {
            message += "状态：已有人入座（" + (hasA ? "左" : "") + (hasA && hasB ? "、" : "")
                + (hasB ? "右" : "") + "）";
        } else {
            message += "状态：空闲";
        }
        message += "\n观众：" + roomState.optInt("watcher_count", 0) + " 人";

        if (mySide != null) {
            // 我已在这房间当玩家 -> 回到棋局画面
            enterGame("player");
            return;
        }

        if (full) {
            AppDialog.confirm(this, "房间 #" + roomCode, message,
                "坐下当观众", null,
                new AppDialog.OnClick() {
                    @Override
                    public void onClick(AppDialog dialog) {
                        doWatch();
                    }
                },
                null).backCancelable().show();
            return;
        }

        AppDialog.confirm(this, "房间 #" + roomCode, message,
            "坐下玩游戏", "坐下当观众",
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    doSit();
                }
            },
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    doWatch();
                }
            }).backCancelable().show();
    }

    // ============================================================
    // 玩家 / 观众 / 离开
    // ============================================================
    private void doSit() {
        final String code = roomCode;
        seatManager.roomSit(code, new SeatManager.ResultSideCallback() {
            @Override
            public void onResult(final String side) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (side == null) {
                            Toast.makeText(PrivateRoomActivity.this,
                                "入座失败：座位可能已被占用", Toast.LENGTH_SHORT).show();
                            pollOnce();
                        } else {
                            enterGame("player");
                        }
                    }
                });
            }
        });
    }

    private void doWatch() {
        final String code = roomCode;
        seatManager.roomWatch(code, new SeatManager.ResultCallback() {
            @Override
            public void onResult(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            enterGame("watcher");
                        } else {
                            Toast.makeText(PrivateRoomActivity.this, message, Toast.LENGTH_SHORT).show();
                            pollOnce();
                        }
                    }
                });
            }
        });
    }

    private void doLeaveRoom() {
        ui.removeCallbacks(pollRunnable);
        final String code = roomCode;
        final String side = mySide;
        // 1. 清除座位/观战关系（让座）
        if (side != null) {
            seatManager.roomLeave(code, null);
        } else {
            seatManager.roomUnwatch(code, null);
        }
        // 2. 刷新房间状态（会自动检测房间是否关闭，如果关闭则弹出提示后 finish）
        startPolling();
        Toast.makeText(this, "已离开座位", Toast.LENGTH_SHORT).show();
    }

    private void enterGame(String role) {
        ui.removeCallbacks(pollRunnable);
        Intent intent = new Intent(this, LocalGameActivity.class);
        intent.putExtra("source", "room");
        intent.putExtra("room_code", roomCode);
        intent.putExtra("role", role);
        // 不 finish：保留房间页在返回栈，棋局内「离开棋局」/观战被踢时自动回到这里
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (client != null && roomCode != null) startPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(pollRunnable);
    }

    // ============================================================
    // 样式
    // ============================================================
    private android.graphics.drawable.GradientDrawable roundedStrokeBg(int fillColor) {
        android.graphics.drawable.GradientDrawable gd =
            new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        gd.setColor(fillColor);
        gd.setStroke(2, android.graphics.Color.WHITE);
        return gd;
    }

    private int dp(float value) {
        return (int) (value * density + 0.5f);
    }
}