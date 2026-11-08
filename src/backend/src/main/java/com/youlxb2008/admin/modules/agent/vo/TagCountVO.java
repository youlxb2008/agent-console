package com.youlxb2008.admin.modules.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 标签聚合项（契约 api.md §3，M3.8 起 GET /api/portal/notebooks/{id}/notes/tags）
 * <p>
 * {tag, count}；count 降序再 tag 字典序。
 */
@Data
@AllArgsConstructor
public class TagCountVO {

	/** 标签名（展开时 trim、去空、去重） */
	private String tag;

	/** 引用次数 */
	private long count;
}
