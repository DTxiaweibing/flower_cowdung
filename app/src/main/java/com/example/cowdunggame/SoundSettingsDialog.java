// SoundSettingsDialog.java
// 点"声音"开关弹出的设置框：两个独立开关——音效 / 音乐。
// 偏好持久化到 CowDungPrefs；音效变化通过 onSfxChanged 回调刷新UI，
// 音乐变化直接驱动 BgmManager。
package com.example.cowdunggame;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class SoundSettingsDialog {

    public static void show(Context context, Runnable onSfxChanged) {
        SharedPreferences sp = context.getSharedPreferences("CowDungPrefs", Context.MODE_PRIVATE);
        boolean sfx = sp.getBoolean("soundEnabled", true);
        boolean music = sp.getBoolean("musicEnabled", true);

        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (density * 16 + 0.5f);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(row(context, "音效", sfx, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean("soundEnabled", isChecked).apply();
                if (onSfxChanged != null) onSfxChanged.run();
            }
        }));

        // 分隔线（两行开关之间）
        int divH = (int) (density + 0.5f);
        int divPadX = (int) (density * 4 + 0.5f);
        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, divH);
        dlp.setMargins(divPadX, 0, divPadX, 0);
        divider.setLayoutParams(dlp);
        root.addView(divider);

        root.addView(row(context, "音乐", music, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean("musicEnabled", isChecked).apply();
                BgmManager.get().setMusicEnabled(context, isChecked);
            }
        }));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("声音设置")
                .setView(root)
                .setPositiveButton("关闭", null)
                .create();
        dialog.show();
        // 圆角背景
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_round_bg);
        }
    }

    private static LinearLayout row(Context context, String label, boolean checked,
                                    CompoundButton.OnCheckedChangeListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (density * 8 + 0.5f), 0, (int) (density * 8 + 0.5f));

        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(16);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(context);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);

        row.addView(tv);
        row.addView(sw);
        return row;
    }
}
