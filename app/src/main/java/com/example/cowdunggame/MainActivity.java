// MainActivity.java
// 《鲜花与牛粪》核心游戏：人机对弈
// 说明：电脑 AI 已抽离到 ComputerAI 模块（Nim 最优策略），
//       人机模式直接调用；双人对战模式下同模块可作为后台机器人随时提示走法。
package com.example.cowdunggame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class MainActivity extends Activity {

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
    private Random random = new Random();
    private int gameCount = 0;

    // 昵称存储（本地身份，后续由服务端 user_id 取代）
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "CowDungPrefs";
    private static final String KEY_PLAYER_NAME = "PlayerName";
    private static final String KEY_PLAYER_GENDER = "PlayerGender";
    private String playerName;
    private String playerGender = "male"; // male/female，用于本人头像 man/women
    private EditText etMessageInput;
    private Button btnSendMessage;
    private Button btnNickname;

    // 中间对战栏：左(我) / 中央按钮 / 右(电脑)
    private TextView tvPlayerName;
    private TextView tvComputerName;
    private TextView tvPlayerCountdown;
    private TextView tvComputerCountdown;
    private Button btnExitGame;
    private ImageView imgPlayerFinger;
    private ImageView imgComputerFinger;

    // 倒计时
    private Handler countdownHandler = new Handler(Looper.getMainLooper());
    private Runnable countdownRunnable;
    private int countdownSeconds;

    // 长按「确认选择」3 秒显示提示走法
    private Handler hintHandler = new Handler(Looper.getMainLooper());
    private Runnable hintRunnable;
    private String hintMessage = "";
    private boolean hintShowing = false;

    // 音效：点击鲜花「滴答」，确认选择「send」，胜利/失败音效；喇叭开关
    private SoundPool soundPool;
    private int soundDida;
    private int soundSend;
    private int soundWin;
    private int soundLose;
    private boolean soundEnabled = true;

    // 胜负结果图：常驻显示，直到按下「退出棋局」或「准备好了」
    private ImageView resultImage;

    // 本局来源：pve(人机对战)/lobby(游戏大厅)/private(私密房间)，决定退出棋局回到哪里
    private String gameSource = "pve";

    // 观众列表容器（每观众一行昵称，可长按踢出；仅对局玩家可踢）
    private LinearLayout watcherArea;
    private final Map<String, Integer> watcherRows = new HashMap<>();
    private final ArrayList<String> watcherNames = new ArrayList<>();

    // 脏话过滤
    private final BadWordFilter badWordFilter = new BadWordFilter();

    // 回合时长：人机对战玩家 3 分钟；真人对战 45 秒（开发文档 4.4）
    private static final int PLAYER_TURN_SECONDS_HOST = 180;
    private static final int PLAYER_TURN_SECONDS_VS = 45;
    private static final int COMPUTER_THINK_SECONDS = 5;

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

    // 默认来源为「系统」的通用日志
    private void addLog(String message) {
        addLog("系统", message);
    }

    // ===== 观众管理 =====
    // 观众进入：日志提醒「XXX来了!」，并加入观众列表
    public void onWatcherEnter(String nickname) {
        if (nickname == null || nickname.isEmpty()) return;
        if (!watcherNames.contains(nickname)) {
            watcherNames.add(nickname);
            addWatcherRow(nickname);
        }
        addLog(nickname + "来了!");
    }

    // 观众离开/被踢：移除列表
    public void removeWatcher(String nickname) {
        if (nickname == null) return;
        if (watcherNames.contains(nickname)) {
            watcherNames.remove(nickname);
            Integer index = watcherRows.remove(nickname);
            if (index != null && index < watcherArea.getChildCount()) {
                watcherArea.removeViewAt(index);
            }
            rebuildWatcherRows();
        }
    }

    // 追加一个观众行（底部按钮触发，仅对局玩家可长按踢出）
    private void addWatcherRow(final String nickname) {
        if (watcherArea == null) return;
        final TextView row = new TextView(this);
        row.setText("   " + nickname);
        row.setTextSize(14);
        row.setTextColor(Color.WHITE);
        row.setSingleLine(true);
        row.setPadding(px(4), px(4), px(4), px(4));

        // 是否当前对局玩家（非人机对战、且是下棋玩家而非观众本人）可踢人
        final boolean canKick = !"pve".equals(gameSource);
        row.setLongClickable(canKick);
        if (canKick) {
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showKickDialog(nickname);
                    return true;
                }
            });
        }

        watcherArea.addView(row);
        watcherRows.put(nickname, watcherArea.getChildCount() - 1);
    }

    // 重建观众行索引（某行被删后索引变化）
    private void rebuildWatcherRows() {
        watcherRows.clear();
        for (int i = 0; i < watcherArea.getChildCount(); i++) {
            TextView row = (TextView) watcherArea.getChildAt(i);
            watcherRows.put(row.getText().toString().trim(), i);
        }
    }

    // 长按观众弹出的踢人确认框
    private void showKickDialog(final String nickname) {
        new AlertDialog.Builder(this)
            .setTitle("踢出观战")
            .setMessage("确定将 " + nickname + " 踢出该房间吗？")
            .setPositiveButton("踢出", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    kickWatcher(nickname);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // 执行踢出：本地移除 + 提示；联网时调用 host_kick RPC（由调用方传入 roomCode）
    public void kickWatcher(String nickname) {
        removeWatcher(nickname);
        addLog(nickname + " 已被踢出房间");
        // TODO(联网): 若有 room_code 且本人是房主，调用 host_kick RPC
    }

    // 获取玩家昵称：有昵称显示昵称，无昵称显示「昵称」
    private String getPlayerName() {
        return playerName == null || playerName.isEmpty() ? "昵称" : playerName;
    }

    // 刷新底部昵称按钮：有昵称显示昵称，无昵称显示「昵称」，点击可修改
    private void updateNicknameButton() {
        String name = playerName == null || playerName.isEmpty() ? "昵称" : playerName;
        if (btnNickname != null) {
            btnNickname.setText(name);
        }
        if (tvPlayerName != null) {
            tvPlayerName.setText(name);
        }
    }

    // 收起软键盘（昵称按钮/发送时调用，避免键盘长期占位）
    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }
    }

    // 发送聊天消息
    private void sendChatMessage() {
        String text = etMessageInput != null ? etMessageInput.getText().toString().trim() : "";
        if (text.isEmpty()) {
            addLog("请输入要发送的消息");
            return;
        }
        // 脏话过滤：命中敏感词替换为 *
        if (badWordFilter.containsBadWord(text)) {
            addLog("检测到不文明用语，已自动屏蔽");
        }
        text = badWordFilter.filter(text);
        addLog(playerName, text);
        if (etMessageInput != null) {
            etMessageInput.setText("");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        playerName = sharedPreferences.getString(KEY_PLAYER_NAME, "");
        playerGender = sharedPreferences.getString(KEY_PLAYER_GENDER, "male");
        if (getIntent() != null && getIntent().hasExtra("source")) {
            gameSource = getIntent().getStringExtra("source");
        }

        resetSelectionState();

        // 主布局
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.BLACK);

        // 棋盘容器 - 高度 = 屏幕总高度 × 42%（开发文档 5.3：棋盘区 42%）
        // FrameLayout：棋盘铺底，胜负图片叠加覆盖居中
        int boardHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        rowsContainer = new FrameLayout(this);
        rowsContainer.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, boardHeight);
        boardParams.setMargins(20, 0, 20, 0);
        rowsContainer.setLayoutParams(boardParams);

        // 中间对战栏：整体金色外框，内部 5 格各 20%，统一高度并垂直居中
        //   我的昵称 | 我的倒计时/退出棋局 | 确认选择(按钮) | 电脑倒计时 | 电脑昵称
        // 金色外框 = 屏幕高度百分比，内部按钮 = 金色外框的 90%
        final float goldenRatio = 0.06f; // 金色外框约占屏幕高度 6%
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

        // 格1 我的昵称：唯一需要白色边框的显示框
        tvPlayerName = new TextView(this);
        tvPlayerName.setText(playerName == null || playerName.isEmpty() ? "昵称" : playerName);
        tvPlayerName.setTextSize(13);
        tvPlayerName.setTextColor(Color.WHITE);
        tvPlayerName.setGravity(Gravity.CENTER);
        tvPlayerName.setSingleLine(true);
        tvPlayerName.setBackground(roundedStrokeBg(0xFF1C1C1C));
        LinearLayout.LayoutParams name1Params = new LinearLayout.LayoutParams(0, middleHeight, 20);
        name1Params.setMargins(dp(2), 0, dp(2), 0);
        tvPlayerName.setLayoutParams(name1Params);
        buttonLayout.addView(tvPlayerName);

        // 格2 我的倒计时 / 退出棋局：FrameLayout 让三者重叠居中，只显示其一
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
                exitGame();
            }
        });
        btnExitGame.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        cell2.addView(btnExitGame);

        // 我方手指：左侧人用左手图，电脑回合时指向右侧的电脑；无边框
        imgPlayerFinger = new ImageView(this);
        imgPlayerFinger.setImageResource(R.drawable.left_hand);
        imgPlayerFinger.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgPlayerFinger.setVisibility(View.INVISIBLE);
        imgPlayerFinger.setLayoutParams(new FrameLayout.LayoutParams(
            middleHeight, middleHeight, Gravity.CENTER));
        cell2.addView(imgPlayerFinger);
        buttonLayout.addView(cell2);

        // 格3 中央按钮：准备好了 / 已准备 / 确认选择 / 电脑思考中
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
        // 长按（按住 3 秒）显示当前棋局提示走法
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
                return false; // 不拦截，长按不影响单击
            }
        });
        buttonLayout.addView(btnAction);

        // 格4 电脑倒计时 / 手指：FrameLayout 让两者重叠居中，只显示其一
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

        // 电脑侧手指：右侧人用右手图，我方回合时指向左侧的我方；无边框
        imgComputerFinger = new ImageView(this);
        imgComputerFinger.setImageResource(R.drawable.right_hand);
        imgComputerFinger.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgComputerFinger.setVisibility(View.INVISIBLE);
        imgComputerFinger.setLayoutParams(new FrameLayout.LayoutParams(
            middleHeight, middleHeight, Gravity.CENTER));
        cell4.addView(imgComputerFinger);
        buttonLayout.addView(cell4);

        // 格5 电脑昵称：带白色边框
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

        // 日志区域 - 占满剩余空间，键盘弹出时压缩的是这里
        LinearLayout logLayout = new LinearLayout(this);
        logLayout.setOrientation(LinearLayout.VERTICAL);
        logLayout.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.setMargins(20, 0, 20, 0);
        logLayout.setLayoutParams(logParams);

        TextView logTitle = new TextView(this);
        logTitle.setText("观众列表");
        logTitle.setTextSize(16);
        logTitle.setTextColor(Color.WHITE);
        logLayout.addView(logTitle);

        // 观众容器：每个观众一行昵称，玩家可长按踢出
        watcherArea = new LinearLayout(this);
        watcherArea.setOrientation(LinearLayout.VERTICAL);
        watcherArea.setBackgroundColor(Color.BLACK);
        logLayout.addView(watcherArea);

        TextView logSubTitle = new TextView(this);
        logSubTitle.setText("游戏日志:");
        logSubTitle.setTextSize(14);
        logSubTitle.setTextColor(Color.parseColor("#CCCCCC"));
        logLayout.addView(logSubTitle);

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.BLACK);
        // weight=1 占满除标题与底部聊天栏外的剩余空间，聊天栏才能显示
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollView.setLayoutParams(scrollParams);

        tvGameLog = new TextView(this);
        tvGameLog.setTextSize(14);
        tvGameLog.setTextColor(Color.WHITE);
        tvGameLog.setBackgroundColor(Color.BLACK);
        scrollView.addView(tvGameLog);
        logLayout.addView(scrollView);

        // 聊天输入区（最底部）：昵称(15%) + 输入框(70%，金色边框) + 发送(15%)
        LinearLayout chatLayout = new LinearLayout(this);
        chatLayout.setOrientation(LinearLayout.HORIZONTAL);
        chatLayout.setGravity(Gravity.CENTER_VERTICAL);
        chatLayout.setWeightSum(100);
        chatLayout.setPadding(0, dp(6), 0, 0);
        int barHeight = dp(30); // 略小于中间动作按钮

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
        btnNickname.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                showNameInputDialog();
            }
        });
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

        initSound();
        setupGameBoard(false);
        showGameRules();

        if (playerName.isEmpty()) {
            showNameInputDialog();
        }
    }

    // 昵称输入对话框（本地身份，可随意设置）：昵称 + 性别（男/女，用于本人头像）
    private void showNameInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("请设置昵称和性别");

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), 0);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("昵称");
        input.setText(playerName);
        content.addView(input);

        final String[] genders = {"男", "女"};
        final String[] genderKeys = {"male", "female"};
        final boolean[] selected = {genderKeys[0].equals(playerGender)};
        LinearLayout genderRow = new LinearLayout(this);
        genderRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < genders.length; i++) {
            final int idx = i;
            Button gb = new Button(this);
            gb.setText(genders[i]);
            gb.setTextSize(14);
            gb.setTextColor(Color.WHITE);
            gb.setAllCaps(false);
            gb.setBackground(roundedStrokeBg(genderKeys[idx].equals(playerGender) ? 0xFF1E88E5 : 0xFF3A3A3A));
            LinearLayout.LayoutParams gParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
            gParams.setMargins(dp(4), dp(8), dp(4), 0);
            gb.setLayoutParams(gParams);
            gb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selected[0] = genderKeys[idx].equals("male");
                    // 重设按钮高亮
                    if (genderRow != null) {
                        for (int j = 0; j < genderRow.getChildCount(); j++) {
                            Button other = (Button) genderRow.getChildAt(j);
                            boolean active = genderKeys[j].equals(genderKeys[idx]);
                            other.setBackground(roundedStrokeBg(active ? 0xFF1E88E5 : 0xFF3A3A3A));
                        }
                    }
                }
            });
            genderRow.addView(gb);
        }
        content.addView(genderRow);

        builder.setView(content);
        builder.setPositiveButton("确认", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    name = "玩家";
                }
                String gender = selected[0] ? "male" : "female";
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(KEY_PLAYER_NAME, name);
                editor.putString(KEY_PLAYER_GENDER, gender);
                editor.apply();
                playerName = name;
                playerGender = gender;
                updateNicknameButton();
                addLog("昵称已设置为: " + name + "（" + (selected[0] ? "男" : "女") + "）");
            }
        });
        builder.show();
    }

    // 玩家点击「准备好了」：玩家就绪，人机对战电脑自动就绪，双方就绪后开局
    private void markPlayerReady() {
        hideResultImage();
        if (isGameStarted) return;
        btnAction.setEnabled(false);
        btnAction.setText("已准备");
        addLog(getPlayerName() + "已准备");
        // 人机对战：电脑自动就绪
        addLog("电脑已准备");
        rowsContainer.postDelayed(new Runnable() {
            @Override
            public void run() {
                startGame();
            }
        }, 600);
    }

    // 开始游戏
    private void startGame() {
        isGameStarted = true;
        gameCount++;
        isPlayerTurn = (gameCount % 2 == 1);

        remainingFlowers = new int[]{1, 2, 3, 4, 5, 6};
        resetSelectionState();
        // 开局后隐藏「退出棋局」，轮到谁谁显示倒计时
        btnExitGame.setVisibility(View.GONE);
        stopCountdown();
        setupGameBoard(true);

        if (isPlayerTurn) {
            btnAction.setText("确认选择");
            btnAction.setEnabled(true);
            addLog("游戏开始！轮到" + getPlayerName() + "的回合，请拿取鲜花");
            startCountdown(true, PLAYER_TURN_SECONDS_HOST);
        } else {
            btnAction.setText("电脑思考中...");
            btnAction.setEnabled(false);
            addLog("游戏开始！轮到电脑的回合");
            startCountdown(false, COMPUTER_THINK_SECONDS);
            scheduleComputerTurn();
        }
    }

    // 拿取鲜花
    private void takeFlowers() {
        if (selectedRow == -1 || selectedCount == 0) {
            addLog("请先选择要拿取的鲜花");
            return;
        }
        playSend(); // 确认选择拿走鲜花：send

        addLog(playerName, "拿走了第" + (selectedRow + 1) + "排的" + selectedCount + "朵鲜花");
        remainingFlowers[selectedRow] -= selectedCount;

        // 判胜：玩家拿起最后一朵鲜花
        if (checkGameEnd()) {
            addLog("恭喜" + playerName + "赢了！电脑被迫拿走了牛粪。");
            endGame(true);
            return;
        }

        // 电脑回合
        isPlayerTurn = false;
        stopCountdown();
        resetSelectionState();
        setupGameBoard(true);
        btnAction.setEnabled(false);
        btnAction.setText("电脑思考中...");
        addLog("轮到电脑的回合");
        startCountdown(false, COMPUTER_THINK_SECONDS);
        scheduleComputerTurn();
    }

    // 电脑思考 5 秒后落子，避免太快显得不自然
    private void scheduleComputerTurn() {
        rowsContainer.postDelayed(new Runnable() {
            @Override
            public void run() {
                computerTurn();
            }
        }, COMPUTER_THINK_SECONDS * 1000);
    }

    // 胜负判定：第 2~6 排鲜花是否全部拿完（第 1 排牛粪固定保留）
    private boolean checkGameEnd() {
        int totalFlowers = 0;
        for (int i = 1; i < remainingFlowers.length; i++) {
            totalFlowers += remainingFlowers[i];
        }
        return totalFlowers == 0;
    }

    // 电脑回合：调用 ComputerAI 计算最优/必败随机走法
    private void computerTurn() {
        ComputerAI.Move move = ComputerAI.getNextMove(remainingFlowers);
        if (move == null) {
            // 无棋可走，说明玩家已经拿走最后一朵，判玩家胜
            addLog("恭喜" + playerName + "赢了！电脑被迫拿走了牛粪。");
            endGame(true);
            return;
        }

        int row = move.row;
        int count = move.count;

        addLog("电脑", "拿走了第" + (row + 1) + "排的" + count + "朵鲜花");
        remainingFlowers[row] -= count;

        // 判胜：电脑拿起最后一朵鲜花
        if (checkGameEnd()) {
            addLog("游戏结束！" + getPlayerName() + "被迫拿走了牛粪，电脑赢了。");
            endGame(false);
            return;
        }

        // 轮到玩家
        stopCountdown();
        resetSelectionState();
        isPlayerTurn = true;
        setupGameBoard(true);
        btnAction.setEnabled(true);
        btnAction.setText("确认选择");
        addLog("轮到" + getPlayerName() + "的回合，请拿取鲜花");
        startCountdown(true, PLAYER_TURN_SECONDS_HOST);
    }

    // 对局结束：显示「退出棋局」+「准备好了」两个按钮
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
    }

    // 游戏结束时覆盖棋盘居中显示胜负图（透明背景），常驻直到按「退出棋局」/「准备好了」
    private void showResultImage(boolean playerWin) {
        if (rowsContainer == null) return;
        hideResultImage();
        final ImageView result = new ImageView(this);
        result.setImageResource(playerWin ? R.drawable.you_win : R.drawable.you_lose);
        result.setScaleType(ImageView.ScaleType.FIT_CENTER);
        result.setBackgroundColor(Color.TRANSPARENT);
        // 限制图片尺寸：宽为棋盘宽度的 60%，高不超过棋盘高度的 70%，等比缩放居中
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

    // 移除胜负结果图
    private void hideResultImage() {
        if (resultImage != null) {
            ViewParent parent = resultImage.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(resultImage);
            }
            resultImage = null;
        }
    }

    // 退出棋局：
    //   游戏大厅/私密房间对战 -> 回到原页面（大厅可继续选桌/当观众，私密房间可继续等朋友）
    //   人机对战 -> 直接退出回到主菜单；按系统返回同样回主菜单
    private void exitGame() {
        stopCountdown();
        if ("lobby".equals(gameSource) || "private".equals(gameSource)) {
            finish(); // 返回大厅/私密房间
        } else {
            Intent intent = new Intent(this, MenuActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        }
    }

    // ===== 回合倒计时 =====
    private void startCountdown(final boolean playerSide, int seconds) {
        stopCountdown();
        countdownSeconds = seconds;
        final TextView tv = playerSide ? tvPlayerCountdown : tvComputerCountdown;
        final TextView other = playerSide ? tvComputerCountdown : tvPlayerCountdown;
        other.setVisibility(View.INVISIBLE);
        tv.setVisibility(View.VISIBLE);
        // 手指：谁在倒计时，另一方就伸手指指向对方
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

    // 构建棋盘
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

            // 每行固定 12 格花位布局：标签按内容自适应宽度（不被截断），
            // 剩余宽度由 6 组（空位格 + 花位格）均分，花位位置恒定，
            // 与最后一排一致；鲜花从左到右靠左排布，拿完只留空位格
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
                // 空位格（花间固定间距）
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
                        item.setClickable(false); // 牛粪永远不可点击
                        if (remainingFlowers[i] == 0) {
                            remainingFlowers[i] = 1; // 牛粪排恒定 1 枚
                        }
                    } else {
                        item.setText("🌹");
                        item.setTextColor(Color.RED);
                        if (slot < selectedFlowers[i].length && selectedFlowers[i][slot]) {
                            item.setAlpha(0.5f); // 选中变暗
                        } else {
                            item.setAlpha(1.0f);
                        }
                        // 仅玩家回合可点击
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
                    // 空花位：占格保持固定位置
                    View empty = new View(this);
                    empty.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                    rowLayout.addView(empty);
                }
            }

            // 第一行（牛粪行）最右边放喇叭：音效开关
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

            // 第二行（两朵鲜花）：用 FrameLayout 浮层显示提示文字。
            // 提示文字不参与行布局（不占宽度），无论显示与否鲜花格子位置恒定，
            // 只在长按确认按钮 3 秒时于右侧浮动显示走法提示
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

    // 点击鲜花：选中 / 取消选中
    private void onFlowerClick(int row, int position) {
        if (row == 0) {
            addLog("不能拿牛粪，只能拿鲜花！");
            return;
        }

        // 切换排时清空之前的选中
        if (selectedRow != -1 && selectedRow != row) {
            resetSelectionState();
        }

        selectedRow = row;
        if (position < selectedFlowers[row].length) {
            selectedFlowers[row][position] = !selectedFlowers[row][position];
        }
        playDida(); // 点击鲜花：滴答

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

    // 长按「确认选择」3 秒：显示当前棋局该拿哪排几朵
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
        if (rowsContainer != null) {
            setupGameBoard(true);
        }
    }

    // 重置选中状态
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

    // 点击鲜花：滴答
    private void playDida() {
        if (soundEnabled && soundPool != null && soundDida != 0) {
            soundPool.play(soundDida, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    // 确认选择/拿走鲜花：send
    private void playSend() {
        if (soundEnabled && soundPool != null && soundSend != 0) {
            soundPool.play(soundSend, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    // 胜利音效
    private void playWin() {
        if (soundEnabled && soundPool != null && soundWin != 0) {
            soundPool.play(soundWin, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    // 失败音效
    private void playLose() {
        if (soundEnabled && soundPool != null && soundLose != 0) {
            soundPool.play(soundLose, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    private void toggleSound() {
        soundEnabled = !soundEnabled;
        setupGameBoard(true);
        addLog("音效已" + (soundEnabled ? "开启" : "关闭"));
    }

    // 展示游戏规则
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
            "4. 点击「确认选择 / 鲜花拿来」确认拿取",
            "点击「准备好了」开始" + getPlayerName() + "的第一局游戏！"
        };
        for (String rule : rules) {
            tvGameLog.append(rule + "\n");
        }
    }

    // ===== 检查更新（UpdateManager，update 读取地址当前留空） =====
    private void checkUpdate() {
        final UpdateManager updateManager = new UpdateManager(this);
        updateManager.setCallback(new UpdateManager.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String latestVersion, String updateLog, String apkUrl) {
                showUpdateDialog(latestVersion, updateLog, apkUrl);
                updateManager.shutdown();
            }

            @Override
            public void onUpdateChecked(boolean hasUpdate) {
                if (!hasUpdate) {
                    Toast.makeText(MainActivity.this, R.string.update_no_update, Toast.LENGTH_SHORT).show();
                }
                updateManager.shutdown();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, error != null ? error
                        : getString(R.string.update_error_fetch), Toast.LENGTH_LONG).show();
                updateManager.shutdown();
            }
        });

        String currentVersion;
        try {
            currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            currentVersion = "1.0";
        }
        updateManager.checkForUpdate(currentVersion);
    }

    private void showUpdateDialog(final String version, final String log, final String apkUrl) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.update_title) + " v" + version);
        builder.setMessage((log == null || log.isEmpty()) ? getString(R.string.update_default_log) : log);
        builder.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (apkUrl == null || apkUrl.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "下载地址未配置", Toast.LENGTH_SHORT).show();
                } else {
                    downloadAndInstallApk(apkUrl);
                }
            }
        });
        builder.setNegativeButton(getString(R.string.update_btn_later), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // 24 小时后再提醒
            }
        });
        builder.setNeutralButton(getString(R.string.update_btn_ignore), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                updateIgnoreVersion(version);
            }
        });
        builder.show();
    }

    private void updateIgnoreVersion(final String version) {
        final UpdateManager updateManager = new UpdateManager(this);
        updateManager.ignoreVersion(version);
        updateManager.shutdown();
    }

    // 用系统下载器下载 APK 到公共下载目录，下载完成后通知栏可点击安装
    private void downloadAndInstallApk(String apkUrl) {
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            android.net.Uri uri = android.net.Uri.parse(apkUrl);
            android.app.DownloadManager.Request request =
                    new android.app.DownloadManager.Request(uri);
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setTitle("鲜花与牛粪 新版本下载");
            request.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    "cowdung_update.apk");
            dm.enqueue(request);
            Toast.makeText(this, "开始下载，请留意通知栏", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    // ===== 样式辅助 =====
    private GradientDrawable roundedBg(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(6);
        gd.setColor(color);
        return gd;
    }

    // 圆角 + 白色描边
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