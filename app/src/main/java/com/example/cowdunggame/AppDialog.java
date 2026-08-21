// AppDialog.java
// 通用弹窗：圆角卡片 + 分隔线。可复用于注销确认、提示、确认操作等所有弹窗。
//   样式：圆角深色卡片（金边）+ 标题 / 横分隔线 / 消息 / 横分隔线 / 按钮行(竖分隔线分隔按钮)
package com.example.cowdunggame;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AppDialog {

    public interface OnClick {
        void onClick(AppDialog dialog);
    }

    private final Dialog dialog;
    private final Context context;

    private AppDialog(Context context) {
        this.context = context;
        this.dialog = new Dialog(context);
        this.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    // ============================================================
    // 便捷构造：确认框（标题 + 消息 + 正/负按钮）
    // ============================================================
    public static AppDialog confirm(Activity activity, String title, String message,
                                    String positiveText, String negativeText,
                                    OnClick positive, OnClick negative) {
        final AppDialog appDialog = new AppDialog(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(cardBg());
        final int contentPad = appDialog.dp(20);
        final int btnH = appDialog.dp(52);
        final int titlePadBottom = appDialog.dp(14);

        // 标题
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTextColor(Color.parseColor("#FFD700"));
        titleView.setGravity(Gravity.LEFT);
        titleView.setShadowLayer(4, 2, 2, Color.BLACK);
        titleView.setPadding(contentPad, contentPad, contentPad, titlePadBottom);
        root.addView(titleView);

        // 分隔线
        root.addView(divider(activity));

        // 消息
        final LinearLayout messageBox = new LinearLayout(activity);
        messageBox.setOrientation(LinearLayout.VERTICAL);
        messageBox.setPadding(contentPad, contentPad, contentPad, contentPad);
        TextView messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextSize(15);
        messageView.setTextColor(Color.WHITE);
        messageView.setLineSpacing(2, 1.05f);
        messageView.setShadowLayer(2, 1, 1, Color.BLACK);
        messageBox.addView(messageView);
        root.addView(messageBox);

        // 分隔线
        root.addView(divider(activity));

        // 按钮行
        LinearLayout buttonRow = new LinearLayout(activity);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        root.addView(buttonRow);

        if (negativeText != null && !negativeText.isEmpty()) {
            Button negativeBtn = new Button(activity);
            negativeBtn.setText(negativeText);
            negativeBtn.setTextSize(15);
            negativeBtn.setTextColor(Color.WHITE);
            negativeBtn.setAllCaps(false);
            negativeBtn.setBackground(flatButtonBg(0x00000000));
            negativeBtn.setLayoutParams(new LinearLayout.LayoutParams(0, btnH, 1f));
            if (negative != null) {
                negativeBtn.setOnClickListener(v -> {
                    appDialog.dismiss();
                    negative.onClick(appDialog);
                });
            } else {
                negativeBtn.setOnClickListener(v -> appDialog.dismiss());
            }
            buttonRow.addView(negativeBtn);

            // 按钮之间竖分隔线
            View verticalLine = new View(activity);
            verticalLine.setBackgroundColor(0x66FFFFFF);
            LinearLayout.LayoutParams vp =
                new LinearLayout.LayoutParams(appDialog.dp(1), LinearLayout.LayoutParams.MATCH_PARENT);
            verticalLine.setLayoutParams(vp);
            buttonRow.addView(verticalLine);
        }

        if (positiveText != null && !positiveText.isEmpty()) {
            Button positiveBtn = new Button(activity);
            positiveBtn.setText(positiveText);
            positiveBtn.setTextSize(15);
            positiveBtn.setTextColor(Color.parseColor("#FFD700"));
            positiveBtn.setAllCaps(false);
            positiveBtn.setBackground(flatButtonBg(0x00000000));
            positiveBtn.setLayoutParams(new LinearLayout.LayoutParams(0, btnH, 1f));
            positiveBtn.setOnClickListener(v -> {
                appDialog.dismiss();
                if (positive != null) positive.onClick(appDialog);
            });
            buttonRow.addView(positiveBtn);
        }

        // 窗口配置：圆角卡片、点击外部不取消
        Window window = appDialog.dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new GradientDrawable()); // 透明，由卡片负责圆角
        }
        appDialog.dialog.setContentView(root);
        int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.78f);
        if (window != null) window.setLayout(width, LinearLayout.LayoutParams.WRAP_CONTENT);
        appDialog.dialog.setCancelable(false);
        return appDialog;
    }

    public void show() {
        dialog.show();
    }

    // ============================================================
    // 输入框弹窗：标题 + 单行数字输入 + 正/负按钮（加入房间等场景）
    //   负按钮 -> negative；正按钮 -> positive（调用方用 getInputText() 取输入）
    // ============================================================
    private EditText inputEditText;

    public static AppDialog input(Activity activity, String title, String hint,
                                  String positiveText, String negativeText,
                                  OnClick positive, OnClick negative) {
        final AppDialog appDialog = new AppDialog(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(cardBg());
        final int contentPad = appDialog.dp(20);
        final int btnH = appDialog.dp(52);
        final int titlePadBottom = appDialog.dp(14);

        // 标题
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTextColor(Color.parseColor("#FFD700"));
        titleView.setGravity(Gravity.LEFT);
        titleView.setShadowLayer(4, 2, 2, Color.BLACK);
        titleView.setPadding(contentPad, contentPad, contentPad, titlePadBottom);
        root.addView(titleView);

        // 分隔线
        root.addView(divider(activity));

        // 输入框
        final EditText et = new EditText(activity);
        et.setHint(hint);
        et.setHintTextColor(Color.GRAY);
        et.setTextColor(Color.WHITE);
        et.setTextSize(18);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setShape(GradientDrawable.RECTANGLE);
        inputBg.setCornerRadius(appDialog.dp(8));
        inputBg.setColor(0xFF1C1C1C);
        inputBg.setStroke(2, Color.parseColor("#88FFD700"));
        et.setBackground(inputBg);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, appDialog.dp(52));
        ep.setMargins(contentPad, contentPad, contentPad, contentPad);
        et.setLayoutParams(ep);
        appDialog.inputEditText = et;
        root.addView(et);

        // 分隔线
        root.addView(divider(activity));

        // 按钮行
        LinearLayout buttonRow = new LinearLayout(activity);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        root.addView(buttonRow);

        if (negativeText != null && !negativeText.isEmpty()) {
            Button negativeBtn = new Button(activity);
            negativeBtn.setText(negativeText);
            negativeBtn.setTextSize(15);
            negativeBtn.setTextColor(Color.WHITE);
            negativeBtn.setAllCaps(false);
            negativeBtn.setBackground(flatButtonBg(0x00000000));
            negativeBtn.setLayoutParams(new LinearLayout.LayoutParams(0, btnH, 1f));
            if (negative != null) {
                negativeBtn.setOnClickListener(v -> {
                    appDialog.dismiss();
                    negative.onClick(appDialog);
                });
            } else {
                negativeBtn.setOnClickListener(v -> appDialog.dismiss());
            }
            buttonRow.addView(negativeBtn);

            View verticalLine = new View(activity);
            verticalLine.setBackgroundColor(0x66FFFFFF);
            LinearLayout.LayoutParams vp =
                new LinearLayout.LayoutParams(appDialog.dp(1), LinearLayout.LayoutParams.MATCH_PARENT);
            verticalLine.setLayoutParams(vp);
            buttonRow.addView(verticalLine);
        }

        if (positiveText != null && !positiveText.isEmpty()) {
            Button positiveBtn = new Button(activity);
            positiveBtn.setText(positiveText);
            positiveBtn.setTextSize(15);
            positiveBtn.setTextColor(Color.parseColor("#FFD700"));
            positiveBtn.setAllCaps(false);
            positiveBtn.setBackground(flatButtonBg(0x00000000));
            positiveBtn.setLayoutParams(new LinearLayout.LayoutParams(0, btnH, 1f));
            positiveBtn.setOnClickListener(v -> {
                appDialog.dismiss();
                if (positive != null) positive.onClick(appDialog);
            });
            buttonRow.addView(positiveBtn);
        }

        // 窗口配置：圆角卡片、点击外部不取消
        Window window = appDialog.dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new GradientDrawable());
        }
        appDialog.dialog.setContentView(root);
        int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.78f);
        if (window != null) window.setLayout(width, LinearLayout.LayoutParams.WRAP_CONTENT);
        appDialog.dialog.setCancelable(false);
        return appDialog;
    }

    // 取输入框文字（positive/negative 回调里调用；非 input() 弹窗返回空串）
    public String getInputText() {
        return inputEditText != null ? inputEditText.getText().toString().trim() : "";
    }

    public AppDialog backCancelable() {
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        return this;
    }

    public void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    public Dialog raw() {
        return dialog;
    }

    // ============================================================
    // 样式构件
    // ============================================================
    private static GradientDrawable cardBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(20);
        gd.setColor(0xF2202020);
        gd.setStroke(1, Color.parseColor("#88FFD700"));
        return gd;
    }

    private static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(0x2EFFFFFF);
        line.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return line;
    }

    private static GradientDrawable flatButtonBg(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(0);
        gd.setColor(color);
        return gd;
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}