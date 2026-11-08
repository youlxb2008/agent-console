package com.youlxb2008.admin.modules.ledger.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 台账详情 VO（M4.2，agent 域/管理端/门户共用）
 * <p>
 * 列表字段 + 正文 body（详情接口读正文：inline 优先，否则读 md）。
 * md 文件缺失（孤儿索引行）→ 404（LedgerService.getDetail 兜底）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LedgerDetailVO extends LedgerVO {

	/** 正文全文（body_inline 优先；>500 字时读 md 文件） */
	private String body;
}
