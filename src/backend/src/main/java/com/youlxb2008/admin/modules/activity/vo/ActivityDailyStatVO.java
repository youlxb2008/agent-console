package com.youlxb2008.admin.modules.activity.vo;

import lombok.Data;

/**
 * 活动流按日聚合行（v4.18 智能体页概览仪表盘，
 * GET /api/portal/agents/{agentId}/activity/stats，匿名只读）
 * <p>
 * 服务端按 create_time 墙钟日期分日（Java 侧分组，跨 H2/MySQL 兼容，评审打回
 * 修正弃用 H2 专有 FORMATDATETIME）：单行 = 一天（DDL create_time 即事件墙钟）。
 * 14 天窗口下返回最多 14 行；窗口内无事件的日子不出现在列表里（前端按 statDate
 * 填充 gap 灰色斜纹柱）。
 * <p>
 * 字段命名（task-book §2）：statDate/events/sessions/tools/turns，全部非负，
 * 前端拿到的 today 字段 = 列表最后一行。
 * <p>
 * 注意：statDate 用 String 而非 Date——DATE 类型经 MyBatis 默认 TypeHandler
 * （Date）会变成 1970 起的 epoch，直接给 yyyy-MM-dd 字符串零转换成本。
 * 任务书"直接 DATE() 即可"是字段命名口径，实际类型由实现决定。
 */
@Data
public class ActivityDailyStatVO {

    /** 日期 yyyy-MM-dd（北京墙钟，Java 侧 DateTimeFormatter 按日格式化） */
    private String statDate;

    /** 当日事件总数（含 turn_started/tool_call/status_changed/turn_completed） */
    private Long events;

    /** 当日独立会话数（COUNT DISTINCT session_key，NULL session_key 计 0 不计） */
    private Long sessions;

    /** 当日 tool_call 事件数 */
    private Long tools;

    /** 当日 turn_completed 事件数 */
    private Long turns;

}
