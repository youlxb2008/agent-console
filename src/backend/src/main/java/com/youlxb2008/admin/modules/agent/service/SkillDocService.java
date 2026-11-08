package com.youlxb2008.admin.modules.agent.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Skill 文档与 API 参考文档生成服务（v4.2 新增）
 * <p>
 * 设计要点：
 * - 接口明细（参数表/curl 示例）静态写进 SKILL.md；
 * - 本项目按需求拆成两层：<b>SKILL.md 只做模块介绍 + API 索引（轻量概览）</b>，
 *   <b>接口明细由 agent 运行时动态拉取 {@code GET /api/agent/api-docs}</b>，
 *   保证 agent 拿到的永远是与服务端代码同步的最新定义，SKILL.md 不随接口细节膨胀。
 * - 两份文档同源于下方 MODULES 元数据常量（单一真相源），不会两处不一致；
 *   新增 agent 域接口时在 MODULES 补一条元数据即可，两份文档同时更新。
 * <p>
 * 端点挂载（均返回 text/markdown 纯文本，不走 ResultVO 包装）：
 * - {@code GET /api/agent/skill-doc}（X-API-Key，任意有效 key，免 scope）
 * - {@code GET /api/agent/api-docs}（同上）
 * - {@code GET /api/agent/admin/skill-doc}（管理端 JWT，下载按钮用）
 */
@Service
public class SkillDocService {

	/** agent 域基础路径：仅相对路径，同源部署跟随当前域名 */
	private static final String BASE = "/api/agent";

	/** curl 示例里的密钥占位（真实密钥由管理端生成，绝不写死在文档中） */
	private static final String KEY_PLACEHOLDER = "ac_your_key";

	/* ==================== 元数据结构（record 只读，天然线程安全） ==================== */

	/** 单个请求参数（body 字段或 query 参数） */
	public record DocParam(String name, String type, boolean required, String desc) {
	}

	/** 单个端点：method+path+scope+一句话说明+参数表+请求示例+业务规则 */
	public record DocEndpoint(String method, String path, String scope, String summary,
			List<DocParam> params, String bodyExample, List<String> rules) {
	}

	/** 模块（一组同域端点）：key/scope 用于索引表，concepts 写进概览的核心概念 */
	public record DocModule(String key, String name, String scopeNote, String intro,
			List<String> concepts, List<DocEndpoint> endpoints) {
	}

	/* ==================== 端点元数据总表（与三个 ApiController 一一对应，单一真相源） ====================
	 * 新增 agent 域接口时：在此补一条 DocEndpoint（或新模块），skill-doc 与 api-docs 两份文档同步生效。
	 * 注意与 docs/接口契约/api.md 保持语义一致（该校验列入发版验收）。
	 */

