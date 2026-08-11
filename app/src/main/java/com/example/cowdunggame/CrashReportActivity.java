package com.example.cowdunggame;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 崩溃日志查看页：展示最新崩溃日志，支持分享与清空（清空后回到主界面）。
 */
public class CrashReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.crash_report_title);
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setText(R.string.crash_occurred);
        detail.setTextColor(0xFFB0BEC5);
        detail.setTextSize(13);
        root.addView(detail);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollView.setLayoutParams(scrollParams);

        HorizontalScrollView horizontalScrollView = new HorizontalScrollView(this);

        TextView tvLog = new TextView(this);
        tvLog.setTextSize(12);
        tvLog.setTextColor(Color.WHITE);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        String log = CrashHandler.getLatestCrashLog(this);
        tvLog.setText(log != null ? log : getString(R.string.crash_no_log_found));
        horizontalScrollView.addView(tvLog);
        scrollView.addView(horizontalScrollView);
        root.addView(scrollView);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, dp(16), 0, 0);

        Button btnShare = new Button(this);
        btnShare.setText(R.string.crash_btn_share);
        btnShare.setTextColor(Color.WHITE);
        btnShare.setBackground(roundedBg(0xFFD84315));
        btnShare.setLayoutParams(btnParams(1, dp(10)));

        Button btnClear = new Button(this);
        btnClear.setText(R.string.crash_btn_clear);
        btnClear.setTextColor(Color.WHITE);
        btnClear.setBackground(roundedBg(0xFF757575));
        btnClear.setLayoutParams(btnParams(1, dp(10)));

        btnShare.setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, tvLog.getText().toString());
            startActivity(Intent.createChooser(share, getString(R.string.crash_share_title)));
        });

        btnClear.setOnClickListener(v -> {
            CrashHandler.clearCrashLogs(this);
            Toast.makeText(this, getString(R.string.crash_log_cleared), Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });

        buttonRow.addView(btnShare);
        buttonRow.addView(btnClear);
        root.addView(buttonRow);

        setContentView(root);
    }

    private GradientDrawable roundedBg(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(8);
        gd.setColor(color);
        return gd;
    }

    private LinearLayout.LayoutParams btnParams(int weight, int marginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(marginPx, 0, marginPx, 0);
        return lp;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}