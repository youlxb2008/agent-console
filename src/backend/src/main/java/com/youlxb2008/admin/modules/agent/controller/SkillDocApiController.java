package com.youlxb2008.admin.modules.agent.controller;

import com.youlxb2008.admin.modules.agent.service.SkillDocService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * agent 域文档端点（v4.2 新增）
 * <p>
 * 两个端点均返回 markdown 纯文本（不走 ResultVO 包装），供 AI 助手直接消费：
 * <ul>
 *   <li>{@code GET /api/agent/skill-doc} —— SKILL.md 概览：模块介绍 + API 索引（轻量）</li>
 *   <li>{@code GET /api/agent/api-docs} —— 完整 API 参考：参数表/示例/业务规则（动态拉取，本项目核心差异点）</li>
 * </ul>
 * 鉴权：ApiKeyAuthFilter 已保证 X-API-Key 有效；<b>不标 {@code @RequireScope}</b>——
 * 查文档不应依赖业务 scope（与 /me、/kb/search 同口径，避免"查文档需要文档权限"的循环）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@Tag(name = "agent 域文档", description = "SKILL 概览与 API 参考动态文档（任意有效 key 可调，markdown 纯文本）")
public class SkillDocApiController {

	@Resource
	private SkillDocService skillDocService;

	/**
	 * SKILL.md 概览文档：模块介绍 + API 索引 + 动态拉取指引。
	 * 管理端下载按钮下载的就是这份内容（与 GET /api/agent/admin/skill-doc 输出一致）。
	 */
	@Operation(summary = "Skill 文档（概览：模块介绍 + API 索引；响应为 markdown 纯文本）")
	@GetMapping(value = "/skill-doc", produces = "text/markdown;charset=UTF-8")
	public ResponseEntity<String> skillDoc() {
		return textMarkdown(skillDocService.generateSkillDoc());
	}

	/**
	 * 完整 API 参考文档：每端点参数表 + 请求示例 + 业务规则 + curl。
	 * agent 接入时动态拉取，保证与服务端代码同步（明细不固化在 SKILL.md 里）。
	 */
	@Operation(summary = "API 参考文档（完整：参数/示例/规则；响应为 markdown 纯文本）")
	@GetMapping(value = "/api-docs", produces = "text/markdown;charset=UTF-8")
	public ResponseEntity<String> apiDocs() {
		return textMarkdown(skillDocService.generateApiDocs());
	}

	/** 统一以 text/markdown + UTF-8 输出（浏览器直接预览，curl 拿原文） */
	private ResponseEntity<String> textMarkdown(String body) {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
				.body(body);
	}
}