	private static final List<DocModule> MODULES = List.of(

			// ---------------- 模块 0：系统（自检/审计） ----------------
			new DocModule("system", "系统", "任意有效 key / audit:read",
					"身份自检与操作审计：接入后先调 /me 确认 key 有效与 scopes。",
					List.of(),
					List.of(
							new DocEndpoint("GET", "/me", "（无需 scope）", "识别自己：自检 key 是否有效、查看自己的 scopes",
									List.of(), null,
									List.of("返回 {agentId, name, displayName, scopes}——scopes 决定能调哪些接口")),
							new DocEndpoint("GET", "/audit", "audit:read", "自己的审计记录分页（每次 API 调用都会留痕）",
									List.of(
											new DocParam("page", "long", false, "页码，默认 1"),
											new DocParam("size", "long", false, "每页条数，默认 10"),
											new DocParam("action", "string", false, "按动作过滤，如 note.write / key.verify")),
									null, List.of("只返回 principal=自己 agent 名的记录")))),

			// ---------------- 模块 1：笔记（notes，核心写作能力） ----------------
			new DocModule("notes", "笔记", "notes:write",
					"Markdown 笔记写作：upsert 智能覆写、目录树、协作多写。",
					List.of(
							"笔记本是独立内容容器：笔记必须挂在一个笔记本下（notebookId），agent 须已被管理员关联到该笔记本才能写（否则 403）",
							"nid（n_ + 10 位 base62）是笔记唯一对外引用键，创建后终身不变；覆写同名笔记也保留原 nid",
							"upsert 锚点 = (dirPath, title)：未命中→新建；命中且自己已关联→覆写（保留 nid）；命中但未关联→另建新篇（不覆写他人笔记）",
							"协作语义：notes:write = 可新建 + 可覆写/删除已关联笔记；多篇笔记可由多个 agent 共同贡献",
							"文件系统是真相源：先原子落盘再写索引；contentMd 上限 2MB"),
					List.of(
							new DocEndpoint("POST", "/notes", "notes:write", "upsert 笔记（新建或智能覆写，唯一写入口）",
									List.of(
											new DocParam("notebookId", "long", true, "目标笔记本 id（agent 须已关联，否则 403）"),
											new DocParam("title", "string", true, "标题 ≤200 字符；与 dirPath 共同构成覆写锚点"),
											new DocParam("dirPath", "string", false, "目录路径（/ 分层），空串=根目录；层深 ≤10、单段 ≤64"),
											new DocParam("contentMd", "string", false, "Markdown 正文，≤2MB"),
											new DocParam("tags", "string", false, "标签，英文逗号分隔（如 \"巡检,日报\"），仅落索引")),
									"{\"notebookId\":3,\"dirPath\":\"工作日志/巡检记录\",\"title\":\"2026-09-04 巡检报告\",\"contentMd\":\"# 巡检\",\"tags\":\"巡检,日报\"}",
									List.of(
											"返回 {nid, dirPath, title, notebookId, updateTime}：新建返回新 nid，覆写返回原 nid",
											"校验链（fail-closed）：notebook 不存在/已软删→404；agent 未关联→403；title/dirPath 黑名单字符→400",
											"跨笔记本覆写 = 移动语义：notebookId 更新为新值并再次校验目标笔记本")),
							new DocEndpoint("GET", "/notes/tree", "notes:write", "自己可写的笔记目录树（关联的所有笔记本）",
									List.of(), null,
									List.of("节点带 notebookId/notebookName；未关联任何笔记本时返回空树",
											"folder 字典序在前、note 字典序在后")),
							new DocEndpoint("PATCH", "/notes/{nid}", "notes:write", "挪动/重命名笔记（按 nid 改元数据，保留 nid 与正文）",
									List.of(
											new DocParam("notebookId", "long", false, "移动到目标笔记本（三选一至少传一项）"),
											new DocParam("dirPath", "string", false, "新目录路径"),
											new DocParam("title", "string", false, "新标题")),
									"{\"dirPath\":\"归档/2026\",\"title\":\"巡检报告-归档\"}",
									List.of("三项全空→400；nid 不存在或未关联→统一 404（防枚举）",
											"目标元组 (notebookId,dirPath,title) 与同本其他笔记重复→422",
											"不动文件与关联行——文件路径只跟 nid 走")),
							new DocEndpoint("DELETE", "/notes/{nid}", "notes:write", "删除笔记（物理删文件+索引+全部关联行，不可恢复）",
									List.of(), null,
									List.of("nid 不存在或当前 agent 未关联→统一 404（防枚举）",
											"已关联即有权删（协作写语义）")))),

			// ---------------- 模块 2：笔记本（notebooks） ----------------
			new DocModule("notebooks", "笔记本", "notes:write / notebook:manage",
					"独立内容容器：读列表走 notes:write，建/改/删走 notebook:manage。",
					List.of(
							"写笔记前先 GET /notebooks 拿可用笔记本；空列表说明管理员尚未关联，引导用户找管理员",
							"删除笔记本为软删：笔记文件保留但门户不再展示，且解除全部 agent 关联"),
					List.of(
							new DocEndpoint("GET", "/notebooks", "notes:write", "自己关联的笔记本列表（写笔记下拉数据源）",
									List.of(), null,
									List.of("返回 [{id, name, description, cover, status, createTime}]")),
							new DocEndpoint("POST", "/notebooks", "notebook:manage", "新建笔记本",
									List.of(
											new DocParam("name", "string", true, "名称，未软删范围内唯一"),
											new DocParam("description", "string", false, "简介")),
									"{\"name\":\"项目知识库\",\"description\":\"沉淀项目文档\"}",
									List.of("重名→业务异常")),
							new DocEndpoint("PUT", "/notebooks/{id}", "notebook:manage", "编辑笔记本",
									List.of(
											new DocParam("name", "string", false, "新名称"),
											new DocParam("description", "string", false, "新简介")),
									"{\"description\":\"更新后的简介\"}",
									List.of("id 走路径，body 不携带 id")),
							new DocEndpoint("DELETE", "/notebooks/{id}", "notebook:manage", "软删笔记本（解除全部 agent 关联）",
									List.of(), null,
									List.of("笔记保留但不再展示；再次写该本→404")))),

			// ---------------- 模块 3：知识库检索（kb，无独立 scope） ----------------
			new DocModule("kb", "知识库检索", "任意有效 key（权限=笔记本关联）",
					"混合检索（标题+BM25+语义向量）与 RAG 问答，不引入新 scope。",
					List.of(
							"权限完全复用 agent↔笔记本关联：指定 notebookId 须已关联（否则 403）；省略则自动收敛到已关联笔记本范围，绝不全库检索",
							"写笔记成功后索引钩子自动触发（分块+向量化+全文索引），无需手动建索引",
							"索引未建/向量未配置会自动降级（对应检索路跳过），不报错"),
					List.of(
							new DocEndpoint("GET", "/kb/search", "（无需 scope）", "混合检索笔记",
									List.of(
											new DocParam("q", "string", true, "检索词，≤100 字符"),
											new DocParam("notebookId", "long", false, "限定笔记本（须已关联）；省略=已关联全部"),
											new DocParam("limit", "int", false, "返回条数，默认 10，最大 20")),
									null,
									List.of("返回 [{nid, notebookId, title, dirPath, snippet, score, sources[]}]；sources 标记命中路（title/bm25/vector）")),
							new DocEndpoint("POST", "/kb/ask", "（无需 scope）", "RAG 问答（基于笔记内容生成答案）",
									List.of(
											new DocParam("question", "string", true, "问题"),
											new DocParam("notebookId", "long", false, "限定笔记本（须已关联）")),
									"{\"question\":\"部署端口是多少\",\"notebookId\":3}",
									List.of("返回 {answer, references[{nid, notebookId, title, dirPath}]}，answer 内 [n] 与 references 下标对应",
											"未检索到相关内容→固定话术拒答不编造；未配置模型 key→400")))),

			// ---------------- 模块 4：目标（objectives） ----------------
			new DocModule("objectives", "目标", "objective:write",
					"每 agent 私域 todolist（\"我要去哪\"）：md 正文 + checkbox 子项，进度写时刷新。",
					List.of(
							"oid（o_ + 10 位 base62）为唯一引用键；状态 0未开始/1进行中/2已完成/3已放弃",
							"子项写在 md 里（- [x] / - [ ] 语法），progress = done/total*100，改 bodyMd 时重算",
							"删除为软删（md 保留，门户不再展示）"),
					List.of(
							new DocEndpoint("GET", "/objectives", "objective:write", "自己的目标列表",
									List.of(
											new DocParam("status", "int", false, "状态过滤（0/1/2/3）"),
											new DocParam("period", "string", false, "周期过滤")),
									null,
									List.of("返回 [{oid, title, status, period, targetDate, progress, updateTime}]")),
							new DocEndpoint("POST", "/objectives", "objective:write", "建目标",
									List.of(
											new DocParam("title", "string", true, "标题"),
											new DocParam("period", "string", false, "周期（如 2026-Q3）"),
											new DocParam("targetDate", "string", false, "目标日期 yyyy-MM-dd"),
											new DocParam("bodyMd", "string", false, "描述正文（可含 checkbox 子项预填）")),
									"{\"title\":\"完成 KB 索引重建\",\"period\":\"2026-Q3\",\"bodyMd\":\"## 描述\\n...\"}",
									List.of("返回含生成的 oid")),
							new DocEndpoint("GET", "/objectives/{oid}", "objective:write", "目标详情（md 全文 + 子项解析）",
									List.of(), null,
									List.of("非本人目标→404（防枚举）；oid 格式非法→404")),
							new DocEndpoint("PATCH", "/objectives/{oid}", "objective:write", "改目标",
									List.of(
											new DocParam("title", "string", false, "新标题（至少传一项）"),
											new DocParam("status", "int", false, "新状态"),
											new DocParam("bodyMd", "string", false, "新正文（重算 progress）")),
									"{\"status\":2}",
									List.of("全空→400；改 bodyMd 覆写 md 并重算 progress")),
							new DocEndpoint("DELETE", "/objectives/{oid}", "objective:write", "软删目标",
									List.of(), null, List.of()))),

			// ---------------- 模块 5：台账（ledger） ----------------
			new DocModule("ledger", "台账", "ledger:write",
					"每 agent 私域流水（\"我做过/想过/决定过什么\"）：内驱自记，与外驱任务区分。",
					List.of(
							"5 种类型：progress 进展 / observation 观察 / decision 决策 / memo 备忘 / session 会话",
							"body 双轨制：≤500 字存索引列；>500 字落 md 文件，列表不读文件、详情才读正文",
							"可挂 4 类引用：refTaskId / refRequirementId / refObjectiveId / refNoteNid；删除为软删"),
					List.of(
							new DocEndpoint("GET", "/ledger", "ledger:write", "自己的台账流（create_time 倒序）",
									List.of(
											new DocParam("type", "string", false, "类型过滤（5 枚举）"),
											new DocParam("refTaskId", "long", false, "按任务引用过滤")),
									null,
									List.of("返回 [{lid, type, title, ref 四列, createTime}]，不含正文（性能考虑）")),
							new DocEndpoint("POST", "/ledger", "ledger:write", "记一笔台账",
									List.of(
											new DocParam("type", "string", true, "类型（progress/observation/decision/memo/session）"),
											new DocParam("title", "string", true, "标题"),
											new DocParam("body", "string", false, "正文（≤500 字存索引，超长自动落文件）"),
											new DocParam("refNoteNid", "string", false, "关联笔记 nid（另有 refTaskId/refObjectiveId 等）")),
									"{\"type\":\"decision\",\"title\":\"选用 RRF 融合\",\"body\":\"三路召回 RRF 优于单路...\",\"refNoteNid\":\"n_xxx\"}",
									List.of("返回 {lid, type, title, createTime}")),
							new DocEndpoint("GET", "/ledger/{lid}", "ledger:write", "台账详情（读正文全文）",
									List.of(), null,
									List.of("inline 优先，否则读 md；非本人→404（防枚举）")),
							new DocEndpoint("PATCH", "/ledger/{lid}", "ledger:write", "改台账",
									List.of(
											new DocParam("type", "string", false, "新类型（至少传一项）"),
											new DocParam("title", "string", false, "新标题"),
											new DocParam("body", "string", false, "新正文（含空串，重切分双轨）")),
									"{\"title\":\"更新标题\"}",
									List.of("全空→400；body 提供则按双轨重切分（原 md 文件保留）")),
							new DocEndpoint("DELETE", "/ledger/{lid}", "ledger:write", "软删台账",
									List.of(), null, List.of()))),

			// ---------------- 模块 6：待办（todos，M5.2 调研池迁入） ----------------
			new DocModule("todos", "待办", "todo:write",
					"每 agent 私域 todolist（调研池迁入）：d_ 键 + 截止日期/优先级排序，状态机灵活流转。",
					List.of(
							"todoId（d_ + 10 位 base62）为唯一引用键；状态 0待办/1进行中/2已完成/3已放弃（任意流转不强校验）",
							"排序（M5.3）：(due_date IS NULL) ASC, due_date ASC, priority DESC, id ASC——有截止日期在前按日期升序，无日期按优先级排后；tags 为逗号分隔字符串（不收 JSON 数组）",
							"越权（操作非本人）→ 403；不存在/已软删 → 404；删除为软删（is_deleted=1）",
							"门户匿名只读：GET /api/portal/agents/{agentId}/todos（分页，status 筛选）"),
					List.of(
							new DocEndpoint("GET", "/todos", "todo:write", "自己的待办分页（有截止日期在前按日期升序，无日期按优先级）",
									List.of(
											new DocParam("status", "int", false, "状态过滤（0/1/2/3）"),
											new DocParam("priority", "int", false, "优先级过滤（1-5）"),
											new DocParam("page", "long", false, "页码，默认 1"),
											new DocParam("pageSize", "long", false, "每页条数，默认 20 最大 50")),
									null,
									List.of("返回 BPage[{todoId, title, content, status, priority, tags, dueDate, createTime, updateTime}]；越权无（私域自动限定）")),
							new DocEndpoint("POST", "/todos", "todo:write", "新建待办",
									List.of(
											new DocParam("title", "string", true, "标题 ≤200 字符"),
											new DocParam("content", "string", false, "md 正文：来源/原因/产出要求"),
											new DocParam("priority", "int", false, "优先级 1-5，默认 3"),
											new DocParam("tags", "string", false, "逗号分隔字符串，如 \"调研,池子\""),
											new DocParam("status", "int", false, "状态，默认 0"),
											new DocParam("dueDate", "string", false, "截止日期 yyyy-MM-dd（可选；有值排前，过期标红）")),
									"{\"title\":\"调研池迁入待办\",\"content\":\"来源：演示数据\",\"priority\":1,\"tags\":\"调研,池子\",\"dueDate\":\"2026-09-30\"}",
									List.of("返回含生成的 todoId；title 非空、≤200")),
							new DocEndpoint("GET", "/todos/{todoId}", "todo:write", "待办详情（含 content）",
									List.of(), null,
									List.of("越权（非本人）→ 403；不存在/已软删 → 404")),
							new DocEndpoint("PATCH", "/todos/{todoId}", "todo:write", "部分改待办",
									List.of(
											new DocParam("title", "string", false, "新标题（至少传一项）"),
											new DocParam("content", "string", false, "新正文"),
											new DocParam("status", "int", false, "新状态（任意流转）"),
											new DocParam("priority", "int", false, "新优先级 1-5"),
											new DocParam("tags", "string", false, "新标签逗号串"),
											new DocParam("dueDate", "string", false, "新截止日期 yyyy-MM-dd（null=不修改）")),
									"{\"status\":2}",
									List.of("全空 → 400；越权 → 403；不存在/已软删 → 404")),
							new DocEndpoint("DELETE", "/todos/{todoId}", "todo:write", "软删待办",
									List.of(), null,
									List.of("越权 → 403；不存在/已软删 → 404")))));

