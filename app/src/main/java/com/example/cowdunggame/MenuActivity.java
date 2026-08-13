// MenuActivity.java
// 启动页（主菜单）= 实时游戏大厅：
//   背景：地板砖 + 实时显示大厅桌子（真实数据，Realtime 推送刷新 + 心跳保活）
//   先判断平板/手机：平板每行 3 桌，手机每行 2 桌；固定 30 桌，图形复用，上下滑动。
//   悬浮半透明按钮：人机对战 / 私人房间 / 新建桌子 / 进入大厅。
//   点击桌子：坐下玩游戏（入座）或当观众。
package com.example.cowdunggame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class MenuActivity extends Activity {

    private static final int TOTAL_TABLES = 30; // 大厅固定 30 桌

    private final Handler main = new Handler(Looper.getMainLooper());
    private SupabaseClient client;
    private RealtimeClient realtime;
    private JSONArray tables = new JSONArray();
    private Map<String, JSONObject> profileMap = new HashMap<>();

    private LinearLayout gridContainer;
    private int tablesPerRow = 2; // 手机 2，平板 3
    private boolean identityReady = false;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (client != null) client.heartbeat();
            main.postDelayed(this, 60_000);
        }
    };

    private final Runnable fallbackRefresh = new Runnable() {
        @Override
        public void run() {
            if (identityReady) refreshTables();
            main.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int screenH = getResources().getDisplayMetrics().heightPixels;
        // 先判断平板还是手机：平板每行 3 桌，手机每行 2 桌
        float minDp = Math.min(screenW, screenH) / getResources().getDisplayMetrics().density;
        tablesPerRow = (minDp >= 600f) ? 3 : 2;

        FrameLayout root = new FrameLayout(this);

        // 背景地板
        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

        // 滚动的大厅桌子区
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setPadding(0, (int) (screenH * 0.04f), 0, (int) (screenH * 0.24f));
        gridContainer.setLayoutParams(new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        scrollView.addView(gridContainer);
        root.addView(scrollView);

        // 顶部标题
        TextView title = new TextView(this);
        title.setText("鲜花与牛粪 · 大厅");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(4), 2, 2, Color.BLACK);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, (int) (screenH * 0.05f));
        titleParams.topMargin = (int) (screenH * 0.01f);
        title.setLayoutParams(titleParams);
        root.addView(title);

        // 透明悬浮按钮（底部）
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(panelBg());
        int panelW = (int) (screenW * 0.42f);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
            panelW, LinearLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        panelParams.bottomMargin = (int) (screenH * 0.03f);
        panel.setLayoutParams(panelParams);

        addFloatButton(panel, "人机对战", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MenuActivity.this, MainActivity.class);
                intent.putExtra("source", "pve");
                startActivity(intent);
            }
        });
        addFloatButton(panel, "私人房间", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MenuActivity.this, PrivateRoomActivity.class);
                startActivity(intent);
            }
        });
        addFloatButton(panel, "新建桌子", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCreateTable();
            }
        });
        root.addView(panel);

        setContentView(root);

        // 身份 + 数据初始化
        SupabaseIdentity identity = new SupabaseIdentity(this);
        identity.ensureIdentity(new SupabaseIdentity.Callback() {
            @Override
            public void onDone(boolean ok, String error) {
                client = identity.getClient();
                identityReady = true;
                startRealtime();
                refreshTables();
                main.post(heartbeat);
                main.post(fallbackRefresh);
            }
        });
    }

    private void addFloatButton(LinearLayout panel, final String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(btnBg());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        if (panel.getChildCount() > 0) bp.topMargin = dp(8);
        b.setLayoutParams(bp);
        b.setOnClickListener(click);
        panel.addView(b);
    }

    private void startRealtime() {
        realtime = new RealtimeClient(new RealtimeClient.Listener() {
            @Override
            public void onChanged(String table, String event, JSONObject record) {
                if ("lobby_tables".equals(table) || "profiles".equals(table)) {
                    refreshTables();
                }
            }
        });
        realtime.subscribe();
    }

    private void refreshTables() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray newTables = client.fetchLobbyTables();
                final JSONArray profiles = client.fetchProfiles();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (newTables != null) tables = newTables;
                        if (profiles != null) {
                            profileMap.clear();
                            for (int i = 0; i < profiles.length(); i++) {
                                JSONObject p = profiles.optJSONObject(i);
                                if (p != null) profileMap.put(p.optString("id"), p);
                            }
                        }
                        renderTables();
                    }
                });
            }
        }).start();
    }

    // 固定 30 桌：不足 30 用空桌补齐（复用 idle 图 + 预留位），手机 2 列 / 平板 3 列
    private void renderTables() {
        if (gridContainer == null) return;
        gridContainer.removeAllViews();

        int screenW = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;
        TableDishView.RowLayout layout =
            TableDishView.computeRowLayout(screenW, density, tablesPerRow, 18, 150);

        int rowH = layout.tableSidePx;
        int rowMargin = layout.gapPx;
        int rowGapPx = (int) (TableDishView.ROW_GAP_DP * density);
        int total = Math.max(TOTAL_TABLES, tables.length());

        int index = 0;
        while (index < total) {
            int colsInRow = Math.min(tablesPerRow, total - index);
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setGravity(Gravity.CENTER);
            rowView.setPadding(rowMargin, 0, rowMargin, 0);
            LinearLayout.LayoutParams rowParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH);
            if (index > 0) rowParams.topMargin = rowGapPx;
            rowView.setLayoutParams(rowParams);

            for (int col = 0; col < colsInRow; col++) {
                final int slot = index++;
                final JSONObject t = slot < tables.length() ? tables.optJSONObject(slot) : null;
                final String tableId = t == null ? "" : t.optString("id", "");

                TableDishView table = new TableDishView(this);
                if (t != null) {
                    applyTableState(table, t);
                    table.setRoomLabel(tableId);
                } else {
                    table.setState(false, false, false, false, true, true);
                }
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    layout.tableSidePx, layout.tableSidePx);
                if (col > 0) tp.leftMargin = rowMargin; // 两桌间距 = 头像/2
                table.setLayoutParams(tp);
                final boolean hasData = t != null;
                table.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (hasData) showTableChoice(tableId, t);
                        else Toast.makeText(MenuActivity.this,
                            "这张桌子还空着，点「新建桌子」开一桌", Toast.LENGTH_SHORT).show();
                    }
                });
                rowView.addView(table);
            }
            gridContainer.addView(rowView);
        }
    }

    private void applyTableState(TableDishView table, JSONObject t) {
        boolean playing = "playing".equals(t.optString("status"));
        String a = t.optString("player_a_id", "");
        String b = t.optString("player_b_id", "");
        boolean left = !a.isEmpty();
        boolean right = !b.isEmpty();
        int watchers = t.optInt("watcher_count", 0);
        boolean spec = watchers > 0;
        boolean aMale = genderOf(a, true);
        boolean bMale = genderOf(b, true);
        table.setState(playing, left, right, spec, aMale, bMale);
    }

    private boolean genderOf(String userId, boolean dflt) {
        if (userId == null || userId.isEmpty()) return dflt;
        JSONObject p = profileMap.get(userId);
        if (p == null) return dflt;
        return "male".equals(p.optString("gender", dflt ? "male" : "female"));
    }

    private void onCreateTable() {
        final SupabaseIdentity identity = new SupabaseIdentity(this);
        identity.requirePlayerIdentity(new SupabaseIdentity.Callback() {
            @Override
            public void onDone(boolean ok, String error) {
                if (!ok) return;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String tid = identity.getClient().createLobbyTable();
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (tid != null) {
                                    Toast.makeText(MenuActivity.this,
                                        "已创建桌子 " + tid, Toast.LENGTH_SHORT).show();
                                    refreshTables();
                                } else {
                                    Toast.makeText(MenuActivity.this,
                                        "建桌失败", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });
    }

    private void showTableChoice(final String tableId, final JSONObject tableObj) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("桌 " + tableId);
        builder.setItems(new String[]{"坐下玩游戏", "当观众"},
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        final SupabaseIdentity identity = new SupabaseIdentity(MenuActivity.this);
                        identity.requirePlayerIdentity(new SupabaseIdentity.Callback() {
                            @Override
                            public void onDone(boolean ok, String error) {
                                if (!ok) return;
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        final boolean joined = identity.getClient().joinTable(tableId);
                                        main.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (!joined) {
                                                    Toast.makeText(MenuActivity.this,
                                                        "入座失败（可能已满）", Toast.LENGTH_SHORT).show();
                                                    refreshTables();
                                                    return;
                                                }
                                                refreshTables();
                                                Intent intent = new Intent(MenuActivity.this, MainActivity.class);
                                                intent.putExtra("source", "lobby");
                                                startActivity(intent);
                                            }
                                        });
                                    }
                                }).start();
                            }
                        });
                    } else {
                        Intent intent = new Intent(MenuActivity.this, MainActivity.class);
                        intent.putExtra("source", "lobby");
                        intent.putExtra("asWatcher", true);
                        startActivity(intent);
                    }
                }
            });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(heartbeat);
        main.removeCallbacks(fallbackRefresh);
        if (realtime != null) realtime.close();
    }

    private GradientDrawable panelBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(14));
        gd.setColor(0x99000000);
        gd.setStroke(1, Color.parseColor("#66FFD700"));
        return gd;
    }

    private GradientDrawable btnBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        gd.setColor(0xAA202020);
        gd.setStroke(1, Color.parseColor("#88FFD700"));
        return gd;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}