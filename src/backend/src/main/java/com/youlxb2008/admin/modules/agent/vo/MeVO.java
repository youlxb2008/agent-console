package com.youlxb2008.admin.modules.agent.vo;

import java.util.List;

import lombok.Data;

/**
 * agent 域：/me 响应（契约 api.md §1.7）
 * <p>
 * 供 agent 自检 key 是否有效。
 */
@Data
public class MeVO {

	private Long agentId;

	private String name;

	private String displayName;

	private List<String> scopes;
}
