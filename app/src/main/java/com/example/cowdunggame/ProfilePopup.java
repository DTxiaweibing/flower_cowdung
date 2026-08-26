package com.example.cowdunggame;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.widget.FrameLayout;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class ProfilePopup {

    private static final String[] LEVELS = {
            "新兵", "士兵", "班长", "排长", "连长", "营长", "团长", "旅长", "司令", "军长", "元帅"
    };

    public static String levelName(int score) {
        int idx = score / 200;
        if (idx < 0) idx = 0;
        if (idx >= LEVELS.length) idx = LEVELS.length - 1;
        return LEVELS[idx];
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
        addLine(act, box, "军衔：" + levelName(score), (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, "排名：" + (rank > 0 ? rank : "暂无"), (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, "积分：" + score, (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, "胜利：" + wins, (int) (screenW * 0.042f), textColor, false);
        addLine(act, box, "失败：" + losses, (int) (screenW * 0.042f), textColor, false);

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

        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable dismiss = new Runnable() {
            @Override
            public void run() {
                if (overlay.getParent() != null) content.removeView(overlay);
            }
        };
        h.postDelayed(dismiss, 5000); // 5 秒无操作自动隐藏

        // 任何点击立即关闭
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                h.removeCallbacks(dismiss);
                if (overlay.getParent() != null) content.removeView(overlay);
            }
        });
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
