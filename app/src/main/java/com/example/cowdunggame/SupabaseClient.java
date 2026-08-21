// SupabaseClient.java (app-new 精简版)
// 极简纯 Java Supabase REST 客户端（HttpURLConnection）。
//   仅保留：匿名/账号注册、登录、注销、会话持久化、通用 RPC、读取本人资料。
//   在线对局/大厅相关的全部方法不再引入，按需重新实现。
package com.example.cowdunggame;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SupabaseClient {

    public static final String PROJECT_URL =
        "https://uihalfuswgilzzhzmgpv.supabase.co";
    public static final String ANON_KEY =
        "sb_publishable_HaCpd4tIhhaunf8S-b7FoQ_6uBGzbLM";

    private static final String PREFS = "CowDungPrefs";
    private static final String KEY_TOKEN   = "SupabaseToken";
    private static final String KEY_USER   = "SupabaseUserId";
    private static final String KEY_EXP    = "SupabaseTokenExp";
    private static final String KEY_REFRESH = "SupabaseRefreshToken";

    private final Context context;
    private String accessToken;
    private String refreshToken;
    private long accessTokenExp;
    private String userId;

    public SupabaseClient(Context context) {
        this.context = context.getApplicationContext();
        loadSession();
    }

    private void loadSession() {
        SharedPreferences sp = prefs();
        accessToken = sp.getString(KEY_TOKEN, null);
        refreshToken = sp.getString(KEY_REFRESH, null);
        accessTokenExp = sp.getLong(KEY_EXP, 0);
        userId = sp.getString(KEY_USER, null);
    }

    private void saveSession() {
        prefs().edit()
            .putString(KEY_TOKEN, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXP, accessTokenExp)
            .putString(KEY_USER, userId)
            .apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasSession() {
        return accessToken != null && userId != null;
    }

    public String getUserId() {
        return userId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public static class AuthResult {
        public boolean ok;
        public String error;
        public JSONObject session;
    }

    // 昵称 -> 确定性 email（同名必同邮箱）
    public static String nickToEmail(String nick) {
        StringBuilder sb = new StringBuilder("u");
        byte[] b = nick.getBytes(StandardCharsets.UTF_8);
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString() + "@cowdung.com";
    }

    public AuthResult signUp(String email, String password) {
        AuthResult r = new AuthResult();
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            JSONObject resp = postJson(PROJECT_URL + "/auth/v1/signup", body, null);
            if (resp == null || resp.has("error")) {
                r.ok = false;
                r.error = resp == null ? "no_response"
                    : resp.optString("error_description",
                        resp.optString("msg", resp.optString("error", "unknown")));
                return r;
            }
            r.ok = true;
            r.session = resp;
            JSONObject user = resp.optJSONObject("user");
            if (user != null) userId = user.optString("id", null);
            if (resp.optString("access_token", "").isEmpty()) {
                saveSession();
                r.error = "confirm_required";
                return r;
            }
            captureSession(resp);
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    public AuthResult signIn(String email, String password) {
        AuthResult r = new AuthResult();
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            JSONObject resp = postJson(
                PROJECT_URL + "/auth/v1/token?grant_type=password", body, null);
            if (resp == null || resp.has("error")) {
                r.ok = false;
                r.error = resp == null ? "no_response"
                    : resp.optString("error_description",
                        resp.optString("msg", resp.optString("error", "unknown")));
                return r;
            }
            r.ok = true;
            r.session = resp;
            captureSession(resp);
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    public void signOut() {
        try {
            if (accessToken != null) {
                postJson(PROJECT_URL + "/auth/v1/logout", new JSONObject(), accessToken);
            }
        } catch (Exception ignore) { }
        accessToken = null;
        refreshToken = null;
        accessTokenExp = 0;
        userId = null;
        saveSession();
    }

    private AuthResult refreshSession() {
        AuthResult r = new AuthResult();
        if (refreshToken == null) {
            r.ok = false;
            r.error = "no_refresh_token";
            return r;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refreshToken);
            JSONObject resp = postJson(
                PROJECT_URL + "/auth/v1/token?grant_type=refresh_token", body, null);
            if (resp == null || resp.has("error")) {
                r.ok = false;
                r.error = resp == null ? "no_response" : "refresh_failed";
                return r;
            }
            r.ok = true;
            r.session = resp;
            captureSession(resp);
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    private void captureSession(JSONObject s) throws Exception {
        accessToken = s.optString("access_token", null);
        refreshToken = s.optString("refresh_token", refreshToken);
        long exp = s.optLong("expires_in", 3600);
        accessTokenExp = System.currentTimeMillis() / 1000 + exp;
        JSONObject user = s.optJSONObject("user");
        if (user != null) userId = user.optString("id", null);
        saveSession();
    }

    public boolean ensureFreshToken() {
        if (accessToken == null) return false;
        long now = System.currentTimeMillis() / 1000;
        if (accessTokenExp - now < 30) {
            return refreshSession().ok;
        }
        return true;
    }

    public static class RpcResult {
        public boolean ok;
        public String error;
        public JSONObject json;
        public JSONArray array;
        public String rawText;
    }

    public RpcResult rpc(String name, JSONObject args) {
        RpcResult r = new RpcResult();
        try {
            if (!ensureFreshToken() || accessToken == null) {
                r.ok = false;
                r.error = "not_authenticated";
                return r;
            }
            return doRpc(name, args, accessToken);
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    public RpcResult rpcAnon(String name, JSONObject args) {
        return doRpc(name, args, null);
    }

    private RpcResult doRpc(String name, JSONObject args, String token) {
        RpcResult r = new RpcResult();
        try {
            HttpURLConnection conn = open(PROJECT_URL + "/rest/v1/rpc/" + name);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", ANON_KEY);
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setRequestProperty("Prefer", "return=representation");
            conn.setDoOutput(true);
            byte[] body = (args == null ? "{}" : args.toString())
                .getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String text = read(conn);
            r.rawText = text;
            if (code >= 200 && code < 300) {
                r.ok = true;
                try {
                    if (text.startsWith("[")) {
                        r.array = new JSONArray(text);
                    } else {
                        r.json = new JSONObject(text);
                    }
                } catch (Exception ignore) { }
            } else {
                r.ok = false;
                r.error = extractError(text, code);
            }
            conn.disconnect();
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    public JSONObject readOwnProfile() {
        try {
            if (!ensureFreshToken() || accessToken == null || userId == null) return null;
            String urlStr = PROJECT_URL + "/rest/v1/profiles?id=eq." + userId;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            String text = read(conn);
            conn.disconnect();
            if (code >= 200 && code < 300 && text.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(text);
                return arr.length() > 0 ? arr.getJSONObject(0) : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // 人机大厅（PvE）桌状态：读表 / 入座 / 离座 / 观战 / 开局上报
    // 表与 RPC 见 supabase/pve_tables.sql（预置 20 桌，APP 不建桌）
    // ============================================================

    // 拉取全部 PvE 桌状态（按桌号升序）：
    //   {id, num, status, player_id, watcher_count, player:{gender, nickname}(若有人坐)}
    public JSONArray fetchPveTables() {
        try {
            if (!ensureFreshToken() || accessToken == null) return null;
            String urlStr = PROJECT_URL + "/rest/v1/pve_tables?select=*,player:profiles!pve_tables_player_id_fkey(gender,nickname)&order=num";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            String text = read(conn);
            conn.disconnect();
            if (code >= 200 && code < 300 && text.trim().startsWith("[")) {
                return new JSONArray(text);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 拉取单桌状态（分钟重放用）：返回含 game_state 与 player 资料的整行
    public JSONObject fetchPveTable(String tid) {
        try {
            if (!ensureFreshToken() || accessToken == null) return null;
            String urlStr = PROJECT_URL + "/rest/v1/pve_tables?select=*,player:profiles!pve_tables_player_id_fkey(gender,nickname)&id=eq." + tid;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            String text = read(conn);
            conn.disconnect();
            if (code >= 200 && code < 300 && text.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(text);
                return arr.length() > 0 ? arr.getJSONObject(0) : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 玩家本人整包上报棋局状态（每步落子后调用）
    public boolean pveReportState(String tid, JSONObject state) {
        JSONObject args = new JSONObject();
        try {
            args.put("tid", tid);
            args.put("state", state);
        } catch (Exception ignore) { }
        return rpc("pve_report_state", args).ok;
    }

    public boolean pveSit(String tid) {
        return rpc("pve_sit", arg("tid", tid)).ok;
    }

    public boolean pveLeave(String tid) {
        return rpc("pve_leave", arg("tid", tid)).ok;
    }

    public boolean pveWatch(String tid) {
        return rpc("pve_watch", arg("tid", tid)).ok;
    }

    public boolean pveUnwatch(String tid) {
        return rpc("pve_unwatch", arg("tid", tid)).ok;
    }

    public boolean pveStart(String tid) {
        return rpc("pve_start", arg("tid", tid)).ok;
    }

    public boolean pveEnd(String tid) {
        return rpc("pve_end", arg("tid", tid)).ok;
    }

    public boolean pveHeartbeat(String tid) {
        return rpc("pve_heartbeat", arg("tid", tid)).ok;
    }

    // 玩家对局中退出 = 判负：写 finished/computer 到 game_state，并原子释放座位
    public boolean pveForfeit(String tid, JSONObject state) {
        JSONObject args = new JSONObject();
        try {
            args.put("tid", tid);
            args.put("state", state != null ? state : new JSONObject());
        } catch (Exception ignore) { }
        return rpc("pve_forfeit", args).ok;
    }

    // ============================================================
    // 人人大厅（PvP）桌状态：读表 / 入座 / 离座 / 观战 / 开局上报
    // 表与 RPC 见 supabase/pvp_tables.sql（预置 20 桌，双玩家位）
    // ============================================================

    // 拉取全部 PvP 桌状态（按桌号升序）：
    //   {id, num, status, player_a_id, player_b_id, current_turn_id, ready_a, ready_b,
    //    game_state, watcher_count, player_a:{gender,nickname}(若 A 坐), player_b:{...}}
    public JSONArray fetchPvpTables() {
        try {
            if (!ensureFreshToken() || accessToken == null) return null;
            String urlStr = PROJECT_URL
                + "/rest/v1/pvp_tables?select=*,player_a:profiles!pvp_tables_player_a_id_fkey(gender,nickname),"
                + "player_b:profiles!pvp_tables_player_b_id_fkey(gender,nickname)&order=num";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            String text = read(conn);
            conn.disconnect();
            if (code >= 200 && code < 300 && text.trim().startsWith("[")) {
                return new JSONArray(text);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 拉取单桌状态（轮询/重放用）：返回含 game_state 与双玩家资料的整行
    public JSONObject fetchPvpTable(String tid) {
        try {
            if (!ensureFreshToken() || accessToken == null) return null;
            String urlStr = PROJECT_URL
                + "/rest/v1/pvp_tables?select=*,player_a:profiles!pvp_tables_player_a_id_fkey(gender,nickname),"
                + "player_b:profiles!pvp_tables_player_b_id_fkey(gender,nickname)&id=eq." + tid;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            String text = read(conn);
            conn.disconnect();
            if (code >= 200 && code < 300 && text.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(text);
                return arr.length() > 0 ? arr.getJSONObject(0) : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 玩家本人整包上报棋局状态（A/B 双人：落子在本地判定后整包写入，轮询同步）
    public boolean pvpReportState(String tid, JSONObject state) {
        JSONObject args = new JSONObject();
        try {
            args.put("tid", tid);
            args.put("state", state);
        } catch (Exception ignore) { }
        return rpc("pvp_report_state", args).ok;
    }

    // 坐下当玩家：优先坐 A 位（左座 / 先手）；若 A 已有人则坐 B 位（右座 / 后手）
    public boolean pvpSit(String tid) {
        JSONObject t = fetchPvpTable(tid);
        boolean aFree = t == null || t.isNull("player_a_id");
        JSONObject args = new JSONObject();
        try {
            args.put("tid", tid);
        } catch (Exception ignore) { }
        return rpc(aFree ? "pvp_sit_a" : "pvp_sit_b", args).ok;
    }

    public boolean pvpLeave(String tid) {
        return rpc("pvp_leave", arg("tid", tid)).ok;
    }

    public boolean pvpWatch(String tid) {
        return rpc("pvp_watch", arg("tid", tid)).ok;
    }

    public boolean pvpUnwatch(String tid) {
        return rpc("pvp_unwatch", arg("tid", tid)).ok;
    }

    // 按"准备好了"：双方就绪且都有人 -> 服务端置 playing 并初始化 game_state（A 先手）
    public boolean pvpReady(String tid) {
        return rpc("pvp_ready", arg("tid", tid)).ok;
    }

    public boolean pvpEnd(String tid) {
        return rpc("pvp_end", arg("tid", tid)).ok;
    }

    public boolean pvpHeartbeat(String tid) {
        return rpc("pvp_heartbeat", arg("tid", tid)).ok;
    }

    // ============================================================
    // 私密房间：读房 / 建房 / 入座 / 准备 / 上报 / 退出 / 观战 / 心跳
    // 表与 RPC 见 supabase/private_rooms.sql（room_code 4 位主键，镜像 pvp）
    // ============================================================

    // 创建房间：服务端返回 4 位房间号（char(4) 标量）
    public String roomCreate() {
        RpcResult res = rpc("room_create", new JSONObject());
        if (!res.ok) return null;
        String v = null;
        if (res.rawText != null && !res.rawText.trim().isEmpty()) {
            v = res.rawText.trim();
            if (v.startsWith("[")) {
                try { v = new JSONArray(v).optString(0, null); } catch (Exception ignore) { }
            }
        }
        if (v == null && res.json != null) {
            v = res.json.optString("room_create", null);
            if (v == null || v.isEmpty()) v = res.json.toString();
        }
        if (v == null || v.isEmpty()) return null;
        v = v.replace("\"", "").trim();
        return (v.length() == 4 && v.chars().allMatch(Character::isDigit)) ? v : null;
    }

    // 用房间号进入：不存在/已结束报错，成功返回 true
    public boolean roomJoin(String code) {
        return rpc("room_join", arg("code", code)).ok;
    }

    // 拉取房间状态（整行）：含 game_state 与双玩家资料
    public JSONObject fetchRoom(String code) {
        try {
            if (!ensureFreshToken() || accessToken == null) return null;
            String urlStr = PROJECT_URL
                + "/rest/v1/private_rooms?select=*,player_a:profiles!private_rooms_player_a_id_fkey(gender,nickname),"
                + "player_b:profiles!private_rooms_player_b_id_fkey(gender,nickname)&room_code=eq." + code;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code2 = conn.getResponseCode();
            String text = read(conn);
            conn.disconnect();
            if (code2 >= 200 && code2 < 300 && text.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(text);
                return arr.length() > 0 ? arr.getJSONObject(0) : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 入座：A 空则坐 A（先手），否则坐 B；返回坐席 'a'/'b'/null
    public String roomSit(String code) {
        RpcResult res = rpc("room_sit", arg("code", code));
        if (!res.ok) return null;
        String v = null;
        if (res.json != null) {
            v = res.json.optString("room_sit", null);
            if (v == null || "null".equals(v)) v = null;
        }
        if (v == null && res.rawText != null && !res.rawText.trim().isEmpty()) {
            v = res.rawText.replace("\"", "").trim();
        }
        if (v == null || v.trim().isEmpty()) return null;
        v = v.trim();
        return ("a".equals(v) || "b".equals(v)) ? v : null;
    }

    // 玩家本人整包上报棋局状态
    public boolean roomReportState(String code, JSONObject state) {
        JSONObject args = new JSONObject();
        try {
            args.put("code", code);
            args.put("state", state);
        } catch (Exception ignore) { }
        return rpc("room_report_state", args).ok;
    }

    public boolean roomReady(String code) {
        return rpc("room_ready", arg("code", code)).ok;
    }

    public boolean roomEnd(String code) {
        return rpc("room_end", arg("code", code)).ok;
    }

    public boolean roomLeave(String code) {
        return rpc("room_leave", arg("code", code)).ok;
    }

    public boolean roomWatch(String code) {
        return rpc("room_watch", arg("code", code)).ok;
    }

    public boolean roomUnwatch(String code) {
        return rpc("room_unwatch", arg("code", code)).ok;
    }

    public boolean roomHeartbeat(String code) {
        return rpc("room_heartbeat", arg("code", code)).ok;
    }

    private JSONObject arg(String key, String value) {
        JSONObject o = new JSONObject();
        try {
            o.put(key, value);
        } catch (Exception ignore) { }
        return o;
    }

    private JSONObject postJson(String urlStr, JSONObject body, String token)
        throws Exception {
        HttpURLConnection conn = open(urlStr);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("apikey", ANON_KEY);
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        } else if (accessToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        String text = read(conn);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            JSONObject err = new JSONObject();
            err.put("error", code);
            err.put("error_description", text);
            return err;
        }
        try {
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private HttpURLConnection open(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        return conn;
    }

    private String read(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getErrorStream();
        if (is == null) is = conn.getInputStream();
        if (is == null) return "";
        BufferedReader br = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private String extractError(String text, int code) {
        if (text == null || text.trim().isEmpty()) return "http_" + code;
        try {
            JSONArray arr = new JSONArray(text);
            if (arr.length() > 0) {
                JSONObject o = arr.optJSONObject(0);
                if (o != null) {
                    String m = o.optString("message", null);
                    if (m != null) return m;
                }
            }
            return text;
        } catch (Exception e) {
            String t = text.trim();
            if (t.startsWith("\"") && t.endsWith("\"")) {
                return t.substring(1, t.length() - 1);
            }
            return t;
        }
    }
}