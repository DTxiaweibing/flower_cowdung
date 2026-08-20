// SeatManager.java (app-new)
// 大厅坐席状态机与退出规则的独立封装（见开发文档第 25 章）。
// 任何游戏状态（人机大厅 / 人人大厅 / 私密房间 / 对局页）都可调用同一套方法：
//   入座：坐下当玩家（pve_sit）/ 坐下当观众（pve_watch）
//   退出：玩家停摆直接离座（pve_leave）/ 观众直接退观（pve_unwatch）
//   判负：玩家对局中退出 = 判负写库 + 释放座位（pve_forfeit，原子）
//   遗言：进程存活期间周期心跳刷新 last_active_at（startHeartbeat），
//         服务端定时任务兜底释放超时座位（见 pve_tables.sql）。
// 约定：
//   - 所有 RPC 在后台线程执行，结果通过 callback 回到主线程；
//   - callback 允许为 null（纯写库，不关心结果，如 onDestroy 遗言）。
package com.example.cowdunggame;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

public class SeatManager {

    public interface ResultCallback {
        void onResult(boolean ok, String message);
    }

    private static final long HEARTBEAT_INTERVAL_MS = 20_000L;

    private final SupabaseClient client;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private String heartbeatTableId;

    public SeatManager(SupabaseClient client) {
        this.client = client;
    }

    // ============================================================
    // 入座（第 25.3 条）
    // ============================================================

    // 坐下当玩家：仅空桌可坐；一人限坐一桌；成功后自动清空旧观战关系
    public void sitAsPlayer(final String tableId, final ResultCallback cb) {
        background(new Runnable() {
            @Override
            public void run() {
                boolean ok = client != null && client.pveSit(tableId);
                deliver(cb, ok, ok ? "入座成功" : "入座失败：座位可能已被占用");
            }
        });
    }

    // 坐下当观众：空桌/有人桌均可，观战不限量；一人限观一桌
    public void sitAsWatcher(final String tableId, final ResultCallback cb) {
        background(new Runnable() {
            @Override
            public void run() {
                boolean ok = client != null && client.pveWatch(tableId);
                deliver(cb, ok, ok ? "入座成功" : "观战失败：可能已在其他桌当玩家");
            }
        });
    }

    // ============================================================
    // 退出（第 25.4 条）
    // ============================================================

    // 玩家停摆退出：直接离座写库，大厅立即显示空座
    public void leaveSeat(final String tableId, final ResultCallback cb) {
        background(new Runnable() {
            @Override
            public void run() {
                boolean ok = client != null && client.pveLeave(tableId);
                deliver(cb, ok, ok ? "已离座" : "离座失败");
            }
        });
    }

    // 观众退出：直接移除观战关系写库，无二次确认弹窗
    public void leaveWatch(final String tableId, final ResultCallback cb) {
        background(new Runnable() {
            @Override
            public void run() {
                boolean ok = client != null && client.pveUnwatch(tableId);
                deliver(cb, ok, ok ? "已退出观战" : "退出观战失败");
            }
        });
    }

    // 玩家对局中退出 = 判负：先写 finished/computer 到 game_state，再释放座位（原子）
    // finalGameState 可传 null（只判负不关心棋盘明细）
    public void forfeitAndLeave(final String tableId, final JSONObject finalGameState,
                                final ResultCallback cb) {
        background(new Runnable() {
            @Override
            public void run() {
                boolean ok = client != null && client.pveForfeit(tableId, finalGameState);
                deliver(cb, ok, ok ? "已退出并判负" : "判负离座失败");
            }
        });
    }

    // ============================================================
    // 遗言：心跳（第 25.5 条）
    // ============================================================

    // 进程存活期间周期上报心跳（刷新 last_active_at，防止被服务端误清）
    public void startHeartbeat(final String tableId) {
        stopHeartbeat();
        heartbeatTableId = tableId;
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat(heartbeatTableId);
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        };
        heartbeatHandler.post(heartbeatRunnable);
    }

    public void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
        heartbeatTableId = null;
    }

    private void sendHeartbeat(final String tableId) {
        if (client == null || tableId == null) return;
        background(new Runnable() {
            @Override
            public void run() {
                try {
                    client.pveHeartbeat(tableId);
                } catch (Exception ignore) { }
            }
        });
    }

    // ============================================================
    // 纯规则判定（第 25.2 / 25.3 / 25.4 条，静态，任意界面可用）
    // ============================================================

    // 该桌是否有人坐在玩家位（state 为 pve_tables 行；player_id 为 null 时 isNull 判断）
    public static boolean hasPlayer(JSONObject state) {
        return state != null && !state.isNull("player_id");
    }

    public static boolean isPlaying(JSONObject state) {
        return state != null && "playing".equals(state.optString("status", ""));
    }

    // 我是否正坐在这桌的玩家位
    public static boolean isMySeat(JSONObject state, String myId) {
        return hasPlayer(state) && myId != null
            && myId.equals(state.optString("player_id", ""));
    }

    // 该桌是否空闲（无玩家位 -> 可坐下当玩家，也可当观众）
    public static boolean isTableFree(JSONObject state) {
        return !hasPlayer(state);
    }

    // 是否需要「退出 = 判负」的确认弹窗（第 25.4 条）：
    //   观众 → 否（直接退出）；玩家停摆 → 否（直接退出）；玩家对局中 → 是
    public static boolean needsForfeitConfirm(boolean isWatcher, boolean playing) {
        return !isWatcher && playing;
    }

    // ============================================================
    // 内部
    // ============================================================

    private void background(Runnable r) {
        if (r == null || client == null) return;
        new Thread(r).start();
    }

    private void deliver(final ResultCallback cb, final boolean ok, final String message) {
        if (cb == null) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(ok, message);
            }
        });
    }
}