	/* ==================== 文档生成：概览版 SKILL.md ==================== */

	/**
	 * 生成 SKILL.md（轻量概览：模块介绍 + API 索引 + 动态拉取指引）。
	 * 有意不含参数表/请求示例——那些在 api-docs 里，agent 接入时动态拉取。
	 */
	public String generateSkillDoc() {
		StringBuilder sb = new StringBuilder(8 * 1024);
		sb.append("# Agent Console API Skill\n\n");
		sb.append("> 本文档供 AI 助手读取，使其理解 Agent Console 的笔记、笔记本、知识库、目标、台账等 API 并按需调用。\n");
		sb.append("> 将此文件上传或粘贴给 AI 助手即可生效。本文档为**概览索引**：只含模块介绍与 API 清单，\n");
		sb.append("> **接口明细（参数表/请求示例/业务规则）请通过 `GET ").append(BASE).append("/api-docs` 动态拉取**，保证与服务端始终同步。\n\n");

		/* 快速开始 */
		sb.append("## 快速开始\n\n");
		sb.append("- **Base URL**: `").append(BASE).append("`（同源部署，跟随当前域名）\n");
		sb.append("- **认证**: 所有请求带 Header `X-API-Key: <你的密钥>`\n");
		sb.append("- **响应体**: 统一 `{success, code, msg, data}`，业务数据在 `data` 里\n");
		sb.append("- **第一步**: 调 `GET ").append(BASE).append("/me` 自检 key 有效性并查看自己的 scopes\n\n");
		sb.append("```bash\ncurl -H \"X-API-Key: ").append(KEY_PLACEHOLDER).append("\" ").append(BASE).append("/me\n```\n\n");

		/* 模块总览 */
		sb.append("## 模块总览\n\n");
		sb.append("| 模块 | 路径前缀 | 权限 | 一句话说明 |\n| --- | --- | --- | --- |\n");
		for (DocModule m : MODULES) {
			sb.append("| ").append(m.name()).append(" | `").append(modulePrefix(m)).append("` | ")
					.append(m.scopeNote()).append(" | ").append(firstSentence(m.intro())).append(" |\n");
		}
		sb.append('\n');

		/* 核心概念（写笔记前必读） */
		sb.append("## 核心概念（调笔记/知识库接口前必读）\n\n");
		for (DocModule m : MODULES) {
			for (String c : m.concepts()) {
				sb.append("- ").append(c).append('\n');
			}
		}
		sb.append('\n');

		/* API 索引（每端点一行，明细看 api-docs） */
		sb.append("## API 索引\n\n");
		for (DocModule m : MODULES) {
			sb.append("### ").append(m.name()).append('\n');
			sb.append("| 方法 | 路径 | 说明 |\n| --- | --- | --- |\n");
			for (DocEndpoint e : m.endpoints()) {
				sb.append("| `").append(e.method()).append("` | `").append(BASE).append(e.path())
						.append("` | ").append(e.summary()).append(" |\n");
			}
			sb.append('\n');
		}

		/* 动态拉取完整文档（本设计的核心） */
		sb.append("## 动态获取完整 API 参考文档\n\n");
		sb.append("上表每个接口的参数表、请求/响应示例、错误码与业务规则，**均在运行时动态拉取**，不固化在本文件里：\n\n");
		sb.append("```bash\n# 拉取完整 API 参考文档（markdown 纯文本）\n");
		sb.append("curl -H \"X-API-Key: ").append(KEY_PLACEHOLDER).append("\" ").append(BASE).append("/api-docs\n```\n\n");
		sb.append("**建议**：接入时、以及发现 API 行为与预期不符（404/参数不匹配）时，先拉一次最新文档再重试。\n\n");

		/* 文档自更新 */
		sb.append("## 本文档自更新\n\n");
		sb.append("```bash\ncurl -H \"X-API-Key: ").append(KEY_PLACEHOLDER).append("\" ").append(BASE).append("/skill-doc\n```\n");
		sb.append("将响应体**原样写入**本地 SKILL.md（整体覆盖，不要拼接）。\n\n");

		/* 通用规则 */
		sb.append("## 通用规则\n\n");
		sb.append("- 错误码：`401` 无效 key；`403` scope 不足 / agent 未关联笔记本 / agent 停用；`404` 资源不存在或防枚举统一语义；`422` 挪动笔记锚点冲突；`429` 触发限流\n");
		sb.append("- 审计：每次调用（含被拒）都会写审计日志，可在 `GET /audit` 查询自己的记录\n");
		sb.append("- 中文内容请确保 UTF-8 编码（`Content-Type: application/json; charset=utf-8`）\n");
		return sb.toString();
	}

