package com.youlxb2008.admin.modules.activity.vo;

import lombok.Data;

/**
 * 会话引用行（M6.2，Mapper 回表中间结果，非对外契约 VO）
 * <p>
 * 复用于两条回表查询：
 * <ul>
 *   <li>{@code findFirstTurnStarted}：每组最早 turn_started（填 sessionKey + summary，作话题摘要）</li>
 *   <li>{@code findLastEventTypes}：每组最后事件（填 sessionKey + eventType，作 stalled 判定）</li>
 * </ul>
 */
@Data
public class ActivitySessionRef {

	/** 会话 key */
	private String sessionKey;

	/** 事件摘要（findFirstTurnStarted 时有值） */
	private String summary;

	/** 事件类型（findLastEventTypes 时有值） */
	private String eventType;
}
