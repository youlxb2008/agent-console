package com.youlxb2008.admin.modules.agent.memory.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.youlxb2008.admin.modules.agent.memory.entity.Memory;
import com.youlxb2008.admin.modules.agent.memory.service.MemoryService;

/**
 * 记忆衰减纯函数单测（M7.1 M2，任务书 §3.2 单测口径）
 * <p>
 * 覆盖：衰减判定边界（89/90/91 天）、dialogue/system 不衰减、weight 公式
 * （base 三档 / log2 访问分 / min 30 封顶 / 近期活跃 +20 / 窗口外不加分）。
 * 纯函数无 Spring 上下文，时间全部显式构造，无时钟敏感。
 */
@DisplayName("M7.1 记忆衰减：shouldArchive 边界 + calcWeight 公式")
class MemoryDecayJobTest {

	/** 固定「当前时间」，消除测试时钟敏感 */
	private final Date now = new Date(1_800_000_000_000L);

	private Memory memory(String source, Date lastAccessedAt, Date confirmedAt) {
		Memory memory = new Memory();
		memory.setId(1L);
		memory.setSource(source);
		memory.setStatus(MemoryService.STATUS_ACTIVE);
		memory.setCreateTime(new Date(now.getTime() - 200L * 24 * 3600 * 1000));
		memory.setLastAccessedAt(lastAccessedAt);
		memory.setConfirmedAt(confirmedAt);
		return memory;
	}

	private Date daysAgo(long days) {
		return new Date(now.getTime() - days * 24L * 3600 * 1000);
	}

	// ------------------------------------------------------------
	// 衰减判定边界（任务书：89天/90天/91天）
	// ------------------------------------------------------------

	@Test
	@DisplayName("T_decay_01 89 天前访问/确认：不衰减（未到窗口）")
	void shouldNotArchiveAt89Days() {
		Memory memory = memory("self", daysAgo(89), daysAgo(89));
		assertFalse(MemoryDecayJob.shouldArchive(memory, MemoryDecayJob.decayCutoff(now, 90)),
				"89 天 > 90 天窗口，不应衰减");
	}

	@Test
	@DisplayName("T_decay_02 恰好 90 天前：不衰减（=cutoff 不早于 cutoff，严格小于判定）")
	void shouldNotArchiveAtExactly90Days() {
		Memory memory = memory("self", daysAgo(90), daysAgo(90));
		assertFalse(MemoryDecayJob.shouldArchive(memory, MemoryDecayJob.decayCutoff(now, 90)),
				"恰好等于 cutoff 不算「早于」，不衰减");
	}

	@Test
	@DisplayName("T_decay_03 91 天前访问/确认：衰减")
	void shouldArchiveAt91Days() {
		Memory memory = memory("self", daysAgo(91), daysAgo(91));
		assertTrue(MemoryDecayJob.shouldArchive(memory, MemoryDecayJob.decayCutoff(now, 90)),
				"91 天 < cutoff，应衰减");
	}

	@Test
	@DisplayName("T_decay_04 91 天前访问但 89 天前确认：不衰减（须两者均超期）")
	void shouldNotArchiveWhenConfirmedRecently() {
		Memory memory = memory("self", daysAgo(91), daysAgo(89));
		assertFalse(MemoryDecayJob.shouldArchive(memory, MemoryDecayJob.decayCutoff(now, 90)),
				"confirmed_at 未超期，不衰减");
	}

	@Test
	@DisplayName("T_decay_05 dialogue 来源 91 天：不衰减（dialogue/system 不自动衰减）")
	void shouldNotArchiveDialogue() {
		Memory memory = memory("dialogue", daysAgo(91), daysAgo(91));
		assertFalse(MemoryDecayJob.shouldArchive(memory, MemoryDecayJob.decayCutoff(now, 90)),
				"dialogue 来源永不自动衰减");
	}

	@Test
	@DisplayName("T_decay_06 system 来源 91 天：不衰减")
	void shouldNotArchiveSystem() {
		Memory memory = memory("system", daysAgo(91), daysAgo(91));
		assertFalse(MemoryDecayJob.shouldArchive(memory, MemoryDecayJob.decayCutoff(now, 90)),
				"system 来源永不自动衰减");
	}

	@Test
	@DisplayName("T_decay_07 访问/确认为 NULL：以 create_time 兜底判定")
	void shouldArchiveWithNullTimesByCreateTime() {
		Memory memory = memory("self", null, null);
		// createTime = 200 天前 > 90 天窗口 → 衰减
		assertTrue(MemoryDecayJob.shouldArchive(memory, MemoryDecayJob.decayCutoff(now, 90)),
				"从未访问/确认的记忆按创建时间起算，200 天前创建应衰减");
	}

	// ------------------------------------------------------------
	// weight 公式（base + min(30, log2(1+access)*10) + 近期 20）
	// ------------------------------------------------------------

	@Test
	@DisplayName("T_weight_01 dialogue 基础分 30，无访问无近期")
	void weightDialogueBase() {
		assertEquals(30, MemoryDecayJob.calcWeight("dialogue", 0, null, null, now, 90));
	}

	@Test
	@DisplayName("T_weight_02 system 基础分 20")
	void weightSystemBase() {
		assertEquals(20, MemoryDecayJob.calcWeight("system", 0, null, null, now, 90));
	}