	/* ==================== 文档生成：详情版 api-docs ==================== */

	/**
	 * 生成 API 参考文档（完整版：每端点参数表 + 请求示例 + 业务规则 + curl）。
	 * agent 接入时动态拉取本文档（GET /api/agent/api-docs），SKILL.md 中只留索引。
	 */
	public String generateApiDocs() {
		StringBuilder sb = new StringBuilder(24 * 1024);
		sb.append("# Agent Console API 参考（动态文档）\n\n");
		sb.append("> 由服务端从代码内元数据实时生成，始终与当前部署版本一致。\n");
		sb.append("> 认证：Header `X-API-Key`；Base URL：`").append(BASE).append("`；统一响应体 `{success, code, msg, data}`。\n\n");

		for (DocModule m : MODULES) {
			sb.append("---\n\n## 模块：").append(m.name()).append('\n');
			sb.append("> 权限：").append(m.scopeNote()).append("　|　").append(m.intro()).append("\n\n");
			if (!m.concepts().isEmpty()) {
				sb.append("**核心概念**：\n\n");
				for (String c : m.concepts()) {
					sb.append("- ").append(c).append('\n');
				}
				sb.append('\n');
			}
			for (DocEndpoint e : m.endpoints()) {
				appendEndpointDetail(sb, e);
			}
		}
		return sb.toString();
	}

