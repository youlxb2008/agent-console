package com.youlxb2008.admin.modules.agent.memory.job;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.youlxb2008.admin.modules.agent.memory.entity.Memory;
import com.youlxb2008.admin.modules.agent.memory.service.MemoryService;
import com.youlxb2008.admin.modules.system.entity.SiteOption;
import com.youlxb2008.admin.modules.system.mapper.SiteOptionMapper;
import com.youlxb2008.admin.modules.system.service.CronHandlerRegistry;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * 记忆衰减任务（M7.1 M2，任务书 §3.2，方案稿 §7）
 * <p>
 * 注册进现有 system:cron 机制（{@link CronHandlerRegistry}），handler 名
 * {@code memoryDecayJob}；t_cron_job 种子行 status=0（禁用）——用户明确指示
 * 「先别着急遗忘」，自动衰减的实际启用等用户拍板，本任务只交付能力不启用。
 * <p>
 * 逻辑（每次触发两步）：
 * <ol>
 *   <li>衰减归档：source=self 且 status=1 且 last_accessed_at 与 confirmed_at
 *       均早于 now-N 天 → status=2（N 读 t_option key={@code memory_decay_days}，
 *       默认 90，空值/非法值用默认）；source=dialogue/system 不自动衰减</li>
 *   <li>全量重算 weight：base(dialogue:30/system:20/self:10)
 *       + min(30, log2(1+access_count)*10)
 *       + (decayDays 天内有 access/confirm ? 20 : 0)；
 *       M7.1-M3a 落库取 max(公式值, weight_floor) 保底（0=不保底）</li>
 * </ol>
 * <p>
 * NULL 语义：last_accessed_at/confirmed_at 为 NULL 时衰减判定以 create_time 兜底
 * （「从未访问视为从创建起算」）；weight 加分项无访问/确认记录则不加分。
 * 加分窗口与衰减判定互补自洽：任一时间 ≥ now-N → 不归档且 +20；均 &lt; now-N →
 * 归档且不加分（窗口天数同用 decayDays）。
 */
@Slf4j
@Component
public class MemoryDecayJob {

	/** CronHandlerRegistry 注册名（t_cron_job.handler_class 对应值） */
	public static final String HANDLER_NAME = "memoryDecayJob";

	/** 衰减窗口默认天数（t_option memory_decay_days 缺省/空值/非法值时） */
	public static final int DEFAULT_DECAY_DAYS = 90;

	/** weight 基础分：dialogue */
	public static final int BASE_DIALOGUE = 30;
	/** weight 基础分：system */
	public static final int BASE_SYSTEM = 20;
	/** weight 基础分：self */
	public static final int BASE_SELF = 10;
	/** weight 访问分上限 */
	public static final int ACCESS_SCORE_CAP = 30;
	/** weight 近期活跃加分 */
	public static final int RECENT_BONUS = 20;

	private static final long DAY_MS = 24L * 3600 * 1000;

	private final CronHandlerRegistry cronHandlerRegistry;
	private final MemoryService memoryService;
	private final SiteOptionMapper siteOptionMapper;

	@Autowired
	public MemoryDecayJob(CronHandlerRegistry cronHandlerRegistry, MemoryService memoryService,
			SiteOptionMapper siteOptionMapper) {
		this.cronHandlerRegistry = cronHandlerRegistry;
		this.memoryService = memoryService;
		this.siteOptionMapper = siteOptionMapper;
	}

	/**
	 * 应用启动后注册 handler（照 SA CronHandlerRegistry @PostConstruct 注册范式）
	 */
	@PostConstruct
	public void register() {
		cronHandlerRegistry.register(HANDLER_NAME, this::execute,
				"记忆衰减：source=self 超期未访问的记忆归档（1→2）+ 全量重算 weight（默认禁用，启用需用户明确指示）");
		log.info("记忆衰减任务已注册（handler={}，t_cron_job 种子 status=0 禁用）", HANDLER_NAME);
	}

	/**
	 * 执行衰减（CronHandlerFn 签名；params 当前未用，预留）
	 *
	 * @return 执行摘要（落 t_cron_log.result）
	 */
	public String execute(String params) {
		int decayDays = resolveDecayDays();
		Date now = new Date();
		Date cutoff = decayCutoff(now, decayDays);

		// 1. 衰减归档：source=self 且 status=1（dialogue/system 由查询条件天然排除）
		List<Memory> candidates = memoryService.list(Wrappers.<Memory>lambdaQuery()
				.eq(Memory::getSource, "self")
				.eq(Memory::getStatus, MemoryService.STATUS_ACTIVE));
		int archived = 0;
		for (Memory memory : candidates) {
			if (shouldArchive(memory, cutoff)) {
				memory.setStatus(MemoryService.STATUS_ARCHIVED);
				memory.setUpdateTime(now);
				memoryService.updateById(memory);
				archived++;
			}
		}

		// 2. 全量重算 weight（所有未删除行，含归档/被取代——任务书「全量重算」口径）
		//    M7.1-M3a：落库 weight = max(calcWeight, weight_floor)，公式本身不动；
		//    weight_floor=0/null 的条目行为与 M7.1 完全一致（回归保障）
		List<Memory> all = memoryService.list();
		int recalculated = 0;
		for (Memory memory : all) {
			int weight = applyFloor(
					calcWeight(memory.getSource(), memory.getAccessCount(),
							memory.getLastAccessedAt(), memory.getConfirmedAt(), now, decayDays),
					memory.getWeightFloor());
			if (memory.getWeight() == null || memory.getWeight() != weight) {
				Memory patch = new Memory();
				patch.setId(memory.getId());
				patch.setWeight(weight);
				patch.setUpdateTime(now);
				memoryService.updateById(patch);
				recalculated++;
			}
		}

		String summary = "decayDays=" + decayDays + ", archived=" + archived
				+ ", weightRecalculated=" + recalculated + "/" + all.size();
		log.info("记忆衰减完成：{}", summary);
		return summary;
	}

