package com.youlxb2008.admin.modules.agent.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.memory.service.MemoryService;
import com.youlxb2008.admin.modules.agent.service.AgentResourceService;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.vo.AgentSimpleVO;
import com.youlxb2008.admin.modules.agent.vo.PortalAgentVO;
import com.youlxb2008.admin.modules.agent.vo.PortalSiteVO;
import com.youlxb2008.admin.modules.agent.vo.TagCountVO;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;
import com.youlxb2008.admin.modules.note.vo.NoteContentVO;
import com.youlxb2008.admin.modules.note.vo.NoteSummaryVO;
import com.youlxb2008.admin.modules.notebook.service.NotebookService;
import com.youlxb2008.admin.modules.notebook.vo.NotebookVO;
import com.youlxb2008.admin.modules.objective.service.ObjectiveService;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveDetailVO;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveStatusCountVO;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveVO;
import com.youlxb2008.admin.modules.ledger.service.LedgerService;
import com.youlxb2008.admin.modules.ledger.vo.LedgerDetailVO;
import com.youlxb2008.admin.modules.ledger.vo.LedgerVO;
import com.youlxb2008.admin.modules.system.service.SiteOptionService;
import com.youlxb2008.admin.modules.activity.service.ActivityService;
import com.youlxb2008.admin.modules.activity.vo.ActivityCurrentVO;
import com.youlxb2008.admin.modules.activity.vo.ActivityDailyStatVO;
import com.youlxb2008.admin.modules.activity.vo.ActivityVO;
import com.youlxb2008.admin.modules.todo.service.TodoService;
import com.youlxb2008.admin.modules.todo.vo.TodoStatusCountVO;
import com.youlxb2008.admin.modules.todo.vo.TodoVO;
import com.youlxb2008.admin.utils.ResultVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 门户域 Controller（v3 笔记本化：聚合全部改走 t_agent_resource）
 * <p>
 * 鉴权：public=匿名。限流由 AgentFilterConfig 的 RateLimitFilter 覆盖
 * （/api/portal/*，600/min，principal=IP）。
 * <b>字段白名单</b>：绝不暴露 key_hash/scopes/审计/IP。
 * <p>
 * v3 语义（设计 §5.4，M3.8 RESTful 化更新）：
 * <ul>
 * <li>/agents：noteCount/lastNoteAt 改关联表聚合；响应加 notebookName</li>
 * <li>/notes/tree?agentId=：🔥 M3.8 下线 404（agentId 已弃用；等价能力
 * /notebooks/{id}/notes/tree）</li>
 * <li>/notes/{nid}：仅 nid 路径定位（🔥 移除 ?nid= 与 agentId+path 兜底，文件已扁平）；响应加
 * contributors</li>
 * <li>search/tags/by-tag/recent：M3.8 起 notebookId 走路径
 * /notebooks/{id}/notes/...，notebook 不存在/已软删 → 404</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/portal")
@Tag(name = "门户域", description = "匿名只读：站点信息/笔记本概览/笔记树与内容")
public class PortalController {

	/** t_option 白名单键 */
	private static final String OPT_SITE_TITLE = "site_title";
	private static final String OPT_SITE_DESCRIPTION = "site_description";
	/** 门户质感方案（管理平台站点设置配置；值域 aurora / relief / grid，由前端兜底校验） */
	private static final String OPT_PORTAL_TEXTURE = "portal_texture";

	/** 缺省值（t_option 未配置时使用） */
	private static final String DEFAULT_SITE_TITLE = "Agent Console";
	private static final String DEFAULT_SITE_DESCRIPTION = "Agent 笔记门户";
	private static final String DEFAULT_PORTAL_TEXTURE = "aurora";

	/** 门户流式分页：每页默认条数 / 单页上限（objectives/ledger 列表分页共用） */
	private static final long PAGE_DEFAULT_SIZE = 20L;
	private static final long PAGE_MAX_SIZE = 50L;

	/** M1 门户包：/bus 消息流转分页口径（任务书 §1：默认 20、上限 100） */
	private static final long BUS_PAGE_DEFAULT_SIZE = 20L;
	private static final long BUS_PAGE_MAX_SIZE = 100L;

	/** v2.1：keyword 长度上限（契约 §3） */
	private static final int MAX_KEYWORD_LEN = 100;
	/** v2.1：tag 长度上限（契约 §3） */
	private static final int MAX_TAG_LEN = 50;
	/** v2.1：search 缺省 limit */
	private static final int SEARCH_DEFAULT_LIMIT = 20;
	/** v2.1：recent 缺省 limit */
	private static final int RECENT_DEFAULT_LIMIT = 10;

	@Autowired
	private AgentService agentService;

	@Autowired
	private AgentResourceService agentResourceService;

	@Autowired
	private NoteMetaService noteMetaService;

	/** v3.5：门户笔记本概览聚合 */
	@Autowired
	private NotebookService notebookService;

	@Autowired
	private SiteOptionService siteOptionService;

	/** M4.1：门户目标只读（匿名） */
	@Autowired
	private ObjectiveService objectiveService;

	/** M4.2：门户台账只读（匿名，单 agent 流 + 全部聚合流） */
	@Autowired
	private LedgerService ledgerService;

	/** M5.2：门户待办只读（匿名，单 agent 分页） */
	@Autowired
	private TodoService todoService;

	/** M6.1：门户活动流只读（匿名，单 agent 倒序流 + current 聚合） */
	@Autowired
	private ActivityService activityService;

	/** M7.1-M3：门户记忆只读（匿名，仅活跃记忆） */
	@Autowired
	private MemoryService memoryService;

	/** M1 门户包：消息总线只读（匿名；VO 字段白名单见 PortalBusMessageVO） */
	@Autowired
	private BusMessageService busMessageService;

	/**
	 * 3.x GET /site — 站点信息（t_option 白名单键，缺省给默认值）
	 */
	@Operation(summary = "站点信息（白名单键）")
	@GetMapping("/site")
	public ResultVO site() {
		Map<String, String> options = siteOptionService.getAllOptions();
		PortalSiteVO vo = new PortalSiteVO();
		vo.setSiteTitle(options.getOrDefault(OPT_SITE_TITLE, DEFAULT_SITE_TITLE));
		vo.setSiteDescription(options.getOrDefault(OPT_SITE_DESCRIPTION, DEFAULT_SITE_DESCRIPTION));
		vo.setPortalTexture(options.getOrDefault(OPT_PORTAL_TEXTURE, DEFAULT_PORTAL_TEXTURE));
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * 3.x GET /agents — active 笔记本概览（v3：聚合改走 t_agent_resource；响应加
	 * notebookName。M5.4-R2：移除 contributorCount——协作者是笔记本维度概念
	 * （t_agent_notebook 授权关系），agent 维度无协作者语义）
	 */
	@Operation(summary = "active 笔记本概览（关联表聚合 + notebookName）")
	@GetMapping("/agents")
	public ResultVO agents() {
		List<Agent> activeAgents = agentService.list(
				Wrappers.<Agent>lambdaQuery().eq(Agent::getStatus, 1).orderByAsc(Agent::getCreateTime));
		List<PortalAgentVO> list = activeAgents.stream().map(this::toPortalAgentVO).collect(Collectors.toList());
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * 3.5 新增 GET /notebooks — 未软删笔记本概览（v4.0，门户首页卡片列表）
	 * <p>
	 * 全部 active 笔记本 + noteCount/lastNoteAt/agentCount 聚合；全部门户公开（D3）。
	 */
	@Operation(summary = "未软删笔记本概览（门户首页卡片列表）")
	@GetMapping("/notebooks")
	public ResultVO notebooks() {
		List<NotebookVO> list = notebookService.portalNotebooks();
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * 3.5 新增 GET /notebooks/{id}/notes/tree — 单笔记本下目录树（门户选中笔记本后展示）
	 * <p>
	 * 直接 WHERE notebook_id=id 过滤；不满足不存在/已软删 → 空树。
	 */
	@Operation(summary = "单笔记本下目录树（门户选中笔记本后展示）")
	@GetMapping("/notebooks/{id}/notes/tree")
	public ResultVO notebookNotesTree(@PathVariable Long id) {
		if (notebookService.getByIdOrNull(id) == null) {
			return ResultVO.getSuccessVO("查询成功").setData(List.of());
		}
		List<NoteMeta> notes = noteMetaService.listByNotebook(id);
		noteMetaService.fillNotebookNames(notes);
		return ResultVO.getSuccessVO("查询成功")
				.setData(noteMetaService.buildTree(notes));
	}

	/**
	 * M3.8 GET /notes/{nid} — 读单个笔记（v3：仅 nid 定位；RESTful：nid 走路径）
	 * <p>
	 * 孤儿索引行（有行无文件）→ 404；🔥 移除 v2 的 agentId+path 兜底（文件已扁平，path 无意义）。
	 * nid 格式强校验 ^n_[0-9A-Za-z]{10}$（NoteStorageManager 读侧 fail-closed，非规范 → 404
	 * 防注入/防枚举）。
	 * 响应含 contributors（全部关联 agent，详情页贡献者 chips 数据源）。
	 */
	@Operation(summary = "读笔记内容（nid 路径定位；含 contributors 贡献者列表）")
	@GetMapping("/notes/{nid}")
	public ResultVO noteContent(@PathVariable String nid) {
		return ResultVO.getSuccessVO("查询成功").setData(noteStorageManager.readContent(nid));
	}

	/**
	 * GET /notes/{nid}/raw — 笔记 markdown 原文（text/markdown 纯文本，无 ResultVO JSON 包装）
	 * <p>
	 * 用途：门户「复制 MD 链接」——把该 URL 直接给智能体/脚本 fetch，
	 * 拿到的即纯 markdown 正文，无需解析 JSON 包裹（与 /notes/{nid} 同数据源同权限，匿名只读）。
	 * nid 非法/笔记不存在 → 404（readContent 读侧 fail-closed，异常统一走全局 JSON 错误体）。
	 */
	@Operation(summary = "笔记 markdown 原文（text/markdown 纯文本，供智能体直接抓取）")
	@GetMapping(value = "/notes/{nid}/raw", produces = "text/markdown;charset=UTF-8")
	public org.springframework.http.ResponseEntity<String> noteRawMarkdown(@PathVariable String nid) {
		NoteContentVO vo = noteStorageManager.readContent(nid);
		// 正文直接作为响应体；contentMd 理论上恒非空（读文件真相源），空串兜底防 NPE
		String md = vo.getContentMd() == null ? "" : vo.getContentMd();
		return org.springframework.http.ResponseEntity.ok()
				.header("Content-Disposition", "inline; filename=\"" + vo.getNid() + ".md\"")
				.body(md);
	}

	/**
	 * M4.1 GET /api/portal/agents/{agentId}/objectives — 某 agent 目标 todolist（匿名只读）
	 * <p>
	 * 含 progress/status/period/targetDate；agent 不存在 → 404；仅
	 * is_deleted=0（fail-closed）。
	 */
	@Operation(summary = "某 agent 目标 todolist（进度条/状态/周期/截止）")
	@GetMapping("/agents/{agentId}/objectives")
	public ResultVO agentObjectives(@PathVariable Long agentId) {
		List<ObjectiveVO> list = objectiveService.portalListByAgent(agentId);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * GET /api/portal/agents/{agentId}/objectives/page — 某 agent 目标 todolist
	 * 分页（匿名只读）
	 * <p>
	 * 流式加载用：page 从 1 起；size 默认 {@link #PAGE_DEFAULT_SIZE}、钳制 ≤
	 * {@link #PAGE_MAX_SIZE}；status 可选筛选（0未开始/1进行中/2已完成/3已放弃，缺省全部）；
	 * agent 不存在 → 404。IPage 由 setData 自动转 BPage。
	 */
	@Operation(summary = "某 agent 目标 todolist 分页（流式加载；status 筛选）")
	@GetMapping("/agents/{agentId}/objectives/page")
	public ResultVO agentObjectivesPage(@PathVariable Long agentId,
			@RequestParam(required = false) Integer status,
			@RequestParam(required = false, defaultValue = "1") long page,
			@RequestParam(required = false, defaultValue = "" + PAGE_DEFAULT_SIZE) long size) {
		long clamped = Math.min(Math.max(size, 1), PAGE_MAX_SIZE);
		return ResultVO.getSuccessVO("查询成功")
				.setData(objectiveService.portalPageByAgent(agentId, status, page, clamped));
	}

	/**
	 * GET /api/portal/agents/{agentId}/objectives/status-counts — 某 agent 目标状态计数
	 * 分页（匿名只读）
	 * <p>
	 * 全量口径（不受 status 筛选影响）：详情页筛选胶囊与目标 Tab 角标数据源。
	 * 前端旧实现基于已加载条目统计，切筛选后计数漂移；改由服务端 group by 一次返回。
	 * agent 不存在 → 404；仅统计未软删。
	 */
	@Operation(summary = "某 agent 目标状态计数（全量；筛选胶囊/Tab 角标用）")
	@GetMapping("/agents/{agentId}/objectives/status-counts")
	public ResultVO agentObjectiveStatusCounts(@PathVariable Long agentId) {
		return ResultVO.getSuccessVO("查询成功")
				.setData(objectiveService.portalStatusCountsByAgent(agentId));
	}

	/**
	 * GET /api/portal/agents/{agentId}/notebooks — 某 agent 名下已关联笔记本概览（匿名只读）
	 * <p>
	 * 智能体详情页「笔记本」Tab 数据源：noteCount/lastNoteAt/agentCount 聚合
	 * （NotebookService.portalNotebooksByAgent，与门户笔记本概览同一 VO 与排序）。
	 * agent 不存在 → 404（与 objectives/ledger 同语义）；无关联返回空列表；
	 * 已软删/hidden 笔记本自动过滤（软删走 @TableLogic，hidden 走 status 过滤）。
	 */
	@Operation(summary = "某 agent 名下已关联笔记本概览（详情页笔记本 Tab）")
	@GetMapping("/agents/{agentId}/notebooks")
	public ResultVO agentNotebooks(@PathVariable Long agentId) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("智能体不存在");
		}
		List<NotebookVO> list = notebookService.portalNotebooksByAgent(agentId);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * M4.1 GET /api/portal/objectives/{oid} — 目标详情（匿名只读；md 全文 + checkbox 子项）
	 * <p>
	 * 目标不存在/已软删 → 404；oid 格式强校验（^o_[0-9A-Za-z]{10}$，fail-closed）。
	 */
	@Operation(summary = "目标详情（md 全文 + checkbox 子项解析）")
	@GetMapping("/objectives/{oid}")
	public ResultVO objectiveDetail(@PathVariable String oid) {
		ObjectiveDetailVO vo = objectiveService.portalDetail(oid);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * M4.2 GET /api/portal/agents/{agentId}/ledger — 某 agent 台账流（匿名只读，create_time
	 * 倒序）
	 * <p>
	 * agent 不存在 → 404；只读索引不读文件（列表/流接口不返回正文，详情另行读取）。
	 */
	@Operation(summary = "某 agent 台账流（create_time 倒序，只读索引）")
	@GetMapping("/agents/{agentId}/ledger")
	public ResultVO agentLedger(@PathVariable Long agentId) {
		List<LedgerVO> list = ledgerService.portalListByAgent(agentId);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * GET /api/portal/agents/{agentId}/ledger/page — 某 agent 台账流分页（匿名只读）
	 * <p>
	 * 流式加载用：page 从 1 起；size 默认 {@link #PAGE_DEFAULT_SIZE}、钳制 ≤
	 * {@link #PAGE_MAX_SIZE}；agent 不存在 → 404。IPage 由 setData 自动转 BPage。
	 */
	@Operation(summary = "某 agent 台账流分页（流式加载，只读索引）")
	@GetMapping("/agents/{agentId}/ledger/page")
	public ResultVO agentLedgerPage(@PathVariable Long agentId,
			@RequestParam(required = false, defaultValue = "1") long page,
			@RequestParam(required = false, defaultValue = "" + PAGE_DEFAULT_SIZE) long size) {
		long clamped = Math.min(Math.max(size, 1), PAGE_MAX_SIZE);
		return ResultVO.getSuccessVO("查询成功")
				.setData(ledgerService.portalPageByAgent(agentId, page, clamped));
	}

	/**
	 * M4.2 GET /api/portal/ledger — 全部 agent 台账聚合流（匿名只读，create_time 倒序，含 agentName）
	 */
	@Operation(summary = "全部 agent 台账聚合流（倒序，含 agentName）")
	@GetMapping("/ledger")
	public ResultVO ledgerAggregate() {
		List<LedgerVO> list = ledgerService.portalAggregate();
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * GET /api/portal/ledger/page — 全部 agent 台账聚合流分页（匿名只读，create_time 倒序，含
	 * agentName）
	 * <p>
	 * 流式加载用：page 从 1 起；size 默认 {@link #PAGE_DEFAULT_SIZE}、钳制 ≤
	 * {@link #PAGE_MAX_SIZE}。IPage 由 setData 自动转 BPage。
	 */
	@Operation(summary = "全部 agent 台账聚合流分页（流式加载，含 agentName）")
	@GetMapping("/ledger/page")
	public ResultVO ledgerAggregatePage(
			@RequestParam(required = false, defaultValue = "1") long page,
			@RequestParam(required = false, defaultValue = "" + PAGE_DEFAULT_SIZE) long size) {
		long clamped = Math.min(Math.max(size, 1), PAGE_MAX_SIZE);
		return ResultVO.getSuccessVO("查询成功")
				.setData(ledgerService.portalPageAggregate(page, clamped));
	}

	/**
	 * M5.2 GET /api/portal/agents/{agentId}/todos — 某 agent 待办分页（匿名只读）
	 * <p>
	 * 排序同 agent 域（M5.3：有截止日期在前按日期升序，无日期按 priority DESC, id ASC）；status 可选筛选；
	 * page 从 1 起；size 默认 {@link #PAGE_DEFAULT_SIZE}、钳制 ≤ {@link #PAGE_MAX_SIZE}；
	 * agent 不存在 → 404；仅 is_deleted=0（fail-closed）。IPage 由 setData 自动转 BPage。
	 */
	@Operation(summary = "某 agent 待办分页（有截止日期在前按日期升序，无日期按 priority 降序；status 筛选）")
	@GetMapping("/agents/{agentId}/todos")
	public ResultVO agentTodos(@PathVariable Long agentId,
			@RequestParam(required = false) Integer status,
			@RequestParam(required = false, defaultValue = "1") long page,
			@RequestParam(required = false, defaultValue = "" + PAGE_DEFAULT_SIZE) long size,
			@RequestParam(required = false) Long pageSize) {
		// size 为门户既有分页参数惯例；pageSize 兼容任务书契约写法，二选一优先 pageSize
		long eff = (pageSize != null && pageSize > 0) ? pageSize : size;
		long clamped = Math.min(Math.max(eff, 1), PAGE_MAX_SIZE);
		return ResultVO.getSuccessVO("查询成功")
				.setData(todoService.portalPageByAgent(agentId, status, page, clamped));
	}

	/**
	 * GET /api/portal/agents/{agentId}/todos/status-counts — 某 agent 待办状态计数
	 * 分页（匿名只读）
	 * <p>
	 * 全量口径（不受 status 筛选影响）：待办 Tab 角标数据源（同目标域 status-counts）。
	 * agent 不存在 → 404；仅统计未软删。
	 */
	@Operation(summary = "某 agent 待办状态计数（全量；Tab 角标用）")
	@GetMapping("/agents/{agentId}/todos/status-counts")
	public ResultVO agentTodoStatusCounts(@PathVariable Long agentId) {
		return ResultVO.getSuccessVO("查询成功")
				.setData(todoService.portalStatusCountsByAgent(agentId));
	}

	/**
	 * M6.1 GET /api/portal/agents/{agentId}/activity — 某 agent 活动流（匿名只读）
	 * <p>
	 * create_time 倒序（同时间 id 倒序）；limit 默认 50、钳制 ≤ 200；
	 * M6.2 新增可选 sessionKey 精确过滤（话题轨迹页拉单会话事件序列，limit 放行至 200）。
	 * agent 不存在/已停用 → 空列表（照 portal 容错风格，不抛 404 防枚举）。
	 * 响应 data：{"list":[{id, eventType, toolName, summary, durationMs, outcome, sessionKey, createTime}]}。
	 */
	@Operation(summary = "某 agent 活动流（create_time 倒序；limit 默认 50 上限 200；可选 sessionKey 精确过滤；agent 不存在 → 空列表）")
	@GetMapping("/agents/{agentId}/activity")
	public ResultVO agentActivity(@PathVariable Long agentId,
			@RequestParam(required = false, defaultValue = "50") Integer limit,
			@RequestParam(required = false) String sessionKey) {
		return ResultVO.getSuccessVO("查询成功")
				.setData(Map.of("list", activityService.listByAgent(agentId, limit, sessionKey)));
	}

	/**
	 * M6.2 GET /api/portal/agents/{agentId}/activity/sessions — 话题聚合（匿名只读）
	 * <p>
	 * 按 session_key 分组统计（turn/tool/错误计数 + 超限计数 + 最后事件），flags 为读时
	 * 计算的确定性诊断（loop_suspect/long_turn/long_tool/error_prone/stalled，不落表）。
	 * limit 默认 20、钳制 ≤ 50；agent 不存在/已停用 → 空列表（防枚举）。
	 */
	@Operation(summary = "某 agent 话题聚合（按会话分组统计 + 确定性诊断 flags；limit 默认 20 上限 50）")
	@GetMapping("/agents/{agentId}/activity/sessions")
	public ResultVO agentActivitySessions(@PathVariable Long agentId,
			@RequestParam(required = false, defaultValue = "20") Integer limit) {
		return ResultVO.getSuccessVO("查询成功")
				.setData(Map.of("list", activityService.listSessions(agentId, limit)));
	}

	/**
	 * M6.1 GET /api/portal/agents/{agentId}/activity/current — 当前状态聚合（匿名只读，服务端计算）
	 * <p>
	 * running 判定：最近一条事件非 turn_completed 且最后事件时间距今 ≤ 10 分钟；否则 idle
	 * （10 分钟兜底：插件失联/agent 容器重启后不许永远「执行中」）。
	 * agent 不存在/已停用/无事件 → idle 空态（不抛 404，防枚举）。
	 */
	@Operation(summary = "某 agent 当前状态聚合（running/idle，服务端计算；10 分钟兜底）")
	@GetMapping("/agents/{agentId}/activity/current")
	public ResultVO agentActivityCurrent(@PathVariable Long agentId) {
		ActivityCurrentVO vo = activityService.currentByAgent(agentId);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * v4.18 GET /api/portal/agents/{agentId}/activity/stats — 某 agent 活动按天聚合
	 * （匿名只读，智能体页「概览」仪表盘柱状图数据源）。
	 * <p>
	 * days 默认 14、钳制 1~31（Service 侧）；agent 不存在/已停用 → 空列表
	 * （与 sessions 端点同口径防枚举）。返回每日一行：北京墙钟 yyyy-MM-dd +
	 * events/sessions/tools/turns；缺失日期不出现在列表里（前端按 statDate
	 * 补 gap 灰色斜纹柱）。
	 * <p>
	 * 不动 agent 域契约、不改 t_activity DDL、不加新依赖。
	 */
	@Operation(summary = "某 agent 活动按天聚合（days 默认 14 钳制 1~31；缺失日期不返回，前端补 gap）")
	@GetMapping("/agents/{agentId}/activity/stats")
	public ResultVO agentActivityStats(@PathVariable Long agentId,
			@RequestParam(required = false, defaultValue = "14") Integer days) {
		List<ActivityDailyStatVO> list = activityService.statsByAgent(agentId, days);
		return ResultVO.getSuccessVO("查询成功")
				.setData(Map.of("list", list, "days", activityService.resolveStatsDays(days)));
	}

	/**
	 * M7.1-M3 GET /api/portal/agents/{agentId}/memories/page — 某 agent 记忆分页（匿名只读）
	 * <p>
	 * 流式加载用：page 从 1 起；size 默认 {@link #PAGE_DEFAULT_SIZE}、钳制 ≤
	 * {@link #PAGE_MAX_SIZE}；仅活跃（status=1）记忆，weight DESC, id DESC；
	 * agent 不存在 → 404（与 ledger/page 一致），存在但无记忆 → 空页。
	 * IPage 由 setData 自动转 BPage。本期不做 memType 筛选、不做 status-counts（任务书：无需求别加）。
	 */
	@Operation(summary = "某 agent 记忆分页（流式加载，匿名只读，仅活跃记忆）")
	@GetMapping("/agents/{agentId}/memories/page")
	public ResultVO agentMemoryPage(@PathVariable Long agentId,
			@RequestParam(required = false, defaultValue = "1") long page,
			@RequestParam(required = false, defaultValue = "" + PAGE_DEFAULT_SIZE) long size) {
		long clamped = Math.min(Math.max(size, 1), PAGE_MAX_SIZE);
		return ResultVO.getSuccessVO("查询成功")
				.setData(memoryService.portalPageByAgent(agentId, page, clamped));
	}

	/**
	 * GET /api/portal/ledger/{lid} — 台账详情（匿名只读；读正文：inline 优先，否则读 md）
	 * <p>
	 * 与笔记详情同模式：lid 不存在/已软删/格式非法 → 404（findByLid 内 isLidValid 强校验，fail-closed 防枚举）；
	 * md 文件缺失（孤儿索引行）→ 404（getDetail 兜底）。复用 agent 域/管理端共用的 getDetail。
	 */
	@Operation(summary = "台账详情（读正文：inline 优先，否则读 md）")
	@GetMapping("/ledger/{lid}")
	public ResultVO ledgerDetail(@PathVariable String lid) {
		LedgerDetailVO vo = ledgerService.getDetail(lid);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	// ============================================================
	// M1 门户包：消息总线只读（匿名公开域）
	// ============================================================

	/**
	 * M1 门户包 GET /api/portal/bus/messages — 消息流转分页（匿名只读）
	 * <p>
	 * t_agent_message 按 id 倒序；page 从 1 起，size 默认 {@link #BUS_PAGE_DEFAULT_SIZE}、
	 * 钳制 ≤ {@link #BUS_PAGE_MAX_SIZE}；筛选 from/to（agentId 精确）、type（msg_type 精确）、
	 * status（精确）、topic（精确）、days（近 N 天，1~366；缺省/0=不过滤）、
	 * keyword（title/topic LIKE，转义）均可空。响应走 PortalBusMessageVO 白名单：绝不返回 payload /
	 * session_key / model / last_error（匿名公开域防内部内容泄漏）。IPage 由 setData 自动转 BPage。
	 * bus-ui-v2 扩参：topic / days / keyword / channel。
	 */
	@Operation(summary = "消息流转分页（匿名只读；id 倒序；筛选 from/to/type/status/topic/days/keyword/channel 可空）")
	@GetMapping("/bus/messages")
	public ResultVO busMessages(
			@RequestParam(name = "from", required = false) Long from,
			@RequestParam(name = "to", required = false) Long to,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) Integer status,
			@RequestParam(required = false) String topic,
			@RequestParam(required = false) Integer days,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String channel,
			@RequestParam(required = false, defaultValue = "1") long page,
			@RequestParam(required = false, defaultValue = "" + BUS_PAGE_DEFAULT_SIZE) long size) {
		long clamped = Math.min(Math.max(size, 1), BUS_PAGE_MAX_SIZE);
		return ResultVO.getSuccessVO("查询成功")
				.setData(busMessageService.portalPage(from, to, status, type, topic, days, keyword,
						channel, page, clamped));
	}

	/**
	 * M1 门户包 GET /api/portal/bus/stats — 消息流转统计（匿名只读）
	 * <p>
	 * {todayTotal, delivered, pending, failed}：今日进线 / 已投递(status=1) /
	 * 待投(status=0) / 失败(其余状态合计)；除「今日」外均为全时段口径。
	 */
	@Operation(summary = "消息流转统计（今日进线/已投递/待投/失败）")
	@GetMapping("/bus/stats")
	public ResultVO busStats() {
		return ResultVO.getSuccessVO("查询成功").setData(busMessageService.portalStats());
	}

	// ============================================================
	// KB（知识库）：混合检索 + RAG 问答（匿名只读）
	// ============================================================

	@Autowired
	private com.youlxb2008.admin.modules.note.kb.service.KnowledgeSearchService knowledgeSearchService;

	/**
	 * GET /kb/search — 知识库混合检索（标题 + BM25 全文 + 语义向量，RRF 融合）
	 * <p>
	 * q 必填 ≤100；notebookId 可选（空=全库）；limit 默认 10、钳制 ≤20。
	 * 索引未建/key 未配置时自动降级（BM25/语义路跳过，仅标题路），不报错。
	 */
	@Operation(summary = "知识库混合检索（标题+BM25+语义；notebookId 空=全库）")
	@GetMapping("/kb/search")
	public ResultVO kbSearch(@RequestParam String q,
			@RequestParam(required = false) Long notebookId,
			@RequestParam(required = false, defaultValue = "10") Integer limit) {
		if (q == null || q.isBlank()) {
			throw new BadRequestException("q 不能为空");
		}
		if (q.trim().length() > MAX_KEYWORD_LEN) {
			throw new BadRequestException("q 长度不能超过 " + MAX_KEYWORD_LEN);
		}
		return ResultVO.getSuccessVO("查询成功")
				.setData(knowledgeSearchService.hybridSearch(q, notebookId, limit == null ? 10 : limit));
	}

	/**
	 * POST /kb/ask — 知识库 RAG 问答（检索 top6 片段 → qwen 生成答案 + 引用列表）
	 * <p>
	 * body：{question 必填, notebookId 可选}；未配置百炼 key → 400（提示系统配置）。
	 */
	@Operation(summary = "知识库 RAG 问答（附引用笔记列表）")
	@PostMapping("/kb/ask")
	public ResultVO kbAsk(@RequestBody KbAskReq req) {
		return ResultVO.getSuccessVO("查询成功")
				.setData(knowledgeSearchService.ask(req.getQuestion(), req.getNotebookId()));
	}

	/** /kb/ask 请求体 */
	@lombok.Data
	public static class KbAskReq {
		/** 用户问题（必填） */
		private String question;
		/** 范围过滤（可选，空=全库） */
		private Long notebookId;
	}

	// ============================================================
	// M3.8 RESTful 化：search / tags / by-tag / recent 的 notebookId 从
	// query string 迁入路径（/notebooks/{id}/notes/...）；notebook 不存在/
	// 已软删 → 404（requireNotebook，fail-closed）。筛选/分页仍走 query。
	// ============================================================

	/**
	 * M3.8 GET /notebooks/{id}/notes/search — 标题搜索（RESTful：notebookId 走路径）
	 * <p>
	 * notebookId 走路径且必填；keyword 必填非空、≤100；limit 默认 20、钳制 ≤50。
	 * notebook 不存在/已软删 → 404。
	 */
	@Operation(summary = "标题搜索（notebookId 走路径；keyword 空白 → 400）")
	@GetMapping("/notebooks/{id}/notes/search")
	public ResultVO searchNotes(@PathVariable Long id,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false, defaultValue = "" + SEARCH_DEFAULT_LIMIT) Integer limit) {
		requireNotebook(id);
		if (keyword == null || keyword.isBlank()) {
			throw new BadRequestException("keyword 不能为空");
		}
		if (keyword.trim().length() > MAX_KEYWORD_LEN) {
			throw new BadRequestException("keyword 长度不能超过 " + MAX_KEYWORD_LEN);
		}
		List<NoteSummaryVO> list = toSummaries(noteMetaService.searchByKeyword(id, keyword, limit));
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * M3.8 GET /notebooks/{id}/notes/tags — tags 聚合（RESTful：notebookId 走路径）
	 * <p>
	 * notebookId 走路径且必填；逗号分隔展开 trim/去重；count 降序再 tag 字典序。
	 */
	@Operation(summary = "tags 聚合（notebookId 走路径；count 降序再 tag 字典序）")
	@GetMapping("/notebooks/{id}/notes/tags")
	public ResultVO noteTags(@PathVariable Long id) {
		requireNotebook(id);
		List<TagCountVO> list = noteMetaService.aggregateTags(id);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * M3.8 GET /notebooks/{id}/notes/by-tag — 按 tag 精确筛选（RESTful：notebookId 走路径）
	 * <p>
	 * notebookId 走路径且必填；tag 必填非空、≤50；limit 默认 20、钳制 ≤50。
	 */
	@Operation(summary = "按 tag 精确筛选（notebookId 走路径；tag 空白 → 400）")
	@GetMapping("/notebooks/{id}/notes/by-tag")
	public ResultVO notesByTag(@PathVariable Long id,
			@RequestParam(required = false) String tag,
			@RequestParam(required = false, defaultValue = "" + SEARCH_DEFAULT_LIMIT) Integer limit) {
		requireNotebook(id);
		if (tag == null || tag.isBlank()) {
			throw new BadRequestException("tag 不能为空");
		}
		if (tag.trim().length() > MAX_TAG_LEN) {
			throw new BadRequestException("tag 长度不能超过 " + MAX_TAG_LEN);
		}
		List<NoteSummaryVO> list = toSummaries(noteMetaService.listByTag(id, tag, limit));
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * M3.8 GET /notebooks/{id}/notes/recent — 最近更新（RESTful：notebookId 走路径）
	 * <p>
	 * notebookId 走路径且必填；update_time 降序、同时间 id 降序；limit 默认 10、钳制 ≤50。
	 */
	@Operation(summary = "最近更新（notebookId 走路径；update_time 降序）")
	@GetMapping("/notebooks/{id}/notes/recent")
	public ResultVO recentNotes(@PathVariable Long id,
			@RequestParam(required = false, defaultValue = "" + RECENT_DEFAULT_LIMIT) Integer limit) {
		requireNotebook(id);
		List<NoteSummaryVO> list = toSummaries(noteMetaService.listRecent(id, limit));
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	// ------------------------------------------------------------
	// 私有
	// ------------------------------------------------------------

	/**
	 * M3.8：notebook 不存在或已软删 → 404（getByIdOrNull 经 @TableLogic 自动过滤
	 * is_deleted，fail-closed）
	 */
	private void requireNotebook(Long id) {
		if (notebookService.getByIdOrNull(id) == null) {
			throw new NotFoundException("笔记本不存在");
		}
	}

	private PortalAgentVO toPortalAgentVO(Agent agent) {
		PortalAgentVO vo = new PortalAgentVO();
		vo.setId(agent.getId());
		vo.setName(agent.getName());
		vo.setDisplayName(agent.getDisplayName());
		// v4.3：头像 URL（门户卡片有图显示图片，无图色块兜底）
		vo.setAvatar(agent.getAvatar());
		// 笔记本名 = display_name 优先回退 name（门户"笔记本"文案，隐藏 agent 术语）
		vo.setNotebookName(agent.getDisplayName() != null ? agent.getDisplayName() : agent.getName());
		vo.setStatus(agent.getStatus());
		// v3：三聚合全部改走关联表（无主化后 t_note_meta 无 agent 维度）
		vo.setNoteCount(agentResourceService.countByAgent(agent.getId()));
		vo.setLastNoteAt(agentResourceService.maxUpdateTimeByAgent(agent.getId()));
		return vo;
	}

	// note 域 Manager 注入（Controller→Manager，分层合规；content?nid= 真相源读取）
	@Autowired
	private NoteStorageManager noteStorageManager;

	/** 索引行 → NoteSummaryVO（v3：contributors 逐条查，limit ≤50 规模无压力） */
	private List<NoteSummaryVO> toSummaries(List<NoteMeta> metas) {
		if (metas == null || metas.isEmpty()) {
			return List.of();
		}
		return metas.stream().map(m -> {
			NoteSummaryVO vo = new NoteSummaryVO();
			vo.setNid(m.getNid());
			vo.setTitle(m.getTitle());
			vo.setDirPath(m.getDirPath());
			vo.setUpdateTime(m.getUpdateTime());
			// v3：贡献者多标签取代 agentName 单值
			List<AgentSimpleVO> contributors = agentResourceService.listNoteContributors(m.getNid());
			vo.setContributors(contributors);
			return vo;
		}).collect(Collectors.toList());
	}
}
