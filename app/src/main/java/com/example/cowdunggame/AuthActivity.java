// AuthActivity.java (app-new 精简版)
// 注册 + 登录 同一个 Activity，临时使用白色背景。
//   注册：昵称 + 性别 + 密码 --> check_nickname 查重 --> signUp 建号 --> create_profile 建档
//   登录：昵称 + 密码 --> nickToEmail 找回 --> signIn
//   已登录且 session 有效：直接进主菜单。
package com.example.cowdunggame;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class AuthActivity extends Activity {

    private static final String PREFS = "CowDungPrefs";
    private static final String KEY_LOGGED_IN = "LoggedIn";

    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText etNick;
    private final BadWordFilter badWordFilter = new BadWordFilter();
    private EditText etPassword;
    private Button btnMale;
    private Button btnFemale;
    private boolean maleSelected = false;
    private boolean genderSelected = false;
    private TextView tvStatus;
    private Button btnSubmit;
    private Button btnSwitch;
    private boolean registerMode = false;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SupabaseClient client = new SupabaseClient(this);
        if (prefs().getBoolean(KEY_LOGGED_IN, false) && client.hasSession()) {
            startActivity(new Intent(this, MenuActivity.class));
            finish();
            return;
        }

        buildUI();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        // 背景图（注册/登录页共用）
        ImageView bgImage = new ImageView(this);
        bgImage.setImageResource(R.drawable.auth_bg);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImage.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bgImage);

        // 半透明蒙层，保证黑色文字在背景图上清晰可读
        View bgOverlay = new View(this);
        bgOverlay.setBackgroundColor(0x99FFFFFF);
        bgOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bgOverlay);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        card.setPadding((int) (screenW * 0.08f), dp(60), (int) (screenW * 0.08f), dp(40));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
            (int) (screenW * 0.86f), LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        card.setLayoutParams(cardParams);
        root.addView(card);

        // 标题上方的小白花装饰
        ImageView flower = new ImageView(this);
        flower.setImageResource(R.drawable.white_flower);
        flower.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int fw = (int) (screenW * 0.20f);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(fw, fw);
        flp.gravity = Gravity.CENTER_HORIZONTAL;
        flp.bottomMargin = dp(6);
        flower.setLayoutParams(flp);
        card.addView(flower);

        TextView title = new TextView(this);
        title.setText("鲜花与牛粪");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(20));
        card.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setText("注册新账号，或登录现有账号");
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(Color.GRAY);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, 0, 0, dp(20));
        card.addView(tvStatus);

        etNick = new EditText(this);
        etNick.setSingleLine(true);
        etNick.setHint(registerMode ? "昵称（限 4 汉字以内）" : "昵称");
        etNick.setTextColor(Color.BLACK);
        etNick.setHintTextColor(Color.GRAY);
        etNick.setBackground(editBg());
        etNick.setPadding(dp(10), dp(10), dp(10), dp(10));
        etNick.setTextSize(16);
        card.addView(etNick);

        // 性别行（注册必选；登录隐藏）
        LinearLayout genderRow = new LinearLayout(this);
        genderRow.setOrientation(LinearLayout.HORIZONTAL);
        genderRow.setPadding(0, dp(10), 0, 0);
        genderRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        btnMale = genderBtn("男", genderRow);
        btnFemale = genderBtn("女", genderRow);
        resetGender(false);
        genderRow.addView(btnMale);
        genderRow.addView(btnFemale);
        card.addView(genderRow);

        etPassword = new EditText(this);
        etPassword.setSingleLine(true);
        etPassword.setHint("密码（至少 6 位）");
        etPassword.setTextColor(Color.BLACK);
        etPassword.setHintTextColor(Color.GRAY);
        etPassword.setTextSize(16);
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // 密码可见性切换：眼睛图标（睁眼=明文，闭眼=密文）
        LinearLayout pwRow = new LinearLayout(this);
        pwRow.setOrientation(LinearLayout.HORIZONTAL);
        pwRow.setGravity(Gravity.CENTER_VERTICAL);
        pwRow.setBackground(editBg());
        int pwPad = dp(10);
        pwRow.setPadding(pwPad, pwPad, pwPad, pwPad);

        etPassword.setBackground(null);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        etPassword.setLayoutParams(etLp);
        pwRow.addView(etPassword);

        final ImageView ivEye = new ImageView(this);
        ivEye.setImageResource(R.drawable.ic_eye_closed);
        ivEye.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int eyeSize = dp(28);
        LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams(eyeSize, eyeSize);
        ivEye.setLayoutParams(eyeLp);
        ivEye.setPadding(dp(6), 0, 0, 0);
        ivEye.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean visible = (etPassword.getInputType()
                        & InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0;
                if (visible) {
                    etPassword.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    ivEye.setImageResource(R.drawable.ic_eye_closed);
                } else {
                    etPassword.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    ivEye.setImageResource(R.drawable.ic_eye_open);
                }
                etPassword.setSelection(etPassword.getText().length());
            }
        });
        pwRow.addView(ivEye);

        card.addView(pwRow);

        btnSubmit = new Button(this);
        btnSubmit.setText(registerMode ? "注 册" : "登 录");
        btnSubmit.setTextSize(16);
        btnSubmit.setTextColor(Color.WHITE);
        btnSubmit.setAllCaps(false);
        btnSubmit.setBackground(btnBg(0xFF1E88E5));
        btnSubmit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                if (registerMode) doRegister();
                else doLogin();
            }
        });
        LinearLayout.LayoutParams bsp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        bsp.topMargin = dp(18);
        btnSubmit.setLayoutParams(bsp);
        card.addView(btnSubmit);

        btnSwitch = new Button(this);
        btnSwitch.setText("没有账号？去注册");
        btnSwitch.setTextSize(13);
        btnSwitch.setTextColor(Color.parseColor("#1E88E5"));
        btnSwitch.setAllCaps(false);
        btnSwitch.setBackground(btnBg(0xFFEEEEEE));
        btnSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                toggleMode();
            }
        });
        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        ssp.topMargin = dp(12);
        btnSwitch.setLayoutParams(ssp);
        card.addView(btnSwitch);

        setContentView(root);
        if (!registerMode) showGender(false);
    }

    private void toggleMode() {
        registerMode = !registerMode;
        btnSubmit.setText(registerMode ? "注 册" : "登 录");
        btnSwitch.setText(registerMode ? "已有账号？去登录" : "没有账号？去注册");
        tvStatus.setText(registerMode
            ? "注册新账号（昵称唯一，请选择性别）"
            : "登录您现有的账号");
        showGender(registerMode);
        if (registerMode) resetGender(false);
        etNick.setHint(registerMode ? "昵称（限 4 汉字以内）" : "昵称");
        etPassword.setHint(registerMode ? "密码（至少 6 位）" : "密码");
    }

    private void showGender(boolean show) {
        btnMale.setVisibility(show ? View.VISIBLE : View.GONE);
        btnFemale.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void resetGender(boolean male) {
        maleSelected = male;
        genderSelected = male;
        setGenderBg(btnMale, male);
        setGenderBg(btnFemale, !male);
    }

    private void setGenderBg(Button b, boolean active) {
        b.setBackground(active ? btnBg(0xFF1E88E5) : btnBg(0xFFECECEC));
        b.setTextColor(active ? Color.WHITE : Color.BLACK);
    }

    private Button genderBtn(final String label, final LinearLayout row) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(Color.BLACK);
        b.setAllCaps(false);
        b.setPadding(0, dp(10), 0, dp(10));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean male = "男".equals(label);
                maleSelected = male;
                genderSelected = true;
                setGenderBg(btnMale, male);
                setGenderBg(btnFemale, !male);
            }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        gp.setMargins(0, 0, dp(8), 0);
        b.setLayoutParams(gp);
        return b;
    }

    // ==================== 注册 ====================
    private void doRegister() {
        String rawNick = etNick.getText().toString().trim();
        final String password = etPassword.getText().toString();
        if (!genderSelected) {
            toast("请选择性别");
            return;
        }
        if (!isValidNick(rawNick)) {
            toast("昵称不合法：请输入 1~4 个汉字/字符");
            return;
        }
        if (badWordFilter.containsBadWord(rawNick)) {
            toast("昵称含不文明用语，请修改后重试");
            return;
        }
        final String nick = rawNick;
        if (password.length() < 6) {
            toast("密码至少 6 位");
            return;
        }
        setBusy(true);
        final SupabaseClient client = new SupabaseClient(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject args = new JSONObject();
                try { args.put("n", nick); } catch (Exception ignore) { }
                SupabaseClient.RpcResult check = client.rpcAnon("check_nickname", args);
                if (!check.ok) {
                    postResult("昵称查询失败，请重试");
                    return;
                }
                if (check.json != null && check.json.has("_rpc_result")
                        && !"true".equalsIgnoreCase(check.json.optString("_rpc_result"))) {
                    postResult("昵称「" + nick + "」已被使用，请更换一个");
                    return;
                }
                if (check.rawText != null && check.rawText.trim().contains("false")) {
                    postResult("昵称「" + nick + "」已被使用，请更换一个");
                    return;
                }
                SupabaseClient.AuthResult ar = client.signUp(
                    SupabaseClient.nickToEmail(nick), password);
                if (!ar.ok) {
                    postAuthError(ar.error);
                    return;
                }
                JSONObject pa = new JSONObject();
                try {
                    pa.put("nick", nick);
                    pa.put("g", maleSelected ? "male" : "female");
                } catch (Exception ignore) { }
                SupabaseClient.RpcResult pr = client.rpc("create_profile", pa);
                if (!pr.ok) {
                    postResult("注册建档失败：" + pr.error);
                    client.signOut();
                    return;
                }
                saveLocalProfile(nick, maleSelected ? "male" : "female", password);
                prefs().edit().putBoolean(KEY_LOGGED_IN, true).apply();
                postSuccess("注册成功，欢迎「" + nick + "」");
            }
        }).start();
    }

    // ==================== 登录 ====================
    private void doLogin() {
        final String nick = etNick.getText().toString().trim();
        final String password = etPassword.getText().toString();
        if (nick.isEmpty() || password.isEmpty()) {
            toast("请输入昵称和密码");
            return;
        }
        setBusy(true);
        final SupabaseClient client = new SupabaseClient(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                SupabaseClient.AuthResult ar = client.signIn(
                    SupabaseClient.nickToEmail(nick), password);
                if (!ar.ok) {
                    postAuthError(ar.error);
                    return;
                }
                JSONObject profile = client.readOwnProfile();
                String pn = profile != null ? profile.optString("nickname", nick) : nick;
                String pg = profile != null ? profile.optString("gender", "male") : "male";
                saveLocalProfile(pn, pg, password);
                prefs().edit().putBoolean(KEY_LOGGED_IN, true).apply();
                postSuccess("欢迎回来「" + pn + "」");
            }
        }).start();
    }

    private boolean isValidNick(String nick) {
        if (nick == null || nick.isEmpty()) return false;
        int len = nick.codePointCount(0, nick.length());
        return len >= 1 && len <= 4;
    }

    private void setBusy(boolean b) {
        busy = b;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btnSubmit.setEnabled(!busy);
                btnSubmit.setText(busy ? (registerMode ? "注册中..." : "登录中...")
                    : (registerMode ? "注 册" : "登 录"));
            }
        });
    }

    private void saveLocalProfile(String name, String gender, String password) {
        prefs().edit()
            .putString("PlayerName", name)
            .putString("PlayerGender", gender)
            .putString("PlayerPassword", password == null ? "" : password)
            .apply();
    }

    private void postResult(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                setBusy(false);
                toast(msg);
            }
        });
    }

    private void postAuthError(String error) {
        if (error != null && error.contains("429")) {
            postResult("请求过于频繁（429），请稍等几分钟再试");
        } else if (error != null && error.contains("confirm_required")) {
            postResult("需先邮箱确认（当前用测试域名无法收邮件）");
        } else {
            postResult("错误：" + error);
        }
    }

    private void postSuccess(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                setBusy(false);
                Toast.makeText(AuthActivity.this, msg, Toast.LENGTH_SHORT).show();
                startActivity(new Intent(AuthActivity.this, MenuActivity.class));
                finish();
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private GradientDrawable editBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(8));
        gd.setColor(Color.parseColor("#F2F2F2"));
        gd.setStroke(1, Color.parseColor("#DDDDDD"));
        return gd;
    }

    private GradientDrawable btnBg(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(8));
        gd.setColor(color);
        return gd;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}