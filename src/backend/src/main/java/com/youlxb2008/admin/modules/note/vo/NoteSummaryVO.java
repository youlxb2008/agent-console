package com.youlxb2008.admin.modules.note.vo;

import java.util.Date;
import java.util.List;

import com.youlxb2008.admin.modules.agent.vo.AgentSimpleVO;

import lombok.Data;

/**
 * 门户笔记摘要（v3 迁入 note 域；search / by-tag / recent 三接口共用）
 * <p>
 * 只读索引、不读文件内容；字段白名单：绝不暴露 key_hash/scopes/审计/IP。
 * v3：🔥 移除 agentId/agentName 单值；✨ 增 contributors[]（贡献者多标签）。
 */
@Data
public class NoteSummaryVO {

	/** 对外引用键 n_xxx */
	private String nid;

	/** 笔记标题 */
	private String title;

	/** 目录路径（根目录 = 空串） */
	private String dirPath;

	/** 最近更新（索引 update_time） */
	private Date updateTime;

	/** 贡献者（全部关联 agent；超 3 个前端显示 +N） */
	private List<AgentSimpleVO> contributors;
}
