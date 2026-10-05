# supabase 脚本

## 现在只需要两个文件

| 文件 | 什么时候用 |
| --- | --- |
| `bootstrap.sql` | **建新库**。全新 Supabase 项目，整个文件一次性粘贴进 SQL Editor 运行。 |
| `hotfix_room_leave.sql` | **补现有的线上库**。`room_leave` 的末位离席修复，线上那套还没打过。 |

新库不需要再跑任何别的脚本 —— `bootstrap.sql` 已经包含全部表、函数、触发器、
RLS、策略、权限和定时任务。

## bootstrap.sql 是什么

它是 `db_backup/schema.sql`（2026-10-03 从线上导出的真实结构）的 `public` 段，
原样搬过来，再补三样备份里没有的东西：

1. **12 个 cron 定时任务** —— pg_cron 不进 schema dump，从脚本里逐个捞回来的。
   每个任务先按名字 unschedule 再 schedule：pg_cron 允许重名，重复执行会多出一个
   `xxx (1)`，回收器就会每分钟跑两遍。
2. **`on_auth_user_created` 触发器** —— 新用户注册时自动建 `profiles` 行。
   它在备份的 `auth` 段里，而 `auth` 段整体不能搬（Supabase 自带的 27 张表、
   183 条授权在新项目里本来就存在，重放会冲突），所以单独把这一个触发器摘出来。
3. **`room_leave` 修复版** —— 覆盖备份里的旧版本，顺带删掉遗留的 `char(1)` 重载。

结尾有一段校验：12 个任务没建齐就直接 `raise exception`。宁可报错，也不要看着
"执行成功" 然后回收器静默停摆。

跑完之后建议在 SQL Editor 补一句，让 PostgREST 重新加载函数签名：

```sql
notify pgrst, 'reload schema';
```

## 为什么其余脚本都进了 archive

`db_backup/schema.sql` 是**合并后的结果**，不是某一步。它已经包含了
`schema.sql` / `pvp_tables.sql` / `private_rooms.sql` / `run_all_pve.sql` /
`ranking_scores.sql` / `chat_messages.sql` 以及各个 `fix_*.sql` 的最新状态 ——
逐个函数比对过，只有 `room_leave` 因为修得比备份晚而不同。

所以那些增量脚本在建新库时是多余的，而且**有害**：单独重跑 `pvp_tables.sql` 会把
`*_watcher_inc/dec()` 覆盖回旧版；`fix_round_lifecycle.sql` 里有 4 个 `drop function`，
整份重跑可能拆掉依赖。

全部移到 `archive/`，只留备查。新环境不要再执行它们。

### archive/ 里新收进去的 12 个

`schema.sql`、`pvp_tables.sql`、`pve_tables.sql`、`private_rooms.sql`、
`run_all_pve.sql`、`chat_messages.sql`、`ranking_scores.sql`、
`fix_round_lifecycle.sql`、`fix_kick_watcher.sql`、`fix_watcher_count_recursion.sql`、
`fix_ban_guards.sql`、`fix_drop_legacy_kick.sql`

### archive/ 里原有的 20 个

旧版一键脚本和一次性运维补丁（清脏数据、完全重置等），依旧只在需要考古时翻。

- `run_all.sql`：旧版一键合并，已被 pvp/pve/private 取代。
- `fix_30_tables.sql`、`fix_tables_20.sql`：早期大厅桌数预置（30 → 20）。
- `fix_account_auth.sql`：账号体系（昵称派生 email 登录）历史补丁。
- `fix_missing_rpcs.sql`：早期补齐缺失 RPC。
- `fix_profile_null.sql`：修复新用户建档 NOT NULL 报 500。
- `fix_ready_open.sql`：双方准备才开局。
- `fix_room_lifecycle.sql`、`fix_sit_room.sql`、`private_room_fix.sql`：私房旧版补丁。
- `fix_chat_lifecycle.sql`：聊天按桌隔离 + 末位清场。
- `fix_pve_game_sync.sql`、`fix_pve_forfeit.sql`：已并入 `run_all_pve.sql`。
- `fix_watchers_unlimited.sql`：观战不限人数旧补丁。
- `fix_pve_cleanup.sql`、`fix_pve_cleanup_watchers.sql`、`fix_pve_reset.sql`：PvE 脏数据清理（先查后删）。
- `fix_cleanup_profiles.sql`：清理垃圾昵称（先查后删）。
- `fix_reset_all.sql`、`fix_verify_reset.sql`：开发环境完全重置 + 验证。

## drafts/

本地草稿，`.gitignore` 已忽略，不提交：聊天删除相关的试验与回滚脚本。

## 注意事项

- `db_backup/` 也在 `.gitignore` 里（含账号数据）。`bootstrap.sql` 是它的可提交
  精简版，两者不要混用。
- 备份文件的行尾是混的（CRLF 和 `\r\r\n` 都有），`bootstrap.sql` 已统一成 LF。
  用某些工具按行读备份时行号会错位，别拿行号去定位东西。
- 全部文件 UTF-8 无 BOM。