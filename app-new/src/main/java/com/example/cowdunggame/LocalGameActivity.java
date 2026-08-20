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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private boolean leavingTable = false; // 防止返回键重复触发离桌

    // 观战模式：真实玩家信息（从数据库拉取，替代本地昵称）
    private boolean isWatcher = false;
    private String watcherPlayerName = "玩家";

    // 观战轮询
    private Handler watchHandler = new Handler(Looper.getMainLooper());
    private Runnable watchRunnable;
    private int lastRenderedMoveCount = -1;

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

        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("table_no")) {
            tableNo = intent.getStringExtra("table_no");
            if (tableNo == null) tableNo = String.valueOf(intent.getIntExtra("table_no", 0));
        }
        String role = getIntent() != null ? getIntent().getStringExtra("role") : null;
        isWatcher = "watcher".equals(role);
        if (tableNo != null && !tableNo.isEmpty()) {
            client = new SupabaseClient(this);
            seatManager = new SeatManager(client);
            // 遗言：玩家/观众进程存活期间持续心跳（20s/次，配合服务端 3 分钟超时兜底）
            seatManager.startHeartbeat(tableNo);
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
        btnExitGame.setText("退出棋局");
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

        LinearLayout logLayout = new LinearLayout(this);
        logLayout.setOrientation(LinearLayout.VERTICAL);
        logLayout.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.setMargins(20, 0, 20, 0);
        logLayout.setLayoutParams(logParams);

        TextView logTitle = new TextView(this);
        logTitle.setText("游戏日志");
        logTitle.setTextSize(16);
        logTitle.setTextColor(Color.WHITE);
        logLayout.addView(logTitle);

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
        } else {
            setupGameBoard(false);
            showGameRules();
            addLog("机器人已就座并自动准备好，点「准备好了」开始");
        }
    }

    // ===== 观战模式 =====
    // 只读看牌：拉取该桌 game_state，重放每一步，显示真实玩家昵称
    private void setupWatcherMode() {
        tvPlayerName.setText("观战中");
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
                final JSONObject table = client.fetchPveTable(tableNo);
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

        // 真实玩家昵称（从拉取到的 player 资料取）
        JSONObject player = table.optJSONObject("player");
        String nick = (player != null ? player.optString("nickname", "") : "").trim();
        if (nick.isEmpty()) nick = "玩家";
        watcherPlayerName = nick;
        tvPlayerName.setText(watcherPlayerName);

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
        BadWordFilter badWordFilter = new BadWordFilter();
        if (badWordFilter.containsBadWord(text)) {
            addLog("检测到不文明用语，已自动屏蔽");
        }
        text = badWordFilter.filter(text);
        addLog(getPlayerName(), text);
        if (etMessageInput != null) {
            etMessageInput.setText("");
        }
    }

    // ===== 对局流程 =====
    private void markPlayerReady() {
        if (isGameStarted) return;
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
        addLog(getPlayerName(), "拿走了第" + (selectedRow + 1) + "排的" + selectedCount + "朵鲜花");
        appendMove("player", selectedRow, selectedCount);
        remainingFlowers[selectedRow] -= selectedCount;

        if (checkGameEnd()) {
            addLog("恭喜" + getPlayerName() + "赢了！电脑被迫拿走了牛粪。");
            reportState("finished", "", "player");
            endGame(true);
            return;
        }

        isPlayerTurn = false;
        stopCountdown();
        resetSelectionState();
        setupGameBoard(true);
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
            "退出棋局",
            "对局进行中，退出将判定为输，确定要退出吗？",
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
        seatManager.stopHeartbeat();
        final JSONObject finalState = buildFinalState();
        seatManager.forfeitAndLeave(tableNo, finalState, null);
        addLog("你中途退出了棋局，判定为输");
        finish();
    }

    // 观众 / 停摆玩家退出：离座写库 + 回大厅（写库放后台线程，界面立即退出）
    private void leaveAndExit() {
        if (leavingTable) return;
        leavingTable = true;
        stopCountdown();
        watchHandler.removeCallbacksAndMessages(null);
        seatManager.stopHeartbeat();
        if (isWatcher) {
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
        soundEnabled = !soundEnabled;
        setupGameBoard(true);
        addLog("音效已" + (soundEnabled ? "开启" : "关闭"));
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
        if (seatManager != null) {
            seatManager.stopHeartbeat();
            // 遗言机制：进程被清/异常退出，尽力写库释放座位/观战（服务端超时兜底）
            if (tableNo != null && !tableNo.isEmpty() && !leavingTable) {
                if (!isWatcher && isGameStarted) {
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