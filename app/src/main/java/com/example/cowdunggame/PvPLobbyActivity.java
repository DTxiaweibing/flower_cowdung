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
    // 大厅每 3 秒拉一次。对局内是 2 秒，但大厅只有 20 张小卡片，
    // 且卡面只有「有人/空闲/对局中/观众数」这几个粗粒度状态，
    // 3 秒足够，人眼分辨不出差别，服务器和流量都省一点。
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
    // 以前每次刷新都 removeAllViews() 重建 20 张卡 —— 改成轮询后
    // 那样每秒都在拆视图树，会闪并且吃掉用户正在按下的那张卡。
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

        // 20 张卡片骨架先摆出来，别让用户对着空屏等第一次网络返回。
        // 卡面内容由 refresh() 填。
        buildGrid();
        gridBuilt = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从对局返回后刷新状态；停在本页时继续每 3 秒轮询，
        // 免得「看到有人，走过去人已经走了」。
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
    // 以及两个人的性别与昵称（对方改了资料也得跟着变）。
    // 不覆盖 game_state —— 大厅根本不读它。
    private String sigOf(JSONObject state) {
        if (state == null) return "-";
        StringBuilder sb = new StringBuilder();
        sb.append(state.optString("status", "")).append('|')
            .append(state.isNull("player_a_id") ? "" : state.optString("player_a_id")).append('|')
            .append(state.isNull("player_b_id") ? "" : state.optString("player_b_id")).append('|')
            .append(state.optInt("watcher_count", 0));
        appendProfileSig(sb, state, "player_a");
        appendProfileSig(sb, state, "player_b");
        return sb.toString();
    }

    private void appendProfileSig(StringBuilder sb, JSONObject state, String key) {
        JSONObject o = state.optJSONObject(key);
        if (o == null) {
            sb.append("|-");
            return;
        }
        sb.append('|').append(o.optString("gender", "")).append(':')
            .append(o.optString("nickname", ""));
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

        table.setState(playing, hasA, hasB, watchers,
            leftMale, rightMale, false); // PvP 右侧也是真人，非 AIBOT
        if (hasA) table.setPlayerLabel(aNick.isEmpty() ? "先入座" : aNick);
        if (hasB) table.setRightPlayerLabel(bNick.isEmpty() ? "后入座" : bNick);

        // 重新绑 click：闭包里存的是这一份 state，差量刷新时
        // state 会是最新的，弹窗就不会再拿旧快照算「满座/有人」。
        table.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSeatDialog(tableNo, state, iAmSeated);
            }
        });
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
        // 上一轮还没回来就跳过本轮。3 秒一次的网络请求遇上弱网会叠成
        // 一串并发，晚到的旧响应会把新状态盖回去。
        if (pollInFlight) return;
        pollInFlight = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray arr = client.fetchPvpTables();
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
