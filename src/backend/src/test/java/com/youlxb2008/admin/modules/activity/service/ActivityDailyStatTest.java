package com.youlxb2008.admin.modules.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.activity.entity.Activity;
import com.youlxb2008.admin.modules.activity.vo.ActivityDailyStatVO;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * v4.18 智能体页「概览」仪表盘：活动按天聚合测试
 * （任务书 GET /api/portal/agents/{agentId}/activity/stats）。
 * <p>
 * 覆盖：多天数据按 statDate 升序聚合 + events/sessions/tools/turns 计数口径 +
 * days 钳制 1~31（默认 14）/null 与边界（0/负数/超大）+ agent 不可见 → 空列表
 * （防枚举）+ 缺失日期不出现在列表里（前端按 statDate 补 gap）。
 * <p>
 * 使用独立内存 H2（MODE=MySQL），全部 @Transactional 回滚，不触碰开发库/真实数据。
 * 服务端按 Asia/Shanghai 墙钟分组（create_time 即事件墙钟，跨 H2/MySQL 兼容）；
 * 测试的窗口起点/期望日期同步用 Asia/Shanghai Calendar，与容器默认 TZ 无关。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
        // 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
        "spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcactivity418;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
        // 数据根目录落到 maven target 下，隔离真实数据
        "web.data-root=./target/test-data-activity418" })
@Transactional
@Rollback(true)
class ActivityDailyStatTest {

    @Resource
    private AgentService agentService;

    @Resource
    private ActivityService activityService;

    /** 名称后缀，保证同 JVM 内多次调用不重名 */
    private static final AtomicLong SEQ = new AtomicLong(0);

    private Agent newAgent(String tag) {
        String name = "ag_act418_" + tag + "_" + SEQ.incrementAndGet();
        return agentService.createAgent(name, "v4.18 测试 agent", null, "活动 418 测试 agent", 1, 1L);
    }

    /** 直插一行（指定 createTime，构造按天聚合场景） */
    private Activity row(Long agentId, String sessionKey, String eventType, String toolName, String summary,
            Integer durationMs, String outcome, Date createTime) {
        Activity r = new Activity();
        r.setAgentId(agentId);
        r.setEventType(eventType);
        r.setSessionKey(sessionKey);
        r.setToolName(toolName);
        r.setSummary(summary);
        r.setDurationMs(durationMs);
        r.setOutcome(outcome);
        r.setCreateTime(createTime);
        activityService.save(r);
        return r;
    }

