// LocalGameActivity.java (app-new 精简版)
// 本地人机对局（鲜花与牛粪）。完全离线，无 Supabase 依赖。
//   规则：六排（1 牛粪 + 2..6 鲜花），每人任取任意排任意数量；
//         拿最后一朵鲜花的人迫使对方拿牛粪 -> 对方输。
//   机器人（ComputerAI Nim 最优策略）在玩家「准备好了」后自动开局；
//   我方准备 + 电脑自动准备，双方就绪即开始。
package com.example.cowdunggame;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class LocalGameActivity extends Activity {

    private FrameLayout rowsContainer;
    private TextView tvGameLog;
    private Button btnAction;
    private ScrollView scrollView;

    private boolean isGameStarted = false;
    private boolean isPlayerTurn = true;
    private int selectedRow = -1;
    private int selectedCount = 0;
    private int[] remainingFlowers = {1, 2, 3, 4, 5, 6};
    private boolean[][] selectedFlowers;
    private int gameCount = 0;

    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "CowDungPrefs";
    private String playerName;
    private String playerGender = "male";
    private EditText etMessageInput;
    private Button btnNickname;

    // 人机桌状态上报（进入时传入 table_no）
    private SupabaseClient client;
    private SeatManager seatManager;
    private String tableNo;
    private String roomCode;
    private LinearLayout logLayout;
    private boolean leavingTable = false; // 防止返回键重复触发离桌

    // 观战模式：真实玩家信息（从数据库拉取，替代本地昵称）
    private boolean isWatcher = false;
    private String watcherPlayerName = "玩家";

    // 观战轮询
    private Handler watchHandler = new Handler(Looper.getMainLooper());

    // 聊天同步（REST 轮询，1.5s/次）
    private long lastChatId = 0;
    private boolean chatActive = false;
    private Handler chatHandler = new Handler(Looper.getMainLooper());
    private Runnable chatPoll;
    private final BadWordFilter badWordFilter = new BadWordFilter();
    private Runnable watchRunnable;
    private int lastRenderedMoveCount = -1;

    // ===== 人人对局（PvP）：来源 source=pvp，本机机器人关闭，改为轮询对方棋子 =====
    private boolean isPvp = false;        // 是否人人对局
    private boolean isRoom = false;        // 是否私密房间对局（source=room，行为与 PvP 一致，走房间 RPC）
    private String mySide = null;         // 本桌我坐哪侧：'a'(左/先手) / 'b'(右/后手)
    private String opponentName = "对手"; // 对方昵称（轮询到资料后更新）
    private String tvPlayerNameId = null;   // 左侧昵称对应玩家 id（点开资料用）
    private String tvComputerNameId = null; // 右侧昵称对应玩家 id（点开资料用）
    private boolean settled = false;       // 本局积分是否已上报（防重复结算）
    private String pvpANick = "等待对手入座..."; // A 侧昵称（观战/日志统一显示用）

    // 点击昵称打开对方/自己的资料卡
    private final View.OnClickListener nameClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            String uid = (v == tvPlayerName) ? tvPlayerNameId : tvComputerNameId;
            String name = (v == tvPlayerName) ? tvPlayerName.getText().toString()
                                              : tvComputerName.getText().toString();
            if (uid != null && !uid.isEmpty()) openProfile(uid, name);
        }
    };
    private String pvpBNick = "等待对手入座..."; // B 侧昵称
    private boolean pvpResultShown = false; // 防重复显示胜负图
    private boolean pvpStarted = false;   // 对方开局后才可操作（双方就绪自动开局）
    private int pvpLogMoveCount = 0;      // 已写入日志的落子步数（轮询增量补记对方落子）
    // 观战日志增量重建快照（避免每次轮询清空重画打断阅读）
    private String lastPvpWatcherTurn = "";
    private boolean lastWatcherReadyA = false;
    private boolean lastWatcherReadyB = false;

    // 本局落子记录（每步上报数据库供观战重放）
    private JSONArray moveList = new JSONArray();

    private TextView tvPlayerName;
    private TextView tvComputerName;
    private TextView tvPlayerCountdown;
    private TextView tvComputerCountdown;
    private Button btnExitGame;
    private ImageView imgPlayerFinger;
    private ImageView imgComputerFinger;

    private Handler countdownHandler = new Handler(Looper.getMainLooper());
    private Runnable countdownRunnable;
    private int countdownSeconds;

    private Handler hintHandler = new Handler(Looper.getMainLooper());
    private Runnable hintRunnable;
    private String hintMessage = "";
    private boolean hintShowing = false;

    private Handler popupHandler;      // 观众列表弹窗 5 秒自动隐藏
    private PopupWindow watcherPopup;  // 观众列表弹窗（销毁时关闭，避免销毁后 dismiss 异常）

    private SoundPool soundPool;
    private int soundDida;
    private int soundSend;
    private int soundWin;
    private int soundLose;
    private boolean soundEnabled = true;

    private ImageView resultImage;

    private static final int PLAYER_TURN_SECONDS = 180;
    private static final int COMPUTER_THINK_SECONDS = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        playerName = sharedPreferences.getString("PlayerName", "");
        playerGender = sharedPreferences.getString("PlayerGender", "male");
        soundEnabled = sharedPreferences.getBoolean("soundEnabled", true);

        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("table_no")) {
            tableNo = intent.getStringExtra("table_no");
            if (tableNo == null) tableNo = String.valueOf(intent.getIntExtra("table_no", 0));
        }
        roomCode = getIntent() != null ? getIntent().getStringExtra("room_code") : null;
        String role = getIntent() != null ? getIntent().getStringExtra("role") : null;
        String source = getIntent() != null ? getIntent().getStringExtra("source") : null;
        isPvp = "pvp".equals(source);
        isRoom = "room".equals(source);
        isWatcher = "watcher".equals(role);
        if (isRoom && roomCode != null && roomCode.length() == 4) {
            tableNo = roomCode; // 私密房间：桌号即 4 位房间号
        }
        if (tableNo != null && !tableNo.isEmpty()) {
            client = new SupabaseClient(this);
            seatManager = new SeatManager(client);
            tvPlayerNameId = client.getUserId(); // 左侧默认是自己
            // 遗言：玩家/观众进程存活期间持续心跳（20s/次，配合服务端 3 分钟超时兜底）
            if (isPvp) {
                seatManager.startPvpHeartbeat(tableNo);
            } else if (isRoom) {
                seatManager.startRoomHeartbeat(tableNo);
            } else {
                seatManager.startHeartbeat(tableNo);
            }
        }

        initChat();

        resetSelectionState();

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.BLACK);

        int boardHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        rowsContainer = new FrameLayout(this);
        rowsContainer.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, boardHeight);
        boardParams.setMargins(20, 0, 20, 0);
        rowsContainer.setLayoutParams(boardParams);

        final float goldenRatio = 0.06f;
        final int screenH = getResources().getDisplayMetrics().heightPixels;
        final int goldenHeight = (int) (screenH * goldenRatio);
        final int middleHeight = (int) (goldenHeight * 0.9f);
        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(Gravity.CENTER_VERTICAL);
        buttonLayout.setWeightSum(100);
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setShape(GradientDrawable.RECTANGLE);
        frameBg.setCornerRadius(dp(10));
        frameBg.setColor(Color.BLACK);
        frameBg.setStroke(2, Color.parseColor("#FFD700"));
        buttonLayout.setBackground(frameBg);
        buttonLayout.setPadding(dp(3), dp(1), dp(3), dp(1));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, goldenHeight);
        buttonParams.setMargins(20, dp(3), 20, dp(3));
        buttonLayout.setLayoutParams(buttonParams);

        tvPlayerName = new TextView(this);
        tvPlayerName.setText(playerName == null || playerName.isEmpty() ? "玩家" : playerName);
        tvPlayerName.setTextSize(13);
        tvPlayerName.setTextColor(Color.WHITE);
        tvPlayerName.setGravity(Gravity.CENTER);
        tvPlayerName.setSingleLine(true);
        tvPlayerName.setBackground(roundedStrokeBg(0xFF1C1C1C));
        LinearLayout.LayoutParams name1Params = new LinearLayout.LayoutParams(0, middleHeight, 20);
        name1Params.setMargins(dp(2), 0, dp(2), 0);
        tvPlayerName.setLayoutParams(name1Params);
        buttonLayout.addView(tvPlayerName);

        FrameLayout cell2 = new FrameLayout(this);
        LinearLayout.LayoutParams cell2Params = new LinearLayout.LayoutParams(0, middleHeight, 20);
        cell2Params.setMargins(dp(2), 0, dp(2), 0);
        cell2.setLayoutParams(cell2Params);

        tvPlayerCountdown = new TextView(this);
        tvPlayerCountdown.setTextSize(16);
        tvPlayerCountdown.setTextColor(Color.parseColor("#FFD700"));
        tvPlayerCountdown.setGravity(Gravity.CENTER);
        tvPlayerCountdown.setVisibility(View.INVISIBLE);
        tvPlayerCountdown.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        cell2.addView(tvPlayerCountdown);

        btnExitGame = new Button(this);
        btnExitGame.setText("离开棋局");
        btnExitGame.setTextSize(12);
        btnExitGame.setTextColor(Color.WHITE);
        btnExitGame.setBackground(roundedStrokeBg(0xFFB71C1C));
        btnExitGame.setPadding(0, 0, 0, 0);
        btnExitGame.setVisibility(View.VISIBLE);
        btnExitGame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleExitPress();
            }
        });
        btnExitGame.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        cell2.addView(btnExitGame);

        imgPlayerFinger = new ImageView(this);
        imgPlayerFinger.setImageResource(R.drawable.left_hand);
        imgPlayerFinger.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgPlayerFinger.setVisibility(View.INVISIBLE);
        imgPlayerFinger.setLayoutParams(new FrameLayout.LayoutParams(
            middleHeight, middleHeight, Gravity.CENTER));
        cell2.addView(imgPlayerFinger);
        buttonLayout.addView(cell2);

        btnAction = new Button(this);
        btnAction.setText("准备好了");
        btnAction.setTextSize(13);
        btnAction.setTextColor(Color.WHITE);
        btnAction.setBackground(roundedStrokeBg(0xFF3A3A3A));
        btnAction.setPadding(0, 0, 0, 0);
        btnAction.setLayoutParams(new LinearLayout.LayoutParams(0, middleHeight, 20));
        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isGameStarted) {
                    markPlayerReady();
                } else if (selectedRow != -1 && selectedCount > 0) {
                    takeFlowers();
                } else {
                    addLog("请先选择要拿取的鲜花");
                }
            }
        });
        btnAction.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        hintHandler.removeCallbacks(hintRunnable);
                        hintRunnable = new Runnable() {
                            @Override
                            public void run() {
                                showTurnHint();
                            }
                        };
                        hintHandler.postDelayed(hintRunnable, 3000);
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        hintHandler.removeCallbacks(hintRunnable);
                        hideTurnHint();
                        break;
                }
                return false;
            }
        });
        buttonLayout.addView(btnAction);

        FrameLayout cell4 = new FrameLayout(this);
        LinearLayout.LayoutParams cell4Params = new LinearLayout.LayoutParams(0, middleHeight, 20);
        cell4Params.setMargins(dp(2), 0, dp(2), 0);
        cell4.setLayoutParams(cell4Params);

        tvComputerCountdown = new TextView(this);
        tvComputerCountdown.setTextSize(16);
        tvComputerCountdown.setTextColor(Color.parseColor("#FFD700"));
        tvComputerCountdown.setGravity(Gravity.CENTER);
        tvComputerCountdown.setVisibility(View.INVISIBLE);
        tvComputerCountdown.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        cell4.addView(tvComputerCountdown);

        imgComputerFinger = new ImageView(this);
        imgComputerFinger.setImageResource(R.drawable.right_hand);
        imgComputerFinger.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgComputerFinger.setVisibility(View.INVISIBLE);
        imgComputerFinger.setLayoutParams(new FrameLayout.LayoutParams(
            middleHeight, middleHeight, Gravity.CENTER));
        cell4.addView(imgComputerFinger);
        buttonLayout.addView(cell4);

        tvComputerName = new TextView(this);
        tvComputerName.setText("电脑");
        tvComputerName.setTextSize(13);
        tvComputerName.setTextColor(Color.WHITE);
        tvComputerName.setGravity(Gravity.CENTER);
        tvComputerName.setSingleLine(true);
        tvComputerName.setBackground(roundedStrokeBg(0xFF1C1C1C));
        LinearLayout.LayoutParams name5Params = new LinearLayout.LayoutParams(0, middleHeight, 20);
        name5Params.setMargins(dp(2), 0, dp(2), 0);
        tvComputerName.setLayoutParams(name5Params);
        buttonLayout.addView(tvComputerName);

        // 昵称可点开资料卡（有 id 时才真正可点）
        tvPlayerName.setClickable(true);
        tvPlayerName.setOnClickListener(nameClickListener);
        tvComputerName.setClickable(true);
        tvComputerName.setOnClickListener(nameClickListener);

        logLayout = new LinearLayout(this);
        logLayout.setOrientation(LinearLayout.VERTICAL);
        logLayout.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.setMargins(20, 0, 20, 0);
        logLayout.setLayoutParams(logParams);

        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView logTitle = new TextView(this);
        logTitle.setText("游戏日志");
        logTitle.setTextSize(16);
        logTitle.setTextColor(Color.WHITE);
        logTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        logHeader.addView(logTitle);

        Button btnWatchers = new Button(this);
        btnWatchers.setText("观众列表");
        btnWatchers.setTextSize(16);
        btnWatchers.setAllCaps(false);
        btnWatchers.setTextColor(Color.WHITE);
        btnWatchers.setBackground(null);
        btnWatchers.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnWatchers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWatcherList();
            }
        });
        logHeader.addView(btnWatchers);

        logLayout.addView(logHeader);

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollView.setLayoutParams(scrollParams);

        tvGameLog = new TextView(this);
        tvGameLog.setTextSize(14);
        tvGameLog.setTextColor(Color.WHITE);
        tvGameLog.setBackgroundColor(Color.BLACK);
        scrollView.addView(tvGameLog);
        logLayout.addView(scrollView);

        // 底部聊天栏：昵称 + 输入框 + 发送（本地日志，含敏感词过滤）
        LinearLayout chatLayout = new LinearLayout(this);
        chatLayout.setOrientation(LinearLayout.HORIZONTAL);
        chatLayout.setGravity(Gravity.CENTER_VERTICAL);
        chatLayout.setWeightSum(100);
        chatLayout.setPadding(0, dp(6), 0, 0);
        int barHeight = dp(30);

        btnNickname = new Button(this);
        btnNickname.setAllCaps(false);
        btnNickname.setTextSize(12);
        btnNickname.setTextColor(Color.WHITE);
        btnNickname.setBackground(roundedStrokeBg(0xFF2D2D2D));
        btnNickname.setSingleLine(true);
        btnNickname.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams nickParams = new LinearLayout.LayoutParams(
            0, barHeight, 15);
        nickParams.setMargins(0, 0, px(4), 0);
        btnNickname.setLayoutParams(nickParams);
        updateNicknameButton();

        etMessageInput = new EditText(this);
        etMessageInput.setHint("输入消息...");
        etMessageInput.setHintTextColor(Color.GRAY);
        etMessageInput.setTextColor(Color.WHITE);
        etMessageInput.setTextSize(14);
        etMessageInput.setSingleLine(true);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setShape(GradientDrawable.RECTANGLE);
        inputBg.setCornerRadius(dp(6));
        inputBg.setColor(0xFF1C1C1C);
        inputBg.setStroke(3, Color.parseColor("#FFD700"));
        etMessageInput.setBackground(inputBg);
        etMessageInput.setPadding(px(10), px(2), px(10), px(2));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            0, barHeight, 70);
        inputParams.setMargins(0, 0, px(4), 0);
        etMessageInput.setLayoutParams(inputParams);
        etMessageInput.clearFocus();

        Button btnSendMessage = new Button(this);
        btnSendMessage.setText("发送");
        btnSendMessage.setTextSize(13);
        btnSendMessage.setTextColor(Color.WHITE);
        btnSendMessage.setBackground(roundedStrokeBg(0xFF1E88E5));
        btnSendMessage.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
            0, barHeight, 15);
        btnSendMessage.setLayoutParams(sendParams);
        btnSendMessage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendChatMessage();
            }
        });

        chatLayout.addView(btnNickname);
        chatLayout.addView(etMessageInput);
        chatLayout.addView(btnSendMessage);
        logLayout.addView(chatLayout);

        mainLayout.addView(rowsContainer);
        mainLayout.addView(buttonLayout);
        mainLayout.addView(logLayout);

        setContentView(mainLayout);

        initSound();
        if (isWatcher) {
            setupWatcherMode();
        } else if (isPvp || isRoom) {
            setupPvpPlayerMode();
        } else {
            setupGameBoard(false);
            showGameRules();
            addLog("机器人已就座并自动准备好，点「准备好了」开始");
        }
    }

    // ===== 人人对局（PvP）玩家模式 =====
    // 与 PvE 的区别：无本机 AI；整包 game_state 每 2s 轮询同步；
    //   - 开局：先点「准备好了」上报 pvp_ready；双方就绪 -> 服务端置 playing，A 先手
    //   - 轮到我的回合：棋盘可点击；提交后整包上报，轮询等对方落子
    //   - 胜负：轮询读到 finished，winner 判定我胜/负，显示结果图（不上本地结算）
    private void setupPvpPlayerMode() {
        tvPlayerName.setText(playerName == null || playerName.isEmpty() ? "我" : playerName);
        btnAction.setEnabled(false);
        btnAction.setText("准备好了");
        setupGameBoard(false);
        showPvpRules();
        if (isRoom) {
            addLog("私密房间 #" + tableNo + "：点「准备好了」，双方就绪开局（首局左边先手，之后每局交换）");
        } else {
            addLog("人人对局：坐下即对战。点「准备好了」，双方就绪开局（首局左边先手，之后每局交换）");
        }
        startPvpPolling();
    }

    private void showPvpRules() {
        if (tvGameLog != null) tvGameLog.setText("");
        String[] rules = {
            "=== 鲜花与牛粪（人人对战）===",
            "1. 首局先入座者（左）先手，之后每局交换先后手，双方轮流拿花",
            "2. 不能拿牛粪，只能拿鲜花",
            "3. 被迫拿走牛粪的玩家输掉游戏",
            "操作：",
            "1. 点「准备好了」等待对手",
            "2. 轮到你了，点选鲜花（变暗=已选），点「确认选择」",
            "3. 对方回合等待其落子，画面自动刷新"
        };
        for (String rule : rules) {
            tvGameLog.append(rule + "\n");
        }
    }

    // PvP 轮询：每 2s 拉取本桌 game_state，同步棋盘与回合
    private void startPvpPolling() {
        watchHandler.removeCallbacks(watchRunnable);
        watchRunnable = new Runnable() {
            @Override
            public void run() {
                pollPvpOnce();
                watchHandler.postDelayed(watchRunnable, 2000);
            }
        };
        watchHandler.postDelayed(watchRunnable, 500);
    }

    private void pollPvpOnce() {
        if (client == null || tableNo == null) return;
        async(new Runnable() {
            @Override
            public void run() {
                final JSONObject table = isRoom ? client.fetchRoom(tableNo)
                    : client.fetchPvpTable(tableNo);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        renderPvpState(table);
                    }
                });
            }
        });
    }

    // 用数据库 game_state 驱动：回合指示 / 棋盘 / 胜负 / 双方昵称
    private void renderPvpState(JSONObject table) {
        if (table == null) return;

        // 确定我坐哪侧
        String uid = client != null ? client.getUserId() : null;
        if (mySide == null) {
            mySide = SeatManager.mySide(table, uid);
        }

        // 双方昵称
        JSONObject a = table.optJSONObject("player_a");
        JSONObject b = table.optJSONObject("player_b");
        String aNick = (a != null ? a.optString("nickname", "") : "").trim();
        String bNick = (b != null ? b.optString("nickname", "") : "").trim();
        String aId = (a != null ? a.optString("id", "") : "").trim();
        String bId = (b != null ? b.optString("id", "") : "").trim();
        pvpANick = aNick.isEmpty() ? "等待对手入座..." : aNick;
        pvpBNick = bNick.isEmpty() ? "等待对手入座..." : bNick;
        if ("a".equals(mySide)) {
            tvPlayerName.setText(aNick.isEmpty() ? (playerName.isEmpty() ? "我" : playerName) : aNick);
            tvPlayerNameId = aId.isEmpty() ? tvPlayerNameId : aId;
            opponentName = bNick.isEmpty() ? "等待对手入座..." : bNick;
            tvComputerNameId = bId.isEmpty() ? tvComputerNameId : bId;
        } else if ("b".equals(mySide)) {
            tvPlayerName.setText(bNick.isEmpty() ? (playerName.isEmpty() ? "我" : playerName) : bNick);
            tvPlayerNameId = bId.isEmpty() ? tvPlayerNameId : bId;
            opponentName = aNick.isEmpty() ? "等待对手入座..." : aNick;
            tvComputerNameId = aId.isEmpty() ? tvComputerNameId : aId;
        }
        tvComputerName.setText(opponentName);

        String st = table.optString("status", "open");
        JSONObject gs = table.optJSONObject("game_state");
        if (gs == null) gs = new JSONObject();
        String gsStatus = gs.optString("status", "");

        if ("finished".equals(gsStatus)) {
            // 胜负已定：winner 'a'/'b'
            String winner = gs.optString("winner", "");
            boolean iWon = winner.equals(mySide);
            // 积分结算（人人 +5/-1，私密 +10/-2），仅结算一次
            if (!settled) {
                settled = true;
                final String wId = "a".equals(winner) ? aId : bId;
                final String lId = "a".equals(winner) ? bId : aId;
                final String rType = isRoom ? "private" : "lobby";
                final String tId = isRoom ? null : tableNo;
                final String rCode = isRoom ? roomCode : null;
                if (!wId.isEmpty() && !lId.isEmpty() && client != null) {
                    async(new Runnable() {
                        @Override
                        public void run() {
                            client.finishGame(tId, rCode, rType, wId, lId);
                        }
                    });
                }
            }
            if (!pvpResultShown) {
                pvpResultShown = true;
                isGameStarted = false;
                stopCountdown();
                btnAction.setEnabled(true);
                btnAction.setText("准备好了");
                // 先按最终棋盘重绘：把最后一手（拿花）同步到本地，
                // 否则被动方（输家）屏幕停在对手最后一手之前，鲜花没被拿走却已显示输图标
                JSONArray endFlowers = gs.optJSONArray("flowers");
                if (endFlowers != null && endFlowers.length() == 6) {
                    for (int i = 0; i < 6; i++) {
                        remainingFlowers[i] = endFlowers.optInt(i, 0);
                    }
                }
                // 重绘必须放在 showResultImage 之前且只执行一次：
                // setupGameBoard 会 removeAllViews 清掉 rowsContainer，
                // 若每次轮询都执行会把胜负图标一起清掉（图标应保留到「准备好了」）
                setupGameBoard(false);
                // 保留对局过程日志，仅追加本局结果
                if ("a".equals(winner) || "b".equals(winner)) {
                    addLog(iWon ? "你赢了！" : "你输了，" + opponentName + " 赢了");
                }
                addLog("本局结束，点「准备好了」再来一局");
                if (iWon) {
                    playWin();
                    showResultImage(true);
                } else {
                    playLose();
                    showResultImage(false);
                }
            }
            return;
        }

        // 等待开局 / 对局中
        if ("playing".equals(st) && "ongoing".equals(gsStatus)) {
            if (!isGameStarted) {
                isGameStarted = true;
                pvpStarted = true;
                gameCount++;
                // 新一局必须重置：否则第二局结束时因 pvpResultShown 已为 true
                // 不再显示结果、按钮卡在"对方回合中"，游戏无法继续
                pvpResultShown = false;
                settled = false; // 新一局重新结算积分
                pvpLogMoveCount = 0;
                moveList = new JSONArray();
                hideResultImage();
                // 从数据库还原棋盘（开局 1..6）
                JSONArray flowers = gs.optJSONArray("flowers");
                if (flowers != null && flowers.length() == 6) {
                    for (int i = 0; i < 6; i++) {
                        remainingFlowers[i] = flowers.optInt(i, 0);
                    }
                } else {
                    remainingFlowers = new int[]{1, 2, 3, 4, 5, 6};
                }
                // 回合指示：turn 为 'a'/'b'
                String turn = gs.optString("turn", "a");
                isPlayerTurn = mySide != null && mySide.equals(turn);
                resetSelectionState();
                btnAction.setEnabled(isPlayerTurn);
                btnAction.setText(isPlayerTurn ? "确认选择" : "对方回合中...");
                stopCountdown();
                setupGameBoard(isPlayerTurn);
                addLog("对局开始！" + (isPlayerTurn
                    ? "轮到" + pvpMyName() + "的回合" : "轮到" + opponentName + "的回合"));
                if (isPlayerTurn) startCountdown(true, PLAYER_TURN_SECONDS);
            } else {
                // 同步对方落子后的棋盘 & 回合
                String turn = gs.optString("turn", "");
                // 增量补记对方落子：日志与观战重放一致（我的落子已在 takeFlowers 记录于计数内）
                JSONArray gsMoves = gs.optJSONArray("moves");
                if (gsMoves != null && gsMoves.length() > pvpLogMoveCount) {
                    boolean opponentMoved = false;
                    for (int i = pvpLogMoveCount; i < gsMoves.length(); i++) {
                        JSONObject m = gsMoves.optJSONObject(i);
                        if (m == null) continue;
                        String side = m.optString("side", "");
                        int row = m.optInt("row", -1);
                        int count = m.optInt("count", 0);
                        if (!"a".equals(side) && !"b".equals(side)) continue;
                        logPvpMove(pvpNameOf(side), row, count);
                        if (mySide != null && !side.equals(mySide)) opponentMoved = true;
                    }
                    pvpLogMoveCount = gsMoves.length();
                    if (opponentMoved) playSend();
                }
                boolean myTurnNow = mySide != null && mySide.equals(turn);
                if (myTurnNow != isPlayerTurn) {
                    isPlayerTurn = myTurnNow;
                    JSONArray flowers = gs.optJSONArray("flowers");
                    if (flowers != null && flowers.length() == 6) {
                        for (int i = 0; i < 6; i++) {
                            remainingFlowers[i] = flowers.optInt(i, 0);
                        }
                    }
                    resetSelectionState();
                    btnAction.setEnabled(isPlayerTurn);
                    btnAction.setText(isPlayerTurn ? "确认选择" : "对方回合中...");
                    stopCountdown();
                    setupGameBoard(isPlayerTurn);
                    if (isPlayerTurn) {
                        addLog("轮到" + pvpMyName() + "的回合");
                    }
                    if (isPlayerTurn) startCountdown(true, PLAYER_TURN_SECONDS);
                }
            }
        } else {
            // 尚未开局：等待双方就绪
            if (btnAction != null && !isGameStarted && !pvpResultShown) {
                boolean full = SeatManager.isPvpFull(table);
                boolean iReady = SeatManager.iAmReady(table, uid);
                boolean oppReady = SeatManager.opponentReady(table, uid);
                if (full) {
                    btnAction.setEnabled(true);
                    btnAction.setText(iReady ? "已准备，等待对方..." : "准备好了");
                    if (oppReady && !iReady) {
                        addLog(opponentName + "已准备，等你准备");
                    }
                } else {
                    btnAction.setEnabled(false);
                    btnAction.setText("等待对手入座...");
                }
            }
        }
    }

    private void rebuildPvpLog(JSONObject gs, String winner) {
        if (tvGameLog == null) return;
        JSONArray moves = gs.optJSONArray("moves");
        if (moves == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 第 ").append(tableNo).append(" 桌 对局记录 ===\n");
        sb.append("先入座(A)：").append(pvpNameOf("a")).append("　后入座(B)：").append(pvpNameOf("b")).append("\n\n");
        for (int i = 0; i < moves.length(); i++) {
            JSONObject m = moves.optJSONObject(i);
            if (m == null) continue;
            String side = m.optString("side", "");
            int row = m.optInt("row", -1);
            int count = m.optInt("count", 0);
            if (!"a".equals(side) && !"b".equals(side)) continue;
            sb.append(pvpNameOf(side)).append("拿走了第").append(row + 1)
              .append("排的").append(count).append("朵鲜花\n");
        }
        sb.append("\n本局结束：");
        if ("a".equals(winner) || "b".equals(winner)) {
            String wName = pvpNameOf(winner);
            String lName = pvpNameOf("a".equals(winner) ? "b" : "a");
            sb.append(wName).append(" 赢，").append(lName).append(" 输\n");
        } else {
            sb.append("平局\n");
        }
        tvGameLog.setText(sb.toString());
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    }

    // ===== 观战模式 =====
    // 只读看牌：拉取该桌 game_state，重放每一步，显示真实玩家昵称
    private void setupWatcherMode() {
        tvPlayerName.setText(isPvp ? "观战中(人人)" : (isRoom ? "观战中(房间)" : "观战中"));
        btnAction.setEnabled(false);
        btnAction.setText("观战中");
        btnAction.setBackground(roundedStrokeBg(0xFF2D2D2D));
        btnExitGame.setText("退出观战");
        btnExitGame.setVisibility(View.VISIBLE);
        setupGameBoard(false);
        showGameRules();
        addLog("观战模式：正在拉取第 " + tableNo + " 桌对局...");
        startWatchPolling();
    }

    private void startWatchPolling() {
        watchHandler.removeCallbacks(watchRunnable);
        watchRunnable = new Runnable() {
            @Override
            public void run() {
                pollTableOnce();
                watchHandler.postDelayed(watchRunnable, 2000);
            }
        };
        watchHandler.post(watchRunnable);
    }

    private void pollTableOnce() {
        if (client == null || tableNo == null) return;
        async(new Runnable() {
            @Override
            public void run() {
                final JSONObject table = isRoom ? client.fetchRoom(tableNo)
                    : (isPvp ? client.fetchPvpTable(tableNo)
                        : client.fetchPveTable(tableNo));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        renderWatcherState(table);
                    }
                });
            }
        });
    }

    // 用数据库 game_state 重放：棋盘 + 落子记录 + 胜负 + 真实玩家名
    private void renderWatcherState(JSONObject table) {
        if (table == null) return;

        if (isPvp) {
            renderPvpWatcherState(table);
            return;
        }

        // 真实玩家昵称（从拉取到的 player 资料取）
        JSONObject player = table.optJSONObject("player");
        String nick = (player != null ? player.optString("nickname", "") : "").trim();
        if (nick.isEmpty()) nick = "玩家";
        watcherPlayerName = nick;
        tvPlayerName.setText(watcherPlayerName);
        setupComputerName("电脑");

        JSONObject gs = table.optJSONObject("game_state");
        if (gs == null) gs = new JSONObject();

        // 从题面还原棋盘：flowers 数组 -> remainingFlowers
        JSONArray flowers = gs.optJSONArray("flowers");
        if (flowers != null && flowers.length() == 6) {
            for (int i = 0; i < 6; i++) {
                remainingFlowers[i] = flowers.optInt(i, 0);
            }
            setupGameBoard(false); // 只读，不可点击
        }

        // 落子记录：一次性全部重放到日志
        JSONArray moves = gs.optJSONArray("moves");
        int moveCount = moves != null ? moves.length() : 0;
        if (moveCount != lastRenderedMoveCount) {
            lastRenderedMoveCount = moveCount;
            rebuildWatcherLog(gs, moves);
        }

        // 当前回合指示（手指）
        String turn = gs.optString("turn", "");
        String gsStatus = gs.optString("status", "");
        if ("ongoing".equals(gsStatus) && !"finished".equals(gsStatus)) {
            boolean playerTurn = "player".equals(turn);
            if (imgPlayerFinger != null) {
                imgPlayerFinger.setVisibility(playerTurn ? View.VISIBLE : View.INVISIBLE);
            }
            if (imgComputerFinger != null) {
                imgComputerFinger.setVisibility(playerTurn ? View.INVISIBLE : View.VISIBLE);
            }
        } else {
            if (imgPlayerFinger != null) imgPlayerFinger.setVisibility(View.INVISIBLE);
            if (imgComputerFinger != null) imgComputerFinger.setVisibility(View.INVISIBLE);
        }

        // 观众不关心输赢：不显示「你赢了/你输了」图，不播胜负音乐
        // （观战从不上屏胜负图，playWin/playLose 只发生在玩家模式 endGame 中）
        hideResultImage();
    }

    // 人人观战：显示双玩家昵称与回合指示（A 左/B 右），不上胜负图
    private void renderPvpWatcherState(JSONObject table) {
        JSONObject a = table.optJSONObject("player_a");
        JSONObject b = table.optJSONObject("player_b");

        // 双方玩家都已离场 -> 服务端已清空本桌观众，自动退回上一页（房间页/大厅）
        if (a == null && b == null) {
            android.widget.Toast.makeText(this, "双方玩家已离开，观战结束",
                android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String aNick = a != null ? a.optString("nickname", "") : "";
        String bNick = b != null ? b.optString("nickname", "") : "";
        if (aNick.isEmpty()) aNick = "先入座空";
        if (bNick.isEmpty()) bNick = "后入座空";
        tvPlayerName.setText(aNick);
        setupComputerName(bNick);
        tvPlayerNameId = (a != null ? a.optString("id", "") : "").trim();
        tvComputerNameId = (b != null ? b.optString("id", "") : "").trim();

        JSONObject gs = table.optJSONObject("game_state");
        if (gs == null) gs = new JSONObject();

        JSONArray flowers = gs.optJSONArray("flowers");
        if (flowers != null && flowers.length() == 6) {
            for (int i = 0; i < 6; i++) {
                remainingFlowers[i] = flowers.optInt(i, 0);
            }
            setupGameBoard(false);
        }

        boolean readyA = "a".equals(table.optString("ready_a", "false")) || Boolean.TRUE.equals(table.opt("ready_a"));
        boolean readyB = "b".equals(table.optString("ready_b", "false")) || Boolean.TRUE.equals(table.opt("ready_b"));
        String turn = gs.optString("turn", "");
        JSONArray moves = gs.optJSONArray("moves");
        int moveCount = moves != null ? moves.length() : 0;
        if (moveCount != lastRenderedMoveCount
                || !turn.equals(lastPvpWatcherTurn)
                || readyA != lastWatcherReadyA
                || readyB != lastWatcherReadyB) {
            lastRenderedMoveCount = moveCount;
            lastPvpWatcherTurn = turn;
            lastWatcherReadyA = readyA;
            lastWatcherReadyB = readyB;
            rebuildPvpWatcherLog(gs, moves, aNick, bNick, readyA, readyB);
        }

        String gsStatus = gs.optString("status", "");
        if ("ongoing".equals(gsStatus)) {
            boolean aTurn = "a".equals(turn);
            if (imgPlayerFinger != null) {
                imgPlayerFinger.setVisibility(aTurn ? View.VISIBLE : View.INVISIBLE);
            }
            if (imgComputerFinger != null) {
                imgComputerFinger.setVisibility(aTurn ? View.INVISIBLE : View.VISIBLE);
            }
        } else {
            if (imgPlayerFinger != null) imgPlayerFinger.setVisibility(View.INVISIBLE);
            if (imgComputerFinger != null) imgComputerFinger.setVisibility(View.INVISIBLE);
        }
        hideResultImage();
    }

    private void rebuildPvpWatcherLog(JSONObject gs, JSONArray moves,
                                      String aNick, String bNick,
                                      boolean readyA, boolean readyB) {
        if (tvGameLog == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 观战：第 ").append(tableNo).append(" 桌 ===\n");
        sb.append("A·先入座：" ).append(aNick)
          .append("\nB·后入座：").append(bNick).append("\n\n");
        String gsStatus = gs.optString("status", "");
        String turn = gs.optString("turn", "");
        if (("ongoing").equals(gsStatus)) {
            if (moves != null && moves.length() > 0) {
                for (int i = 0; i < moves.length(); i++) {
                    JSONObject m = moves.optJSONObject(i);
                    if (m == null) continue;
                    String side = m.optString("side", "");
                    if (!"a".equals(side) && !"b".equals(side)) continue;
                    int row = m.optInt("row", -1);
                    int count = m.optInt("count", 0);
                    sb.append(("a".equals(side) ? aNick : bNick))
                      .append("拿走了第").append(row + 1).append("排的").append(count).append("朵鲜花\n");
                }
            }
            String turnNick = "a".equals(turn) ? aNick : bNick;
            sb.append("\n系统提示：轮到 ").append(turnNick).append(" 的回合\n");
        } else if ("finished".equals(gsStatus)) {
            if (moves != null) {
                for (int i = 0; i < moves.length(); i++) {
                    JSONObject m = moves.optJSONObject(i);
                    if (m == null) continue;
                    String side = m.optString("side", "");
                    if (!"a".equals(side) && !"b".equals(side)) continue;
                    int row = m.optInt("row", -1);
                    int count = m.optInt("count", 0);
                    sb.append(("a".equals(side) ? aNick : bNick))
                      .append("拿走了第").append(row + 1).append("排的").append(count).append("朵鲜花\n");
                }
            }
            String winner = gs.optString("winner", "");
            if ("a".equals(winner) || "b".equals(winner)) {
                String wName = "a".equals(winner) ? aNick : bNick;
                String lName = "a".equals(winner) ? bNick : aNick;
                sb.append("\n本局结束：").append(wName).append(" 赢，")
                  .append(lName).append(" 输\n");
            }
        } else {
            sb.append("等待开局...\n");
            if (readyA) sb.append("系统提示：").append(aNick).append(" 已准备\n");
            if (readyB) sb.append("系统提示：").append(bNick).append(" 已准备\n");
        }
        tvGameLog.setText(sb.toString());
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    }

    private void setupComputerName(String name) {
        if (tvComputerName != null) tvComputerName.setText(name);
    }

    private void rebuildWatcherLog(JSONObject gs, JSONArray moves) {
        if (tvGameLog == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 观战：第 ").append(tableNo).append(" 桌 ===\n");
        sb.append("玩家：").append(watcherPlayerName).append("　电脑：电脑\n\n");
        String gsStatus = gs.optString("status", "");
        if (("ongoing").equals(gsStatus) || "finished".equals(gsStatus)) {
            if (moves != null) {
                for (int i = 0; i < moves.length(); i++) {
                    JSONObject m = moves.optJSONObject(i);
                    if (m == null) continue;
                    String side = m.optString("side", "");
                    int row = m.optInt("row", -1);
                    int count = m.optInt("count", 0);
                    sb.append(side.equals("computer") ? "电脑" : watcherPlayerName)
                      .append("拿走了第").append(row + 1).append("排的").append(count).append("朵鲜花\n");
                }
            }
            String winner = gs.optString("winner", "");
            if ("finished".equals(gsStatus) && !winner.isEmpty()) {
                sb.append("\n本局结束：");
                sb.append(("computer".equals(winner) ? "电脑赢了" : watcherPlayerName + " 赢了")).append("\n");
            } else if ("finished".equals(gsStatus)) {
                sb.append("\n本局结束，玩家可再来一局\n");
            }
        } else {
            sb.append("等待玩家开局...\n");
        }
        tvGameLog.setText(sb.toString());
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    }

    // ===== 日志 =====
    private void addLog(String prefix, String message) {
        String formattedMessage = "[" + prefix + "] " + message;
        if (tvGameLog != null) {
            tvGameLog.append(formattedMessage + "\n");
        }
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    }

    private void addLog(String message) {
        addLog("系统", message);
    }

    // PvP 落子日志：统一用昵称（与观战重放一致，玩家/观众看到同一套）
    private void logPvpMove(String who, int row, int count) {
        if (tvGameLog == null) return;
        tvGameLog.append(who + "拿走了第" + (row + 1)
            + "排的" + count + "朵鲜花\n");
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    }

    // 我这一侧的昵称（日志统一用昵称显示）
    private String pvpMyName() {
        if ("a".equals(mySide)) {
            return pvpANick.isEmpty() ? getPlayerName() : pvpANick;
        } else if ("b".equals(mySide)) {
            return pvpBNick.isEmpty() ? getPlayerName() : pvpBNick;
        }
        return getPlayerName();
    }

    // 指定 side 的昵称（观战/日志通用）
    private String pvpNameOf(String side) {
        return "a".equals(side)
            ? (pvpANick.isEmpty() ? "等待对手入座..." : pvpANick)
            : (pvpBNick.isEmpty() ? "等待对手入座..." : pvpBNick);
    }

    private String getPlayerName() {
        return playerName == null || playerName.isEmpty() ? "玩家" : playerName;
    }

    private void updateNicknameButton() {
        String name = playerName == null || playerName.isEmpty() ? "玩家" : playerName;
        if (btnNickname != null) btnNickname.setText(name);
        if (tvPlayerName != null) tvPlayerName.setText(name);
    }

    private void sendChatMessage() {
        String text = etMessageInput != null ? etMessageInput.getText().toString().trim() : "";
        if (text.isEmpty()) {
            addLog("请输入要发送的消息");
            return;
        }
        if (badWordFilter.containsBadWord(text)) {
            addLog("检测到不文明用语，已自动屏蔽");
        }
        final String finalText = badWordFilter.filter(text);
        final String myName = getPlayerName();
        final String myUid = client != null ? client.getUserId() : null;
        if (client != null && tableNo != null && !tableNo.isEmpty()) {
            async(new Runnable() {
                @Override
                public void run() {
                    boolean ok = client.sendChat(tableNo, myUid, myName, finalText);
                    if (ok) {
                        pollChatOnce(); // 立即拉回（含自己刚发的）
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                addLog("系统", "消息发送失败（离线或未登录）");
                            }
                        });
                    }
                }
            });
        }
        if (etMessageInput != null) {
            etMessageInput.setText("");
        }
    }

    // ===== 对局流程 =====
    private void markPlayerReady() {
        if (isGameStarted) return;
        if (isPvp || isRoom) {
            // 人人/私密房间：只上报 ready，不本地开局；开局由轮询发现双方就绪后驱动
            hideResultImage();
            btnAction.setEnabled(false);
            btnAction.setText("已准备，等待对方...");
            addLog(pvpMyName() + " 已准备");
            final SeatManager.ResultCallback cb = new SeatManager.ResultCallback() {
                @Override
                public void onResult(boolean ok, String message) {
                    if (!ok) {
                        addLog("准备失败：" + message);
                        btnAction.setEnabled(true);
                        btnAction.setText("准备好了");
                    }
                }
            };
            if (isRoom) {
                seatManager.roomReady(tableNo, cb);
            } else {
                seatManager.pvpReady(tableNo, cb);
            }
            return;
        }
        hideResultImage();
        btnAction.setEnabled(false);
        btnAction.setText("已准备");
        addLog(getPlayerName() + "已准备");
        addLog("电脑已自动准备，开盘中...");
        rowsContainer.postDelayed(new Runnable() {
            @Override
            public void run() {
                startGame();
            }
        }, 600);
    }

    private void startGame() {
        isGameStarted = true;
        gameCount++;
        isPlayerTurn = (gameCount % 2 == 1);

        remainingFlowers = new int[]{1, 2, 3, 4, 5, 6};
        moveList = new JSONArray();
        resetSelectionState();
        btnExitGame.setVisibility(View.GONE);
        stopCountdown();
        setupGameBoard(true);
        reportPlaying();
        reportState("ongoing", isPlayerTurn ? "player" : "computer", "");

        if (isPlayerTurn) {
            btnAction.setText("确认选择");
            btnAction.setEnabled(true);
            addLog("游戏开始！轮到" + getPlayerName() + "的回合，请拿取鲜花");
            startCountdown(true, PLAYER_TURN_SECONDS);
        } else {
            btnAction.setText("电脑思考中...");
            btnAction.setEnabled(false);
            addLog("游戏开始！轮到电脑的回合");
            startCountdown(false, COMPUTER_THINK_SECONDS);
            scheduleComputerTurn();
        }
    }

    private void takeFlowers() {
        if (selectedRow == -1 || selectedCount == 0) {
            addLog("请先选择要拿取的鲜花");
            return;
        }
        playSend();
        if (isPvp || isRoom) {
            // 落子日志统一用昵称（与观战重放一致，玩家/观众同一套）
            logPvpMove(pvpMyName(), selectedRow, selectedCount);
        } else {
            addLog(getPlayerName(), "拿走了第" + (selectedRow + 1) + "排的" + selectedCount + "朵鲜花");
        }
        if (isPvp || isRoom) {
            appendMove(mySide == null ? "b" : mySide, selectedRow, selectedCount);
            pvpLogMoveCount = moveList.length(); // 我的这步已本地记录，轮询时跳过
        } else {
            appendMove("player", selectedRow, selectedCount);
        }
        remainingFlowers[selectedRow] -= selectedCount;

        if (checkGameEnd()) {
            if (isPvp || isRoom) {
                addLog(pvpMyName() + "拿走了最后一朵鲜花，" + opponentName
                    + "被迫拿走牛粪，本局结束");
            } else {
                addLog("恭喜" + getPlayerName() + "赢了！" + "电脑"
                    + "被迫拿走了牛粪。");
            }
            if (isPvp || isRoom) {
                reportPvpState("finished", "", mySide == null ? "a" : mySide);
                endPvpGame(true);
            } else {
                reportState("finished", "", "player");
                endGame(true);
            }
            return;
        }

        isPlayerTurn = false;
        stopCountdown();
        resetSelectionState();
        setupGameBoard(true);
        if (isPvp || isRoom) {
            reportPvpState("ongoing", "a".equals(mySide) ? "b" : "a", "");
            btnAction.setEnabled(false);
            btnAction.setText("对方回合中...");
            addLog("轮到" + opponentName + "的回合");
            return;
        }
        reportState("ongoing", "computer", "");
        btnAction.setEnabled(false);
        btnAction.setText("电脑思考中...");
        addLog("轮到电脑的回合");
        startCountdown(false, COMPUTER_THINK_SECONDS);
        scheduleComputerTurn();
    }

    private void scheduleComputerTurn() {
        rowsContainer.postDelayed(new Runnable() {
            @Override
            public void run() {
                computerTurn();
            }
        }, COMPUTER_THINK_SECONDS * 1000);
    }

    private boolean checkGameEnd() {
        int totalFlowers = 0;
        for (int i = 1; i < remainingFlowers.length; i++) {
            totalFlowers += remainingFlowers[i];
        }
        return totalFlowers == 0;
    }

    private void computerTurn() {
        ComputerAI.Move move = ComputerAI.getNextMove(remainingFlowers);
        if (move == null) {
            addLog("恭喜" + getPlayerName() + "赢了！电脑被迫拿走了牛粪。");
            reportState("finished", "", "player");
            endGame(true);
            return;
        }
        int row = move.row;
        int count = move.count;
        playSend(); // 电脑拿花也播放「拿走」音效，与玩家拿花一致
        addLog("电脑", "拿走了第" + (row + 1) + "排的" + count + "朵鲜花");
        appendMove("computer", row, count);
        remainingFlowers[row] -= count;

        if (checkGameEnd()) {
            addLog("游戏结束！" + getPlayerName() + "被迫拿走了牛粪，电脑赢了。");
            reportState("finished", "", "computer");
            endGame(false);
            return;
        }

        stopCountdown();
        resetSelectionState();
        isPlayerTurn = true;
        setupGameBoard(true);
        reportState("ongoing", "player", "");
        btnAction.setEnabled(true);
        btnAction.setText("确认选择");
        addLog("轮到" + getPlayerName() + "的回合，请拿取鲜花");
        startCountdown(true, PLAYER_TURN_SECONDS);
    }

    private void endGame(boolean playerWin) {
        isGameStarted = false;
        stopCountdown();
        remainingFlowers = new int[]{1, 2, 3, 4, 5, 6};
        resetSelectionState();
        setupGameBoard(false);
        btnAction.setText("准备好了");
        btnAction.setEnabled(true);
        btnExitGame.setVisibility(View.VISIBLE);
        addLog("本局结束，可再来一局");
        if (playerWin) {
            playWin();
        } else {
            playLose();
        }
        showResultImage(playerWin);
        reportSeated(); // 对局结束，回到「已入座」状态
        // 人机积分：赢 +1（胜场+1），输仅总场次+1（不扣分也不加分）
        if (client != null) {
            final boolean won = playerWin;
            async(new Runnable() {
                @Override
                public void run() {
                    client.pveFinish(client.getUserId(), won);
                }
            });
        }
    }

    // 打开玩家资料卡（点昵称弹出半屏圆角弹窗，白底黑字，悬浮在棋盘/日志区，5 秒自动隐藏）
    // 点昵称即时弹出资料卡（先用界面已有昵称秒显），再异步仅拉一次 getUserRank 刷新排名/积分
    private void openProfile(String uid, String knownName) {
        final String name = (knownName != null && !knownName.isEmpty()) ? knownName : "无名";
        // 立即展示（占位数据），避免等待两轮网络请求造成的卡顿
        ProfilePopup.show(LocalGameActivity.this, name, 0, 0, 0, 0, false, logLayout);
        if (uid == null || uid.isEmpty() || client == null) return;
        final String fid = uid;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final org.json.JSONObject rk = client.getUserRank(fid);
                if (rk == null) return;
                final int rank = rk.optInt("rank", 0);
                final int score = rk.optInt("score", 0);
                final int wins = rk.optInt("wins", 0);
                final int losses = rk.optInt("losses", 0);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 用真实数据刷新（ProfilePopup 静态 handler 会取消上一次并替换弹窗）
                        ProfilePopup.show(LocalGameActivity.this, name, score, rank,
                                wins, losses, false, logLayout);
                    }
                });
            }
        }).start();
    }

    // ===== 人机桌状态上报（仅当从大厅带桌号进入） =====
    private void reportPlaying() {
        async(new Runnable() {
            @Override
            public void run() {
                client.pveStart(tableNo);
            }
        });
    }

    private void reportSeated() {
        async(new Runnable() {
            @Override
            public void run() {
                client.pveEnd(tableNo);
            }
        });
    }

    // 记录一步落子（player 或 computer），供整包上报
    private void appendMove(String side, int row, int count) {
        JSONObject m = new JSONObject();
        try {
            m.put("side", side);
            m.put("row", row);
            m.put("count", count);
        } catch (Exception ignore) { }
        moveList.put(m);
    }

    // 整包上报当前棋局到数据库（观战重放的数据源）
    // status: ongoing/等待/finished；turn: 当前该谁走；winner: 胜者
    private void reportState(final String status, final String turn, final String winner) {
        if (isWatcher || client == null || tableNo == null) return;
        final JSONObject state = new JSONObject();
        try {
            JSONArray flowers = new JSONArray();
            for (int v : remainingFlowers) flowers.put(v);
            state.put("flowers", flowers);
            state.put("turn", turn);
            state.put("winner", winner);
            state.put("status", status);
            JSONArray movesCopy = new JSONArray();
            for (int i = 0; i < moveList.length(); i++) {
                movesCopy.put(moveList.get(i));
            }
            state.put("moves", movesCopy);
        } catch (Exception ignore) { }
        async(new Runnable() {
            @Override
            public void run() {
                client.pveReportState(tableNo, state);
            }
        });
    }

    // 人人对局（PvP）整包上报：走 pvp_report_state，side 用 'a'/'b'
    private void reportPvpState(final String status, final String turn, final String winner) {
        if (isWatcher || client == null || tableNo == null) return;
        final JSONObject state = new JSONObject();
        try {
            JSONArray flowers = new JSONArray();
            for (int v : remainingFlowers) flowers.put(v);
            state.put("flowers", flowers);
            state.put("turn", turn);
            state.put("winner", winner);
            state.put("status", status);
            JSONArray movesCopy = new JSONArray();
            for (int i = 0; i < moveList.length(); i++) {
                movesCopy.put(moveList.get(i));
            }
            state.put("moves", movesCopy);
        } catch (Exception ignore) { }
        async(new Runnable() {
            @Override
            public void run() {
                if (isRoom) {
                    client.roomReportState(tableNo, state);
                } else {
                    client.pvpReportState(tableNo, state);
                }
            }
        });
    }

    // 人人对局结束（本地判定我赢/输后）：显示结果图，回到可再准备；不下方重置棋盘
    private void endPvpGame(boolean iWon) {
        isGameStarted = false;
        stopCountdown();
        resetSelectionState();
        setupGameBoard(false);
        btnAction.setText("准备好了");
        btnAction.setEnabled(true);
        if (iWon) {
            playWin();
        } else {
            playLose();
        }
        showResultImage(iWon);
    }

    // 拉取并展示当前桌的观众昵称（仅观众，不含玩家）
    private void showWatcherList() {
        if (client == null || tableNo == null) return;
        final String mode = isRoom ? "room" : (isPvp ? "pvp" : "pve");
        final String idOrCode = isRoom ? roomCode : tableNo;
        if (idOrCode == null || idOrCode.isEmpty()) return;
        async(new Runnable() {
            @Override
            public void run() {
                final List<String> names = client.fetchWatcherNicknames(mode, idOrCode);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showWatcherListDialog(names);
                    }
                });
            }
        });
    }

    // 观众列表弹窗：白底黑字、圆角、宽=屏宽1/2；悬浮在游戏日志区域内，
    // 通过计算日志区在屏幕中的位置与高度权重来定位，绝不遮挡下方操作按钮。
    private void showWatcherListDialog(List<String> names) {
        if (names == null) names = new ArrayList<>();
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int popupW = screenW / 2;

        TextView title = new TextView(this);
        title.setText("观众列表（" + names.size() + " 人）");
        title.setTextSize(16);
        title.setTextColor(Color.BLACK);
        title.setPadding(dp(16), dp(12), dp(16), 0);

        TextView body = new TextView(this);
        StringBuilder sb = new StringBuilder();
        if (names.isEmpty()) {
            sb.append("暂无观众");
        } else {
            for (String n : names) {
                sb.append("· ").append(n).append("\n");
            }
        }
        body.setText(sb.toString().trim());
        body.setTextSize(14);
        body.setTextColor(Color.BLACK);
        body.setPadding(dp(16), dp(8), dp(16), dp(12));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        content.setBackground(bg);
        content.addView(title);
        content.addView(body);

        ScrollView sv = new ScrollView(this);
        sv.addView(content);

        // 先测量内容高度，限制弹窗不超过日志区域高度（超出则内部滚动）
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int desiredH = content.getMeasuredHeight();
        int maxAllowed = (logLayout != null && logLayout.getHeight() > 0)
                ? logLayout.getHeight() : ViewGroup.LayoutParams.WRAP_CONTENT;
        int popupH = (maxAllowed != ViewGroup.LayoutParams.WRAP_CONTENT)
                ? Math.min(desiredH, maxAllowed) : desiredH;

        watcherPopup = new PopupWindow(sv, popupW, popupH, true);
        watcherPopup.setOutsideTouchable(true);
        watcherPopup.setFocusable(true);
        watcherPopup.setElevation(dp(8));

        // 5 秒无操作自动隐藏
        popupHandler = new Handler(Looper.getMainLooper());
        popupHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (watcherPopup != null && watcherPopup.isShowing()) watcherPopup.dismiss();
            }
        }, 5000);

        if (logLayout != null) {
            int[] loc = new int[2];
            logLayout.getLocationOnScreen(loc);
            int logW = logLayout.getWidth();
            int x = loc[0] + (logW - popupW) / 2;
            int y = loc[1];
            watcherPopup.showAtLocation(logLayout, Gravity.TOP | Gravity.LEFT, x, y);
        } else {
            watcherPopup.showAtLocation(findViewById(android.R.id.content),
                    Gravity.TOP | Gravity.LEFT, popupW / 2, dp(80));
        }
    }

    // ===== 聊天同步（玩家+观众，按 tableNo 聚合，1.5s 轮询）=====

    private void initChat() {
        if (client == null || tableNo == null || tableNo.isEmpty()) return;
        chatActive = true;
        chatPoll = new Runnable() {
            @Override
            public void run() {
                if (!chatActive) return;
                pollChatOnce();
                if (chatActive) chatHandler.postDelayed(this, 1500);
            }
        };
        async(new Runnable() {
            @Override
            public void run() {
                final JSONArray hist = client.fetchChatHistory(tableNo, 50);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        renderHistory(hist);
                        chatHandler.postDelayed(chatPoll, 1500);
                    }
                });
            }
        });
    }

    // 历史为降序（新→旧），按 旧→新 渲染，并把游标推到最大 id
    private void renderHistory(JSONArray hist) {
        if (hist == null || hist.length() == 0) return;
        for (int i = hist.length() - 1; i >= 0; i--) {
            JSONObject o = hist.optJSONObject(i);
            if (o == null) continue;
            long id = o.optLong("id", 0);
            String name = o.optString("sender_name", "");
            String msg = badWordFilter.filter(o.optString("message", ""));
            addLog(name, msg);
            if (id > lastChatId) lastChatId = id;
        }
    }

    // 增量拉取并渲染新消息；每条都过一次敏感词过滤（接收方过滤）
    private void pollChatOnce() {
        if (!chatActive || client == null || tableNo == null) return;
        async(new Runnable() {
            @Override
            public void run() {
                final JSONArray msgs = client.fetchChatAfter(tableNo, lastChatId);
                if (msgs == null) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < msgs.length(); i++) {
                            JSONObject o = msgs.optJSONObject(i);
                            if (o == null) continue;
                            long id = o.optLong("id", 0);
                            if (id <= lastChatId) continue;
                            String name = o.optString("sender_name", "");
                            String msg = badWordFilter.filter(o.optString("message", ""));
                            addLog(name, msg);
                            if (id > lastChatId) lastChatId = id;
                        }
                    }
                });
            }
        });
    }

    private void stopChat() {
        chatActive = false;
        chatHandler.removeCallbacksAndMessages(null);
    }

    // 后台线程执行，忽略异常
    private void async(Runnable r) {
        if (r == null || client == null || tableNo == null) return;
        new Thread(r).start();
    }

    private void showResultImage(boolean playerWin) {
        if (rowsContainer == null) return;
        hideResultImage();
        final ImageView result = new ImageView(this);
        result.setImageResource(playerWin ? R.drawable.you_win : R.drawable.you_lose);
        result.setScaleType(ImageView.ScaleType.FIT_CENTER);
        result.setBackgroundColor(Color.TRANSPARENT);
        rowsContainer.post(new Runnable() {
            @Override
            public void run() {
                int bw = rowsContainer.getWidth();
                int bh = rowsContainer.getHeight();
                int w = (int) (bw * 0.6f);
                int h = (int) (bh * 0.7f);
                FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(w, h);
                rp.gravity = Gravity.CENTER;
                result.setLayoutParams(rp);
                resultImage = result;
                rowsContainer.addView(result);
            }
        });
    }

    private void hideResultImage() {
        if (resultImage != null) {
            ViewParent parent = resultImage.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(resultImage);
            }
            resultImage = null;
        }
    }

    // ===== 退出（第 25.4 条） =====
    // 观众退出 / 玩家停摆退出：直接离开回大厅站立；玩家对局中退出：弹窗确认后判负。
    private void handleExitPress() {
        if (leavingTable) return;
        if (client == null || tableNo == null || tableNo.isEmpty()) {
            finish();
            return;
        }
        if (SeatManager.needsForfeitConfirm(isWatcher, isGameStarted)) {
            // 玩家 + 对局进行中 -> 弹窗确认，确认后判负离场
            confirmForfeitAndExit();
        } else {
            // 观众 / 停摆玩家 -> 直接离开
            leaveAndExit();
        }
    }

    // 系统返回键：走统一退出逻辑（第 25.4 条）
    @Override
    public void onBackPressed() {
        handleExitPress();
    }

    // 玩家对局中退出：弹窗「退出将判定为输」
    private void confirmForfeitAndExit() {
        stopCountdown();
        AppDialog.confirm(this,
            "离开棋局",
            "对局进行中，离开将判负，确定要离开吗？",
            "退出并判负", "取消",
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    doForfeitAndExit();
                }
            },
            null).show();
    }

    // 判负 + 离座写库 + 回大厅（写库放后台线程，界面立即退出）
    private void doForfeitAndExit() {
        if (leavingTable) return;
        leavingTable = true;
        stopCountdown();
        watchHandler.removeCallbacksAndMessages(null);
        stopChat();
        seatManager.stopHeartbeat();
        if (isPvp || isRoom) {
            // 人人桌/私密房间：退出后服务端自动判对方胜并释放座位
            if (isRoom) {
                seatManager.roomLeave(tableNo, null);
            } else {
                seatManager.pvpLeave(tableNo, null);
            }
            reportPvpState("finished", "", ("a".equals(mySide) ? "b" : "a"));
        } else {
            final JSONObject finalState = buildFinalState();
            seatManager.forfeitAndLeave(tableNo, finalState, null);
        }
        addLog("你中途退出了棋局，判定为输");
        finish();
    }

    // 观众 / 停摆玩家退出：离座写库 + 回大厅（写库放后台线程，界面立即退出）
    private void leaveAndExit() {
        if (leavingTable) return;
        leavingTable = true;
        stopCountdown();
        watchHandler.removeCallbacksAndMessages(null);
        stopChat();
        seatManager.stopHeartbeat();
        if (isPvp || isRoom) {
            if (isWatcher) {
                if (isRoom) {
                    seatManager.roomUnwatch(tableNo, null);
                } else {
                    seatManager.pvpLeaveWatch(tableNo, null);
                }
            } else {
                if (isRoom) {
                    seatManager.roomLeave(tableNo, null);
                } else {
                    seatManager.pvpLeave(tableNo, null);
                }
            }
        } else if (isWatcher) {
            seatManager.leaveWatch(tableNo, null);
        } else {
            seatManager.leaveSeat(tableNo, null);
        }
        finish();
    }

    // 组装判定为输时的最终棋局状态（含当前棋盘与落子记录）
    private JSONObject buildFinalState() {
        JSONObject state = new JSONObject();
        try {
            JSONArray flowers = new JSONArray();
            for (int v : remainingFlowers) flowers.put(v);
            state.put("flowers", flowers);
            state.put("turn", "");
            state.put("winner", "computer");
            state.put("status", "finished");
            JSONArray movesCopy = new JSONArray();
            for (int i = 0; i < moveList.length(); i++) {
                movesCopy.put(moveList.get(i));
            }
            state.put("moves", movesCopy);
        } catch (Exception ignore) { }
        return state;
    }

    // ===== 回合倒计时 =====
    private void startCountdown(final boolean playerSide, int seconds) {
        stopCountdown();
        countdownSeconds = seconds;
        final TextView tv = playerSide ? tvPlayerCountdown : tvComputerCountdown;
        final TextView other = playerSide ? tvComputerCountdown : tvPlayerCountdown;
        other.setVisibility(View.INVISIBLE);
        tv.setVisibility(View.VISIBLE);
        if (imgPlayerFinger != null) {
            imgPlayerFinger.setVisibility(playerSide ? View.INVISIBLE : View.VISIBLE);
        }
        if (imgComputerFinger != null) {
            imgComputerFinger.setVisibility(playerSide ? View.VISIBLE : View.INVISIBLE);
        }
        updateCountdownText(tv);
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                countdownSeconds--;
                if (countdownSeconds <= 0) {
                    updateCountdownText(tv);
                    tv.setVisibility(View.INVISIBLE);
                    if (playerSide) {
                        if (isPvp || isRoom) {
                            final String mySideFinal = mySide;
                            addLog(pvpMyName() + "的回合超时，判负，"
                                + pvpNameOf("a".equals(mySideFinal) ? "b" : "a") + " 赢了。");
                            reportPvpState("finished", "",
                                ("a".equals(mySideFinal) ? "b" : "a"));
                            endPvpGame(false);
                        } else {
                            addLog(getPlayerName() + "的回合超时，判负，电脑赢了。");
                            reportState("finished", "", "computer");
                            endGame(false);
                        }
                    }
                    return;
                }
                updateCountdownText(tv);
                countdownHandler.postDelayed(this, 1000);
            }
        };
        countdownHandler.postDelayed(countdownRunnable, 1000);
    }

    private void stopCountdown() {
        if (countdownRunnable != null) {
            countdownHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
        if (tvPlayerCountdown != null) tvPlayerCountdown.setVisibility(View.INVISIBLE);
        if (tvComputerCountdown != null) tvComputerCountdown.setVisibility(View.INVISIBLE);
        if (imgPlayerFinger != null) imgPlayerFinger.setVisibility(View.INVISIBLE);
        if (imgComputerFinger != null) imgComputerFinger.setVisibility(View.INVISIBLE);
    }

    private void updateCountdownText(TextView tv) {
        int m = countdownSeconds / 60;
        int s = countdownSeconds % 60;
        tv.setText(String.format(Locale.US, "%d:%02d", m, s));
    }

    // ===== 棋盘构建 =====
    private void setupGameBoard(boolean enableClicks) {
        rowsContainer.removeAllViews();

        LinearLayout centerContainer = new LinearLayout(this);
        centerContainer.setOrientation(LinearLayout.VERTICAL);
        centerContainer.setGravity(Gravity.CENTER_VERTICAL);
        centerContainer.setBackgroundColor(Color.BLACK);
        centerContainer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < 6; i++) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER_VERTICAL);
            rowLayout.setBackgroundColor(Color.BLACK);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
            rowParams.setMargins(0, 2, 0, 2);
            rowLayout.setLayoutParams(rowParams);

            rowLayout.setWeightSum(12);

            TextView label = new TextView(this);
            label.setText("第" + (i + 1) + "排: ");
            label.setTextSize(16);
            label.setTextColor(Color.WHITE);
            label.setSingleLine(true);
            label.setPadding(0, 0, px(4), 0);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            rowLayout.addView(label);

            for (int slot = 0; slot < 6; slot++) {
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                rowLayout.addView(gap);

                if (slot < remainingFlowers[i]) {
                    TextView item = new TextView(this);
                    if (i == 0) {
                        item.setText("💩");
                        item.setTextColor(Color.WHITE);
                        item.setAlpha(1.0f);
                        item.setClickable(false);
                        if (remainingFlowers[i] == 0) {
                            remainingFlowers[i] = 1;
                        }
                    } else {
                        item.setText("🌹");
                        item.setTextColor(Color.RED);
                        if (slot < selectedFlowers[i].length && selectedFlowers[i][slot]) {
                            item.setAlpha(0.5f);
                        } else {
                            item.setAlpha(1.0f);
                        }
                        if (enableClicks && isPlayerTurn) {
                            final int row = i;
                            final int position = slot;
                            item.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    onFlowerClick(row, position);
                                }
                            });
                            item.setClickable(true);
                        } else {
                            item.setClickable(false);
                        }
                    }

                    item.setTextSize(20);
                    item.setSingleLine(true);
                    item.setGravity(Gravity.CENTER);
                    item.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                    rowLayout.addView(item);
                } else {
                    View empty = new View(this);
                    empty.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                    rowLayout.addView(empty);
                }
            }

            if (i == 0) {
                TextView speaker = new TextView(this);
                speaker.setText(soundEnabled ? "🔊" : "🔇");
                speaker.setTextSize(16);
                speaker.setGravity(Gravity.CENTER);
                speaker.setClickable(true);
                speaker.setPadding(px(4), 0, 0, 0);
                speaker.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
                speaker.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleSound();
                    }
                });
                rowLayout.addView(speaker);
            }

            if (i == 1) {
                FrameLayout frame = new FrameLayout(this);
                frame.setLayoutParams(rowLayout.getLayoutParams());
                frame.setBackgroundColor(Color.BLACK);
                rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));
                frame.addView(rowLayout);

                TextView hint = new TextView(this);
                hint.setText(hintMessage != null ? hintMessage : "");
                hint.setTextColor(Color.WHITE);
                hint.setTextSize(16);
                hint.setSingleLine(true);
                hint.setPadding(px(6), 0, px(6), 0);
                if (hintMessage == null || hintMessage.isEmpty()) {
                    hint.setVisibility(View.GONE);
                }
                FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
                hp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                hint.setLayoutParams(hp);
                frame.addView(hint);

                centerContainer.addView(frame);
            } else {
                centerContainer.addView(rowLayout);
            }
        }

        rowsContainer.addView(centerContainer);
    }

    private void onFlowerClick(int row, int position) {
        if (row == 0) {
            addLog("不能拿牛粪，只能拿鲜花！");
            return;
        }
        if (selectedRow != -1 && selectedRow != row) {
            resetSelectionState();
        }
        selectedRow = row;
        if (position < selectedFlowers[row].length) {
            selectedFlowers[row][position] = !selectedFlowers[row][position];
        }
        playDida();

        selectedCount = 0;
        for (int i = 0; i < selectedFlowers[row].length; i++) {
            if (selectedFlowers[row][i]) {
                selectedCount++;
            }
        }

        setupGameBoard(true);

        if (selectedCount > 0) {
            btnAction.setText("鲜花拿来 (" + selectedCount + "朵)");
        } else {
            btnAction.setText("确认选择");
            selectedRow = -1;
        }
    }

    private void showTurnHint() {
        if (isPvp) return; // 人人对局无 AI 提示
        if (!isGameStarted || !isPlayerTurn) {
            return;
        }
        ComputerAI.Move move = ComputerAI.getHint(remainingFlowers);
        if (move == null || move.row < 1) {
            hintMessage = "";
            return;
        }
        hintMessage = "从第" + (move.row + 1) + "排拿" + move.count + "朵";
        hintShowing = true;
        setupGameBoard(true);
    }

    private void hideTurnHint() {
        if (!hintShowing) {
            return;
        }
        hintShowing = false;
        hintMessage = "";
        setupGameBoard(true);
    }

    private void toggleSound() {
        SoundSettingsDialog.show(this, new Runnable() {
            @Override
            public void run() {
                setupGameBoard(true);
            }
        });
    }

    private void resetSelectionState() {
        selectedFlowers = new boolean[6][];
        for (int i = 0; i < 6; i++) {
            selectedFlowers[i] = new boolean[i + 1];
        }
        selectedRow = -1;
        selectedCount = 0;
        if (btnAction != null) {
            btnAction.setText("确认选择");
        }
    }

    private void showGameRules() {
        if (tvGameLog != null) {
            tvGameLog.setText("");
        }
        String[] rules = {
            "=== 鲜花与牛粪游戏规则 ===",
            "游戏目标：避免拿到最后一排的牛粪",
            "1. 玩家轮流从任意一排拿走任意数量的鲜花",
            "2. 不能拿牛粪，只能拿鲜花",
            "3. 被迫拿走牛粪的玩家输掉游戏",
            "操作说明：",
            "1. 点击「准备好了」开始与电脑对战",
            "2. 点击选中要拿取的鲜花，选中的鲜花会变暗显示",
            "3. 再次点击已选中的鲜花取消选择",
            "4. 点击「确认选择 / 鲜花拿来」确认拿取"
        };
        for (String rule : rules) {
            tvGameLog.append(rule + "\n");
        }
    }

    // ===== 音效 =====
    private void initSound() {
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        soundPool = new SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build();
        soundDida = soundPool.load(this, R.raw.dida, 1);
        soundSend = soundPool.load(this, R.raw.send, 1);
        soundWin = soundPool.load(this, R.raw.win, 1);
        soundLose = soundPool.load(this, R.raw.lose, 1);
    }

    private void playDida() {
        if (soundEnabled && soundPool != null && soundDida != 0) {
            soundPool.play(soundDida, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    private void playSend() {
        if (soundEnabled && soundPool != null && soundSend != 0) {
            soundPool.play(soundSend, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    private void playWin() {
        if (soundEnabled && soundPool != null && soundWin != 0) {
            soundPool.play(soundWin, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    private void playLose() {
        if (soundEnabled && soundPool != null && soundLose != 0) {
            soundPool.play(soundLose, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        countdownHandler.removeCallbacksAndMessages(null);
        hintHandler.removeCallbacksAndMessages(null);
        watchHandler.removeCallbacksAndMessages(null);
        // 清理未在上面的「统一清理」列表中、且仍可能持有 Activity 的延时任务
        if (rowsContainer != null) rowsContainer.removeCallbacks(null);
        if (popupHandler != null) popupHandler.removeCallbacksAndMessages(null);
        if (watcherPopup != null && watcherPopup.isShowing()) watcherPopup.dismiss();
        stopChat();
        if (seatManager != null) {
            seatManager.stopHeartbeat();
            // 遗言机制：进程被清/异常退出，尽力写库释放座位/观战（服务端超时兜底）
            if (tableNo != null && !tableNo.isEmpty() && !leavingTable) {
                if (isPvp || isRoom) {
                    if (!isWatcher && isGameStarted) {
                        if (isRoom) {
                            seatManager.roomLeave(tableNo, null); // 对局中退出 -> 服务端判对方胜
                        } else {
                            seatManager.pvpLeave(tableNo, null);
                        }
                    } else if (isWatcher) {
                        if (isRoom) {
                            seatManager.roomUnwatch(tableNo, null);
                        } else {
                            seatManager.pvpLeaveWatch(tableNo, null);
                        }
                    } else {
                        if (isRoom) {
                            seatManager.roomLeave(tableNo, null);
                        } else {
                            seatManager.pvpLeave(tableNo, null);
                        }
                    }
                } else if (!isWatcher && isGameStarted) {
                    seatManager.forfeitAndLeave(tableNo, buildFinalState(), null); // 对局中判负
                } else if (isWatcher) {
                    seatManager.leaveWatch(tableNo, null);
                } else {
                    seatManager.leaveSeat(tableNo, null);
                }
            }
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    private GradientDrawable roundedStrokeBg(int fillColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(6));
        gd.setColor(fillColor);
        gd.setStroke(2, Color.WHITE);
        return gd;
    }

    private int px(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int dp(float value) {
        return px(value);
    }
}