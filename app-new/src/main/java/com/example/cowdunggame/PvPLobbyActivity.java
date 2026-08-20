// PvPLobbyActivity.java
// 人人游戏大厅：桌子预置在数据库（supabase/pvp_tables.sql，固定 20 桌，APP 不建桌）。
//   每桌 A(左/先手)/B(右/后手) 两个真人玩家位，先坐桌者为先手；
//   双方按「准备好了」（pvp_ready）后才开局，对局整包状态轮询同步。
//   点击任意桌 -> 弹窗选择「坐下玩游戏」或「坐下当观众」。
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

public class PvPLobbyActivity extends Activity {

    private static final int TOTAL_TABLES = 20; // 数据库预置桌数（与 pvp_tables.sql 一致）
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

        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

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

    // 一张桌子卡片：A(左)/B(右) 双真人座位 + 对局状态 + 观众数
    private void addTableCard(LinearLayout rowView, final int tableNo, final String myId) {
        final JSONObject state = tableStates.get(String.valueOf(tableNo));

        GameTableView table = new GameTableView(this, layout);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            layout.cardSidePx, layout.cardSidePx);
        if (rowView.getChildCount() > 0) lp.leftMargin = layout.blankPx;
        table.setLayoutParams(lp);

        boolean playing = SeatManager.isPvpPlaying(state);
        boolean hasA = SeatManager.hasPlayerA(state);
        boolean hasB = SeatManager.hasPlayerB(state);
        int watchers = state != null ? state.optInt("watcher_count", 0) : 0;
        boolean iAmA = myId != null && hasA && myId.equals(state.optString("player_a_id", ""));
        boolean iAmB = myId != null && hasB && myId.equals(state.optString("player_b_id", ""));
        boolean iAmSeated = iAmA || iAmB;

        // A 座（左）：性别来自数据库，本地 prefs 兜底
        boolean leftMale = true;
        String aNick = "";
        JSONObject aObj = state != null ? state.optJSONObject("player_a") : null;
        if (aObj != null) {
            if (aObj.has("gender")) leftMale = "male".equals(aObj.optString("gender"));
            aNick = aObj.optString("nickname", "");
        } else if (!hasA) {
            leftMale = !"female".equals(getSharedPreferences("CowDungPrefs",
                MODE_PRIVATE).getString("PlayerGender", "male"));
        }
        // B 座（右）：右侧非机器人，显示真实玩家
        boolean rightMale = true;
        String bNick = "";
        JSONObject bObj = state != null ? state.optJSONObject("player_b") : null;
        if (bObj != null) {
            if (bObj.has("gender")) rightMale = "male".equals(bObj.optString("gender"));
            bNick = bObj.optString("nickname", "");
        }

        String label = String.valueOf(tableNo);
        if (iAmA) label += "·A你";
        else if (iAmB) label += "·B你";
        if (playing) label += "·对局中";
        else if (hasA && hasB) label += "·满座";
        else if (hasA || hasB) label += "·有人";
        else label += "·空闲";
        table.setTableNo(label);

        table.setState(playing, hasA, hasB, watchers > 0,
            leftMale, rightMale, false); // PvP 右侧也是真人，非 AIBOT
        if (hasA) table.setPlayerLabel(aNick.isEmpty() ? "左座" : aNick);
        if (hasB) table.setRightPlayerLabel(bNick.isEmpty() ? "右座" : bNick);

        table.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSeatDialog(tableNo, state, iAmSeated);
            }
        });

        rowView.addView(table);
    }

    // ============================================================
    // 点击桌 -> 选择 坐下玩游戏 / 坐下当观众
    // ============================================================
    private void showSeatDialog(final int tableNo, final JSONObject state,
                                final boolean iAmSeated) {
        boolean hasA = SeatManager.hasPlayerA(state);
        boolean hasB = SeatManager.hasPlayerB(state);
        boolean playing = SeatManager.isPvpPlaying(state);
        boolean full = hasA && hasB;

        String message = "第 " + tableNo + " 桌\n";
        if (full) {
            message += playing ? "状态：对局中（左右已有人）" : "状态：满座，等开局";
        } else if (hasA || hasB) {
            message += "状态：已有人入座（" + (hasA ? "左" : "") + (hasA && hasB ? "、" : "")
                + (hasB ? "右" : "") + "）";
        } else {
            message += "状态：空闲";
        }
        message += "\n观众：" + (state != null ? state.optInt("watcher_count", 0) : 0) + " 人";

        if (iAmSeated) {
            // 我正坐在这桌 -> 直接回到棋局画面
            enterGame(tableNo, "player");
            return;
        }

        // 非本桌玩家：满座时只能观战（返回键可关闭弹窗，反悔不坐）
        if (full) {
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
        seatManager.pvpSitAsPlayer(tid, new SeatManager.ResultCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (ok) {
                    enterGame(tableNo, "player");
                } else {
                    Toast.makeText(PvPLobbyActivity.this, message, Toast.LENGTH_SHORT).show();
                    loadTables();
                }
            }
        });
    }

    private void doWatch(final int tableNo) {
        final String tid = String.valueOf(tableNo);
        seatManager.pvpSitAsWatcher(tid, new SeatManager.ResultCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (ok) {
                    enterGame(tableNo, "watcher");
                } else {
                    Toast.makeText(PvPLobbyActivity.this, message, Toast.LENGTH_SHORT).show();
                    loadTables();
                }
            }
        });
    }

    private void enterGame(int tableNo, String role) {
        Intent intent = new Intent(this, LocalGameActivity.class);
        intent.putExtra("source", "pvp");
        intent.putExtra("table_no", String.valueOf(tableNo));
        intent.putExtra("role", role);
        startActivity(intent);
    }

    // 从数据库拉取全部桌子状态并刷新渲染
    private void loadTables() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray arr = client.fetchPvpTables();
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