    /** Asia/Shanghai 时区「今天 - N 天」的零点（与服务端分组口径一致） */
    private Date daysAgoStartOfDay(int days) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        cal.add(Calendar.DAY_OF_MONTH, -days);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    /** yyyy-MM-dd（与服务端分组口径一致：Asia/Shanghai 墙钟取日期） */
    private static String dateKey(Date d) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        cal.setTime(d);
        int y = cal.get(Calendar.YEAR);
        int m = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        return String.format("%04d-%02d-%02d", y, m, day);
    }

    // ------------------------------------------------------------
    // T_v418_01 多天聚合：events/sessions/tools/turns 计数正确
    // + statDate 升序排列 + 缺失日期不出现在列表里
    // ------------------------------------------------------------
    @Test
    void testDailyAggregation() {
        log.info("========== 开始测试 T_v418_01 多天聚合 ==========");
        Agent agent = newAgent("a01");
        // 今日：3 turn + 2 tool（1 failed）
        Date today0 = daysAgoStartOfDay(0);
        row(agent.getId(), "websocket:t1", "turn_started", null, "今日话题 1", null, null,
                new Date(today0.getTime() + 10 * 60_000L));
        row(agent.getId(), "websocket:t1", "tool_call", "exec", "ls", 1000, null,
                new Date(today0.getTime() + 11 * 60_000L));
        row(agent.getId(), "websocket:t1", "tool_call", "exec", "bad", 200, "failed",
                new Date(today0.getTime() + 12 * 60_000L));
        row(agent.getId(), "websocket:t1", "turn_completed", null, "ok", 30_000, "completed",
                new Date(today0.getTime() + 13 * 60_000L));
        row(agent.getId(), "websocket:t1", "turn_started", null, "今日话题 2", null, null,
                new Date(today0.getTime() + 14 * 60_000L));
        // 昨日：1 turn + 1 tool
        Date yest0 = daysAgoStartOfDay(1);
        row(agent.getId(), "websocket:y1", "turn_started", null, "昨日话题", null, null,
                new Date(yest0.getTime() + 10 * 60_000L));
        row(agent.getId(), "websocket:y1", "tool_call", "search", "query", 800, null,
                new Date(yest0.getTime() + 11 * 60_000L));
        // 3 天前：仅 1 status_changed
        Date d3 = daysAgoStartOfDay(3);
        row(agent.getId(), null, "status_changed", null, "状态变更", null, null, d3);

        List<ActivityDailyStatVO> rows = activityService.statsByAgent(agent.getId(), 14);
        // 期望 3 行：3 天前 / 昨日 / 今日（缺失日：前 1 天/前 2 天不出现在列表里，由前端按 statDate 补 gap）
        assertEquals(3, rows.size(), "3 天前/昨日/今日 = 3 行；缺失日期不出现在列表");
        // 升序
        assertEquals(dateKey(d3), rows.get(0).getStatDate(), "statDate 升序：3 天前在前");
        assertEquals(dateKey(yest0), rows.get(1).getStatDate(), "昨日");
        assertEquals(dateKey(today0), rows.get(2).getStatDate(), "今日");
        // 今日计数
        ActivityDailyStatVO today = rows.get(2);
        assertEquals(Long.valueOf(5L), today.getEvents(), "今日 events=3 turn + 2 tool = 5");
        assertEquals(Long.valueOf(2L), today.getTools(), "今日 tool_call × 2");
        assertEquals(Long.valueOf(1L), today.getTurns(), "今日 turn_completed × 1");
        // 今日会话数：t1 一个 sessionKey
        assertEquals(Long.valueOf(1L), today.getSessions(), "今日 sessionKey t1 一条");
        // 昨日计数
        ActivityDailyStatVO yest = rows.get(1);
        assertEquals(Long.valueOf(2L), yest.getEvents(), "昨日 events=1 turn + 1 tool = 2");
        assertEquals(Long.valueOf(1L), yest.getSessions(), "昨日 sessionKey y1 一条");
        // 3 天前计数：仅 status_changed 1 条
        ActivityDailyStatVO d3Row = rows.get(0);
        assertEquals(Long.valueOf(1L), d3Row.getEvents());
        assertEquals(Long.valueOf(0L), d3Row.getTools(), "3 天前无 tool_call");
        assertEquals(Long.valueOf(0L), d3Row.getTurns(), "3 天前无 turn_completed");
        assertEquals(Long.valueOf(0L), d3Row.getSessions(), "3 天前 status_changed sessionKey=null 计 0");
        log.info("========== 测试执行完成 ==========");
    }

    // ------------------------------------------------------------
    // T_v418_02 days 钳制：null/0/负 → 默认 14；超大 → 钳到 31；<1 → 钳到 1
    // ------------------------------------------------------------
    @Test
    void testDaysClamp() {
        log.info("========== 开始测试 T_v418_02 days 钳制 ==========");
        Agent agent = newAgent("a02");
        Date today0 = daysAgoStartOfDay(0);
        row(agent.getId(), "websocket:c", "turn_started", null, "x", null, null, today0);

        // null/0/负数 → 默认 14（行为一致）
        assertNotNull(activityService.statsByAgent(agent.getId(), null));
        assertEquals(1, activityService.statsByAgent(agent.getId(), null).size());
        assertEquals(1, activityService.statsByAgent(agent.getId(), 0).size());
        assertEquals(1, activityService.statsByAgent(agent.getId(), -5).size());
        // 1 → 1（钳制下限）
        assertEquals(1, activityService.statsByAgent(agent.getId(), 1).size());
        // 31 → 1（上限钳制仍含今日）
        assertEquals(1, activityService.statsByAgent(agent.getId(), 31).size());
        // 100 → 1（超限钳到 31 仍含今日）
        assertEquals(1, activityService.statsByAgent(agent.getId(), 100).size());
        log.info("========== 测试执行完成 ==========");
    }

    // ------------------------------------------------------------
    // T_v418_03 agent 不可见 → 空列表（防枚举）
    // ------------------------------------------------------------
    @Test
    void testInvisibleAgentReturnsEmpty() {
        log.info("========== 开始测试 T_v418_03 不可见 agent ==========");
        // 不存在的 agent id
        List<ActivityDailyStatVO> rows = activityService.statsByAgent(999999L, 14);
        assertTrue(rows.isEmpty(), "不存在的 agent 应返回空列表（防枚举）");
        log.info("========== 测试执行完成 ==========");
    }
}
