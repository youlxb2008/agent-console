package com.youlxb2008.admin.modules.agent.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * 管理端：API Key 列表项（契约 api.md §2，GET /keys）
 * <p>
 * 展示 prefix/明文/scopes/lastUsedAt 等识别字段，
 * v4.1 起返回 keyPlain 供历史 key 随时复制（旧数据为 null，前端兜底）；
 * <b>永不返回 key_hash</b>。
 * <p>
 * M5.4-R1：key 改硬删除语义（吊销概念下线），status/revokedAt 不再输出
 * （t_api_key 列结构保留，代码不再写入）。
 */
@Data
public class ApiKeyAdminVO {

	private Long id;

	private Long agentId;

	/** 明文前缀（识别用，非完整 key） */
	private String keyPrefix;

	/** 明文 key（管理端复制用；NULL=旧 key 未存明文，不可复制） */
	private String keyPlain;

	/** 权限列表（JSON 反序列化） */
	private List<String> scopes;

	private Date expiresAt;

	private Date lastUsedAt;

	private Date createTime;
}
