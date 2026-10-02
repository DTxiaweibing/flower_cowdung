package com.example.cowdunggame;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 观众详情大弹窗。
 *
 * 展示单个观众的资料（昵称 / 性别 / 职务 / 排名 / 积分 / 胜负），
 * 末尾按需提供「踢出该观众」按钮 —— 只有坐在本桌对局的 A/B 玩家可见（canKick），
 * 观众之间互相不可见（canKick=false，底部只有「返回」）。
 *
 * 与 ProfilePopup 的区别：
 *   · ProfilePopup 是半屏小卡 + 5 秒自动隐藏 + 点哪都关，这里是居中大窗 + 手动关闭，
 *     因为末尾有需要点的操作按钮，自动消失会打断操作。
 *   · 独立成类而不是复用 ProfilePopup，是为了避开 ProfilePopup 的静态
 *     handler/overlay 字段 —— 从观众列表（PopupWindow）里再弹 ProfilePopup 会
 *     互相 removeCallbacks/removeView，把上一层弹窗一起干掉。
 */
public class WatcherInfoDialog {

    public interface Action {
        /** 点「返回」：关掉本窗并重新拉起观众列表 */
        void onBack();
        /** 点「踢出」：交给 Activity 做二次确认（本窗先不关，确认框盖在上面） */
        void onKick();
        /** 本窗因用户操作被关闭（点遮罩/点返回）。用于让 Activity 作废在途的异步刷新 */
        void onClosed();
    }

    private static FrameLayout sOverlay;
    private static ViewGroup sContent;
    private static Action sAction;

    private static int dp(Activity act, float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 真正移除窗口（不通知，由各调用方决定要不要回调 onClosed） */
    private static void removeOverlay() {
        if (sOverlay != null && sContent != null && sOverlay.getParent() != null) {
            sContent.removeView(sOverlay);
        }
        sOverlay = null;
        sContent = null;
    }

    /** 用户主动关闭：移除 + 通知 Activity */
    private static void closeAndNotify() {
        Action a = sAction;
        removeOverlay();
        sAction = null;
        if (a != null) a.onClosed();
    }

    /** 关闭当前资料窗（踢出成功后由 Activity 调用，避免与确认框叠在一起） */
    public static void dismiss() {
        removeOverlay();
        sAction = null;
    }

    private static String genderText(String g) {
        if ("female".equals(g) || "女".equals(g) || "f".equals(g)) return "女";
        if ("male".equals(g) || "男".equals(g) || "m".equals(g)) return "男";
        return "未知";
    }

    private static void addLine(Activity act, LinearLayout parent, String text,
                                float sizeSp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(act, 6);
        t.setLayoutParams(p);
        parent.addView(t);
    }

    private static TextView makeButton(Activity act, String text, int textColor, int bgColor) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(act, 10));
        g.setColor(bgColor);
        b.setBackground(g);
        return b;
    }

    /**
     * 显示观众详情。
     *
     * @param canKick 是否显示「踢出该观众」——由调用方按“我是不是本桌玩家”决定。
     *                真正的权限仍在服务端 RPC 里再校验一次，这里只管界面。
     */
    public static void show(final Activity act, SupabaseClient.WatcherInfo w,
                            boolean canKick, final Action action) {
        if (act == null || act.isFinishing() || w == null) return;
        final ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
        if (content == null) return;

        // 同一时刻只保留一个资料窗：先移除上一次的（openWatcherInfo 会先用占位数据
        // 弹一次、拿到排名后再调本方法刷新一次）
        removeOverlay();

        final FrameLayout overlay = new FrameLayout(act);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 20);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(act, 16));
        bg.setColor(Color.WHITE);
        card.setBackground(bg);

        // ---- 资料区（内容对齐现有资料卡 ProfilePopup：职务/排名/积分/胜利/失败 + 性别）----
        String nick = (w.nickname == null || w.nickname.isEmpty()) ? "无名" : w.nickname;
        addLine(act, card, nick, 20, Color.BLACK, true);
        addLine(act, card, "性别：" + genderText(w.gender), 14, 0xFF555555, false);
        addLine(act, card, "职务：" + ProfilePopup.levelName(w.score), 15, Color.BLACK, false);
        addLine(act, card, "排名：" + (w.rank > 0 ? w.rank : "暂无"), 15, Color.BLACK, false);
        addLine(act, card, "积分：" + w.score, 15, Color.BLACK, false);
        addLine(act, card, "胜利：" + w.wins + "  失败：" + w.losses, 15, Color.BLACK, false);
        addLine(act, card, "对局场次：" + w.totalGames, 15, Color.BLACK, false);
        addLine(act, card, ProfilePopup.upgradeHint(w.score), 13, 0xFF3B7DD8, false);

        // ---- 分隔线 ----
        View divider = new View(act);
        divider.setBackgroundColor(0x22000000);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1));
        divLp.topMargin = dp(act, 4);
        divLp.bottomMargin = dp(act, 12);
        divider.setLayoutParams(divLp);
        card.addView(divider);

        // ---- 底部按钮行：踢出（仅玩家）+ 返回 ----
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        if (canKick) {
            TextView kick = makeButton(act, "踢出该观众", Color.WHITE, 0xFFD64545);
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            kp.rightMargin = dp(act, 8);
            kick.setLayoutParams(kp);
            kick.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (action != null) action.onKick();
                }
            });
            row.addView(kick);
        }

        TextView back = makeButton(act, canKick ? "返回" : "关闭", Color.WHITE, 0xFF3B7DD8);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (canKick) bp.leftMargin = dp(act, 8);
        back.setLayoutParams(bp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Action a = sAction;
                closeAndNotify();
                if (a != null) a.onBack();
            }
        });
        row.addView(back);
        card.addView(row);

        // ---- 装配 ----
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                (int) (act.getResources().getDisplayMetrics().widthPixels * 0.78f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);
        overlay.addView(card);
        content.addView(overlay);

        sOverlay = overlay;
        sContent = content;
        sAction = action;

        // 点暗色遮罩：只关窗，不重开列表（想回列表请点「返回/关闭」）
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAndNotify();
            }
        });
    }
}
