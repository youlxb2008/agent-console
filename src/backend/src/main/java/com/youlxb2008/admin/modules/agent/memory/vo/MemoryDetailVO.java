package com.youlxb2008.admin.modules.agent.memory.vo;

import java.util.List;

import lombok.Data;

/**
 * 记忆详情 VO（M7.1，GET /api/agent/memories/{id}）
 * <p>
 * 含 superseded 链回溯（任务书 §2.2）：本条被取代（status=3）时沿 superseded_by
 * 向前找链尾最新版；chain 为途经节点摘要（含本条），latest 为链尾完整条目
 * （本条即链尾时 latest=null）。
 */
@Data
public class MemoryDetailVO {

	/** 本条完整字段（含 contentMd 全文） */
	private MemoryVO memory;

	/** 结论正文（本条全文；冗余字段方便调用方直取） */
	private String contentMd;

	/** superseded 链途经节点摘要（按链序：本条 → … → 链尾） */
	private List<ChainNode> chain;

	/** 链尾最新版（本条未被取代时为 null） */
	private MemoryVO latest;

	/** 链途经节点摘要 */
	@Data
	public static class ChainNode {

		/** 记忆 id */
		private Long id;

		/** 一句话摘要 */
		private String title;

		/** 生命周期 */
		private Integer status;

		/** 取代本条的新记忆 id */
		private Long supersededBy;
	}
}
