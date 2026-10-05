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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SupabaseClient {

    public static final String PROJECT_URL =
        "https://uihalfuswgilzzhzmgpv.supabase.co";
    public static final String ANON_KEY =
        "sb_publishable_HaCpd4tIhhaunf8S-b7FoQ_6uBGzbLM";

    // 备用通道：Cloudflare 反代（Pages Functions，见 supabase/cloudflare-proxy/）。
    // 这条线路按 DNS 污染 + TLS SNI 双重封锁 *.supabase.co 和 *.workers.dev，
    // 直连必然超时；*.pages.dev 的 DNS 是干净的，所以反代走 Pages。
    // 留空则只用直连；填了之后两条通道并行探测，谁先通用谁。
    public static final String PROXY_URL = "https://cowdung-proxy.pages.dev";

    private static final int PROBE_TIMEOUT = 6000;
    private static volatile String baseUrl;
    private static final Object BASE_LOCK = new Object();

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
            JSONObject resp = postJson(base() + "/auth/v1/signup", body, null);
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
                base() + "/auth/v1/token?grant_type=password", body, null);
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
                postJson(base() + "/auth/v1/logout", new JSONObject(), accessToken);
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
                base() + "/auth/v1/token?grant_type=refresh_token", body, null);
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

    // 刷新锁：轮询 / 心跳 / 聊天 / 资料卡 会从多个线程同时调 ensureFreshToken。
    // Supabase 的 refresh_token 是一次性轮换的，并发刷新时后到的请求会拿着已失效的
    // 旧 refresh_token 拿到 400，导致 ensureFreshToken 误报 false、请求被静默丢弃。
    private final Object tokenLock = new Object();

    public boolean ensureFreshToken() {
        if (accessToken == null) return false;
        long now = System.currentTimeMillis() / 1000;
        if (accessTokenExp - now >= 30) return true;
        synchronized (tokenLock) {
            // 等锁期间可能已被别的线程刷新过，先复判再决定要不要真的发刷新请求
            if (accessToken == null) return false;
            if (accessTokenExp - System.currentTimeMillis() / 1000 >= 30) return true;
            return refreshSession().ok;
        }
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
            HttpURLConnection conn = open(base() + "/rest/v1/rpc/" + name);
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
            String urlStr = base() + "/rest/v1/profiles?id=eq." + userId;
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
            String urlStr = base() + "/rest/v1/pve_tables?select=*,player:profiles!pve_tables_player_id_fkey(gender,nickname)&order=num";
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
            String urlStr = base() + "/rest/v1/pve_tables?select=*,player:profiles!pve_tables_player_id_fkey(gender,nickname)&id=eq." + tid;
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
    //   {id, num, status, player_a_id, player_b_id, watcher_count,
    //    player_a:{gender,nickname}(若 A 坐), player_b:{...}}
    // 只列大厅卡面真正读的列。原来用 select=* 把 game_state（整局棋谱
    // + flowers）也拖回来，20 桌每 3 秒一次纯属浪费 —— isPvpPlaying 读的是
    // status 列，hasPlayerA/B 读的是 player_a_id/player_b_id，都不看 game_state。
    public JSONArray fetchPvpTables() {
        try {
            if (!ensureFreshToken() || accessToken == null) return null;
            String urlStr = base()
                + "/rest/v1/pvp_tables?select=id,num,status,player_a_id,player_b_id,watcher_count,"
                + "player_a:profiles!pvp_tables_player_a_id_fkey(gender,nickname,id),"
                + "player_b:profiles!pvp_tables_player_b_id_fkey(gender,nickname,id)&order=num";
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
            String urlStr = base()
                + "/rest/v1/pvp_tables?select=*,player_a:profiles!pvp_tables_player_a_id_fkey(gender,nickname,id),"
                + "player_b:profiles!pvp_tables_player_b_id_fkey(gender,nickname,id)&id=eq." + tid;
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

    // 单个观众的资料（列表行 + 详情弹窗共用）。
    // userId 是踢人操作的唯一依据：没有它就无法知道「点的是哪一行」。
    public static class WatcherInfo {
        public String userId = "";
        public String nickname = "";
        public String gender = "";
        public int score;
        public int wins;
        public int losses;
        public int totalGames;
        public int rank;   // 0 = 暂无排名
    }

    // 取某桌「仅观众」的完整资料列表（绝不返回玩家）。
    //   mode="pvp" -> pvp_watchers(table_id)
    //   mode="pve" -> pve_watchers(table_id)
    //   mode="room"-> private_room_watchers(room_code)
    // watchers 表本身只含观众，玩家不会进入结果。
    // 失败（无网/未登录/RLS）返回空列表。
    public List<WatcherInfo> fetchWatcherProfiles(String mode, String idOrCode) {
        List<WatcherInfo> result = new ArrayList<>();
        if (idOrCode == null || idOrCode.isEmpty()) return result;
        if (!ensureFreshToken() || accessToken == null) return result;
        try {
            String table;
            String filter;
            if ("room".equals(mode)) {
                // 私房观战关系在 private_room_watchers（room_watch/room_unwatch 写的表）。
                // 早期这里错读老表 room_members，导致私房的观众列表和真实观众对不上。
                table = "private_room_watchers";
                filter = "room_code=eq." + idOrCode;
            } else if ("pve".equals(mode)) {
                table = "pve_watchers";
                filter = "table_id=eq." + idOrCode;
            } else {
                table = "pvp_watchers";
                filter = "table_id=eq." + idOrCode;
            }
            JSONArray watchers = getArray(base() + "/rest/v1/" + table
                    + "?select=user_id&" + filter);
            if (watchers == null || watchers.length() == 0) return result;
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < watchers.length(); i++) {
                JSONObject o = watchers.optJSONObject(i);
                if (o == null) continue;
                String uid = o.optString("user_id", null);
                if (uid != null && !uid.isEmpty()) ids.add(uid);
            }
            if (ids.isEmpty()) return result;
            StringBuilder inIds = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) inIds.append(",");
                inIds.append(ids.get(i));
            }
            JSONArray profs = getArray(base() + "/rest/v1/profiles"
                    + "?select=id,nickname,gender,score,wins,losses,total_games"
                    + "&id=in.(" + inIds + ")");
            if (profs == null) return result;
            // PostgREST 的 in.() 返回顺序不保证，必须按 id 关联，不能按下标对齐
            java.util.Map<String, WatcherInfo> byId = new java.util.HashMap<>();
            for (int i = 0; i < profs.length(); i++) {
                JSONObject o = profs.optJSONObject(i);
                if (o == null) continue;
                String pid = o.optString("id", "");
                if (pid.isEmpty()) continue;
                WatcherInfo w = new WatcherInfo();
                w.userId = pid;
                w.nickname = o.optString("nickname", "").trim();
                w.gender = o.optString("gender", "");
                w.score = o.optInt("score", 0);
                w.wins = o.optInt("wins", 0);
                w.losses = o.optInt("losses", 0);
                w.totalGames = o.optInt("total_games", 0);
                byId.put(pid, w);
            }
            // 保持 watchers 表的顺序输出
            for (String uid : ids) {
                WatcherInfo w = byId.get(uid);
                if (w != null) result.add(w);
            }
        } catch (Exception ignore) {
        }
        return result;
    }

    // 观众被踢 + 临时禁入。仅本桌 A/B 玩家可调用（服务端鉴权，UI 隐藏只是体验）。
    // banMinutes = 0 表示只踢出不禁入。
    // 返回 RpcResult：ok=false 时 error 里带 NOT_YOUR_TABLE / NOT_A_WATCHER 等异常码。
    public RpcResult pvpKickWatcher(String tid, String targetUid, int banMinutes) {
        JSONObject args = new JSONObject();
        try {
            args.put("tid", tid);
            args.put("target", targetUid);
            args.put("ban_minutes", banMinutes);
        } catch (Exception ignore) { }
        return rpc("pvp_kick_watcher", args);
    }

    public RpcResult roomKickWatcher(String code, String targetUid, int banMinutes) {
        JSONObject args = new JSONObject();
        try {
            args.put("code", code);
            args.put("target", targetUid);
            args.put("ban_minutes", banMinutes);
        } catch (Exception ignore) { }
        return rpc("room_kick_watcher", args);
    }

    // 观众自查：我在本桌还剩多少秒禁入。
    //   > 0 = 被踢了且仍在禁入期 -> 客户端应提示并退回大厅
    //   0   = 没有禁入（含网络失败，无法区分时按「没被踢」处理，避免误退）
    public int fetchMyBanSeconds(String mode, String idOrCode) {
        if (idOrCode == null || idOrCode.isEmpty()) return 0;
        RpcResult r;
        if ("room".equals(mode)) {
            r = rpc("room_my_ban_seconds", arg("code", idOrCode));
        } else {
            r = rpc("pvp_my_ban_seconds", arg("tid", idOrCode));
        }
        if (r == null || !r.ok) return 0;
        int seconds = 0;
        try {
            if (r.json != null) {
                seconds = r.json.optInt("room_my_ban_seconds",
                        r.json.optInt("pvp_my_ban_seconds", 0));
            }
            if (seconds == 0 && r.rawText != null && !r.rawText.trim().isEmpty()) {
                String t = r.rawText.trim();
                if (t.startsWith("[")) {
                    seconds = new JSONArray(t).optInt(0, 0);
                } else {
                    seconds = Integer.parseInt(t.replace("\"", ""));
                }
            }
        } catch (Exception ignore) { }
        return Math.max(0, seconds);
    }

    private JSONArray getArray(String urlStr) {
        try {
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
        } catch (Exception ignore) {
        }
        return null;
    }

    // ===== 聊天消息（人机/人人/私密房间共用，按 scope 隔离）=====
    // scope 形如 'pve:3' / 'pvp:3' / 'room:1234'，带模式前缀避免跨模式撞桌号。
    // 桌内最后一名玩家离席时，chat_messages 里该 scope 的行由服务端触发器整桌删除。

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // 发送一条聊天。返回是否成功写入。
    public boolean sendChat(String scope, String senderId, String senderName, String message) {
        if (scope == null || message == null || message.isEmpty()) return false;
        if (!ensureFreshToken() || accessToken == null) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("table_id", scope);
            if (senderId != null) body.put("sender_id", senderId);
            if (senderName != null) body.put("sender_name", senderName);
            body.put("message", message);
            JSONObject res = postJson(base() + "/rest/v1/chat_messages", body, accessToken);
            return res != null && !res.has("error");
        } catch (Exception e) {
            return false;
        }
    }

    // 拉取某桌 id > lastId 的新消息（升序），用于轮询增量。
    public JSONArray fetchChatAfter(String scope, long lastId) {
        if (scope == null) return null;
        if (!ensureFreshToken() || accessToken == null) return null;
        try {
            String url = base() + "/rest/v1/chat_messages"
                    + "?select=id,sender_id,sender_name,message,created_at"
                    + "&table_id=eq." + enc(scope)
                    + "&id=gt." + lastId
                    + "&order=id.asc&limit=200";
            return getArray(url);
        } catch (Exception e) {
            return null;
        }
    }

    // 拉取某桌最近 limit 条历史（降序，最新在前），聊天开启时初始化用。
    // 清场后本桌必然为空，所以正常情况下这里拿不到任何上一局的消息。
    public JSONArray fetchChatHistory(String scope, int limit) {
        if (scope == null) return null;
        if (!ensureFreshToken() || accessToken == null) return null;
        try {
            String url = base() + "/rest/v1/chat_messages"
                    + "?select=id,sender_id,sender_name,message,created_at"
                    + "&table_id=eq." + enc(scope)
                    + "&order=id.desc&limit=" + limit;
            return getArray(url);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 积分 / 排名 =====

    // 人人/私密房间结算：胜+5/负-1（lobby），胜+10/负-2（private）。服务端幂等。
    public boolean finishGame(String tableId, String roomCode, String roomType,
                              String winnerId, String loserId) {
        if (winnerId == null || loserId == null) return false;
        JSONObject args = new JSONObject();
        try {
            if (tableId != null) args.put("in_table_id", tableId);
            if (roomCode != null) args.put("in_room_code", roomCode);
            args.put("in_room_type", roomType);
            args.put("in_winner_id", winnerId);
            args.put("in_loser_id", loserId);
        } catch (Exception ignore) { }
        RpcResult r = rpc("finish_game", args);
        return r != null && r.ok;
    }

    // 人机结算：赢 true -> +1 胜场；false -> 仅 total_games+1（不扣分也不加分）
    public boolean pveFinish(String playerId, boolean won) {
        if (playerId == null) return false;
        JSONObject args = new JSONObject();
        try {
            args.put("in_player_id", playerId);
            args.put("in_won", won);
        } catch (Exception ignore) { }
        RpcResult r = rpc("pve_finish", args);
        return r != null && r.ok;
    }

    // 倒计时归零时戳服务端一下，请它检查这局该不该判负。
    // 客户端不做判定决策：是否判负由服务端按 deadline / current_turn_id /
    // 对手心跳决定，条件不成立时服务端什么也不写。返回 true 仅表示
    // 「服务端确认已判负」，false 表示还没到期或已被抢先处理。
    //
    // 走 rpcAnon：这个函数只读地确认到期，真正写入的是 security definer 的
    // reap_turn_timeouts，所以不需要用户 token 也安全 —— 顺带避免 token 过期时
    // 漏掉判负（那会让本机一直停在 0:00 等 cron 兜底）。
    public boolean requestTurnTimeoutCheck(String tableId, String roomCode) {
        JSONObject args = new JSONObject();
        try {
            if (tableId != null) args.put("p_table_id", tableId);
            if (roomCode != null) args.put("p_room_code", roomCode);
        } catch (Exception ignore) { }
        RpcResult r = rpcAnon("request_turn_timeout_check", args);
        if (r == null || !r.ok) return false;
        try {
            return "true".equalsIgnoreCase((r.rawText == null ? "" : r.rawText.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    // 回合剩余秒数：服务端按 turn_deadline_at 算好再返回。
    // 客户端只用来显示，不参与判负 —— 判负由服务端 reap_turn_timeouts 执行。
    // 返回 -1 表示取不到（RPC 失败 / 无行动方 / 未开局），调用方保留原值。
    public int turnSecsLeft(String tableId, String roomCode) {
        JSONObject args = new JSONObject();
        try {
            if (tableId != null) args.put("p_table_id", tableId);
            if (roomCode != null) args.put("p_room_code", roomCode);
        } catch (Exception ignore) { }
        RpcResult r = rpc("turn_secs_left", args);
        // 标量返回：PostgREST 对返回 int 的函数会回一个裸数字（如 42 / null）
        if (r == null || !r.ok) return -1;
        try {
            String t = r.rawText == null ? "" : r.rawText.trim();
            if (t.isEmpty() || "null".equals(t)) return -1;
            return (int) Math.ceil(Double.parseDouble(t));
        } catch (Exception e) {
            return -1;
        }
    }

    // 排行榜 Top N（score desc, wins desc, score_reached_at asc）
    public JSONArray getRanking(int limit) {
        JSONObject args = new JSONObject();
        try { args.put("limit_n", limit); } catch (Exception ignore) { }
        RpcResult r = rpc("get_ranking", args);
        if (r != null && r.ok && r.array != null) return r.array;
        return null;
    }

    // 单人真实排名（rank/score/wins/losses/total_games）
    public JSONObject getUserRank(String userId) {
        if (userId == null) return null;
        JSONObject args = new JSONObject();
        try { args.put("in_user_id", userId); } catch (Exception ignore) { }
        RpcResult r = rpc("get_user_rank", args);
        if (r != null && r.ok && r.array != null && r.array.length() > 0) {
            return r.array.optJSONObject(0);
        }
        return null;
    }

    // 拉取单个玩家资料（用于资料卡）
    public JSONObject getProfile(String userId) {
        if (userId == null) return null;
        if (!ensureFreshToken() || accessToken == null) return null;
        try {
            String url = base() + "/rest/v1/profiles"
                    + "?select=id,nickname,gender,score,wins,losses,total_games"
                    + "&id=eq." + userId + "&limit=1";
            JSONArray arr = getArray(url);
            if (arr != null && arr.length() > 0) return arr.optJSONObject(0);
        } catch (Exception e) {
            // ignore
        }
        return null;
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
            String urlStr = base()
                + "/rest/v1/private_rooms?select=*,player_a:profiles!private_rooms_player_a_id_fkey(gender,nickname,id),"
                + "player_b:profiles!private_rooms_player_b_id_fkey(gender,nickname,id)&room_code=eq." + code;
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

    // 当前生效的 base：直连与反代并行探测，先返回者胜出，之后整个进程复用。
    private String base() {
        String b = baseUrl;
        if (b != null) return b;
        synchronized (BASE_LOCK) {
            if (baseUrl != null) return baseUrl;
            baseUrl = pickBase();
            return baseUrl;
        }
    }

    private static List<String> candidates() {
        List<String> list = new ArrayList<>();
        list.add(PROJECT_URL);
        if (PROXY_URL != null && !PROXY_URL.trim().isEmpty()) {
            String p = PROXY_URL.trim();
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            if (!p.equals(PROJECT_URL)) list.add(p);
        }
        return list;
    }

    // 并行探测：两个候选同时发请求，谁先拿到 HTTP 响应就用谁。
    // 串行探测会让被封锁的通道白等满 connect timeout，所以必须并行。
    private String pickBase() {
        final List<String> cands = candidates();
        if (cands.size() == 1) return cands.get(0);
        ExecutorService pool = Executors.newFixedThreadPool(cands.size());
        CompletionService<String> cs = new ExecutorCompletionService<>(pool);
        for (final String c : cands) {
            cs.submit(new Callable<String>() {
                @Override public String call() {
                    return reachable(c) ? c : null;
                }
            });
        }
        try {
            long deadline = System.currentTimeMillis() + PROBE_TIMEOUT;
            for (int i = 0; i < cands.size(); i++) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                Future<String> f = cs.poll(remain, TimeUnit.MILLISECONDS);
                if (f == null) break;
                String got;
                try {
                    got = f.get();
                } catch (Exception e) {
                    continue;
                }
                if (got != null) return got;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        // 都探不通就还走直连，保留原本的报错路径，不改变既有行为
        return cands.get(0);
    }

    // 只要拿到任何 HTTP 状态码就算链路通（401/403 同样是 Supabase 应答）
    private static boolean reachable(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/rest/v1/").openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("apikey", ANON_KEY);
            c.setConnectTimeout(PROBE_TIMEOUT);
            c.setReadTimeout(PROBE_TIMEOUT);
            return c.getResponseCode() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private HttpURLConnection open(String urlStr) throws Exception {
        try {
            return rawOpen(urlStr);
        } catch (IOException first) {
            String switched = switchBase(urlStr);
            if (switched == null) throw first;
            return rawOpen(switched);
        }
    }

    // 选中通道中途失效（换网络、被封）时改写 URL 换另一条通道，只切一次
    private String switchBase(String urlStr) {
        String current = baseUrl;
        if (current == null) return null;
        for (String c : candidates()) {
            if (!c.equals(current) && urlStr.startsWith(current)) {
                baseUrl = c;
                return c + urlStr.substring(current.length());
            }
        }
        return null;
    }

    private HttpURLConnection rawOpen(String urlStr) throws Exception {
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