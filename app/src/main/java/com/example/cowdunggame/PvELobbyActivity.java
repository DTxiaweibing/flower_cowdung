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

    // 大厅每 3 秒拉一次，和人人大厅一致：有人入座/离开能很快反映出来。
    private static final long POLL_MS = 3000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SupabaseClient client;
    private SeatManager seatManager;
    private LinearLayout gridContainer;
    private int screenW, screenH;
    private float density;
    private GameTableView.LayoutInfo layout;

    private final Map<String, JSONObject> tableStates = new HashMap<>();

    // 差量刷新用：卡片只建一次，之后按签名比对，只重画状态变了的桌。
    // 每次轮询都 removeAllViews() 重建 20 张卡会闪，还会吃掉用户正按下的那张卡。
    private final Map<Integer, GameTableView> tableViews = new HashMap<>();
    private final Map<Integer, String> lastSigs = new HashMap<>();
    private boolean gridBuilt = false;
    private boolean polling = false;
    private boolean pollInFlight = false;

    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            loadTables();
            ui.postDelayed(this, POLL_MS);
        }
    };

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

        // 20 张卡片骨架先摆出来，别让用户对着空屏等第一次网络返回。
        // 卡面内容由 refresh() 填。
        buildGrid();
        gridBuilt = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从对局返回后刷新状态；停在本页时继续每 3 秒轮询，
        // 免得「别人坐了还显示空闲，点下去才发现有人」。
        startPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        ui.post(pollTick); // post 无延迟，进页面马上拉一次
    }

    private void stopPolling() {
        polling = false;
        ui.removeCallbacks(pollTick);
    }

    // ============================================================
    // 渲染
    // ============================================================
    // 只建一次 20 张卡片的骨架，之后靠 refresh() 差量更新卡面。
    private void buildGrid() {
        gridContainer.removeAllViews();
        tableViews.clear();
        lastSigs.clear();
        int rowGapPx = (int) (10 * density);
        int rowIndex = -1;

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
            addTableCard(rowView, i + 1);
        }
    }

    // 每轮刷新：算出每张桌的签名，只重画变了的。
    private void refresh() {
        if (!gridBuilt) {
            buildGrid();
            gridBuilt = true;
        }
        final String myId = client.getUserId();
        for (int i = 0; i < TOTAL_TABLES; i++) {
            final int tableNo = i + 1;
            JSONObject st = tableStates.get(String.valueOf(tableNo));
            String sig = sigOf(st);
            if (sig.equals(lastSigs.get(tableNo))) continue;
            lastSigs.put(tableNo, sig);
            applyState(tableNo, st, myId);
        }
    }

    // 签名覆盖卡面上会变的每一个东西：座位占用、对局状态、观众数、
    // 以及入座玩家的性别与昵称。不覆盖 game_state —— 大厅根本不读它。
    private String sigOf(JSONObject state) {
        if (state == null) return "-";
        StringBuilder sb = new StringBuilder();
        sb.append(state.optString("status", "")).append('|')
            .append(state.isNull("player_id") ? "" : state.optString("player_id")).append('|')
            .append(state.optInt("watcher_count", 0));
        JSONObject o = state.optJSONObject("player");
        if (o == null) {
            sb.append("|-");
        } else {
            sb.append('|').append(o.optString("gender", "")).append(':')
                .append(o.optString("nickname", ""));
        }
        return sb.toString();
    }

    // 建一张空卡并登记；卡面内容留给 applyState 填。
    private void addTableCard(LinearLayout rowView, final int tableNo) {
        GameTableView table = new GameTableView(this, layout);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            layout.cardSidePx, layout.cardSidePx);
        if (rowView.getChildCount() > 0) lp.leftMargin = layout.blankPx;
        table.setLayoutParams(lp);
        rowView.addView(table);
        tableViews.put(tableNo, table);
    }

    // 把这一桌的最新状态画到已有卡片上。
    private void applyState(final int tableNo, final JSONObject state, final String myId) {
        GameTableView table = tableViews.get(tableNo);
        if (table == null) return;

        // 从数据库状态驱动渲染
        // 注意：player_id 为 JSON null 时 optString 返回字符串 "null"，必须用 isNull 判断
        boolean hasPlayer = SeatManager.hasPlayer(state);
        boolean playing = SeatManager.isPlaying(state);
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

        table.setState(playing, hasPlayer || iAmPlayer, false, watchers,
            leftMale, true, true); // 右侧恒为 AIBOT；左座按性别显示男女头像
        if (hasPlayer) {
            table.setPlayerLabel(leftNick); // 头像正下方显示昵称（仅玩家，观众不做）
        }

        // 重新绑 click：闭包里存的是这一份 state，差量刷新后 state 是最新的，
        // 弹窗就不会再拿旧快照算「有人/空闲」。
        table.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSeatDialog(tableNo, state, iAmPlayer);
            }
        });
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
        // 上一轮还没回来就跳过本轮。3 秒一次的网络请求遇上弱网会叠成
        // 一串并发，晚到的旧响应会把新状态盖回去。
        if (pollInFlight) return;
        pollInFlight = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray arr = client.fetchPveTables();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        pollInFlight = false;
                        tableStates.clear();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                try {
                                    JSONObject o = arr.getJSONObject(i);
                                    tableStates.put(o.optString("num", o.optString("id")), o);
                                } catch (Exception ignore) { }
                            }
                        }
                        refresh();
                    }
                });
            }
        }).start();
    }
}