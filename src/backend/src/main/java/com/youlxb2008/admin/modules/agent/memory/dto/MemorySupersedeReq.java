package com.youlxb2008.admin.modules.agent.memory.dto;

import lombok.Data;

/**
 * agent 域：取代记忆请求（M7.1，POST /api/agent/memories/{id}/supersede）
 * <p>
 * 二选一按 body 内容判断（任务书 §2.2）：
 * <ul>
 *   <li>newMemoryId 非空 → 关联已有新条（须存在/属于本人/status=1）</li>
 *   <li>newMemoryId 为空且带新记忆字段（title+contentMd 必填）→ 创建新条并关联</li>
 * </ul>
 * 两者都缺 → 400。
 */
@Data
public class MemorySupersedeReq {

	/** 已有新记忆 id（二选一路径 A） */
	private Long newMemoryId;

	/* ---- 以下为路径 B：直接带新记忆字段（语义同 MemoryCreateReq） ---- */

	/** 记忆类型（路径 B 必填） */
	private String memType;

	/** 来源（路径 B 必填） */
	private String source;

	/** 一句话摘要（路径 B 必填） */
	private String title;

	/** 结论正文（路径 B 必填） */
	private String contentMd;

	/** 逗号分隔标签（可空） */
	private String tags;

	/** AC 笔记深链指针（可空） */
	private String refUrl;

	/** 是否路径 B（带新记忆字段创建） */
	public boolean isCreateNew() {
		return newMemoryId == null && (title != null || contentMd != null);
	}
}