	@Test
	@DisplayName("T_weight_03 self 基础分 10")
	void weightSelfBase() {
		assertEquals(10, MemoryDecayJob.calcWeight("self", 0, null, null, now, 90));
	}

	@Test
	@DisplayName("T_weight_04 self + access=1：10 + log2(2)*10 = 20")
	void weightSelfAccess1() {
		assertEquals(20, MemoryDecayJob.calcWeight("self", 1, null, null, now, 90));
	}

	@Test
	@DisplayName("T_weight_05 self + access=3：10 + log2(4)*10 = 30")
	void weightSelfAccess3() {
		assertEquals(30, MemoryDecayJob.calcWeight("self", 3, null, null, now, 90));
	}

	@Test
	@DisplayName("T_weight_06 self + access=7：10 + log2(8)*10 = 40")
	void weightSelfAccess7() {
		assertEquals(40, MemoryDecayJob.calcWeight("self", 7, null, null, now, 90));
	}

	@Test
	@DisplayName("T_weight_07 access=100：访问分封顶 30（self → 40）")
	void weightAccessScoreCapped() {
		assertEquals(40, MemoryDecayJob.calcWeight("self", 100, null, null, now, 90));
	}

	@Test
	@DisplayName("T_weight_08 89 天前有访问：近期活跃 +20（self → 30）")
	void weightRecentAccessBonus() {
		assertEquals(30, MemoryDecayJob.calcWeight("self", 0, daysAgo(89), null, now, 90));
	}

	@Test
	@DisplayName("T_weight_09 89 天前有确认：近期活跃 +20（dialogue → 50）")
	void weightRecentConfirmBonus() {
		assertEquals(50, MemoryDecayJob.calcWeight("dialogue", 0, null, daysAgo(89), now, 90));
	}

	@Test
	@DisplayName("T_weight_10 91 天前访问/确认：窗口外不加分（self → 10）")
	void weightOutOfWindowNoBonus() {
		assertEquals(10, MemoryDecayJob.calcWeight("self", 0, daysAgo(91), daysAgo(91), now, 90));
	}

	@Test
	@DisplayName("T_weight_11 窗口天数可配：decayDays=30 时 31 天前访问不加分")
	void weightCustomDecayDays() {
		assertEquals(10, MemoryDecayJob.calcWeight("self", 0, daysAgo(31), null, now, 30));
		assertEquals(30, MemoryDecayJob.calcWeight("self", 0, daysAgo(29), null, now, 30));
	}

	@Test
	@DisplayName("T_weight_12 accessCount 为 NULL：按 0 计")
	void weightNullAccessCount() {
		assertEquals(10, MemoryDecayJob.calcWeight("self", null, null, null, now, 90));
	}

	// ------------------------------------------------------------
	// 衰减与加分窗口互补自洽（checkpoint 决策③）
	// ------------------------------------------------------------

	@Test
	@DisplayName("T_decay_08 互补自洽：91 天均超期 → 衰减且 weight 无近期加分；89 天 → 不衰减且 +20")
	void decayAndBonusComplementary() {
		Memory stale = memory("self", daysAgo(91), daysAgo(91));
		assertTrue(MemoryDecayJob.shouldArchive(stale, MemoryDecayJob.decayCutoff(now, 90)));
		assertEquals(10, MemoryDecayJob.calcWeight("self", 0, daysAgo(91), daysAgo(91), now, 90),
				"衰减档记忆 weight 无近期加分");

		Memory fresh = memory("self", daysAgo(89), daysAgo(89));
		assertFalse(MemoryDecayJob.shouldArchive(fresh, MemoryDecayJob.decayCutoff(now, 90)));
		assertEquals(30, MemoryDecayJob.calcWeight("self", 0, daysAgo(89), daysAgo(89), now, 90),
				"未衰减档记忆 weight 含近期加分");
	}

	// ------------------------------------------------------------
	// M7.1-M3a weight 保底线 applyFloor（落库 weight = max(calcWeight, weight_floor)）
	// ------------------------------------------------------------

	@Test
	@DisplayName("T_floor_01 保底生效：floor=70、公式值 10 → 70")
	void floorOverridesLowCalc() {
		assertEquals(70, MemoryDecayJob.applyFloor(10, 70), "公式值低于保底线应取保底线");
	}

	@Test
	@DisplayName("T_floor_02 公式更高：floor=70、公式值 85 → 85（保底不封顶）")
	void floorDoesNotCapHighCalc() {
		assertEquals(85, MemoryDecayJob.applyFloor(85, 70), "公式值高于保底线应取公式值");
	}

	@Test
	@DisplayName("T_floor_03 不保底：floor=0 → 公式值（回归保障）")
	void zeroFloorKeepsCalc() {
		assertEquals(10, MemoryDecayJob.applyFloor(10, 0));
		assertEquals(85, MemoryDecayJob.applyFloor(85, 0));
	}

	@Test
	@DisplayName("T_floor_04 floor 为 NULL：按不保底（存量行兼容）")
	void nullFloorKeepsCalc() {
		assertEquals(42, MemoryDecayJob.applyFloor(42, null));
	}
}
