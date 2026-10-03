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
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
    // 聊天作用域：'pve:N' / 'pvp:N' / 'room:CCCC'，带模式前缀保证三种来源互不串桌
    private String chatScope;
    private LinearLayout chatLayout;
    private Button btnSendMessage;
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
            boolean left = (v == tvPlayerName);
            String uid = left ? tvPlayerNameId : tvComputerNameId;
            String name = left ? tvPlayerName.getText().toString()
                               : tvComputerName.getText().toString();
            if (uid != null && !uid.isEmpty()) {
                openProfile(uid, name);
            } else {
                // 之前这里是「什么都不做」，点了像坏了。空位/电脑/未赋值都给出明确反馈。
                if (left) {
                    Toast.makeText(LocalGameActivity.this,
                            "该座位还没有玩家入座", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(LocalGameActivity.this,
                            "电脑没有资料", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };
    private String pvpBNick = "等待对手入座..."; // B 侧昵称
    private boolean pvpResultShown = false; // 防重复显示胜负图
    private boolean pvpStarted = false;   // 对方开局后才可操作（双方就绪自动开局）
    private int pvpLogMoveCount = 0;      // 已写入日志的落子步数（轮询增量补记对方落子）
    // 人人/房间统一日志的快照：内容不变就不重画，避免每次轮询打断阅读
    private String lastPvpLogText = null;

    // 本局落子记录（每步上报数据库供观战重放）
    private JSONArray moveList = new JSONArray();
    // 最近一次轮询拿到的服务端权威棋谱。上报前要用它对齐，否则会把对方那几步覆盖掉
    private JSONArray lastServerMoves = new JSONArray();

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
    // 人人/私密房间倒计时的基线，单位秒。-1 = 本回合还没从服务端拿到。
    private volatile int serverSecsLeft = -1;
    // 上面那个基线是在「当时那一刻」取到的，这里记下那一刻的单调时钟读数，
    // 之后每 tick 用经过的真实时间算剩余。
    //
    // 不能每秒 --countdownSeconds：基线在本回合内是常量，拿它每秒做一次
    // 「和当前值比对，不等就重置」会导致 60→59→拉回60→59 无限抖动。
    // 也不能直接减去数墙钟：设备时间可被改。
    // elapsedRealtime 是单调的（含设备休眠时长），改系统时间不影响它，
    // Handler 被冻结后补跑时经过的时长也算得进去。
    private volatile long serverBaselineMs = 0L;

    private Handler hintHandler = new Handler(Looper.getMainLooper());
    private Runnable hintRunnable;
    private String hintMessage = "";
    private boolean hintShowing = false;

    private Handler popupHandler;      // 观众列表弹窗 5 秒自动隐藏
    private PopupWindow watcherPopup;  // 观众列表弹窗（销毁时关闭，避免销毁后 dismiss 异常）

    // ===== 观众「查看资料 / 踢出」=====
    // 临时禁入时长（分钟）：踢出后对方在此期间无法重新观战本桌。
    // 只踢不拦等于没踢 —— 对方 1 秒内就能重新点进来观战。
    private static final int WATCHER_BAN_MINUTES = 10;

    // 当前打开的观众资料窗对应 user_id：用来作废在途的异步排名刷新，
    // 否则用户点掉资料窗后，迟到的 getUserRank 会把窗口又拉回来。
    private String watcherInfoOpenId = null;

    // 观众轮询计数：每 N 轮探一次「我是否已被踢」，避免每次轮询都多打一个请求
    private int watchPollCount = 0;

    private SoundPool soundPool;
    private int soundDida;
    private int soundSend;
    private int soundWin;
    private int soundLose;
    private boolean soundEnabled = true;

    private ImageView resultImage;

    // 人机玩家每回合 180 秒。人人桌/私密房间不用这个值：真实秒数由服务端
    // turn_secs_left 返回（60 秒，见 fix_round_lifecycle.sql 的 pvp_turn_seconds），
    // 这里只在拉取失败时兜底显示。
    private static final int PLAYER_TURN_SECONDS = 180;
    private static final int COMPUTER_THINK_SECONDS = 2;

    // 人人桌/私密房间在 turn_secs_left 还没到的那几拍，先拿这个值把数字走起来。
    // 刻意和服务端 pvp_turn_seconds() 的 60 对齐：真值补回来时顶多差一两秒，
    // 用户看不出跳变，却换来了「取不到也一定有数字」。
    // 只影响显示，判负仍然只由服务端做。
    private static final int PVP_DISPLAY_FALLBACK_SECONDS = 60;
    // turn_secs_left 失败时的重试次数与退避间隔。3 次 × 400ms ≈ 1.2s，
    // 刚好盖住一次常见的网络抖动，又不至于拖到玩家以为卡死。
    private static final int SECS_LEFT_FETCH_RETRIES = 3;
    private static final long SECS_LEFT_RETRY_DELAY_MS = 400L;

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
            // 聊天作用域带模式前缀：人机 / 人人 / 私密房间的同号桌互不串台
            chatScope = (isRoom ? "room:" : (isPvp ? "pvp:" : "pve:")) + tableNo;
            // 遗言：玩家/观众进程存活期间持续心跳（20s/次，配合服务端 3 分钟超时兜底）
            if (isPvp) {
                seatManager.startPvpHeartbeat(tableNo);
            } else if (isRoom) {
                seatManager.startRoomHeartbeat(tableNo);
            } else {
                seatManager.startHeartbeat(tableNo);
            }
        }

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
        // 默认隐藏：只有本桌有玩家时才由 openChat() 显示
        chatLayout = new LinearLayout(this);
        chatLayout.setOrientation(LinearLayout.HORIZONTAL);
        chatLayout.setGravity(Gravity.CENTER_VERTICAL);
        chatLayout.setWeightSum(100);
        chatLayout.setPadding(0, dp(6), 0, 0);
        chatLayout.setVisibility(View.GONE);
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

        btnSendMessage = new Button(this);
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

        // targetSdk 36 下 Android 强制 edge-to-edge（windowOptOutEdgeToEdgeEnforcement 已失效），
        // 内容默认会画到状态栏/导航栏底下，看起来像全屏沉浸式。
        // 这里把系统栏 + 刘海的高度做成内边距，让棋盘夹在状态栏和导航栏之间显示。
        // mainLayout 背景是黑色，内边距区域与原本的黑色状态栏/导航栏无缝衔接。
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                // 聊天输入框在底部，键盘弹出时用 ime 高度把内容顶起来
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));

                // 棋盘原本按「整屏高度 * 0.42」算固定像素，扣掉系统栏后相对偏大会挤掉聊天日志，
                // 改为按「状态栏与导航栏之间的可见高度」重算。
                if (rowsContainer != null && rowsContainer.getLayoutParams() != null) {
                    int visible = getResources().getDisplayMetrics().heightPixels
                            - bars.top - bars.bottom;
                    if (visible > 0) {
                        ViewGroup.LayoutParams lp = rowsContainer.getLayoutParams();
                        lp.height = (int) (visible * 0.42f);
                        rowsContainer.setLayoutParams(lp);
                    }
                }
                return WindowInsetsCompat.CONSUMED;
            }
        });

        initSound();
        if (isWatcher) {
            // 观众：聊天随本桌有没有玩家开合，由轮询到桌状态后决定
            setupWatcherMode();
        } else {
            // 玩家：入座已由大厅完成，本桌必有玩家，直接开聊天
            openChat();
            if (isPvp || isRoom) {
                setupPvpPlayerMode();
            } else {
                // 人机桌：左侧恒为自己、右侧恒为电脑。
                // 【关键】人机不经过 renderPvpState，tvPlayerNameId 不会被赋值，
                // 保持 null 会让 nameClickListener 里的判空直接吞掉点击 ——
                // 表现就是「人机对战点自己昵称没反应」。这里显式补上自己的 uid。
                if (client != null) tvPlayerNameId = client.getUserId();
                tvComputerNameId = null;   // 电脑没有账号，不给资料卡
                setupGameBoard(false);
                showGameRules();
                addLog("机器人已就座并自动准备好，点「准备好了」开始");
            }
        }
    }

    // ===== 人人对局（PvP）玩家模式 =====
    // 与 PvE 的区别：无本机 AI；整包 game_state 每 2s 轮询同步；
    //   - 开局：先点「准备好了」上报 pvp_ready；双方就绪 -> 服务端置 playing，A 先手
    //   - 轮到我的回合：棋盘可点击；提交后整包上报，轮询等对方落子
    //   - 胜负：轮询读到 finished，winner 判定我胜/负，显示结果图（不上本地结算）
    // ===== 左右座位 =====
    // 棋盘左右位置是固定的：先入座(A) 在左、后入座(B) 在右（人机模式玩家恒在左）。
    // 下面这几个方法把「我 / 对手」映射到固定的左右位置，避免后入座的人把自己显示在左边——
    // 那样两个玩家的屏幕会长得一模一样，分不清谁是自己。
    private boolean iAmLeftSide() {
        if (isPvp || isRoom) {
            return !"b".equals(mySide);
        }
        return true;
    }

    private TextView myCountdownView() {
        return iAmLeftSide() ? tvPlayerCountdown : tvComputerCountdown;
    }

    private TextView opponentCountdownView() {
        return iAmLeftSide() ? tvComputerCountdown : tvPlayerCountdown;
    }

    // 手指可见性只有一个归属函数，每次渲染都无条件调用，手指位置因此是当前状态的纯函数。
    // 规则：playing 中恒显示，且只亮「非行动方」那一侧的手指（手指朝向对手，不朝自己）：
    // A 回合亮右侧(B)的手，B 回合亮左侧(A)的手。
    // 以前只在「我的回合发生翻转」时才调一次，startCountdown 里又写了一遍不同公式，
    // stopCountdown 还会把两边一起藏掉，导致手指时有时无、方向也说不清。
    private void updateTurnFinger(String status, String turn) {
        boolean playing = "ongoing".equals(status);
        boolean aTurn = "a".equals(turn);
        boolean showLeft = playing && !aTurn;
        boolean showRight = playing && aTurn;
        if (imgPlayerFinger != null) {
            imgPlayerFinger.setVisibility(showLeft ? View.VISIBLE : View.INVISIBLE);
        }
        if (imgComputerFinger != null) {
            imgComputerFinger.setVisibility(showRight ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void setupPvpPlayerMode() {
        tvPlayerName.setText(playerName == null || playerName.isEmpty() ? "我" : playerName);
        btnAction.setEnabled(false);
        btnAction.setText("准备好了");
        setupGameBoard(false);
        // 日志直接走与观战共用的渲染器，首次就用同一套格式，
        // 避免开局前是一套文案、轮询到之后又换成另一套
        renderPvpLog(new JSONObject(), "", "", false, false);
        // 立刻拉一次，别等 500ms：进房时左右归属还没定，
        // 晚一拍就会先闪一下「我方在左」，后入座的人尤其明显
        pollPvpOnce();
        startPvpPolling();
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
                        // 轮询日志：确认每 2 秒真的在跑，以及服务端返回的
                        // game_state.status / winner 到底是什么。判负后如果这里
                        // 一直看不到 finished，说明是服务端没写进来（而非界面没渲染）。
                        if (table == null) {
                            Log.d("TurnDebug", "poll table=null");
                        } else {
                            JSONObject gs = table.optJSONObject("game_state");
                            Log.d("TurnDebug", "poll st=" + table.optString("status", "?")
                                    + " gs=" + (gs == null ? "null"
                                        : gs.optString("status", "?") + "/winner="
                                          + gs.optString("winner", "")
                                          + "/timeout=" + gs.optString("timeout", "")
                                          + "/scored=" + gs.optString("scored", ""))
                                    + " deadline=" + table.optString("turn_deadline_at", "null")
                                    + " resultShown=" + pvpResultShown);
                        }
                        renderPvpState(table);
                    }
                });
            }
        });
    }

    // 用数据库 game_state 驱动：回合指示 / 棋盘 / 胜负 / 双方昵称
    private void renderPvpState(JSONObject table) {
        if (table == null) return;

        // 确定我坐哪侧。每次轮询都重新判定，不在第一次读到的结果上「锁死」：
        // 首次轮询若因入座写入尚未可见而读到 null，锁死会让后入座的人永远被当成左侧。
        String uid = client != null ? client.getUserId() : null;
        String sideNow = SeatManager.mySide(table, uid);
        if (!sideNow.equals(mySide)) {
            Log.d("SeatDebug", "mySide " + mySide + " -> " + sideNow
                    + " uid=" + uid
                    + " a_id=" + table.optString("player_a_id", "<null>")
                    + " b_id=" + table.optString("player_b_id", "<null>"));
        }
        mySide = sideNow;

        // 双方昵称
        JSONObject a = table.optJSONObject("player_a");
        JSONObject b = table.optJSONObject("player_b");
        String aNick = (a != null ? a.optString("nickname", "") : "").trim();
        String bNick = (b != null ? b.optString("nickname", "") : "").trim();
        String aId = (a != null ? a.optString("id", "") : "").trim();
        String bId = (b != null ? b.optString("id", "") : "").trim();
        pvpANick = aNick.isEmpty() ? "等待对手入座..." : aNick;
        pvpBNick = bNick.isEmpty() ? "等待对手入座..." : bNick;

        // 左右固定：先入座(A) 恒在左、后入座(B) 恒在右，与「我是谁」无关。
        // 不能按 iAmLeft 交换：iAmLeft=false（我是 B）时 leftId 会取到 bId（我自己），
        // 结果后入座的人被渲染到左格，还会点错资料卡（查成对手）。
        String leftNick = aNick;
        String leftId = aId;
        String rightNick = bNick;
        String rightId = bId;
        String oppNick = "a".equals(mySide) ? bNick : aNick;

        String myFallback = playerName == null || playerName.isEmpty() ? "我" : playerName;
        Log.d("SeatDebug", "render mySide=" + mySide + " iAmLeft=" + iAmLeftSide()
                + " leftId=" + leftId + " rightId=" + rightId);
        tvPlayerName.setText(leftNick.isEmpty() ? myFallback : leftNick);
        // 空位要置 null，不能沿用上一局的 id，否则点空位会弹出上一个人的资料
        tvPlayerNameId = leftId.isEmpty() ? null : leftId;
        opponentName = oppNick.isEmpty() ? "等待对手入座..." : oppNick;
        tvComputerName.setText(rightNick.isEmpty() ? "等待对手入座..." : rightNick);
        tvComputerNameId = rightId.isEmpty() ? null : rightId;

        String st = table.optString("status", "open");
        JSONObject gs = table.optJSONObject("game_state");
        if (gs == null) gs = new JSONObject();
        String gsStatus = gs.optString("status", "");

        // 对局进行中隐藏「离开棋局」，防止中途误触跑路；未开始与本局结束后恢复显示
        if (btnExitGame != null) {
            btnExitGame.setVisibility("ongoing".equals(gsStatus) ? View.GONE : View.VISIBLE);
        }
        // 手指每次渲染都重算一遍，不依赖「回合翻转」这种偶发事件
        updateTurnFinger(gsStatus, gs.optString("turn", ""));

        // 日志与观战共用同一个渲染器：两位玩家和所有观众看到的内容完全一致
        boolean readyA = "a".equals(table.optString("ready_a", "false"))
                || Boolean.TRUE.equals(table.opt("ready_a"));
        boolean readyB = "b".equals(table.optString("ready_b", "false"))
                || Boolean.TRUE.equals(table.opt("ready_b"));
        renderPvpLog(gs, aNick, bNick, readyA, readyB);

        if ("finished".equals(gsStatus)) {
            // 胜负已定：winner 'a'/'b'
            String winner = gs.optString("winner", "");
            Log.d("TurnDebug", "GOT finished winner=" + winner
                    + " mySide=" + mySide + " timeout=" + gs.optString("timeout", "")
                    + " resultShown=" + pvpResultShown + " settled=" + settled);
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
                // 胜负文字不再写进日志：日志要与观战逐字一致，
                // 输赢由 resultImage 表达（观众没有胜负图，也不会多出一行）
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
                lastServerMoves = new JSONArray();
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
                // 每次开局先把服务端的剩余秒数取回来，再起本地显示用的计时器。
                // 取不到（网络抖动）就退回 PLAYER_TURN_SECONDS 兜底，
                // 数字照走，判负仍由服务端负责。
                refreshServerSecsLeft();
                stopCountdown();
                setupGameBoard(isPlayerTurn);
                // 不加 if (isPlayerTurn) 门控：对手回合也要起倒计时，
                // 否则只在轮到自己时那格有数字，另一格全程空白 ——
                // 先入座和后入座看到的就是两套不同画面。
                startCountdown(isPlayerTurn, serverSecsLeftOrDefault());
                // startCountdown -> stopCountdown 会把两侧手指一起灭掉（那是给
                // 人机用的表现）。人人/房间这里立刻按当前 turn 重新点亮，否则换
                // 回合那一刻手指会消失，要等下一拍 2s 轮询才恢复。
                updateTurnFinger(gsStatus, turn);
            } else {
                // 同步对方落子后的棋盘 & 回合
                String turn = gs.optString("turn", "");
                // 增量补记对方落子：只用来判断要不要播音效，
                // 落子文字由统一日志渲染器从 game_state 生成，不在这里追加
                JSONArray gsMoves = gs.optJSONArray("moves");
                if (gsMoves != null) {
                    // 记住服务端权威棋谱：轮询到对方落子时只播音效、不写进 moveList，
                    // moveList 始终只是「我走过的那几步」，上报前靠它补齐对方部分
                    lastServerMoves = gsMoves;
                }
                if (gsMoves != null && gsMoves.length() > pvpLogMoveCount) {
                    boolean opponentMoved = false;
                    for (int i = pvpLogMoveCount; i < gsMoves.length(); i++) {
                        JSONObject m = gsMoves.optJSONObject(i);
                        if (m == null) continue;
                        String side = m.optString("side", "");
                        if (!"a".equals(side) && !"b".equals(side)) continue;
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
                    refreshServerSecsLeft();
                    stopCountdown();
                    setupGameBoard(isPlayerTurn);
                    // 回合翻转：倒计时跟着换到新的行动方那格（理由见开局分支）。
                    // isPlayerTurn 翻转后传给 startCountdown，就自动把两侧
                    // 位置对调，A/B 两个玩家看到的画面因此完全对称。
                    startCountdown(isPlayerTurn, serverSecsLeftOrDefault());
                    // 同上：把被 stopCountdown 灭掉的手指按新 turn 补回来，
                    // 保证手指始终跟随「轮到谁」而不是跟随倒计时重置的时刻。
                    updateTurnFinger(gsStatus, turn);
                }
            }
        } else {
            // 尚未开局：等待双方就绪
            // 换人时服务端会把上一局的 game_state 重置成空局（status='open'）。
            // 本地若还停在上一局，必须把 isGameStarted 落下，否则新一局开局时
            // 不会走重置分支，旧棋谱和旧结算状态会带进新一局。
            if (isGameStarted && !"ongoing".equals(gsStatus) && !"finished".equals(gsStatus)) {
                isGameStarted = false;
            }
            // 同理要清掉上一局的结果图：否则「等待开局」的日志上面
            // 还压着上一局的胜负图，按钮也被 pvpResultShown 卡住不能点。
            if (pvpResultShown) {
                hideResultImage();
                pvpResultShown = false;
            }
            if (btnAction != null && !isGameStarted && !pvpResultShown) {
                boolean full = SeatManager.isPvpFull(table);
                boolean iReady = SeatManager.iAmReady(table, uid);
                if (full) {
                    btnAction.setEnabled(true);
                    btnAction.setText(iReady ? "已准备，等待对方..." : "准备好了");
                } else {
                    btnAction.setEnabled(false);
                    btnAction.setText("等待对手入座...");
                }
            }
        }
    }

    // ===== 人人 / 房间统一日志 =====
    // 两位玩家和所有观战都调用这一个渲染器，内容完全由 game_state 推导，
    // 因此三方看到的棋盘日志逐字相同（不掺「你赢了 / 该你了」这类因人而异的措辞）。
    private String buildPvpLogText(JSONObject gs, String aNick, String bNick,
                                   boolean readyA, boolean readyB) {
        String aName = aNick == null || aNick.isEmpty() ? "先入座空" : aNick;
        String bName = bNick == null || bNick.isEmpty() ? "后入座空" : bNick;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 第 ").append(tableNo).append(" 桌 对局记录 ===\n");
        sb.append("先入座(A·左)：").append(aName).append("\n");
        sb.append("后入座(B·右)：").append(bName).append("\n\n");

        String gsStatus = gs.optString("status", "");
        String turn = gs.optString("turn", "");
        JSONArray moves = gs.optJSONArray("moves");

        if ("ongoing".equals(gsStatus) || "finished".equals(gsStatus)) {
            int moveCount = 0;
            if (moves != null) {
                for (int i = 0; i < moves.length(); i++) {
                    JSONObject m = moves.optJSONObject(i);
                    if (m == null) continue;
                    String side = m.optString("side", "");
                    if (!"a".equals(side) && !"b".equals(side)) continue;
                    int row = m.optInt("row", -1);
                    int count = m.optInt("count", 0);
                    sb.append("a".equals(side) ? aName : bName)
                      .append("拿走了第").append(row + 1)
                      .append("排的").append(count).append("朵").append(flowerEmojis(count)).append("\n");
                    // 每落一手就播报下一手是谁，和人机日志「谁走完→提示轮到谁」同节奏。
                    // 以前只在最后补一句总的回合提示，中间过程看不到换手，
                    // 两边就都只显得出先入座那人的记录。
                    String nextSide = "a".equals(side) ? "b" : "a";
                    sb.append("系统提示：轮到 ")
                      .append("a".equals(nextSide) ? aName : bName)
                      .append(" 的回合\n");
                    moveCount++;
                }
            }
            if ("finished".equals(gsStatus)) {
                String winner = gs.optString("winner", "");
                sb.append("\n本局结束：");
                if ("a".equals(winner) || "b".equals(winner)) {
                    String wName = "a".equals(winner) ? aName : bName;
                    String lName = "a".equals(winner) ? bName : aName;
                    sb.append(wName).append(" 赢，").append(lName).append(" 输\n");
                } else {
                    sb.append("平局\n");
                }
                sb.append("等待双方准备下一局\n");
            } else if (moveCount == 0) {
                // 已进入对局但还没有落子（极少见）：仍要报出当前轮到谁
                sb.append("\n系统提示：轮到 ")
                  .append("a".equals(turn) ? aName : bName).append(" 的回合\n");
            }
        } else {
            sb.append("等待开局...\n");
            if (readyA) sb.append("系统提示：").append(aName).append(" 已准备\n");
            if (readyB) sb.append("系统提示：").append(bName).append(" 已准备\n");
            sb.append("\n规则：\n");
            sb.append("1. 玩家轮流从任意一排拿走任意数量的鲜花\n");
            sb.append("2. 每次只能在同一排当中拿取任意数量的鲜花\n");
            sb.append("3. 不能拿牛粪，只能拿鲜花\n");
            sb.append("4. 被迫拿走牛粪的玩家输掉游戏\n");
            sb.append("5. 点「准备好了」等待对手，双方就绪后开局\n");
        }
        return sb.toString();
    }

    private void renderPvpLog(JSONObject gs, String aNick, String bNick,
                              boolean readyA, boolean readyB) {
        String text = buildPvpLogText(gs, aNick, bNick, readyA, readyB);
        if (text.equals(lastPvpLogText)) return;   // 内容没变就不重画，避免打断阅读
        lastPvpLogText = text;
        if (tvGameLog == null) return;
        tvGameLog.setText(text);
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
        // 每 3 轮（约 6 秒）才探一次「我是否已被踢」：这是本功能唯一的开销，
        // 不值得每 2 秒都多打一个请求。被踢后 watcher 行已被服务端删掉，
        // 所以 ban>0 只可能来自「我正在被观战且刚被踢」这一种情况。
        final boolean checkBan = (++watchPollCount % 3 == 0);
        final String mode = isRoom ? "room" : (isPvp ? "pvp" : "pve");
        final String idOrCode = isRoom ? roomCode : tableNo;
        async(new Runnable() {
            @Override
            public void run() {
                final JSONObject table = isRoom ? client.fetchRoom(tableNo)
                    : (isPvp ? client.fetchPvpTable(tableNo)
                        : client.fetchPveTable(tableNo));
                // 注意：查不到（网络抖动/解析失败）时 fetchMyBanSeconds 返回 0，
                // 宁可漏踢一次也不要因为一次请求失败就把人误踢出房间。
                int banSec = 0;
                if (checkBan && (isPvp || isRoom) && idOrCode != null && !idOrCode.isEmpty()) {
                    banSec = client.fetchMyBanSeconds(mode, idOrCode);
                }
                final int banFinal = banSec;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 被踢优先于桌态渲染：本桌玩家还在，renderWatcherState 会
                        // 继续把界面当成正常观战刷下去，跟退回逻辑打架。
                        if (banFinal > 0) {
                            exitBecauseKicked(banFinal);
                            return;
                        }
                        renderWatcherState(table);
                    }
                });
            }
        });
    }

    // 用数据库 game_state 重放：棋盘 + 落子记录 + 胜负 + 真实玩家名
    private void renderWatcherState(JSONObject table) {
        if (table == null) return;

        // 人人桌与私密房都是「两个真人玩家」：观战渲染完全一致（A 左 / B 右）。
        // 原来这里只判 isPvp，私密房漏进来走了下面的人机分支，
        // 于是房内观众看到的是「玩家 / 电脑」而不是房里两个真人的画面。
        if (isPvp || isRoom) {
            renderPvpWatcherState(table);
            return;
        }

        // 人机观战：player_id 为空即无人（服务端已在座位归零时清空本桌观战关系）
        if (table.isNull("player_id") || table.optString("player_id", "").isEmpty()) {
            exitBecauseTableClosed("本桌玩家已离开，观战结束");
            return;
        }

        // 本桌有玩家 -> 聊天开；玩家全走 -> 聊天关（由 closeChat 负责隐藏与停轮询）
        if (!chatActive) openChat();

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

        // 当前回合指示（手指）：挂在行动方对面——玩家回合时电脑的手指指着玩家
        String turn = gs.optString("turn", "");
        String gsStatus = gs.optString("status", "");
        if ("ongoing".equals(gsStatus) && !"finished".equals(gsStatus)) {
            boolean playerTurn = "player".equals(turn);
            if (imgPlayerFinger != null) {
                imgPlayerFinger.setVisibility(playerTurn ? View.INVISIBLE : View.VISIBLE);
            }
            if (imgComputerFinger != null) {
                imgComputerFinger.setVisibility(playerTurn ? View.VISIBLE : View.INVISIBLE);
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

        // 双方玩家都已离场 -> 服务端已清空本桌观众，聊天关闭并退回大厅
        if (a == null && b == null) {
            exitBecauseTableClosed("双方玩家已离开，观战结束");
            return;
        }

        // 本桌有人 -> 聊天开
        if (!chatActive) openChat();

        String aNick = a != null ? a.optString("nickname", "") : "";
        String bNick = b != null ? b.optString("nickname", "") : "";
        tvPlayerName.setText(aNick.isEmpty() ? "先入座空" : aNick);
        setupComputerName(bNick.isEmpty() ? "后入座空" : bNick);
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
        // 与玩家共用同一个日志渲染器，保证观战和两位玩家看到逐字相同的棋盘记录
        renderPvpLog(gs, aNick, bNick, readyA, readyB);

        String gsStatus = gs.optString("status", "");
        // 手指不受分支控制：playing 就按当前 turn 亮一侧，非 playing 两边都灭
        updateTurnFinger(gsStatus, gs.optString("turn", ""));
        hideResultImage();
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
                      .append("拿走了第").append(row + 1).append("排的").append(count).append("朵").append(flowerEmojis(count)).append("\n");
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

    // 「N朵鲜花」里的鲜花换成 N 个 🌸，例：5朵🌸🌸🌸🌸🌸
    private static String flowerEmojis(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append("🌸");
        return sb.toString();
    }

    // PvP 落子日志：统一用昵称（与观战重放一致，玩家/观众看到同一套）
    private void logPvpMove(String who, int row, int count) {
        if (tvGameLog == null) return;
        tvGameLog.append(who + "拿走了第" + (row + 1)
            + "排的" + count + "朵" + flowerEmojis(count) + "\n");
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
        // 人机桌左侧恒为自己：昵称刷新后要保证资料卡仍能点开
        if (!isPvp && !isRoom && client != null) tvPlayerNameId = client.getUserId();
    }

    private void sendChatMessage() {
        // 聊天已关闭（本桌无玩家 / 已离桌）：直接拒绝，不落库
        if (!chatActive || chatScope == null) {
            addLog("系统", "本桌聊天已关闭");
            return;
        }
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
        final String scope = chatScope;
        if (client != null) {
            async(new Runnable() {
                @Override
                public void run() {
                    boolean ok = client.sendChat(scope, myUid, myName, finalText);
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
            addLog(getPlayerName(), "拿走了第" + (selectedRow + 1) + "排的" + selectedCount + "朵" + flowerEmojis(selectedCount));
        }
        if (isPvp || isRoom) {
            // 上报前先以服务端棋谱为准再叠加我这步。moveList 只装得下「我自己走过的那几步」，
            // 以前直接整包上传，会把对方那几步从 game_state.moves 里抹掉，
            // 于是日志里只剩先入座的人有记录。
            syncMoveListFromServer();
            appendMove(mySide == null ? "b" : mySide, selectedRow, selectedCount);
            pvpLogMoveCount = moveList.length(); // 我的这步已本地记录，轮询时跳过
        } else {
            appendMove("player", selectedRow, selectedCount);
        }
        remainingFlowers[selectedRow] -= selectedCount;

        if (checkGameEnd()) {
            if (!(isPvp || isRoom)) {
                // 人机模式日志是逐条追加的，可以直接写结论；
                // 人人/房间的统一日志由 game_state 推导，这里不写，避免和渲染结果打架
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
            // 上一行已经 isPlayerTurn = false，也就是本地先把回合交给了对方，
            // 而服务端此刻还停在「轮到我」—— 轮询要等下一次才读得到新的 turn，
            // 并且因为 isPlayerTurn 已经是 false，那个翻转分支根本不会进
            // （false != false）。所以对手那格的倒计时必须在这里自己起，
            // 否则谁刚提交完自己那手，谁的屏幕上就空一整个对手回合。
            // 同一函数下面的 PvE 分支本来就有 startCountdown，只有人人漏了。
            //
            // 先作废基线：不作废的话 serverSecsLeft 还是我自己这一回合的残值，
            // 会被原样搬到对方那格，显示成「对手只剩 0:02」这种假象。
            invalidateServerSecsLeft();
            // 兜底值先点亮，别让玩家对着空格等一个 RTT。
            startCountdown(isPlayerTurn, PVP_DISPLAY_FALLBACK_SECONDS);
            reportPvpState("ongoing", "a".equals(mySide) ? "b" : "a", "", new Runnable() {
                @Override
                public void run() {
                    // 上报已落库，这时查到的才是「对手这一回合」的秒数。
                    refreshServerSecsLeft();
                }
            });
            btnAction.setEnabled(false);
            btnAction.setText("对方回合中...");
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
        addLog("电脑", "拿走了第" + (row + 1) + "排的" + count + "朵" + flowerEmojis(count));
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
        if (uid == null || uid.isEmpty()) {
            // 空位还没坐人，没有资料可看；以前这里是直接 return，点起来毫无反应
            Toast.makeText(this, "该座位还没有玩家入座", Toast.LENGTH_SHORT).show();
            return;
        }
        final String name = (knownName != null && !knownName.isEmpty()) ? knownName : "无名";
        // 立即展示（占位数据），避免等待两轮网络请求造成的卡顿
        ProfilePopup.show(LocalGameActivity.this, name, 0, 0, 0, 0, false, logLayout);
        if (client == null) return;
        final String fid = uid;
        Log.d("SeatDebug", "openProfile uid=" + fid
                + " (left=" + tvPlayerNameId + " right=" + tvComputerNameId + ")");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final org.json.JSONObject rk = client.getUserRank(fid);
                Log.d("SeatDebug", "getUserRank " + fid + " -> "
                        + (rk == null ? "NULL" : "ok"));
                if (rk == null) {
                    // 拉取失败以前是静默吞掉的：只留一张全 0 的占位卡，
                    // 用户会以为「对方就是 0 分」或者「点了没反应」
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            Toast.makeText(LocalGameActivity.this,
                                    "读取玩家资料失败，请稍后再试", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                final int rank = rk.optInt("rank", 0);
                final int score = rk.optInt("score", 0);
                final int wins = rk.optInt("wins", 0);
                final int losses = rk.optInt("losses", 0);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
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

    // 上报前把本地棋谱对齐到服务端版本：服务端那份已经包含双方全部落子，
    // 本地 moveList 只是「我走过的那几步」。只在服务端步数不少于本地时才替换，
    // 避免刚开局还没轮询到就用空数组把已有记录冲掉。
    private void syncMoveListFromServer() {
        try {
            if (lastServerMoves == null || lastServerMoves.length() < moveList.length()) return;
            JSONArray merged = new JSONArray();
            for (int i = 0; i < lastServerMoves.length(); i++) {
                JSONObject m = lastServerMoves.optJSONObject(i);
                if (m != null) merged.put(m);
            }
            moveList = merged;
        } catch (Exception ignore) { }
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
        reportPvpState(status, turn, winner, null);
    }

    // afterCommitted 在这次上报落库之后于主线程回调。
    // 需要「先写后读」时必须用它：pvp_report_state 是异步的，紧接着去查
    // turn_secs_left 会和这次写入赛跑，读到的还是上一个回合的秒数。
    private void reportPvpState(final String status, final String turn, final String winner,
                                final Runnable afterCommitted) {
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
                if (afterCommitted == null) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        afterCommitted.run();
                    }
                });
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

    // 我能不能踢本桌观众：必须是我真的坐在 A/B 位上。
    // mySide 每轮从服务端行重算（"a"/"b"/null），观众恒为 null -> 踢出按钮不显示。
    // 人人桌与私密房走同一个 renderPvpState，两者都能正确拿到 mySide。
    // 【不能用 isWatcher】那是进场时的 Intent extra，之后永不改写。
    // 真正的权限仍在服务端 RPC 里再校验一次，这里只负责界面。
    private boolean canKickWatchers() {
        return (isPvp || isRoom) && mySide != null;
    }

    // 拉取并展示当前桌的观众资料列表（仅观众，不含玩家）
    private void showWatcherList() {
        if (client == null || tableNo == null) return;
        final String mode = isRoom ? "room" : (isPvp ? "pvp" : "pve");
        final String idOrCode = isRoom ? roomCode : tableNo;
        if (idOrCode == null || idOrCode.isEmpty()) return;
        async(new Runnable() {
            @Override
            public void run() {
                final List<SupabaseClient.WatcherInfo> list =
                        client.fetchWatcherProfiles(mode, idOrCode);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showWatcherListDialog(list);
                    }
                });
            }
        });
    }

    // 观众列表弹窗：白底黑字、圆角、宽=屏宽1/2；悬浮在游戏日志区域内，
    // 通过计算日志区在屏幕中的位置与高度权重来定位，绝不遮挡下方操作按钮。
    // 每个观众一行、整行可点开资料（踢出按钮由资料窗按身份自行决定显不显示）。
    private void showWatcherListDialog(List<SupabaseClient.WatcherInfo> list) {
        if (list == null) list = new ArrayList<>();
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int popupW = screenW / 2;

        TextView title = new TextView(this);
        title.setText("观众列表（" + list.size() + " 人）");
        title.setTextSize(16);
        title.setTextColor(Color.BLACK);
        title.setPadding(dp(16), dp(12), dp(16), dp(6));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        content.setBackground(bg);
        content.addView(title);

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无观众");
            empty.setTextSize(14);
            empty.setTextColor(Color.BLACK);
            empty.setPadding(dp(16), dp(8), dp(16), dp(12));
            content.addView(empty);
        } else {
            for (int i = 0; i < list.size(); i++) {
                content.addView(buildWatcherRow(list.get(i)));
            }
            TextView hint = new TextView(this);
            hint.setText(canKickWatchers() ? "· 点击观众查看资料，可踢出" : "· 点击观众查看资料");
            hint.setTextSize(11);
            hint.setTextColor(0xFF999999);
            hint.setPadding(dp(16), dp(2), dp(16), dp(10));
            content.addView(hint);
        }

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

    // 观众列表的一行：性别圆点 + 昵称，整行可点
    private View buildWatcherRow(final SupabaseClient.WatcherInfo w) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable rb = new GradientDrawable();
        rb.setColor(0xFFF2F2F2);
        rb.setCornerRadius(dp(8));
        row.setBackground(rb);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(6);
        row.setLayoutParams(rowLp);

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if ("female".equals(w.gender) || "女".equals(w.gender) || "f".equals(w.gender)) {
            d.setColor(0xFFE36DA8);
        } else if ("male".equals(w.gender) || "男".equals(w.gender) || "m".equals(w.gender)) {
            d.setColor(0xFF3B7DD8);
        } else {
            d.setColor(0xFF9E9E9E);
        }
        dot.setBackground(d);
        row.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));

        TextView nick = new TextView(this);
        nick.setText((w.nickname == null || w.nickname.isEmpty()) ? "无名" : w.nickname);
        nick.setTextSize(14);
        nick.setTextColor(Color.BLACK);
        nick.setPadding(dp(8), 0, 0, 0);
        row.addView(nick, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWatcherInfo(w);
            }
        });
        return row;
    }

    // 点某个观众：先关掉列表弹窗，再开资料大窗；随后异步补一次 getUserRank 拿排名。
    // 列表是 PopupWindow、资料窗是挂在 content 根的 overlay，属于两套不同的 window，
    // 叠在一起会互相干掉 —— 所以这里选择「关掉列表、返回时重开」而不是叠加。
    private void openWatcherInfo(final SupabaseClient.WatcherInfo w) {
        if (w == null) return;
        if (watcherPopup != null && watcherPopup.isShowing()) watcherPopup.dismiss();
        if (popupHandler != null) popupHandler.removeCallbacksAndMessages(null);

        watcherInfoOpenId = w.userId;
        showWatcherInfo(w);

        if (client == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final org.json.JSONObject rk = client.getUserRank(w.userId);
                if (rk == null) return;
                final int rank = rk.optInt("rank", 0);
                final int score = rk.optInt("score", w.score);
                final int wins = rk.optInt("wins", w.wins);
                final int losses = rk.optInt("losses", w.losses);
                final int games = rk.optInt("total_games", w.totalGames);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 用户可能已关窗、已切到别人、或正在退出，别把旧窗口拉回来
                        if (isFinishing() || !w.userId.equals(watcherInfoOpenId)) return;
                        w.rank = rank;
                        w.score = score;
                        w.wins = wins;
                        w.losses = losses;
                        w.totalGames = games;
                        showWatcherInfo(w);
                    }
                });
            }
        }).start();
    }

    private void showWatcherInfo(final SupabaseClient.WatcherInfo w) {
        WatcherInfoDialog.show(this, w, canKickWatchers(), new WatcherInfoDialog.Action() {
            @Override
            public void onBack() {
                watcherInfoOpenId = null;
                showWatcherList();   // 返回时重新拉一次列表
            }
            @Override
            public void onKick() {
                confirmKickWatcher(w);
            }
            @Override
            public void onClosed() {
                watcherInfoOpenId = null;
            }
        });
    }

    // 踢出前二次确认：不可逆，且会让对方一段时间内进不来
    private void confirmKickWatcher(final SupabaseClient.WatcherInfo w) {
        if (!canKickWatchers()) return;
        final String nick = (w.nickname == null || w.nickname.isEmpty()) ? "该观众" : w.nickname;
        AppDialog.confirm(this,
            "踢出观众",
            "确定将「" + nick + "」移出本桌观战吗？\n对方 " + WATCHER_BAN_MINUTES + " 分钟内无法再观战本桌。",
            "踢出", "取消",
            new AppDialog.OnClick() {
                @Override
                public void onClick(AppDialog dialog) {
                    doKickWatcher(w, nick);
                }
            },
            null).backCancelable().show();
    }

    private void doKickWatcher(final SupabaseClient.WatcherInfo w, final String nick) {
        if (client == null || tableNo == null) return;
        final boolean roomMode = isRoom;
        final String idOrCode = isRoom ? roomCode : tableNo;
        if (idOrCode == null || idOrCode.isEmpty()) return;
        final String target = w.userId;

        // 先关资料窗，踢完直接重开列表看结果（被踢的人已经从列表消失）
        WatcherInfoDialog.dismiss();
        watcherInfoOpenId = null;

        async(new Runnable() {
            @Override
            public void run() {
                final SupabaseClient.RpcResult r = roomMode
                    ? client.roomKickWatcher(idOrCode, target, WATCHER_BAN_MINUTES)
                    : client.pvpKickWatcher(idOrCode, target, WATCHER_BAN_MINUTES);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (r != null && r.ok) {
                            Toast.makeText(LocalGameActivity.this,
                                "已踢出「" + nick + "」", Toast.LENGTH_SHORT).show();
                            showWatcherList();
                        } else {
                            // 服务端异常码翻成人话，别把 NOT_YOUR_TABLE 之类直接甩给用户
                            String msg = "踢出失败，请稍后再试";
                            if (r != null && r.error != null) {
                                if (r.error.contains("NOT_YOUR_TABLE")) {
                                    msg = "只有本桌玩家可以踢出观众";
                                } else if (r.error.contains("NOT_A_WATCHER")) {
                                    msg = "对方已经不在观众列表里了";
                                } else if (r.error.contains("TABLE_NOT_FOUND")
                                        || r.error.contains("ROOM_NOT_FOUND")) {
                                    msg = "本桌已不存在";
                                }
                            }
                            Toast.makeText(LocalGameActivity.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    // 观众被本桌玩家踢出：提示后退回上一级（PvP 大厅 / 私密房）。
    // 【刻意不复用 exitBecauseTableClosed】那个对 isRoom 会 CLEAR_TASK 回初始页，
    // 适用于「房间被清空」；被踢只是走了一个人、房间还在，抹掉私密房页是错的。
    private void exitBecauseKicked(int banSeconds) {
        if (leavingTable) return;
        leavingTable = true;
        stopCountdown();
        watchHandler.removeCallbacksAndMessages(null);
        stopChat();
        if (seatManager != null) seatManager.stopHeartbeat();
        WatcherInfoDialog.dismiss();
        watcherInfoOpenId = null;
        if (watcherPopup != null && watcherPopup.isShowing()) watcherPopup.dismiss();
        String extra = banSeconds >= 60
                ? "（" + (banSeconds / 60) + " 分钟内无法再观战本桌）" : "";
        Toast.makeText(this, "你已被本桌玩家移出观战" + extra, Toast.LENGTH_LONG).show();
        finishOrHome();   // = finish()，退回发起页：PvP 大厅 / 私密房
    }


    // ===== 聊天（玩家+观众，按 chatScope 隔离，1.5s 轮询）=====
    // 生命周期与桌绑定：桌内有玩家 -> openChat；桌空（末位玩家离席）-> closeChat。
    // 关闭时游标归零，配合服务端整桌删聊天，保证下次开聊天是空白。

    // 开启聊天：显示栏位 + 从空游标开始增量拉取
    private void openChat() {
        if (client == null || chatScope == null || chatScope.isEmpty()) return;
        if (chatActive) return;
        chatActive = true;
        lastChatId = 0;
        if (chatLayout != null) chatLayout.setVisibility(View.VISIBLE);
        if (chatPoll != null) chatHandler.removeCallbacks(chatPoll);
        chatPoll = new Runnable() {
            @Override
            public void run() {
                if (!chatActive) return;
                pollChatOnce();
                if (chatActive) chatHandler.postDelayed(this, 1500);
            }
        };
        // 历史理论上为空（清场已删净），仍拉一次以覆盖与玩家几乎同时进桌的窗口
        async(new Runnable() {
            @Override
            public void run() {
                final JSONArray hist = client.fetchChatHistory(chatScope, 50);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!chatActive) return;
                        renderHistory(hist);
                        chatHandler.postDelayed(chatPoll, 1500);
                    }
                });
            }
        });
    }

    // 关闭聊天：隐藏栏位 + 停轮询 + 清输入 + 游标归零
    private void closeChat() {
        chatActive = false;
        if (chatPoll != null) chatHandler.removeCallbacks(chatPoll);
        if (chatLayout != null) chatLayout.setVisibility(View.GONE);
        if (etMessageInput != null) etMessageInput.setText("");
        lastChatId = 0;
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
        if (!chatActive || client == null || chatScope == null) return;
        async(new Runnable() {
            @Override
            public void run() {
                final JSONArray msgs = client.fetchChatAfter(chatScope, lastChatId);
                if (msgs == null) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!chatActive) return;
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

    // 离桌/退出：彻底停掉聊天
    private void stopChat() {
        closeChat();
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
            finishOrHome();
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

    // 私密房间的返回栈是 MenuActivity -> 房间号页 -> 棋局页，而房间页在
    // enterGame() 里故意没有 finish，所以一直留在栈里。既然在，「离开棋局」
    // 只要 finish 就会退回房间号页：房间页 onResume 会重新轮询，render() 把
    // mySide 重算成 null，正好回到「刚进房间、还没坐下」的样子 —— 座位已让出，
    // 点桌卡可以重新坐下，要回初始页得自己再点一次「退出房间」。
    //
    // 早先这里对 isRoom 直接 CLEAR_TASK 回初始页，把房间页从栈里抹掉了，
    // 房间页那句「不 finish，保留房间页在返回栈」的注释从来没兑现过。
    // PvP / 人机本来就没有房间页，finish 各自退回大厅 / 主界面，不用分叉。
    private void finishOrHome() {
        leavingTable = true;
        finish();
    }

    // 本桌玩家归零（末位离席）被服务端清场：关聊天 + 退回大厅
    private void exitBecauseTableClosed(String message) {
        if (leavingTable) return;
        leavingTable = true;
        stopChat();
        // 若进场时本桌就没人，服务端触发器没踢到我们，这里显式退观避免留残记录
        if (isWatcher && seatManager != null && tableNo != null && !tableNo.isEmpty()) {
            if (isRoom) {
                seatManager.roomUnwatch(tableNo, null);
            } else if (isPvp) {
                seatManager.pvpLeaveWatch(tableNo, null);
            } else {
                seatManager.leaveWatch(tableNo, null);
            }
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        if (isRoom) {
            // 唯一不能退回房间页的情况：房间已经被服务端关掉了。房间页 pollOnce
            // 对 fetchRoom 返回 null 只改文案、不 finish（见 PrivateRoomActivity:168），
            // 退回去就是个「房间不存在」的死页，得再点一次退出房间。房间都没了，
            // 没有再待下去的意义，直接清栈回初始页。
            Intent home = new Intent(this, MenuActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
        }
        finishOrHome();
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
            // 这里不再补一次 reportPvpState("finished", 对手)：
            // pvp_leave / room_leave 服务端已经写好 finished 并立即结算，
            // 那次上报是纯冗余。而且它和 leave 并发发出，谁先到不确定 ——
            // 要是它先到，report_state 会看到「未超时的判负声明」而按
            // NOT_EXPIRED 拒绝（见 fix_round_lifecycle.sql 的回合判负校验）。
            // 退出判负只由 leave 一个入口负责，没有第二条路。
        } else {
            final JSONObject finalState = buildFinalState();
            seatManager.forfeitAndLeave(tableNo, finalState, null);
        }
        addLog("你中途退出了棋局，判定为输");
        finishOrHome();
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
        finishOrHome();
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
    // 当前倒计时挂在哪一侧。服务端剩余秒数是异步取回来的，取回时需要知道
    // 该把倒计时重新起到谁身上（见 restartCountdownAfterServerValue）。
    private Boolean countdownPlayerSide;
    // 归零只打一次日志：停在 0 之后每秒都会重进那个分支。
    private boolean countdownZeroLogged;

    private void startCountdown(final boolean playerSide, int seconds) {
        stopCountdown();
        countdownPlayerSide = playerSide;
        countdownZeroLogged = false;
        // 人人/私密房间的倒计时不靠这个值判负，只是给玩家一个「还剩多久」的
        // 提示；判负由服务端 reap_turn_timeouts 执行。真正驱动显示的是
        // serverSecsLeft（每轮询刷新）。这里保留本地自减作为兜底：
        // 轮询失败时数字仍在走，总比卡住不动好。
        countdownSeconds = seconds;
        // 倒计时挂在「当前行动方」那一侧，而不是固定挂在某一侧：
        // playerSide = true 表示行动方是我 -> 挂我这格；false 表示行动方是对手
        // -> 挂对手那格。后入座(B)的人看到的左右与先入座(A)的人是镜像的，
        // 但两边都满足同一条规则：谁在想，倒计时就在谁那格。
        // 与手指的规则严格相反且互补：手指 updateTurnFinger 亮「非行动方」，
        // 所以 A 回合 = A 处倒计时 + B 处手指，B 回合 = B 处倒计时 + A 处手指。
        final TextView tv = playerSide ? myCountdownView() : opponentCountdownView();
        final TextView other = playerSide ? opponentCountdownView() : myCountdownView();
        other.setVisibility(View.INVISIBLE);
        tv.setVisibility(View.VISIBLE);
        // 人机保留原来的手指表现；人人和房间交给 updateTurnFinger 单一负责，
        // 这里再写一遍会用计时器的公式覆盖掉「行动方对面」这条规则。
        if (!isPvp && !isRoom) {
            boolean actingIsLeft = playerSide ? iAmLeftSide() : !iAmLeftSide();
            if (imgPlayerFinger != null) {
                imgPlayerFinger.setVisibility(actingIsLeft ? View.INVISIBLE : View.VISIBLE);
            }
            if (imgComputerFinger != null) {
                imgComputerFinger.setVisibility(actingIsLeft ? View.VISIBLE : View.INVISIBLE);
            }
        }
        // 人人/房间：数字由「服务端基线 + 单调时钟经过时间」算出，本地不逐秒自减。
        // 本地到点不判负、不上报、不改界面 —— 判负只由服务端 reap_turn_timeouts
        // 执行，客户端这边等轮询把 finished 拉回来（和对手走的是同一条路径，
        // 所以本机被冻结 3 分钟回来看到的是「已判负」，而不是假装的 0:00）。
        final boolean serverAuthoritative = isPvp || isRoom;
        if (serverAuthoritative) {
            countdownSeconds = serverSecsLeftOrDefault();
            Log.d("TurnDebug", "startCountdown side=" + playerSide
                    + " isPvp=" + isPvp + " isRoom=" + isRoom
                    + " serverSecsLeft=" + serverSecsLeft
                    + " computed=" + countdownSeconds
                    + " baselineMs=" + serverBaselineMs);
            if (countdownSeconds < 0) {
                // 服务端秒数还没到手（RPC 在路上，或刚失败过一次）。
                // 这里绝不能 return 把 tv 藏掉 —— refreshServerSecsLeft 失败时
                // 是没有回调来补救的，于是这一整个回合那格都是空的。
                // 对手那格以前从不显示，所以这条只在我把倒计时铺到两侧之后
                // 才暴露成「对手的显示时有时无」。
                //
                // 改成用本地兜底先把数字走起来，RPC 回来后
                // restartCountdownAfterServerValue 会把它纠正成真值。
                // 兜底取 60，与服务端 pvp_turn_seconds() 一致，
                // 所以真值到达时顶多差一两秒，看不出跳变。
                // 判负仍然只由服务端做，这里的数字纯粹是给人看的。
                countdownSeconds = PVP_DISPLAY_FALLBACK_SECONDS;
            }
        }
        updateCountdownText(tv);
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (serverAuthoritative) {
                    // 每 tick 重算，不做「和快照比对再重置」——
                    // 那样会和常量基线互相拉扯，数字在两三个值之间来回抖。
                    final int srv = serverSecsLeftOrDefault();
                    // 中途取不到（基线作废、重试又失败）就沿用上一拍的数字
                    // 继续走。直接把这个 -1 写进 countdownSeconds 会被下面的
                    // <= 0 判成归零，于是凭空发一次 requestServerTimeoutCheck
                    // 并且把数字藏掉 —— 又是一个「时有时无」。
                    if (srv >= 0) countdownSeconds = srv;
                    else if (countdownSeconds > 0) countdownSeconds--;
                } else if (countdownSeconds > 0) {
                    countdownSeconds--;
                }
                if (countdownSeconds <= 0) {
                    updateCountdownText(tv);
                    if (serverAuthoritative) {
                        // 停在 0，不在本地判定胜负。但要请服务端看一眼该不该判：
                        // cron 兜底是每分钟一次（本实例 pg_cron 不支持秒位），
                        // 只等它会白等最多一分钟。
                        //
                        // 这里只是「戳一下」，判负条件全在服务端
                        // （request_turn_timeout_check 内部）：deadline 已过、
                        // current_turn 未变、对手心跳 60 秒内，缺一不判。
                        // 戳完立刻拉一次表，让结果尽快上屏，不用等下一个 2 秒轮询。
                        if (!countdownZeroLogged) {
                            countdownZeroLogged = true;
                            Log.d("TurnDebug", "countdown hit 0, asking server. polling="
                                    + watchHandler.hasCallbacks(watchRunnable));
                            requestServerTimeoutCheck();
                        }
                        tv.setVisibility(View.INVISIBLE);
                        return;
                    }
                    // 人机没有服务端回合概念，仍由本地判负
                    tv.setVisibility(View.INVISIBLE);
                    if (playerSide) {
                        addLog(getPlayerName() + "的回合超时，判负，电脑赢了。");
                        reportState("finished", "", "computer");
                        endGame(false);
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

    // 作废服务端秒数基线，让下一次 startCountdown 退回兜底值。
    // 换回合时必须先作废：旧基线是「上一回合」的，直接沿用会把上一回合
    // 的残值显示到新回合的那一格上。
    private void invalidateServerSecsLeft() {
        serverSecsLeft = -1;
        serverBaselineMs = 0L;
    }

    // 拉服务端算好的本轮剩余秒数（人人桌 / 私密房间），并把单调时钟基线对齐到此刻。
    // 拉不到就保持原值不动：倒计时继续用上一次的值走，判负本来也不靠它。
    //
    // 失败会重试几次：换回合时这一拍正好把 serverSecsLeft 作废成 -1，
    // 如果这一次 RPC 恰好因为网络抖动失败，旧代码就直接 return 了，
    // 既没人来补显、也没人重试，整回合的秒数都停在 -1。
    private void refreshServerSecsLeft() {
        refreshServerSecsLeft(0);
    }

    private void refreshServerSecsLeft(final int attempt) {
        if (!isPvp && !isRoom) return;
        if (isWatcher || client == null || tableNo == null) return;
        final String tId = isRoom ? null : tableNo;
        final String rCode = isRoom ? tableNo : null;
        // 记下这次请求是给哪一拍的。回合在飞行途中翻了的话，这份响应就是
        // 上一回合的旧秒数，直接写进基线会让新回合的显示跳成一个无关的数字 ——
        // 尤其是加了重试之后，窗口比原来长得多。
        final boolean askedForMyTurn = isPlayerTurn;
        // 换回合了：旧基线作废，等这次的服务器值回来再重建。
        // 只在第一拍作废 —— 重试不能碰基线，否则会把已经建好的
        // serverSecsLeft 又抹成 -1，等于自己制造一次「取不到值」。
        if (attempt == 0) {
            invalidateServerSecsLeft();
        }
        async(new Runnable() {
            @Override
            public void run() {
                final int left = client.turnSecsLeft(tId, rCode);
                if (left < 0) {
                    if (attempt < SECS_LEFT_FETCH_RETRIES) {
                        // 主线程上按 400ms 退避重试。startCountdown 那边已经用
                        // 兜底值让数字走起来了，所以这几拍只是把真值补回来。
                        final int next = attempt + 1;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (askedForMyTurn != isPlayerTurn) return; // 回合已翻，旧值作废
                                countdownHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        refreshServerSecsLeft(next);
                                    }
                                }, SECS_LEFT_RETRY_DELAY_MS);
                            }
                        });
                    } else {
                        Log.d("TurnDebug", "turnSecsLeft gave up after "
                                + (attempt + 1) + " tries, keeping local fallback");
                    }
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (askedForMyTurn != isPlayerTurn) return; // 回合已翻，旧值作废
                        serverSecsLeft = left;
                        serverBaselineMs = android.os.SystemClock.elapsedRealtime();
                        // startCountdown 已经先用兜底值把数字走起来了，
                        // 这里拿到真值后要重新起一次纠正回来。
                        restartCountdownAfterServerValue();
                    }
                });
            }
        });
    }

    // 倒计时归零：请服务端判定本轮是否超时，判完立刻拉一次表。
    // 不在本地推胜负 —— 结果一律由轮询到的 game_state 驱动，
    // 这样自己和对手走的是同一条渲染路径，不会出现「一方显示判负、另一方还在走棋」。
    // 戳一次可能碰上网络抖动或对手心跳刚过期，隔几秒再补几次；
    // 服务端条件不满足时返回 false，重试也只在真正该判时才判负。
    private void requestServerTimeoutCheck() {
        requestServerTimeoutCheck(0);
    }

    private void requestServerTimeoutCheck(final int attempt) {
        if (client == null || tableNo == null) return;
        final String tId = isRoom ? null : tableNo;
        final String rCode = isRoom ? tableNo : null;
        async(new Runnable() {
            @Override
            public void run() {
                final boolean judged = client.requestTurnTimeoutCheck(tId, rCode);
                Log.d("TurnDebug", "requestTurnTimeoutCheck attempt=" + attempt
                        + " judged=" + judged);
                if (judged) {
                    // 服务端已判负：马上拉一次，别等下一个 2 秒轮询周期。
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pollPvpOnce();
                        }
                    });
                    return;
                }
                if (attempt >= 3) return;
                countdownHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        requestServerTimeoutCheck(attempt + 1);
                    }
                }, 5000);
            }
        });
    }

    // 服务端剩余秒数到位后把倒计时纠正回真值。
    // startCountdown 现在拿不到服务端值时也会用兜底值先走起来，
    // 所以这里是在「兜底数字」之上覆盖成真值，而不是从空白里救活它。
    private void restartCountdownAfterServerValue() {
        if (countdownPlayerSide == null) return;
        startCountdown(countdownPlayerSide, serverSecsLeftOrDefault());
    }

    private TextView tvCountdownFor(boolean side) {
        return side ? myCountdownView() : opponentCountdownView();
    }

    // 依基线算当前剩余秒数。
    // 还没拿到服务端值 -> -1，调用方退回本地常量兜底。
    private int currentServerSecsLeft() {
        final int base = serverSecsLeft;
        if (base < 0) return -1;
        final long elapsed = android.os.SystemClock.elapsedRealtime() - serverBaselineMs;
        if (elapsed < 0L) return base;
        final int left = base - (int) (elapsed / 1000L);
        return left < 0 ? 0 : left;
    }

    // 服务端剩余秒数；还没取到就用常量兜底。
    // 注意：兜底值只对 PvE 成立（PLAYER_TURN_SECONDS 是人机的 180 秒）。
    // 人人/私密房拿不到服务端值时返回 -1，让调用方保持倒计时不启动，
    // 等 refreshServerSecsLeft() 的 RPC 回来再显示 —— 否则会先闪一个
    // 「3:00」再跳成「1:00」。
    private int serverSecsLeftOrDefault() {
        int left = currentServerSecsLeft();
        if (left < 0) return serverAuthoritativeTurn() ? -1 : PLAYER_TURN_SECONDS;
        return left;
    }

    private boolean serverAuthoritativeTurn() {
        return isPvp || isRoom;
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
                // 开关把新值写进了 SharedPreferences，但本类的 soundEnabled 字段是
                // onCreate 时读一次的缓存。这里必须重新读回来，否则 playDida/playWin
                // 的判断和 🔊 图标都还是旧值 —— 表现就是「开关要退出重进才生效」。
                soundEnabled = sharedPreferences.getBoolean("soundEnabled", true);
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

    // 切后台：停发心跳。判负规则是 60 秒无条件，所以切后台 / 锁屏 /
    // 来电 / 系统弹窗都会在 60 秒后被判负 —— 锁屏和切后台在这里没有区别。
    // 这是刻意的：给后台开缓冲的话，同一种「人不在」有时判负有时不判负，
    // 对手也不知道该等多久。规则统一比规则宽松重要。
    @Override
    protected void onPause() {
        if (seatManager != null) {
            seatManager.pauseHeartbeat();
        }
        super.onPause();
    }

    // 回前台：立刻补一次心跳，让服务端重新看到人。
    // 超过 60 秒的话可能已经判负了，补发只是让服务端尽快收敛。
    @Override
    protected void onResume() {
        super.onResume();
        // 可能是在别的页面（菜单/房间）改了音效开关，回前台时同步一次缓存，
        // 免得本局继续用旧的 soundEnabled。
        if (sharedPreferences != null) {
            soundEnabled = sharedPreferences.getBoolean("soundEnabled", true);
        }
        if (seatManager != null) {
            seatManager.resumeHeartbeat();
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
        WatcherInfoDialog.dismiss();   // 观众资料大窗
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