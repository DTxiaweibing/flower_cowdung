// SupabaseIdentity.java
// M1 身份闭环：
//   启动时静默匿名登录 + 拉取本人 profiles 行；
//   若尚未建档 -> 复用昵称+性别弹窗 -> 调 create_profile RPC -> 持久化。
//   与 MainActivity 共享 CowDungPrefs(PlayerName/PlayerGender)，此处成功建档后本地资料即生效。
package com.example.cowdunggame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SupabaseIdentity {

    public interface Callback {
        void onDone(boolean ok, String error);
    }

    private final Activity activity;
    private final SupabaseClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public SupabaseIdentity(Activity activity) {
        this.activity = activity;
        this.client = new SupabaseClient(activity);
    }

    // 供外部复用客户端（如对局页刷新 token）
    public SupabaseClient getClient() {
        return client;
    }

    // 检查本地是否已完善"玩家身份"（昵称+性别已设置）
    // 匿名 / 未设置者不能当玩家，只能当观众或玩人机对战
    public boolean hasPlayerIdentity() {
        SharedPreferences sp = activity.getSharedPreferences("CowDungPrefs",
            Context.MODE_PRIVATE);
        String name = sp.getString("PlayerName", "");
        String gender = sp.getString("PlayerGender", "");
        return !name.isEmpty() && !gender.isEmpty();
    }

    // 尝试获得玩家身份：若已完善直接回调成功；
    // 否则弹窗提示并引导设置昵称+性别，设置成功才回调 ok
    public void requirePlayerIdentity(final Callback cb) {
        if (hasPlayerIdentity()) {
            if (cb != null) cb.onDone(true, null);
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder warn = new AlertDialog.Builder(activity);
                warn.setTitle("需要玩家身份");
                warn.setMessage("匿名只能当观众或玩人机对战。\n设置昵称和性别后才能坐下当玩家。");
                warn.setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showRegisterDialog(new OnRegisteredListener() {
                            @Override
                            public void onRegistered(boolean ok, String error) {
                                if (cb != null) cb.onDone(ok, error);
                            }
                        });
                    }
                });
                warn.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (cb != null) cb.onDone(false, "cancelled");
                    }
                });
                warn.show();
            }
        });
    }

    // 确保已登录且有服务端资料；没有则弹昵称/性别对话框建档
    public void ensureIdentity(final Callback cb) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean needSignIn = false;
                SupabaseClient.AuthResult ar = new SupabaseClient.AuthResult();
                if (!client.hasSession()) {
                    ar = client.signInAnonymously();
                    needSignIn = !ar.ok;
                    if (!ar.ok) {
                        final String err = ar.error;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(activity, "登录失败: " + err, Toast.LENGTH_LONG).show();
                                if (cb != null) cb.onDone(false, err);
                            }
                        });
                        return;
                    }
                } else {
                    ar.ok = true;
                }

                // 尝试刷新过期的 token（失败则重登）
                if (!client.ensureFreshToken()) {
                    ar = client.signInAnonymously();
                    if (!ar.ok) {
                        final String err = ar.error;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(activity, "登录失败: " + err, Toast.LENGTH_LONG).show();
                                if (cb != null) cb.onDone(false, err);
                            }
                        });
                        return;
                    }
                }

                // 已持有 userId -> 查服务端资料
                if (client.getUserId() != null) {
                    final JSONObject profile = client.readOwnProfile();
                    if (profile != null) {
                        // 已有资料：把服务端昵称/性别同步到本地
                        saveLocalProfile(profile.optString("nickname", ""),
                            profile.optString("gender", "male"));
                        main.post(new Runnable() {
                            @Override
                            public void run() { if (cb != null) cb.onDone(true, null); }
                        });
                        return;
                    }
                }

                // 未建档：主线程弹昵称+性别对话框
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        showRegisterDialog(new OnRegisteredListener() {
                            @Override
                            public void onRegistered(boolean ok, String error) {
                                if (cb != null) cb.onDone(ok, error);
                            }
                        });
                    }
                });
            }
        });
    }

    public interface OnRegisteredListener {
        void onRegistered(boolean ok, String error);
    }

    // 昵称 + 性别 建档对话框
    private void showRegisterDialog(final OnRegisteredListener listener) {
        SharedPreferences sp = activity.getSharedPreferences("CowDungPrefs",
            Context.MODE_PRIVATE);
        final String oldName = sp.getString("PlayerName", "");
        final String oldGender = sp.getString("PlayerGender", "male");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("欢迎！设置昵称和性别");
        builder.setCancelable(false);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), 0);

        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("昵称（最多12字）");
        input.setText(oldName);
        content.addView(input);

        final String[] genders = {"男", "女"};
        final String[] genderKeys = {"male", "female"};
        final boolean[] selectedGender = {genderKeys[0].equals(oldGender)};
        LinearLayout genderRow = new LinearLayout(activity);
        genderRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] genderBtns = new Button[genders.length];
        for (int i = 0; i < genders.length; i++) {
            final int idx = i;
            Button gb = new Button(activity);
            gb.setText(genders[i]);
            gb.setTextSize(14);
            gb.setTextColor(Color.WHITE);
            gb.setAllCaps(false);
            gb.setBackground(roundedStrokeBg(genderKeys[idx].equals(oldGender) ? 0xFF1E88E5 : 0xFF3A3A3A));
            LinearLayout.LayoutParams gParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
            gParams.setMargins(dp(4), dp(8), dp(4), 0);
            gb.setLayoutParams(gParams);
            genderBtns[idx] = gb;
            gb.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    selectedGender[0] = genderKeys[idx].equals("male");
                    for (int j = 0; j < genderBtns.length; j++) {
                        boolean active = genderKeys[j].equals(genderKeys[idx]);
                        genderBtns[j].setBackground(roundedStrokeBg(
                            active ? 0xFF1E88E5 : 0xFF3A3A3A));
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
                if (name.length() > 12) name = name.substring(0, 12);
                if (name.isEmpty()) name = "玩家";
                final String nickname = name;
                final String gender = selectedGender[0] ? "male" : "female";

                // 离线保存一份先
                saveLocalProfile(nickname, gender);

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // 调 create_profile RPC：重名会自动加后缀
                        JSONObject args = new JSONObject();
                        try {
                            args.put("nick", nickname);
                            args.put("g", gender);
                        } catch (Exception ignore) { }
                        SupabaseClient.RpcResult rr = client.rpc("create_profile", args);
                        final boolean ok = rr.ok;
                        final String err = rr.error;
                        if (ok && client.getUserId() != null) {
                            // 服务端返回 uuid，昵称可能被自动改名；回查资料以同步服务端昵称/性别
                            JSONObject profile = client.readOwnProfile();
                            if (profile != null) {
                                String serverNick = profile.optString("nickname", nickname);
                                String serverGender = profile.optString("gender", gender);
                                saveLocalProfile(serverNick, serverGender);
                            }
                        }
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (ok) {
                                    Toast.makeText(activity, "注册成功", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(activity, "注册失败: " + err, Toast.LENGTH_LONG).show();
                                }
                                if (listener != null) listener.onRegistered(ok, err);
                            }
                        });
                    }
                });
            }
        });
        builder.show();
    }

    // 本地资料持久化（与 MainActivity 共用键）
    private void saveLocalProfile(String name, String gender) {
        SharedPreferences.Editor e = activity.getSharedPreferences("CowDungPrefs",
            Context.MODE_PRIVATE).edit();
        e.putString("PlayerName", name);
        e.putString("PlayerGender", gender);
        e.apply();
    }

    private android.graphics.drawable.GradientDrawable roundedStrokeBg(int fillColor) {
        android.graphics.drawable.GradientDrawable gd =
            new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(8));
        gd.setColor(fillColor);
        gd.setStroke(1, 0xFF666666);
        return gd;
    }

    private int dp(float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}