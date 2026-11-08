package com.youlxb2008.admin.modules.agent.controller;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.dto.AgentCreateReq;
import com.youlxb2008.admin.modules.agent.dto.AgentUpdateReq;
import com.youlxb2008.admin.modules.agent.dto.KeyCreateReq;
import com.youlxb2008.admin.modules.agent.dto.KeyUpdateReq;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.entity.ApiKey;
import com.youlxb2008.admin.modules.agent.entity.AuditLog;
import com.youlxb2008.admin.modules.agent.service.AgentResourceService;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.service.SkillDocService;
import com.youlxb2008.admin.modules.agent.vo.AgentAdminVO;
import com.youlxb2008.admin.modules.agent.vo.ApiKeyAdminVO;
import com.youlxb2008.admin.modules.notebook.dto.AgentNotebookLinkReq;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.service.AgentNotebookService;
import com.youlxb2008.admin.modules.notebook.service.NotebookService;
import com.youlxb2008.admin.modules.notebook.vo.NotebookVO;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.jwt.context.UserContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理端 agent 域 Controller（契约 api.md §2；v3：笔记三接口迁出 → NoteAdminController）
 * <p>
 * 鉴权：JWT（{@code /api/agent/admin/**} 已在 ApiKeyAuthFilter/RateLimitFilter 排除，走
 * 管理端 JWT）
 * + {@code @RequiresPermissions}（权限码已在 M1.2a-1 入库，super_admin 已显式授权）。
 * v3：agent 列表增 noteCount（t_agent_resource 聚合，无主化后的关联笔记数）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/admin")
@Tag(name = "管理端 agent 域", description = "JWT + @RequiresPermissions，管理 agent/key/审计")
public class AgentAdminController {

	@Autowired
	private AgentService agentService;

	@Autowired
	private ApiKeyService apiKeyService;

	@Autowired
	private AuditService auditService;

	@Autowired
	private AgentResourceService agentResourceService;

	/** v3.5：agent↔笔记本关联（所属笔记本 tab / 全量覆盖） */
	@Autowired
	private AgentNotebookService agentNotebookService;

	/** v3.5：笔记本档案（notebookNames 回显） */
	@Autowired
	private NotebookService notebookService;

	@Autowired
	private ObjectMapper objectMapper;

	/** v4.2：Skill 文档生成（管理端下载按钮 → GET /skill-doc，输出与 agent 域开放端点一致） */
	@Autowired
	private SkillDocService skillDocService;

	// ------------------------------------------------------------
	// agent 管理（agent:agent:list / save / delete）
	// ------------------------------------------------------------

	/**
	 * 2.x GET /agents — agent 分页列表（含 key 数、最近使用）
	 */
	@Operation(summary = "agent 分页列表（含 key 数、最近使用）")
	@RequiresPermissions("agent:agent:list")
	@GetMapping("/agents")
	public ResultVO listAgents(@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) String keyword) {
		IPage<Agent> result = agentService.pageAgents(page, size, keyword);
		IPage<AgentAdminVO> voPage = result.convert(this::toAgentAdminVO);
		return ResultVO.getSuccessVO("查询成功").setData(voPage);
	}

	/**
	 * 2.x POST /agents — 创建 agent（name 唯一；status 缺省 0=suspended 待审批）
	 */
	@Operation(summary = "创建 agent（name 唯一）")
	@RequiresPermissions("agent:agent:save")
	@PostMapping("/agents")
	public ResultVO createAgent(@RequestBody @Validated AgentCreateReq req, HttpServletRequest request) {
		User user = currentUser();
		Agent agent = agentService.createAgent(req.getName(), req.getDisplayName(), req.getAvatar(),
				req.getDescription(), req.getStatus(), user != null ? user.getId() : null);
		audit(user, "agent.create", "/api/agent/admin/agents", "success", request);
		Map<String, Object> data = new HashMap<>();
		data.put("id", agent.getId());
		data.put("name", agent.getName());
		data.put("status", agent.getStatus());
		return ResultVO.getSuccessVO("创建成功").setData(data);
	}

	/**
	 * 2.x PUT /agents/{id} — 更新 agent（display_name/avatar/description/status）
	 */
	@Operation(summary = "更新 agent（display_name/avatar/description/status）")
	@RequiresPermissions("agent:agent:save")
	@PutMapping("/agents/{id}")
	public ResultVO updateAgent(@PathVariable Long id, @RequestBody AgentUpdateReq req, HttpServletRequest request) {
		User user = currentUser();
		Agent agent = agentService.updateAgent(id, req.getDisplayName(), req.getAvatar(), req.getDescription(),
				req.getStatus());
		audit(user, "agent.update", "/api/agent/admin/agents/" + id, "success", request);
		Map<String, Object> data = new HashMap<>();
		data.put("id", agent.getId());
		data.put("status", agent.getStatus());
		return ResultVO.getSuccessVO("更新成功").setData(data);
	}

	/**
	 * 2.x DELETE /agents/{id} — 已下线占位（M5.4-R1：agent 不支持删除）
	 * <p>
	 * 删除语义修正：agent 只有 吊销/启用（PUT /agents/{id} 的 status 0/1 切换，可随时恢复），
	 * 不再提供删除。占位 handler 返回 404，避免同路径资源存在时 Spring 默认 405。
	 */
	@Operation(summary = "已下线：agent 不支持删除（吊销走 PUT /agents/{id} 的 status=0）")
	@DeleteMapping("/agents/{id}")
	public ResultVO deleteAgent(@PathVariable Long id) {
		throw new NotFoundException("接口已下线：agent 不支持删除，请使用吊销（status=0）");
	}

	// ------------------------------------------------------------
	// 所属笔记本管理（v3.5，notebook:agent:list / notebook:agent:link）
	// ------------------------------------------------------------

	/**
	 * GET /agents/{id}/notebooks — 该 agent 关联的笔记本列表（agent 管理页「所属笔记本」tab 数据源）
	 * <p>
	 * 返回 NotebookVO 列表（未软删笔记本；含 noteCount/agentCount 聚合）。
	 */
	@Operation(summary = "agent 关联的笔记本列表（「所属笔记本」tab 数据源）")
	@RequiresPermissions("notebook:agent:list")
	@GetMapping("/agents/{id}/notebooks")
	public ResultVO agentNotebooks(@PathVariable Long id) {
		if (agentService.getById(id) == null) {
			return ResultVO.getFailedVO("agent 不存在");
		}
		List<Long> nbIds = agentNotebookService.listNotebookIdsByAgent(id);
		List<NotebookVO> list = nbIds.isEmpty() ? List.of()
				: notebookService.listByIds(nbIds).stream()
						.filter(nb -> nb != null)
						.map(this::toNotebookVO)
						.collect(Collectors.toList());
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * PUT /agents/{id}/notebooks — 该 agent 关联笔记本全量覆盖（目标集合即最终态，幂等 replace）
	 * <p>
	 * 不存在的 notebookId（含已软删）忽略；空数组=清空该 agent 全部笔记本关联。
	 */
	@Operation(summary = "agent 关联笔记本全量覆盖（body=notebookIds[]，幂等 replace）")
	@RequiresPermissions("notebook:agent:link")
	@PutMapping("/agents/{id}/notebooks")
	public ResultVO updateAgentNotebooks(@PathVariable Long id,
			@RequestBody(required = false) AgentNotebookLinkReq req, HttpServletRequest request) {
		if (agentService.getById(id) == null) {
			return ResultVO.getFailedVO("agent 不存在");
		}
		User user = currentUser();
		agentNotebookService.replaceByAgent(id,
				req != null ? req.getNotebookIds() : null,
				user != null ? user.getUsername() : "unknown");
		audit(user, "notebook.agent.link", "/api/agent/admin/agents/" + id + "/notebooks", "success", request);
		return ResultVO.getSuccessVO("关联保存成功");
	}

	/**
	 * DELETE /agents/{id}/notebooks/{notebookId} — 单条解除该 agent 与某笔记本的关联
	 */
	@Operation(summary = "单条解除 agent 与笔记本关联")
	@RequiresPermissions("notebook:agent:link")
	@DeleteMapping("/agents/{id}/notebooks/{notebookId}")
	public ResultVO unlinkAgentNotebook(@PathVariable Long id, @PathVariable Long notebookId,
			HttpServletRequest request) {
		if (agentService.getById(id) == null) {
			return ResultVO.getFailedVO("agent 不存在");
		}
		User user = currentUser();
		agentNotebookService.unlink(id, notebookId);
		audit(user, "notebook.agent.unlink", "/api/agent/admin/agents/" + id + "/notebooks/" + notebookId,
				"success", request);
		return ResultVO.getSuccessVO("解除关联成功");
	}

	// ------------------------------------------------------------
	// key 管理（agent:key:list / create / revoke）
	// ------------------------------------------------------------

	/**
	 * 2.x GET /keys?agentId= — key 列表（v4.1 起含 keyPlain 明文，永不返回 hash）
	 */
	@Operation(summary = "key 列表（展示 prefix/明文/scopes，永不返回 hash）")
	@RequiresPermissions("agent:key:list")
	@GetMapping("/keys")
	public ResultVO listKeys(@RequestParam(required = false) Long agentId) {
		List<ApiKeyAdminVO> list = apiKeyService.listByAgent(agentId).stream()
				.map(this::toApiKeyAdminVO)
				.collect(Collectors.toList());
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * 2.x POST /keys — 生成 key（明文落库 + 响应返回，审计 key.create）
	 */
	@Operation(summary = "生成 API Key（明文落库，管理端可随时查看/复制）")
	@RequiresPermissions("agent:key:create")
	@PostMapping("/keys")
	public ResultVO createKey(@RequestBody @Validated KeyCreateReq req, HttpServletRequest request) {
		User user = currentUser();
		ApiKeyService.GeneratedKey generated = apiKeyService.createKey(req.getAgentId(), req.getScopes(),
				req.getExpiresAt());
		audit(user, "key.create", "/api/agent/admin/keys", "success", request);
		Map<String, Object> data = new HashMap<>();
		data.put("keyId", generated.getKeyId());
		data.put("key", generated.getPlainKey());
		data.put("keyPrefix", generated.getKeyPrefix());
		data.put("scopes", req.getScopes());
		data.put("expiresAt", req.getExpiresAt());
		return ResultVO.getSuccessVO("创建成功").setData(data);
	}

	/**
	 * 2.x PUT /keys/{id} — 修改 key（scopes/expiresAt，null=不修改；审计 key.update；M5.4 新增）
	 * <p>
	 * 权限码复用 agent:key:create（改 scopes ≈ 重新授权，权限级别与生成相同；不新增权限码）。
	 */
	@Operation(summary = "修改 API Key（scopes/expiresAt，null=不修改；已吊销 400）")
	@RequiresPermissions("agent:key:create")
	@PutMapping("/keys/{id}")
	public ResultVO updateKey(@PathVariable Long id, @RequestBody KeyUpdateReq req, HttpServletRequest request) {
		User user = currentUser();
		apiKeyService.updateKey(id, req);
		audit(user, "key.update", "/api/agent/admin/keys/" + id, "success", request);
		Map<String, Object> data = new HashMap<>();
		data.put("id", id);
		return ResultVO.getSuccessVO("更新成功").setData(data);
	}

	/**
	 * 2.x DELETE /keys/{id} — 物理删除 key（M5.4-R1 新增，硬删除不可恢复）
	 * <p>
	 * 删除语义修正：key 失效一律走物理删除（吊销概念整体下线）；
	 * 权限码复用 agent:key:revoke（不新增），审计 action=key.delete。
	 */
	@Operation(summary = "删除 API Key（物理删除，不可恢复）")
	@RequiresPermissions("agent:key:revoke")
	@DeleteMapping("/keys/{id}")
	public ResultVO deleteKey(@PathVariable Long id, HttpServletRequest request) {
		User user = currentUser();
		apiKeyService.deleteKey(id);
		audit(user, "key.delete", "/api/agent/admin/keys/" + id, "success", request);
		Map<String, Object> data = new HashMap<>();
		data.put("id", id);
		return ResultVO.getSuccessVO("删除成功").setData(data);
	}

	/**
	 * 2.x PUT /keys/{id}/revoke — 已下线占位（M5.4-R1：吊销概念下线，key 删除走 DELETE /keys/{id}）
	 * <p>
	 * 占位 handler 返回 404，避免同路径资源存在时 Spring 默认 405。
	 */
	@Operation(summary = "已下线：key 删除请使用 DELETE /keys/{id}")
	@PutMapping("/keys/{id}/revoke")
	public ResultVO revokeKey(@PathVariable Long id) {
		throw new NotFoundException("接口已下线：key 删除请使用 DELETE /keys/{id}");
	}

	/**
	 * 2.x GET /skill-doc — 下载 Skill 文档（v4.2 新增）
	 * <p>
	 * 管理端 Key 面板「下载 Skill 文档」按钮数据源（JWT，无需先创建 key）。
	 * 输出与 agent 域开放端点 {@code GET /api/agent/skill-doc} 完全一致（同一 SkillDocService），
	 * 响应为 markdown 纯文本（不走 ResultVO 包装），前端下载后命名为 SKILL.md 提供给 AI 助手。
	 */
	@Operation(summary = "下载 Skill 文档（概览：模块介绍 + API 索引；markdown 纯文本）")
	@RequiresPermissions("agent:key:list")
	@GetMapping(value = "/skill-doc", produces = "text/markdown;charset=UTF-8")
	public ResponseEntity<String> downloadSkillDoc() {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
				.body(skillDocService.generateSkillDoc());
	}

	// ------------------------------------------------------------
	// 审计查询（agent:audit:list）
	// ------------------------------------------------------------

	/**
	 * 2.x GET /audit — 审计分页查询（principal/action/result/时间范围）
	 */
	@Operation(summary = "审计日志分页查询")
	@RequiresPermissions("agent:audit:list")
	@GetMapping("/audit")
	public ResultVO listAudit(@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) String principal,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String result,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date startTime,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date endTime) {
		IPage<AuditLog> resultPage = auditService.pageQuery(page, size, principal, action, result, startTime, endTime);
		return ResultVO.getSuccessVO("查询成功").setData(resultPage);
	}

	// ------------------------------------------------------------
	// 私有转换/工具
	// ------------------------------------------------------------

	private AgentAdminVO toAgentAdminVO(Agent agent) {
		AgentAdminVO vo = new AgentAdminVO();
		vo.setId(agent.getId());
		vo.setName(agent.getName());
		vo.setDisplayName(agent.getDisplayName());
		// v4.3：头像 URL 回显（管理端表单预览 + 编辑回显）
		vo.setAvatar(agent.getAvatar());
		vo.setDescription(agent.getDescription());
		vo.setStatus(agent.getStatus());
		vo.setKeyCount(apiKeyService.countActive(agent.getId()));
		vo.setLastUsedAt(apiKeyService.latestUsedAt(agent.getId()));
		// v3：关联笔记数（无主化后取代"作者笔记数"语义，来自 t_agent_resource 聚合）
		vo.setNoteCount(agentResourceService.countByAgent(agent.getId()));
		// v3.5：关联笔记本 id/名（agent 详情「所属笔记本」回显；无关联时避免 listByIds([]) 生成非法 IN ()）
		List<Long> nbIds = agentNotebookService.listNotebookIdsByAgent(agent.getId());
		vo.setNotebookIds(nbIds);
		vo.setNotebookNames(nbIds.isEmpty() ? List.of()
				: notebookService.listByIds(nbIds).stream().map(Notebook::getName).toList());
		vo.setCreateTime(agent.getCreateTime());
		return vo;
	}

	private ApiKeyAdminVO toApiKeyAdminVO(ApiKey key) {
		ApiKeyAdminVO vo = new ApiKeyAdminVO();
		vo.setId(key.getId());
		vo.setAgentId(key.getAgentId());
		vo.setKeyPrefix(key.getKeyPrefix());
		// v4.1：明文回传给管理端（历史 key 可复制；旧数据为 null，前端兜底展示）
		vo.setKeyPlain(key.getKeyPlain());
		vo.setScopes(fromJsonScopes(key.getScopes()));
		vo.setExpiresAt(key.getExpiresAt());
		vo.setLastUsedAt(key.getLastUsedAt());
		vo.setCreateTime(key.getCreateTime());
		return vo;
	}

	private List<String> fromJsonScopes(String json) {
		// 复用 ApiKeyService.parseScopes：兼容 H2 对 JSON 列的双重编码与 MySQL 正常编码
		return apiKeyService.parseScopes(json);
	}

	/** Notebook → NotebookVO（agent「所属笔记本」tab 列表项，聚合 noteCount/agentCount） */
	private NotebookVO toNotebookVO(Notebook nb) {
		NotebookVO vo = new NotebookVO();
		vo.setId(nb.getId());
		vo.setName(nb.getName());
		vo.setDescription(nb.getDescription());
		vo.setCover(nb.getCover());
		vo.setStatus(nb.getStatus());
		vo.setNoteCount(notebookService.countNotes(nb.getId()));
		vo.setAgentCount(agentNotebookService.countByNotebook(nb.getId()));
		vo.setCreateTime(nb.getCreateTime());
		vo.setUpdateTime(nb.getUpdateTime());
		return vo;
	}

	private User currentUser() {
		return UserContext.getUser();
	}

	private void audit(User user, String action, String resource, String result, HttpServletRequest request) {
		auditService.append(user != null ? user.getUsername() : "unknown", action, resource,
				summary(request), result, getClientIp(request));
	}

	private String getClientIp(HttpServletRequest request) {
		String ip = request.getHeader("X-Forwarded-For");
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("X-Real-IP");
		}
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		return ip;
	}

	private String summary(HttpServletRequest request) {
		return "{\"method\":\"" + request.getMethod() + "\",\"path\":\"" + request.getRequestURI() + "\"}";
	}
}