	/** 单端点详情段落：标题/scope/参数表/请求示例/业务规则/curl */
	private void appendEndpointDetail(StringBuilder sb, DocEndpoint e) {
		sb.append("### ").append(e.method()).append(" `").append(BASE).append(e.path()).append("`\n\n");
		sb.append(e.summary()).append("　（scope：").append(e.scope()).append("）\n\n");

		/* 参数表（body 字段或 query 参数同构呈现，in 由是否有 bodyExample 区分太隐晦，直接注明） */
		if (!e.params().isEmpty()) {
			sb.append("**参数**：\n\n| 字段 | 类型 | 必填 | 说明 |\n| --- | --- | --- | --- |\n");
			for (DocParam p : e.params()) {
				sb.append("| `").append(p.name()).append("` | ").append(p.type()).append(" | ")
						.append(p.required() ? "是" : "否").append(" | ").append(p.desc()).append(" |\n");
			}
			sb.append('\n');
		}

		/* 请求示例 */
		if (e.bodyExample() != null) {
			sb.append("**请求示例**：\n\n```json\n").append(e.bodyExample()).append("\n```\n\n");
		}

		/* 业务规则 */
		if (!e.rules().isEmpty()) {
			sb.append("**业务规则**：\n\n");
			for (String r : e.rules()) {
				sb.append("- ").append(r).append('\n');
			}
			sb.append('\n');
		}

		/* curl 示例（自动拼装：写方法带 -X 与 Content-Type，有 body 则 -d） */
		sb.append("```bash\ncurl");
		if (!"GET".equals(e.method())) {
			sb.append(" -X ").append(e.method());
		}
		sb.append(" -H \"X-API-Key: ").append(KEY_PLACEHOLDER).append("\"");
		if (e.bodyExample() != null) {
			sb.append(" -H \"Content-Type: application/json; charset=utf-8\"");
			sb.append(" \\\n  -d '").append(e.bodyExample()).append("'");
		}
		sb.append(" \"https://<你的域名>").append(BASE).append(e.path()).append("\"\n```\n\n");
	}

	/* ==================== 小工具 ==================== */

	/** 模块路径前缀（供总览表展示）：取该模块第一个端点路径去掉尾部变量段 */
	private String modulePrefix(DocModule m) {
		String p = m.endpoints().get(0).path();
		// 去掉路径里的 {xxx} 变量段与末级资源，保留模块前缀（如 /notes/{nid} → /notes）
		int brace = p.indexOf('{');
		if (brace > 0) {
			p = p.substring(0, brace);
		}
		return p.replaceAll("/$", "");
	}

	/** 取第一句（模块总览表用，避免单元格过长） */
	private String firstSentence(String intro) {
		int idx = intro.indexOf('；');
		return idx > 0 ? intro.substring(0, idx) : intro;
	}
}
