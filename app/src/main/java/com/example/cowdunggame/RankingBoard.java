package com.example.cowdunggame;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public class RankingBoard {

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void show(final Activity act, final SupabaseClient client) {
        if (act == null || act.isFinishing() || client == null) return;
        final int screenW = act.getResources().getDisplayMetrics().widthPixels;
        final int screenH = act.getResources().getDisplayMetrics().heightPixels;

        final FrameLayout overlay = new FrameLayout(act);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(0x80000000);
        overlay.setClickable(true);

        final LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(act, 14));
        card.setBackground(bg);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { /* 点卡片内不关闭 */ }
        });

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                (int) (screenW * 0.92f), (int) (screenH * 0.8f));
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);

        // 标题栏：排行榜 + 关闭
        LinearLayout titleBar = new LinearLayout(act);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(act);
        title.setText("排行榜");
        title.setTextSize(18);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(20);
        close.setTextColor(Color.BLACK);
        close.setPadding(dp(act, 8), 0, 0, 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (overlay.getParent() != null)
                    ((ViewGroup) act.findViewById(android.R.id.content)).removeView(overlay);
            }
        });
        titleBar.addView(close);
        card.addView(titleBar);

        // 我的排名汇总
        final TextView myRankView = new TextView(act);
        myRankView.setTextSize(14);
        myRankView.setTextColor(Color.DKGRAY);
        myRankView.setPadding(0, dp(act, 8), 0, dp(act, 8));
        card.addView(myRankView);

        // 列表
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(act);
        sv.addView(list);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        svLp.weight = 1f;
        sv.setLayoutParams(svLp);
        card.addView(sv);

        overlay.addView(card);
        ((ViewGroup) act.findViewById(android.R.id.content)).addView(overlay);

        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (overlay.getParent() != null)
                    ((ViewGroup) act.findViewById(android.R.id.content)).removeView(overlay);
            }
        });

        final String myUid = client.getUserId();
        final View[] expanded = new View[1];

        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray ranking = client.getRanking(1000);
                final JSONObject myRank = client.getUserRank(myUid);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 我的排名
                        if (myRank != null && myRank.optInt("rank", 0) > 0) {
                            int r = myRank.optInt("rank", 0);
                            int sc = myRank.optInt("score", 0);
                            myRankView.setText("我的排名：第 " + r + " 名 · 积分 " + sc
                                    + " · 军衔 " + ProfilePopup.levelName(sc));
                        } else {
                            myRankView.setText("我的排名：暂无");
                        }

                        if (ranking == null || ranking.length() == 0) {
                            TextView empty = new TextView(act);
                            empty.setText("暂无数据");
                            empty.setTextColor(Color.GRAY);
                            empty.setPadding(0, dp(act, 12), 0, dp(act, 12));
                            list.addView(empty);
                            return;
                        }

                        for (int i = 0; i < ranking.length(); i++) {
                            JSONObject e = ranking.optJSONObject(i);
                            if (e == null) continue;
                            final int no = i + 1;
                            final String id = e.optString("id", "");
                            final String nick = e.optString("nickname", "无名");
                            final int score = e.optInt("score", 0);
                            final int wins = e.optInt("wins", 0);
                            final int losses = e.optInt("losses", 0);
                            final int games = e.optInt("total_games", 0);
                            final String gender = e.optString("gender", "");

                            final LinearLayout row = new LinearLayout(act);
                            row.setOrientation(LinearLayout.VERTICAL);
                            if (id.equals(myUid)) {
                                row.setBackgroundColor(0xFFE8F0FE);
                            }
                            row.setPadding(0, dp(act, 6), 0, dp(act, 6));

                            // 头部：序号 | 昵称(性别点) | 军衔 | 总积分
                            LinearLayout head = new LinearLayout(act);
                            head.setOrientation(LinearLayout.HORIZONTAL);
                            head.setGravity(Gravity.CENTER_VERTICAL);

                            TextView tvNo = colText(act, String.valueOf(no), screenW, true);
                            tvNo.setLayoutParams(weightLp(0.12f));
                            head.addView(tvNo);

                            // 性别点 + 昵称
                            LinearLayout nickBox = new LinearLayout(act);
                            nickBox.setOrientation(LinearLayout.HORIZONTAL);
                            nickBox.setGravity(Gravity.CENTER_VERTICAL);
                            View dot = new View(act);
                            int dotColor;
                            if ("female".equals(gender) || "女".equals(gender) || "f".equals(gender)) {
                                dotColor = 0xFFE36DA8;
                            } else if ("male".equals(gender) || "男".equals(gender) || "m".equals(gender)) {
                                dotColor = 0xFF3B7DD8;
                            } else {
                                dotColor = 0xFF9E9E9E;
                            }
                            GradientDrawable d = new GradientDrawable();
                            d.setShape(GradientDrawable.OVAL);
                            d.setColor(dotColor);
                            dot.setBackground(d);
                            int ds = dp(act, 10);
                            nickBox.addView(dot, new LinearLayout.LayoutParams(ds, ds));
                            TextView tvNick = colText(act, nick, screenW, false);
                            tvNick.setPadding(dp(act, 6), 0, 0, 0);
                            tvNick.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                            nickBox.addView(tvNick);
                            nickBox.setLayoutParams(weightLp(0.40f));
                            head.addView(nickBox);

                            TextView tvRank = colText(act, ProfilePopup.levelName(score), screenW, false);
                            tvRank.setLayoutParams(weightLp(0.24f));
                            head.addView(tvRank);

                            TextView tvScore = colText(act, String.valueOf(score), screenW, true);
                            tvScore.setLayoutParams(weightLp(0.24f));
                            head.addView(tvScore);

                            row.addView(head);

                            // 明细（默认隐藏）
                            final TextView detail = new TextView(act);
                            detail.setText("赢 " + wins + " · 输 " + losses + " · 场次 " + games);
                            detail.setTextSize(13);
                            detail.setTextColor(Color.GRAY);
                            detail.setPadding(dp(act, 24), dp(act, 4), 0, 0);
                            detail.setVisibility(View.GONE);
                            row.addView(detail);

                            head.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (expanded[0] != null && expanded[0] != detail) {
                                        expanded[0].setVisibility(View.GONE);
                                    }
                                    if (detail.getVisibility() == View.VISIBLE) {
                                        detail.setVisibility(View.GONE);
                                        expanded[0] = null;
                                    } else {
                                        detail.setVisibility(View.VISIBLE);
                                        expanded[0] = detail;
                                    }
                                }
                            });

                            list.addView(row);
                        }
                    }
                });
            }
        }).start();
    }

    private static TextView colText(Activity act, String text, int screenW, boolean bold) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (int) (screenW * 0.04f));
        t.setTextColor(Color.BLACK);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private static LinearLayout.LayoutParams weightLp(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }
}
