# supabase 脚本索引

> 全部脚本均为**幂等**，可在 Supabase Dashboard → SQL Editor 整段重复执行。
> 执行建库脚本后建议：`notify pgrst, 'reload schema';`

## 一、建库（全新环境按序执行）

| 顺序 | 文件 | 作用 |
| --- | --- | --- |
| 1 | `schema.sql` | 账号/资料基础：`profiles`、Auth 建档、`finish_game` 等。**必跑**。 |
| 2 | `pvp_tables.sql` | 人人大厅：`pvp_tables` / `pvp_watchers` + 全套 RPC。 |
| 3 | `pve_tables.sql` | 人机大厅：`pve_tables` / `pve_watchers` + RPC。（想一键合并可用 `run_all_pve.sql` 替代本文件） |
| 4 | `private_rooms.sql` | 私密房间：`private_rooms` / `private_room_watchers` + RPC。 |
| 5 | `chat_messages.sql` | 聊天表（人人 / 人机 / 私密共用，按 `table_id` 区分）。 |
| 6 | `ranking_scores.sql` | 积分 / 排名 / 职务统计列与结算函数。 |

> `run_all_pve.sql` = `pve_tables.sql` + 棋局同步 + 判负的**自洽合并版**，只想跑 PvE 时用它即可。

## 二、当前增量修复（新环境在建库之后按序补跑）

| 文件 | 作用 |
| --- | --- |
| `fix_round_lifecycle.sql` | 一局生命周期权威版：回合超时判负、单人参战 60s 回收、末位离席清场（清聊天/棋谱）、cron 兜底。**最后会被它覆盖建库脚本里的同名函数。** |
| `fix_kick_watcher.sql` | 观众「查看资料 / 踢出 / 临时禁入」+ 收紧 watchers 表 DELETE 策略。 |
| `fix_watcher_count_recursion.sql` | 修复「有观众时玩家无法离座、观众永不被踢」：归零分支置 `watcher_count=0` + `watcher_inc/dec` 在连带触发时早退。 |
| `fix_ban_guards.sql` | 线上独有的 `*_ban_guard` 触发器/函数 + 三表 `banned_ids` 列，按线上原文落库（文件头有现状说明）。 |
| `fix_drop_legacy_kick.sql` | **清理**旧版踢人机制：`kick_watcher` / `reset_watcher_kick` + 三表 `kicked` 列 + 三条 `*_no_kicked_rejoin` 策略，已被 `fix_kick_watcher.sql` 的 bans 表机制取代。已对线上执行。 |

> ⚠️ 重跑 `pvp_tables.sql` / `private_rooms.sql` / `pve_tables.sql` / `run_all_pve.sql` 会覆盖其中的 `*_watcher_inc/dec()`，需再跑一次 `fix_watcher_count_recursion.sql`。

## 三、archive/ — 历史 / 被取代 / 一次性脚本（保留备查，勿在新环境执行）

- `run_all.sql`：旧版一键合并（schema + 旧大厅 lobby/rooms 流程），已被 pvp/pve/private 取代。
- `fix_30_tables.sql`、`fix_tables_20.sql`：早期大厅桌数预置（30 → 20）。
- `fix_account_auth.sql`：账号体系（昵称派生 email 登录）历史补丁。
- `fix_missing_rpcs.sql`：早期补齐缺失 RPC。
- `fix_profile_null.sql`：修复新用户建档 NOT NULL 报 500（已并入 `schema.sql`）。
- `fix_ready_open.sql`：双方准备才开局（已并入 `schema.sql` / `run_all.sql`）。
- `fix_room_lifecycle.sql`、`fix_sit_room.sql`、`private_room_fix.sql`：私房旧版补丁，被 `private_rooms.sql` + `fix_round_lifecycle.sql` 取代。
- `fix_chat_lifecycle.sql`：聊天按桌隔离 + 末位清场，被 `fix_round_lifecycle.sql` 取代。
- `fix_pve_game_sync.sql`、`fix_pve_forfeit.sql`：已并入 `run_all_pve.sql`。
- `fix_watchers_unlimited.sql`：观战不限人数旧补丁，逻辑已合入主脚本。
- `fix_pve_cleanup.sql`、`fix_pve_cleanup_watchers.sql`、`fix_pve_reset.sql`：PvE 脏数据清理 / 复位（一次性运维，先查后删）。
- `fix_cleanup_profiles.sql`：清理垃圾昵称（一次性运维，先查后删）。
- `fix_reset_all.sql`、`fix_verify_reset.sql`：开发环境完全重置 + 验证。

## 四、drafts/ — 本地草稿（已被 `.gitignore` 忽略，不提交）

`fix_chat_auto_delete.sql`、`fix_chat_clear_on_sit.sql`、`fix_chat_delete.sql`、`fix_rollback_chat_scripts.sql`：聊天删除相关的试验与回滚脚本，未采用。
