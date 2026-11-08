package com.youlxb2008.admin.modules.agent.dto;

import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * 管理端：修改 API Key 请求（契约 api.md §2，PUT /keys/{id}，M5.4 新增）
 * <p>
 * 语义对齐 M5.3 due_date 的 PATCH 范式：<b>null = 不修改</b>；
 * 两项都为 null → 400「至少修改一项」（Service 层校验）。
 * 不支持「清空过期时间改永久」——需要改永久就吊销重发。
 */
@Data
public class KeyUpdateReq {

	/** 权限列表（可选；非空时整体覆盖，与 createKey 一致） */
	private List<String> scopes;

	/** 过期时间（可选；null=不修改；不支持清空改永久） */
	private Date expiresAt;
}
