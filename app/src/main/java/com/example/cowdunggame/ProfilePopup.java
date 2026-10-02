package com.example.cowdunggame;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class ProfilePopup {

    // 职务体系：共 12 级，满级累计 1008 积分。全部整数，无浮点运算。
    //   累计所需 total(n) = 7 * n^2
    //   本级所需 need(n)  = 7 * (2n - 1)      // n 级的档宽 = n 级所需 - (n-1) 级所需
    // 预存展开值，避免每次运行公式：查表即可，纯整数、零浮点误差。
    private static final int MAX_LEVEL = 12;

    private static final String[] LEVELS = {
            "新兵", "列兵", "上等兵", "班长", "排长", "连长",
            "营长", "团长", "旅长", "师长", "军长", "战区司令"
    };
    // 进第 n 级所需的累计积分：7, 28, 63, 112, 175, 252, 343, 448, 567, 700, 847, 1008
    private static final int[] LEVEL_TOTAL = {
            7, 28, 63, 112, 175, 252, 343, 448, 567, 700, 847, 1008
    };
    // 第 n 级自身的档宽：7, 21, 35, 49, 63, 77, 91, 105, 119, 133, 147, 161
    private static final int[] LEVEL_NEED = {
            7, 21, 35, 49, 63, 77, 91, 105, 119, 133, 147, 161
    };

    // 未入级（累计 < 7）时显示的职务名
    private static final String NO_TITLE = "未入级";

    // 当前显示的资料卡（静态引用，确保新弹窗取消上一次的定时关闭，避免叠加/跨 Activity 短持有）
    private static Handler sHandler;
    private static Runnable sDismiss;
    private static View sOverlay;
    private static ViewGroup sContent;

    // 累计所需积分（公式 7*n*n 的展开表，供测试与调试核对）
    static int total(int level) {
        return LEVEL_TOTAL[level - 1];
    }

    public static String levelName(int score) {
        int lv = getLevelByPoints(score);
        return lv <= 0 ? NO_TITLE : LEVELS[lv - 1];
    }

    // 纯整数查表：找最后一个 total <= score 的级数；未入级返回 0。
    // 不能用除法/取模/开方 —— 12 档虽然满级才 1008，但档宽是等差 14 的奇数倍
    // （7/21/35/...），不是常数步长，线性扫描 12 次最快也最不容易错。
    public static int getLevelByPoints(int score) {
        int level = 0;
        for (int n = 1; n <= MAX_LEVEL; n++) {
            if (score >= LEVEL_TOTAL[n - 1]) {
                level = n;
            } else {
                break;
            }
        }
        return level;
    }

    // 升到下一职务还差多少积分；已满级（战区司令）返回 0。
    // 未入级时同样有效：7 - score。
    public static int pointsToNext(int score) {
        int lv = getLevelByPoints(score);
        if (lv >= MAX_LEVEL) return 0;
        int next = lv + 1;
        return LEVEL_TOTAL[next - 1] - score;
    }

    // 升级提示文案：未入级 / 升级中 / 满级
    public static String upgradeHint(int score) {
        int lv = getLevelByPoints(score);
        if (lv <= 0) return "距「新兵」还差 " + pointsToNext(score) + " 积分";
        if (lv >= MAX_LEVEL) return "已达最高职务「战区司令」";
        return "距「" + LEVELS[lv] + "」还差 " + pointsToNext(score) + " 积分";
    }

    // 距下一职务的进度 0.0~1.0（仅供进度条展示；未入级按 0 起算，满级为 1）
    public static float progress(int score) {
        int lv = getLevelByPoints(score);
        if (lv >= MAX_LEVEL) return 1f;
        int next = lv + 1;
        int current = (lv == 0) ? 0 : LEVEL_TOTAL[lv - 1];
        float gained = score - current;
        float span = LEVEL_NEED[next - 1];
        if (span <= 0) return 1f;
        float p = gained / span;
        return p < 0 ? 0 : (p > 1 ? 1 : p);
    }

    // 显示一个半屏宽、圆角弹窗；blackStyle=true 黑底白字（菜单），false 白底黑字（游戏内）
    // gravity 控制悬浮位置（如 Gravity.CENTER 或 Gravity.CENTER_HORIZONTAL|Gravity.TOP）
    public static void show(Activity act, String nickname, int score, int rank,
                            int wins, int losses, boolean blackStyle, int gravity) {
        showAt(act, nickname, score, rank, wins, losses, blackStyle, gravity, 0, 0);
    }

    // 以某个 View 为锚点定位（与「观众列表」弹窗一致：水平居中于锚点、顶部对齐），
    // 找不到锚点则回退到屏幕居中。
    public static void show(Activity act, String nickname, int score, int rank,
                            int wins, int losses, boolean blackStyle, View anchor) {
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int popupW = screenW / 2;
        int gravity = Gravity.CENTER;
        int x = 0, y = 0;
        if (anchor != null) {
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            int aW = anchor.getWidth();
            x = loc[0] + (aW - popupW) / 2;
            y = loc[1];
            gravity = Gravity.TOP | Gravity.LEFT;
        }
        showAt(act, nickname, score, rank, wins, losses, blackStyle, gravity, x, y);
    }

    private static void showAt(Activity act, String nickname, int score, int rank,
                               int wins, int losses, boolean blackStyle,
                               int gravity, int x, int y) {
        if (act == null || act.isFinishing()) return;
        final ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
        if (content == null) return;

        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        int w = screenW / 2;
        int pad = (int) (screenW * 0.04f);

        final FrameLayout overlay = new FrameLayout(act);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setClickable(true);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(screenW * 0.03f);
        bg.setColor(blackStyle ? Color.BLACK : Color.WHITE);
        box.setBackground(bg);

        int textColor = blackStyle ? Color.WHITE : Color.BLACK;

        addLine(act, box, (nickname == null || nickname.isEmpty() ? "无名" : nickname)
                + "  ·  " + levelName(score), (int) (screenW * 0.05f), textColor, true);
        addLine(act, box, "职务：" + levelName(score), (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, "排名：" + (rank > 0 ? rank : "暂无"), (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, "积分：" + score, (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, "胜利：" + wins, (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, "失败：" + losses, (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, upgradeHint(score), (int) (screenW * 0.038f), textColor, false);
        addProgressBar(act, box, score, textColor);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = gravity;
        if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) {
            bp.topMargin = (y > 0) ? y : (int) (screenH * 0.18f);
        }
        if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT) {
            bp.leftMargin = (x > 0) ? x : 0;
        }
        box.setLayoutParams(bp);
        overlay.addView(box);
        content.addView(overlay);

        // 取消上一次未触发的自动隐藏，并移除可能残留的旧弹窗（防止叠加/跨 Activity 泄漏）
        if (sHandler != null && sDismiss != null) {
            sHandler.removeCallbacks(sDismiss);
            if (sOverlay != null && sContent != null && sOverlay.getParent() != null) {
                sContent.removeView(sOverlay);
            }
        }
        sOverlay = overlay;
        sContent = content;
        sHandler = new Handler(Looper.getMainLooper());
        sDismiss = new Runnable() {
            @Override
            public void run() {
                if (sOverlay != null && sContent != null && sOverlay.getParent() != null) {
                    sContent.removeView(sOverlay);
                }
                sOverlay = null;
                sContent = null;
            }
        };
        sHandler.postDelayed(sDismiss, 5000); // 5 秒无操作自动隐藏

        // 任何点击立即关闭
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sHandler != null && sDismiss != null) sHandler.removeCallbacks(sDismiss);
                if (overlay.getParent() != null) content.removeView(overlay);
            }
        });
    }

    // 升级进度条：横向细条，宽度按屏宽，progress 由纯整数查表算出。
    // 满级显示满格；未入级从 0 起。
    private static void addProgressBar(Activity act, LinearLayout parent, int score, int textColor) {
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        ProgressBar pb = new ProgressBar(act, null,
                android.R.attr.progressBarStyleHorizontal);
        int barH = Math.max(4, (int) (screenW * 0.014f));
        int barW = (int) (screenW * 0.42f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(barW, barH);
        lp.topMargin = (int) (screenW * 0.012f);
        lp.bottomMargin = (int) (screenW * 0.006f);
        pb.setLayoutParams(lp);
        pb.setMax(1000);
        // 用 int 定点推进（乘 1000 再取整），避免进度条自身动画用浮点
        pb.setProgress(Math.round(progress(score) * 1000f));
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(
                textColor == Color.WHITE ? 0xFFFFD700 : 0xFF3B7DD8));
        parent.addView(pb);
    }

    private static void addLine(Activity act, LinearLayout parent, String text, int sizePx,
                               int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = (int) (sizePx * 0.25f);
        t.setLayoutParams(p);
        parent.addView(t);
    }
}
