// PrivateRoomActivity.java
// 私人房间 = 单张桌子的游戏厅：
//   进入先选择「创建房间」还是「加入房间」（输入 4 位房号）。
//   创建/加入成功后即进入单桌大厅 —— 布局逻辑与大厅一致，只是只有 1 张桌子：
//     居中的大桌子，房间号显示在桌面上，四边预留玩家/观众位。
//   点桌子选择「坐下玩游戏」或「当观众」（房主默认已在 A 座）。
//   Realtime 订阅 rooms/room_members/profiles：朋友进出桌面即时变化。
package com.example.cowdunggame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class PrivateRoomActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private SupabaseClient client;
    private RealtimeClient realtime;
    private String roomCode = "";

    private TableDishView table;
    private TextView statusText;

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
            refreshRoom();
            main.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this);
        FloorView floor = new FloorView(this);
        floor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(floor);

        // 顶栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0x99000000);
        topBar.setPadding(dp(6), 0, dp(6), 0);
        topBar.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, (int) (screenH * 0.06f)));

        Button back = new Button(this);
        back.setText("返回");
        back.setTextSize(14);
        back.setTextColor(Color.WHITE);
        back.setAllCaps(false);
        back.setBackground(roundedBg(0xFFB71C1C));
        back.setLayoutParams(new LinearLayout.LayoutParams(0, (int) (screenH * 0.045f), 1f));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        topBar.addView(back);

        TextView title = new TextView(this);
        title.setText("私人房间");
        title.setTextSize(17);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, (int) (screenH * 0.06f), 2f));
        topBar.addView(title);

        Button copyBtn = new Button(this);
        copyBtn.setText("复制房号");
        copyBtn.setTextSize(14);
        copyBtn.setTextColor(Color.WHITE);
        copyBtn.setAllCaps(false);
        copyBtn.setBackground(roundedBg(0xFF1E88E5));
        copyBtn.setLayoutParams(new LinearLayout.LayoutParams(0, (int) (screenH * 0.045f), 1f));
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (roomCode.isEmpty()) {
                    Toast.makeText(PrivateRoomActivity.this, "还没有房间号", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("room", roomCode));
                Toast.makeText(PrivateRoomActivity.this,
                    "已复制房间号 " + roomCode + "，发给朋友即可加入", Toast.LENGTH_LONG).show();
            }
        });
        topBar.addView(copyBtn);
        root.addView(topBar);

        // 中央大桌子（单桌大厅）：尺寸由屏宽按比例计算（head 反解，正方形）
        float density = getResources().getDisplayMetrics().density;
        TableDishView.RowLayout layout =
            TableDishView.computeRowLayout(screenW, density, 1, 20, 200);
        table = new TableDishView(this);
        int side = layout.tableSidePx;
        FrameLayout.LayoutParams tableParams = new FrameLayout.LayoutParams(side, side);
        tableParams.gravity = Gravity.CENTER;
        tableParams.topMargin = (int) (screenH * 0.02f);
        table.setLayoutParams(tableParams);
        table.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (roomCode.isEmpty()) {
                    Toast.makeText(PrivateRoomActivity.this,
                        "请先创建或加入房间", Toast.LENGTH_SHORT).show();
                    return;
                }
                showRoomTableChoice();
            }
        });
        root.addView(table);

        // 底部状态文字
        statusText = new TextView(this);
        statusText.setText("请选择：创建房间 或 加入房间");
        statusText.setTextSize(15);
        statusText.setTextColor(Color.WHITE);
        statusText.setGravity(Gravity.CENTER);
        statusText.setShadowLayer(dp(3), 2, 2, Color.BLACK);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, (int) (screenH * 0.05f));
        sp.gravity = Gravity.BOTTOM;
        sp.bottomMargin = (int) (screenH * 0.04f);
        statusText.setLayoutParams(sp);
        root.addView(statusText);

        setContentView(root);

        // 身份初始化后弹选择框：创建房间 / 加入房间
        SupabaseIdentity identity = new SupabaseIdentity(this);
        identity.ensureIdentity(new SupabaseIdentity.Callback() {
            @Override
            public void onDone(boolean ok, String error) {
                if (!ok) {
                    statusText.setText("身份初始化失败");
                    return;
                }
                client = identity.getClient();
                showEntryChoice();
            }
        });
    }

    // 进入私房的入口：创建房间 / 加入房间
    private void showEntryChoice() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("私人房间");
        builder.setMessage("创建一个新房间，或输入朋友的房间号加入");
        builder.setItems(new String[]{"创建房间", "加入房间"},
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        createRoom();
                    } else {
                        showJoinDialog();
                    }
                }
            });
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                statusText.setText("已取消 · 点「复制房号」旁返回或重进");
            }
        });
        builder.show();
    }

    private void createRoom() {
        statusText.setText("正在创建房间...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String code = client.createRoom();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        enterRoom(code);
                    }
                });
            }
        }).start();
    }

    private void showJoinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("加入房间");
        builder.setMessage("输入朋友的 4 位房间号");
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), 0);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("如 1234");
        content.addView(input);
        builder.setView(content);
        builder.setPositiveButton("加入", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String code = input.getText().toString().trim();
                joinRoom(code);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void joinRoom(final String code) {
        if (code == null || code.length() != 4) {
            Toast.makeText(this, "房间号是 4 位数字", Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText("正在加入房间 " + code + " ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = client.joinRoom(code);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            statusText.setText("加入失败：房间不存在或已结束");
                            showEntryChoice();
                            return;
                        }
                        enterRoom(code);
                    }
                });
            }
        }).start();
    }

    // 进入单桌大厅：设置房号并开始实时刷新
    private void enterRoom(String code) {
        if (code == null || code.isEmpty()) {
            statusText.setText("创建/加入失败");
            showEntryChoice();
            return;
        }
        roomCode = code;
        SharedPreferences.Editor ed = getSharedPreferences("CowDungPrefs",
            Context.MODE_PRIVATE).edit();
        ed.putString("RoomCode", roomCode);
        ed.apply();
        table.setRoomLabel("房间号 " + roomCode);
        statusText.setText("房间号 " + roomCode + " · 点击桌子选择当玩家或观众");
        startRealtime();
        refreshRoom();
        main.post(heartbeat);
        main.post(fallbackRefresh);
    }

    private void startRealtime() {
        realtime = new RealtimeClient(new RealtimeClient.Listener() {
            @Override
            public void onChanged(String t, String event, JSONObject record) {
                if ("rooms".equals(t) || "room_members".equals(t) || "profiles".equals(t)) {
                    refreshRoom();
                }
            }
        });
        realtime.subscribe();
    }

    private void refreshRoom() {
        if (client == null || roomCode.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray rooms = client.fetchRooms();
                final JSONArray members = client.getJsonArray(
                    "/rest/v1/room_members?select=profile:profiles(id,nickname,gender),role"
                        + "&room_code=eq." + roomCode);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        updateFromData(rooms, members);
                    }
                });
            }
        }).start();
    }

    private void updateFromData(JSONArray rooms, JSONArray members) {
        JSONObject myRoom = null;
        if (rooms != null) {
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject r = rooms.optJSONObject(i);
                if (r != null && roomCode.equals(r.optString("room_code"))) {
                    myRoom = r;
                    break;
                }
            }
        }
        if (myRoom == null) {
            statusText.setText("房间 " + roomCode + " 已关闭");
            return;
        }

        boolean playing = "playing".equals(myRoom.optString("status"));
        String a = myRoom.optString("player_a_id", "");
        String b = myRoom.optString("player_b_id", "");

        SharedPreferences sp = getSharedPreferences("CowDungPrefs", Context.MODE_PRIVATE);
        String selfGender = sp.getString("PlayerGender", "male");
        boolean left = !a.isEmpty();
        boolean right = !b.isEmpty();
        boolean leftMale = "male".equals(selfGender);
        boolean rightMale = true;
        if (!right && !left) {
            left = true;
            leftMale = "male".equals(selfGender);
        }

        int watchers = 0;
        if (members != null) {
            for (int i = 0; i < members.length(); i++) {
                JSONObject m = members.optJSONObject(i);
                if (m != null && "watcher".equals(m.optString("role"))) watchers++;
            }
        }

        table.setState(playing, left, right, watchers > 0, leftMale, rightMale);
        if (playing) {
            statusText.setText("对战中 · 房间号 " + roomCode);
        } else {
            statusText.setText("房间号 " + roomCode + " · 点击桌子选择当玩家或观众");
        }
    }

    private void showRoomTableChoice() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择");
        builder.setItems(new String[]{"坐下玩游戏", "当观众"},
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final SupabaseIdentity identity = new SupabaseIdentity(PrivateRoomActivity.this);
                    if (which == 0) {
                        identity.requirePlayerIdentity(new SupabaseIdentity.Callback() {
                            @Override
                            public void onDone(boolean ok, String error) {
                                if (!ok) return;
                                Intent intent = new Intent(PrivateRoomActivity.this, MainActivity.class);
                                intent.putExtra("source", "private");
                                startActivity(intent);
                            }
                        });
                    } else {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final boolean ok = identity.getClient().joinRoom(roomCode);
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!ok) {
                                            Toast.makeText(PrivateRoomActivity.this,
                                                "加入观战失败", Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        refreshRoom();
                                        Intent intent = new Intent(PrivateRoomActivity.this, MainActivity.class);
                                        intent.putExtra("source", "private");
                                        intent.putExtra("asWatcher", true);
                                        startActivity(intent);
                                    }
                                });
                            }
                        }).start();
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

    private GradientDrawable roundedBg(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        gd.setColor(color);
        return gd;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}