	/**
	 * 衰减判定（纯函数，单测直接覆盖）：source=self 且 last_accessed_at 与 confirmed_at
	 * 均早于 cutoff → true。NULL 以 create_time 兜底；dialogue/system 恒 false。
	 *
	 * @param memory 记忆行
	 * @param cutoff 衰减截止时间（now - decayDays）
	 * @return 是否应归档
	 */
	static boolean shouldArchive(Memory memory, Date cutoff) {
		if (!"self".equals(memory.getSource())) {
			return false;
		}
		Date lastAccessed = memory.getLastAccessedAt() != null ? memory.getLastAccessedAt() : memory.getCreateTime();
		Date confirmed = memory.getConfirmedAt() != null ? memory.getConfirmedAt() : memory.getCreateTime();
		if (lastAccessed == null || confirmed == null) {
			return false;
		}
		return lastAccessed.before(cutoff) && confirmed.before(cutoff);
	}

	/**
	 * weight 公式（纯函数，单测直接覆盖）：
	 * base(dialogue:30/system:20/self:10) + min(30, log2(1+access_count)*10)
	 * + (last_accessed_at 或 confirmed_at 在 [now-decayDays, now] 内 ? 20 : 0)。
	 *
	 * @param source          来源（dialogue/self/system，白名单外按 self 最低档 10 计）
	 * @param accessCount     检索命中次数（可空，按 0 计）
	 * @param lastAccessedAt  最后命中时间（可空）
	 * @param confirmedAt     最后确认时间（可空）
	 * @param now             当前时间
	 * @param decayDays       窗口天数（与衰减判定同源）
	 * @return weight
	 */
	static int calcWeight(String source, Integer accessCount, Date lastAccessedAt, Date confirmedAt,
			Date now, int decayDays) {
		int base;
		if ("dialogue".equals(source)) {
			base = BASE_DIALOGUE;
		} else if ("system".equals(source)) {
			base = BASE_SYSTEM;
		} else {
			base = BASE_SELF;
		}
		int access = accessCount != null && accessCount > 0 ? accessCount : 0;
		int accessScore = (int) Math.min(ACCESS_SCORE_CAP, Math.round(Math.log(1 + access) / Math.log(2) * 10));

		Date recent = null;
		if (lastAccessedAt != null && confirmedAt != null) {
			recent = lastAccessedAt.after(confirmedAt) ? lastAccessedAt : confirmedAt;
		} else if (lastAccessedAt != null) {
			recent = lastAccessedAt;
		} else if (confirmedAt != null) {
			recent = confirmedAt;
		}
		int bonus = 0;
		if (recent != null) {
			Date windowStart = new Date(now.getTime() - decayDays * DAY_MS);
			if (!recent.before(windowStart) && !recent.after(now)) {
				bonus = RECENT_BONUS;
			}
		}
		return base + accessScore + bonus;
	}

	/**
	 * weight 保底线（纯函数，M7.1-M3a，单测直接覆盖）：
	 * 落库 weight = max(calcWeight, weight_floor)。weight_floor 为 null 或 ≤0
	 * 视为不保底（行为与 M7.1 完全一致，回归保障）；公式本身不动。
	 *
	 * @param calcWeight  公式计算值
	 * @param weightFloor 保底线（可空；0=不保底）
	 * @return 保底后的 weight
	 */
	static int applyFloor(int calcWeight, Integer weightFloor) {
		if (weightFloor == null || weightFloor <= 0) {
			return calcWeight;
		}
		return Math.max(calcWeight, weightFloor);
	}

	/**
	 * 衰减截止时间（纯函数）：now - decayDays 天。
	 */
	static Date decayCutoff(Date now, int decayDays) {
		return new Date(now.getTime() - decayDays * DAY_MS);
	}

	/**
	 * 读衰减窗口天数：t_option key=memory_decay_days；空值/非法值/查询异常用默认 90。
	 */
	private int resolveDecayDays() {
		try {
			SiteOption option = siteOptionMapper.selectOne(Wrappers.<SiteOption>lambdaQuery()
					.eq(SiteOption::getOptionKey, "memory_decay_days"));
			if (option != null && option.getOptionValue() != null && !option.getOptionValue().isBlank()) {
				return Integer.parseInt(option.getOptionValue().trim());
			}
		} catch (Exception e) {
			log.warn("读取 t_option memory_decay_days 失败，使用默认 {} 天：{}", DEFAULT_DECAY_DAYS, e.getMessage());
		}
		return DEFAULT_DECAY_DAYS;
	}
}
