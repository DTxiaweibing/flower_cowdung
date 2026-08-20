// PvELobbyActivity.java (app-new 重写版)
// 人机游戏大厅：桌子预置在数据库（supabase/pve_tables.sql，固定 20 桌，APP 不建桌）。
//   进入流程：点击任意桌 -> 弹窗选择「坐下玩游戏」或「坐下当观众」。
//   大厅从数据库实时读取每桌状态渲染：是否有人坐 / 是否在玩游戏 / 观众数。
//   人机对局本身不做棋局同步，只上报桌状态（入座/开始/结束/离席）。
package com.example.cowdunggame;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class PvELobbyActivity extends Activity {

    private static final int TOTAL_TABLES = 20; // 数据库预置桌数（与 pve_tables.sql 一致）
    private static final int ROW_COLS = 2;      // 一排放 2 张桌子

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SupabaseClient client;
    private SeatManager seatManager;
    private LinearLayout gridContainer;
    private int screenW, screenH;
    private float density;
    private GameTableView.LayoutInfo layout;

    private final Map<String, JSONObject> tableStates = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        density = getResources().getDisplayMetrics().density;

        client = new SupabaseClient(this);
        seatManager = new SeatManager(client);

        FrameLayout root = new FrameLayout(this);

        // 背景画面（全屏）
        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

        // 桌子网格
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setLayoutParams(new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        scrollView.addView(gridContainer);
        root.addView(scrollView);

        setContentView(root);

        layout = GameTableView.computeLayout(screenW, screenH, density, ROW_COLS);
        gridContainer.setPadding(layout.blankPx, layout.roomPadTop,
            layout.blankPx, layout.roomPadBottom);

        // 先进数据库拉取每桌状态，再一次性渲染（不先渲染空骨架再覆盖）
        loadTables();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTables(); // 从对局返回后刷新状态
    }

    // ============================================================
    // 渲染
    // ============================================================
    // 根据数据库状态刷新全部桌子
    private void renderTables() {
        gridContainer.removeAllViews();
        int rowGapPx = (int) (10 * density);
        int rowIndex = -1;
        final String myId = client.getUserId();

        for (int i = 0; i < TOTAL_TABLES; i++) {
            if (i % ROW_COLS == 0) {
                LinearLayout rowView = new LinearLayout(this);
                rowView.setOrientation(LinearLayout.HORIZONTAL);
                rowView.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        layout.cardSidePx);
                if (rowIndex >= 0) rowParams.topMargin = rowGapPx;
                rowView.setLayoutParams(rowParams);
                gridContainer.addView(rowView);
                rowIndex++;
            }
            LinearLayout rowView =
                (LinearLayout) gridContainer.getChildAt(gridContainer.getChildCount() - 1);
            addTableCard(rowView, i + 1, myId);
        }
    }

    // 一张桌子卡片：桌面 + 座位状态（数据库驱动）+ 桌号
    private void addTableCard(LinearLayout rowView, final int tableNo, final String myId) {
        final JSONObject state = tableStates.get(String.valueOf(tableNo));

        GameTableView table = new GameTableView(this, layout);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            layout.cardSidePx, layout.cardSidePx);
        if (rowView.getChildCount() > 0) lp.leftMargin = layout.blankPx;
        table.setLayoutParams(lp);

        // 从数据库状态驱动渲染
        boolean playing = SeatManager.isPlaying(state);
        // 注意：player_id 为 JSON null 时 optString 返回字符串 "null"，必须用 isNull 判断
        boolean hasPlayer = SeatManager.hasPlayer(state);
        int watchers = state != null ? state.optInt("watcher_count", 0) : 0;
        boolean iAmPlayer = SeatManager.isMySeat(state, myId);

        // 入座玩家性别：优先数据库 profiles.gender，本地 prefs 兜底
        boolean leftMale = true;
        String leftNick = "";
        JSONObject playerObj = state != null ? state.optJSONObject("player") : null;
        if (playerObj != null) {
            if (playerObj.has("gender")) {
                leftMale = "male".equals(playerObj.optString("gender"));
            }
            leftNick = playerObj.optString("nickname", "");
        } else {
            leftMale = !"female".equals(getSharedPreferences("CowDungPrefs",
                MODE_PRIVATE).getString("PlayerGender", "male"));
        }

        String label = String.valueOf(tableNo);
        if (iAmPlayer) label += "·你";
        if (playing) label += "·对局中";
        else if (hasPlayer) label += "·有人";
        else label += "·空闲";
        table.setTableNo(label);

        table.setState(playing, hasPlayer || iAmPlayer, false, watchers > 0,
            leftMale, true, true); // 右侧恒为 AIBOT；左座按性别显示男女头像
        if (hasPlayer) {
            table.setPlayerLabel(leftNick); // 头像正下方显示昵称（仅玩家，观众不做）
        }

        // 点击桌：选择坐下玩游戏 / 坐下当观众
        table.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSeatDialog(tableNo, state, iAmPlayer);
            }
        });

        rowView.addView(table);
    }

    // ============================================================
    // 点击桌 -> 选择 坐下玩游戏 / 坐下当观众
    // ============================================================
    private void showSeatDialog(final int tableNo, final JSONObject state,
                                final boolean iAmPlayer) {
        boolean hasPlayer = SeatManager.hasPlayer(state);
        boolean playing = SeatManager.isPlaying(state);

        String message = "第 " + tableNo + " 桌\n";
        if (hasPlayer) {
            message += playing ? "状态：对局中（桌子上已有人）"
                : "状态：已有人入座";
        } else {
            message += "状态：空闲";
        }
        message += "\n观众：" + (state != null ? state.optInt("watcher_count", 0) : 0) + " 人";

        if (iAmPlayer) {
            // 我正坐在这桌 -> 直接回到棋局画面，不弹「回到棋局/离开座位」旧弹窗
            enterGame(tableNo, "player");
            return;
        }

        // 非本桌玩家：若桌上已有人，只能观战（返回键可关闭弹窗，反悔不坐）
        if (hasPlayer) {
            AppDialog.confirm(this, "第 " + tableNo + " 桌", message,
                "坐下当观众", null,
                new AppDialog.OnClick() {
                    @Override
                    public void onClick(AppDialog dialog) {
                        doWatch(tableNo);
                    }
                },
                null).backCancelable().show();
            return;
        }

        AppDialog.confirm(this, "第 " + tableNo + " 桌", message,
            "坐下玩游戏", "坐下当观众",
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    doSit(tableNo);
                }
            },
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    doWatch(tableNo);
                }
            }).backCancelable().show();
    }

    // ============================================================
    // 数据库操作
    // ============================================================
    private void doSit(final int tableNo) {
        final String tid = String.valueOf(tableNo);
        seatManager.sitAsPlayer(tid, new SeatManager.ResultCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (ok) {
                    enterGame(tableNo, "player");
                } else {
                    Toast.makeText(PvELobbyActivity.this, message, Toast.LENGTH_SHORT).show();
                    loadTables();
                }
            }
        });
    }

    private void doWatch(final int tableNo) {
        final String tid = String.valueOf(tableNo);
        seatManager.sitAsWatcher(tid, new SeatManager.ResultCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (ok) {
                    // 坐下即进入该桌棋局画面，不再停留在大厅
                    enterGame(tableNo, "watcher");
                } else {
                    Toast.makeText(PvELobbyActivity.this, message, Toast.LENGTH_SHORT).show();
                    loadTables();
                }
            }
        });
    }

    private void doLeave(final int tableNo) {
        final String tid = String.valueOf(tableNo);
        seatManager.leaveSeat(tid, new SeatManager.ResultCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                Toast.makeText(PvELobbyActivity.this,
                    "已离开第 " + tableNo + " 桌", Toast.LENGTH_SHORT).show();
                loadTables();
            }
        });
    }

    private void enterGame(int tableNo, String role) {
        Intent intent = new Intent(this, LocalGameActivity.class);
        intent.putExtra("source", "pve");
        intent.putExtra("table_no", String.valueOf(tableNo));
        intent.putExtra("role", role);
        startActivity(intent);
    }

    // 从数据库拉取全部桌子状态并刷新渲染
    private void loadTables() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray arr = client.fetchPveTables();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tableStates.clear();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                try {
                                    JSONObject o = arr.getJSONObject(i);
                                    tableStates.put(o.optString("num", o.optString("id")), o);
                                } catch (Exception ignore) { }
                            }
                        }
                        renderTables();
                    }
                });
            }
        }).start();
    }
}