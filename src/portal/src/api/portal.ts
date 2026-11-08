/**
 * 门户域接口（v3 笔记本化：agent 空间改称笔记本；聚合走 t_agent_resource）
 * 字段白名单：绝不拼装猜测 key_hash/scopes/审计/IP 等后端未返回的字段
 */
import { request } from './request'

/**
 * 后端统一分页包装（BPage；ResultVO.setData 自动转换）
 * curPage 当前页（1 起）/ count 总条数 / pageSize 每页条数 / list 当前页数据
 */
export interface BPage<T> {
  curPage: number
  count: number
  pageSize: number
  list: T[]
}

/** 贡献者（{id, displayName}，笔记详情 chips / 摘要列表共用） */
export interface Contributor {
  id: number
  displayName: string
}

/** GET /api/portal/notebooks 笔记本概览卡（v4：独立笔记本实体，仅未软删） */
export interface PortalNotebook {
  /** notebook id（深链接 ?notebookId= 用） */
  id: number
  name: string
  description: string | null
  /** 封面 URL（空时前端首字母色块兜底） */
  cover: string | null
  /** v4.17 logo URL（空时笔记页左上角回退 cover，方图） */
  logo?: string | null
  /** v4.17 hero URL（空时笔记页空态中央回退 cover，宽幅） */
  hero?: string | null
  /** 恒为 1（仅返回启用状态笔记本） */
  status: number
  /** 笔记数（t_note_meta.notebook_id 聚合） */
  noteCount: number
  /** 最近一篇笔记更新时间（yyyy-MM-dd HH:mm:ss） */
  lastNoteAt: string | null
  /** 关联 agent 数（t_agent_notebook，管理员配置口径） */
  agentCount: number
}

/**
 * GET /api/portal/notes/tree 目录树节点（v3：数据驱动，dir_path 内存聚合）
 * - folder：{name, type:"folder", children[]}（无 updateTime）
 * - note：{name, type:"note", nid, updateTime}（nid 恒有值；🔥 path 字段已删，无孤儿兜底）
 */
export interface NoteTreeNode {
  /** 节点名：folder=目录名；note=笔记标题 */
  name: string
  /** 节点类型：folder / note */
  type: 'folder' | 'note'
  /** note 专属：对外引用键（数据驱动恒有值） */
  nid: string
  /** note 专属：最近更新（yyyy-MM-dd HH:mm:ss）；folder 节点不填 */
  updateTime: string | null
  /** folder 专属：子节点 */
  children?: NoteTreeNode[]
}

/** M3.8 GET /api/portal/notes/{nid} 笔记内容 */
export interface NoteContent {
  /** 对外引用键 */
  nid: string
  /** 标题 */
  title: string
  /** Markdown 原文 */
  contentMd: string
  /** 所在目录路径（根目录为 ""） */
  dirPath: string
  /** 最近更新时间（yyyy-MM-dd HH:mm:ss） */
  updateTime: string | null
  /** 文件字节数 */
  sizeBytes: number
  /** v4：贡献者（纯展示，不承担切换导航） */
  contributors: Contributor[]
}

/**
 * v3 NoteSummaryVO（search / by-tag / recent 三接口共用；只读索引，不读文件内容）
 * 🔥 移除 agentId/agentName 单值；✨ 增 contributors；v4 补 notebookId/notebookName（不定义硬壳，读 contributors）
 */
export interface NoteSummary {
  /** 对外引用键 */
  nid: string
  /** 标题 */
  title: string
  /** 所在目录路径（根目录为 ""） */
  dirPath: string
  /** 最近更新时间（yyyy-MM-dd HH:mm:ss） */
  updateTime: string | null
  /** 贡献者（多标签；纯展示，跳转深链用当前 notebookId） */
  contributors: Contributor[]
}

/** v2.1 TagCount（tags 聚合：count 降序再 tag 字典序） */
export interface TagCount {
  /** 标签名（已 trim） */
  tag: string
  /** 命中笔记数 */
  count: number
}

/**
 * 可选参数 query 拼接：空串/undefined/null 不参与（notebookId 必填，后端 Long 参数传空串会类型解析失败）
 */
function qs(params: Record<string, string | number | null | undefined>): string {
  const query = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&')
  return query ? `?${query}` : ''
}

export const portalApi = {
  /** 站点信息（站点标题 / 描述 / 门户质感方案） */
  site: () => request<PortalSite>('/api/portal/site'),

  /** 全部启用笔记本概览（卡片网格数据源；含 noteCount/agentCount/lastNoteAt） */
  notebooks: () => request<PortalNotebook[]>('/api/portal/notebooks'),

  /**
   * 某 agent 名下已关联笔记本概览（智能体详情页「笔记本」Tab 数据源）
   * 与 /notebooks 同一 VO 口径（noteCount/lastNoteAt/agentCount）；agent 不存在 → 404
   */
  agentNotebooks: (agentId: number | string) =>
    request<PortalNotebook[]>(`/api/portal/agents/${encodeURIComponent(String(agentId))}/notebooks`),

  /**
   * M4.1 兼容保留：active agent 列表（门户「目标」入口取默认 agentId 用）
   * 含 notebookName/noteCount/lastNoteAt；仅 status=1，按 createTime 升序
   */
  agents: () => request<PortalAgent[]>('/api/portal/agents'),

  /** 笔记本目录树（v4：WHERE notebook_id 过滤 + dir_path 内存聚合；nid 恒有值） */
  tree: (notebookId: number | string) =>
    request<NoteTreeNode[]>(`/api/portal/notebooks/${encodeURIComponent(String(notebookId))}/notes/tree`),

  /** M3.8 笔记内容（nid 走路径；含 contributors 贡献者列表） */
  contentByNid: (nid: string) =>
    request<NoteContent>(`/api/portal/notes/${encodeURIComponent(nid)}`),

  /** M3.8 标题搜索（notebookId 走路径；keyword 必填非空、≤100；limit 可选，默认 20 最大 50） */
  searchNotes: (params: { notebookId: number | string; keyword: string; limit?: number }) =>
    request<NoteSummary[]>(
      `/api/portal/notebooks/${encodeURIComponent(String(params.notebookId))}/notes/search${qs({ keyword: params.keyword, limit: params.limit })}`
    ),

  /** M3.8 tags 聚合（notebookId 走路径） */
  noteTags: (notebookId: number | string) =>
    request<TagCount[]>(
      `/api/portal/notebooks/${encodeURIComponent(String(notebookId))}/notes/tags`
    ),

  /** M3.8 按 tag 精确筛选（notebookId 走路径；tag 必填非空、≤50；limit 默认 20） */
  notesByTag: (params: { notebookId: number | string; tag: string; limit?: number }) =>
    request<NoteSummary[]>(
      `/api/portal/notebooks/${encodeURIComponent(String(params.notebookId))}/notes/by-tag${qs({ tag: params.tag, limit: params.limit })}`
    ),

  /** M3.8 最近更新（notebookId 走路径；limit 可选默认 10 最大 50） */
  recentNotes: (params: { notebookId: number | string; limit?: number }) =>
    request<NoteSummary[]>(
      `/api/portal/notebooks/${encodeURIComponent(String(params.notebookId))}/notes/recent${qs({ limit: params.limit })}`
    ),

  /** M4.1 某 agent 目标 todolist（进度/状态/周期/截止；agent 不存在 → 404） */
  objectivesByAgent: (agentId: number | string) =>
    request<PortalObjective[]>(
      `/api/portal/agents/${encodeURIComponent(String(agentId))}/objectives`
    ),

  /** M4.1 某 agent 目标 todolist 分页（流式加载；page 1 起，size 默认 20 最大 50；status 可选筛选 0未开始/1进行中/2已完成/3已放弃，缺省全部） */
  objectivesPageByAgent: (params: {
    agentId: number | string
    status?: number
    page?: number
    size?: number
  }) =>
    request<BPage<PortalObjective>>(
      `/api/portal/agents/${encodeURIComponent(String(params.agentId))}/objectives/page${qs({ status: params.status, page: params.page, size: params.size })}`
    ),

  /** M4.1 目标详情（md 全文 + checkbox 子项；目标不存在/已软删 → 404） */
  objectiveByOid: (oid: string) =>
    request<PortalObjectiveDetail>(`/api/portal/objectives/${encodeURIComponent(oid)}`),

  /** 某 agent 目标状态计数（全量口径，不受筛选影响；筛选胶囊/Tab 角标用；agent 不存在 → 404） */
  objectivesStatusCountsByAgent: (agentId: number | string) =>
    request<PortalStatusCounts>(
      `/api/portal/agents/${encodeURIComponent(String(agentId))}/objectives/status-counts`
    ),

  /** M4.2 某 agent 台账流（create_time 倒序，只读索引；agent 不存在 → 404） */
  ledgerByAgent: (agentId: number | string) =>
    request<PortalLedger[]>(
      `/api/portal/agents/${encodeURIComponent(String(agentId))}/ledger`
    ),

  /** M4.2 某 agent 台账流分页（流式加载；page 1 起，size 默认 20 最大 50） */
  ledgerPageByAgent: (params: { agentId: number | string; page?: number; size?: number }) =>
    request<BPage<PortalLedger>>(
      `/api/portal/agents/${encodeURIComponent(String(params.agentId))}/ledger/page${qs({ page: params.page, size: params.size })}`
    ),

  /** M4.2 全部 agent 台账聚合流（create_time 倒序，含 agentName） */
  ledgerAggregate: () => request<PortalLedger[]>('/api/portal/ledger'),

  /** M4.2 全部 agent 台账聚合流分页（流式加载，含 agentName；page 1 起，size 默认 20 最大 50） */
  ledgerPageAggregate: (params: { page?: number; size?: number }) =>
    request<BPage<PortalLedger>>(`/api/portal/ledger/page${qs({ page: params.page, size: params.size })}`),

  /** 台账详情（读正文：inline 优先，否则读 md；匿名只读，404 fail-closed） */
  ledgerDetail: (lid: string) =>
    request<PortalLedgerDetail>(`/api/portal/ledger/${encodeURIComponent(lid)}`),

  memoriesPageByAgent: (params: { agentId: number | string; page?: number; size?: number }) =>
    request<BPage<PortalMemory>>(
      `/api/portal/agents/${encodeURIComponent(String(params.agentId))}/memories/page${qs({ page: params.page, size: params.size })}`,
    ),

  /**
   * M5.2 某 agent 待办分页（匿名只读；priority DESC, id ASC；status 可选筛选；page 1 起，size 默认 20 最大 50）
   * agent 不存在 → 404；仅 is_deleted=0（fail-closed）
   */
  todosPageByAgent: (params: {
    agentId: number | string
    status?: number
    page?: number
    size?: number
  }) =>
    request<BPage<PortalTodo>>(
      `/api/portal/agents/${encodeURIComponent(String(params.agentId))}/todos${qs({ status: params.status, page: params.page, size: params.size })}`
    ),

  /** 某 agent 待办状态计数（全量口径，不受筛选影响；Tab 角标用；agent 不存在 → 404） */
  todosStatusCountsByAgent: (agentId: number | string) =>
    request<PortalStatusCounts>(
      `/api/portal/agents/${encodeURIComponent(String(agentId))}/todos/status-counts`
    ),

  /** M6.1 某 agent 活动流（匿名只读；create_time 倒序；limit 默认 50 上限 200；agent 不存在 → 空列表）；M6.2 增加可选 sessionKey 精确过滤（话题轨迹页拉单会话事件序列） */
  agentActivity: (agentId: number | string, limit?: number, sessionKey?: string) =>
    request<{ list: PortalActivity[] }>(
      `/api/portal/agents/${encodeURIComponent(String(agentId))}/activity${qs({ limit, sessionKey })}`
    ),

  /** M6.2 某 agent 话题聚合（匿名只读；按会话分组统计 + 确定性诊断 flags；limit 默认 20 上限 50） */
  agentActivitySessions: (agentId: number | string, limit?: number) =>
    request<{ list: PortalActivitySession[] }>(
      `/api/portal/agents/${encodeURIComponent(String(agentId))}/activity/sessions${qs({ limit })}`
    ),

  /** M6.1 某 agent 当前状态聚合（匿名只读，服务端计算 running/idle；agent 不存在 → idle 空态） */
  agentActivityCurrent: (agentId: number | string) =>
    request<PortalActivityCurrent>(
      `/api/portal/agents/${encodeURIComponent(String(agentId))}/activity/current`
    ),

  /** v4.18 某 agent 活动按天聚合（匿名只读；days 默认 14，服务端钳制 1~31；零事件日不返回，前端按 statDate 补 gap 柱） */
  agentActivityStats: (agentId: number | string, days?: number) =>
    request<PortalActivityStatsResp>(
      `/api/portal/agents/${encodeURIComponent(String(agentId))}/activity/stats${qs({ days })}`
    ),

  /** 门户 bus 消息流转分页（匿名只读；id 倒序；筛选 from/to/type/status/topic/days/keyword 可空；page 1 起，size 默认 20 上限 100；bus-ui-v2 扩参） */
  busMessages: (params: {
    from?: number
    to?: number
    type?: string
    status?: number
    topic?: string
    /** 近 N 天（1~366；缺省/0 = 不过滤） */
    days?: number
    /** 标题 / 主题 LIKE 关键词 */
    keyword?: string
    /** 投递通道筛选：ws/api（bus-ui-v2；缺省=全部） */
    channel?: string
    page?: number
    size?: number
  }) => request<BPage<PortalBusMessage>>(`/api/portal/bus/messages${qs(params)}`),

  /** M1 门户包 消息流转统计（今日进线/已投递/待投/失败；除今日外全时段口径） */
  busStats: () => request<PortalBusStats>('/api/portal/bus/stats'),

  /**
   * KB 混合检索（标题 + BM25 全文 + 语义向量，RRF 融合；匿名只读）
   * notebookId 缺省 = 全库搜；未建索引/key 未配置自动降级（只走标题路）
   */
  kbSearch: (params: { q: string; notebookId?: number | string | null; limit?: number }) =>
    request<KbHit[]>(`/api/portal/kb/search${qs({ q: params.q, notebookId: params.notebookId ?? undefined, limit: params.limit })}`),

  /** KB RAG 问答（检索 top6 片段 → qwen 生成答案 + 引用列表；notebookId 缺省 = 全库） */
  kbAsk: (params: { question: string; notebookId?: number | string | null }) =>
    request<KbAsk>('/api/portal/kb/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: params.question, notebookId: params.notebookId ?? undefined })
    })
}

/** GET /api/portal/kb/search 命中项 */
export interface KbHit {
  /** 笔记对外引用键（跳转 /notebooks/{notebookId}/notes/{nid}） */
  nid: string
  /** 所属笔记本 id */
  notebookId: number
  /** 笔记标题 */
  title: string
  /** 目录路径 */
  dirPath: string
  /** 命中片段（最相关分块前 ~160 字） */
  snippet: string
  /** RRF 融合得分 */
  score: number
  /** 命中来源：title / bm25 / vector */
  sources: string[]
}

/** POST /api/portal/kb/ask 应答 */
export interface KbAsk {
  /** 模型答案（markdown，含 [n] 引用标注） */
  answer: string
  /** 引用笔记列表（与答案 [n] 编号对应） */
  references: { nid: string; notebookId: number; title: string; dirPath: string }[]
}

/** GET /api/portal/site */
export interface PortalSite {
  siteTitle: string
  siteDescription: string
  /** 门户质感方案（aurora 柔光 / relief 浮雕 / grid 网格），管理平台站点设置下发，门户端只读应用 */
  portalTexture: string
}

/** M4.1 兼容保留：GET /api/portal/agents 返回的 agent 项（门户「目标」入口取 agentId） */
export interface PortalAgent {
  id: number
  name: string
  displayName?: string | null
  /** v4.3：头像 URL（/uploads/... 相对路径；空=无头像，前端走 tone 渐变色块兜底） */
  avatar?: string | null
  /** 笔记本名（display_name 优先回退 name） */
  notebookName: string
  status: number
  noteCount: number
  lastNoteAt?: string | null
}

/**
 * M4.1 GET /api/portal/agents/{agentId}/objectives 目标列表项
 * （agent 私域 todolist：进度/状态/周期/截止）
 */
export interface PortalObjective {
  /** 对外引用键 o_xxx（详情/深链用） */
  oid: string
  /** 目标标题 */
  title: string
  /** 状态：0未开始 1进行中 2已完成 3已放弃 */
  status: number
  /** 周期：2026-Q4 / 2026 / 自由文本 */
  period?: string | null
  /** 截止日期 yyyy-MM-dd */
  targetDate?: string | null
  /** 进度 0-100（详情 md 子项 checkbox 解析结果） */
  progress: number
  /** 最近更新时间 */
  updateTime?: string | null
}

/**
 * GET /api/portal/agents/{agentId}/objectives/status-counts 目标状态计数（全量）
 * （同构复用于 todos/status-counts；total = 四态之和，不受筛选影响）
 */
export interface PortalStatusCounts {
  /** 全部 */
  total: number
  /** 未开始/待办（status=0） */
  pending: number
  /** 进行中（status=1） */
  doing: number
  /** 已完成（status=2） */
  done: number
  /** 已放弃（status=3） */
  dropped: number
}

/**
 * M4.1 GET /api/portal/objectives/{oid} 目标详情
 * （md 全文 + checkbox 子项解析结果）
 */
export interface PortalObjectiveDetail extends PortalObjective {
  /** 详情 md 全文（`## 描述` + `## 子项` + `## 反思`） */
  contentMd: string
  /** checkbox 子项（- [x]/- [ ]） */
  subItems: { done: boolean; text: string }[]
}

/** 台账类型（契约 §1.4） */
export type PortalLedgerType = 'progress' | 'observation' | 'decision' | 'memo' | 'session'

/**
 * M4.2 GET /api/portal/agents/{agentId}/ledger 台账流项 / GET /api/portal/ledger 聚合流项
 * （只读索引：列表/流接口不返回正文，正文由 ledgerDetail 详情接口另行读取）
 */
export interface PortalLedger {
  /** 对外引用键 l_xxx */
  lid: string
  /** 归属 agent id（聚合流回填） */
  agentId?: number
  /** 归属 agent 展示名（display_name 优先回退 name；聚合流回填） */
  agentName?: string
  /** 类型：progress/observation/decision/memo/session */
  type: PortalLedgerType
  /** 条目标题 */
  title: string
  /** 关联任务 tid（可选） */
  refTaskId?: string | null
  /** 关联需求 rid（可选） */
  refRequirementId?: string | null
  /** 关联目标 oid（可选） */
  refObjectiveId?: string | null
  /** 关联笔记 nid（可选） */
  refNoteNid?: string | null
  /** 创建时间（流水时间 yyyy-MM-dd HH:mm:ss） */
  createTime: string
}

/**
 * GET /api/portal/ledger/{lid} 台账详情（索引字段 + 正文 body 全文）
 * （读正文：body_inline 优先，否则读 md 文件；不存在/软删/孤儿 → 404 fail-closed）
 */
export interface PortalLedgerDetail extends PortalLedger {
  /** 正文全文（≤500 字 inline / >500 字 md，markdown 文本） */
  body: string | null
}

/**
 * M5.2 GET /api/portal/agents/{agentId}/todos 待办分页项
 * （agent 私域 todolist，调研池迁入；M5.3 排序：有截止日期在前按日期升序，无日期按 priority DESC, id ASC）
 */
/**
 * M7.1-M3 GET /api/portal/agents/{agentId}/memories/page 记忆分页项
 * （门户白名单字段：仅活跃 status=1，含 contentMd 全文，无独立详情接口）
 */
export interface PortalMemory {
  id: number
  memType: string
  title: string
  contentMd: string
  tags: string | null
  source: string
  weight: number
  accessCount: number
  createTime: string
  updateTime: string
}

export interface PortalTodo {
  /** 对外引用键 d_xxx */
  todoId: string
  /** 标题 */
  title: string
  /** md 正文：来源/原因/产出要求 */
  content?: string | null
  /** 状态：0待办 1进行中 2已完成 3已放弃 */
  status: number
  /** 优先级 1-5（默认 3） */
  priority: number
  /** 逗号分隔标签（如 "调研,池子"） */
  tags?: string | null
  /** 截止日期 yyyy-MM-dd（M5.3 新增，可空；有值排前，过期标红） */
  dueDate?: string | null
  /** 创建时间（yyyy-MM-dd HH:mm:ss） */
  createTime: string
  /** 最近更新时间（yyyy-MM-dd HH:mm:ss） */
  updateTime: string
}

/** M6.1 活动流事件（GET /api/portal/agents/{id}/activity 列表项，匿名只读） */
export interface PortalActivity {
  /** 事件 id（自增，时间线展示键） */
  id: number
  /** 事件类型：turn_started/status_changed/tool_call/turn_completed */
  eventType: string
  /** 工具名（tool_call 时有值） */
  toolName?: string | null
  /** 摘要（服务端已截断 512） */
  summary?: string | null
  /** 耗时毫秒（tool_call/turn_completed 时有值） */
  durationMs?: number | null
  /** 结果：completed/failed/interrupted（turn_completed 时有值） */
  outcome?: string | null
  /** 来源会话 key */
  sessionKey?: string | null
  /** 事件时间（yyyy-MM-dd HH:mm:ss） */
  createTime: string
}

/** M6.2 话题聚合行（GET /api/portal/agents/{id}/activity/sessions，服务端计算） */
export interface PortalActivitySession {
  /** 会话 key（如 websocket:7257d081-...） */
  sessionKey: string
  /** 话题摘要（该会话最早 turn_started 的 summary；无 turn_started 时 null） */
  topicSummary?: string | null
  /** turn_started 事件数 */
  turnCount: number
  /** tool_call 事件数 */
  toolCallCount: number
  /** outcome=failed 的 tool_call 事件数 */
  errorCount: number
  /** durationMs 合计（毫秒；全部事件叠加口径） */
  totalActiveMs?: number | null
  /** 最后事件时间（yyyy-MM-dd HH:mm:ss） */
  lastEventTime?: string | null
  /** 确定性诊断 flags（固定顺序：loop_suspect/long_turn/long_tool/error_prone/stalled；无异常为空列表） */
  flags: string[]
  /** 诊断依据（flag → 人话解释，如 "同参数工具调用重复 5 次，如：git status"；徽标 tooltip） */
  flagDetails?: Record<string, string> | null
}

/** M6.1 当前状态聚合（GET /api/portal/agents/{id}/activity/current，服务端计算） */
export interface PortalActivityCurrent {
  /** running / idle（10 分钟兜底：最后事件距今超 10 分钟强制 idle） */
  state: 'running' | 'idle'
  /** 正在执行的工具名（running 且最后事件为 tool_call 时有值） */
  toolName?: string | null
  /** 距最后一条事件毫秒数 */
  sinceMs?: number | null
  /** 最近一次 turn_completed 的结果（无完成事件时 null） */
  lastOutcome?: string | null
  /** 最后一条事件摘要 */
  lastSummary?: string | null
  /** 最后一条事件时间（yyyy-MM-dd HH:mm:ss） */
  lastEventTime?: string | null
}

/** v4.18 活动按天聚合行（GET /api/portal/agents/{id}/activity/stats 列表项，匿名只读） */
export interface PortalActivityDailyStat {
  /** 日期 yyyy-MM-dd（北京墙钟；零事件日不出现在列表里，前端补 gap 灰柱） */
  statDate: string
  /** 当日事件总数 */
  events: number
  /** 当日独立会话数（COUNT DISTINCT session_key） */
  sessions: number
  /** 当日 tool_call 事件数 */
  tools: number
  /** 当日 turn_completed 事件数 */
  turns: number
}

/** v4.18 活动按天聚合应答（data = { list, days }，days 为服务端钳制后窗口） */
export interface PortalActivityStatsResp {
  list: PortalActivityDailyStat[]
  days: number
}

/**
 * M1 门户包 GET /api/portal/bus/messages 消息流转行（匿名只读，字段白名单）
 * 后端绝不返回 payload/sessionKey/model/lastError/messageId（防内部内容泄漏）
 */
export interface PortalBusMessage {
  /** 主键（id 倒序排序键） */
  id: number
  /** 生产者 agent id（hover 显示） */
  fromAgentId: number
  /** 生产者展示名（displayName 优先回退 name） */
  fromAgentName: string | null
  /** 消费者 agent id（hover 显示） */
  toAgentId: number
  /** 消费者展示名 */
  toAgentName: string | null
  /** 类型：notify/alert */
  msgType: string
  /** 场景标签 */
  topic: string | null
  /** 标题 */
  title: string | null
  /** 状态：0待投递/1已送达/2已读(M2)/3失败 */
  status: number
  /** 实际投递通道：ws/api；历史行 NULL（前端显「—」，bus-ui-v2） */
  channel: string | null
  /** 重试次数（>0 才展示） */
  retryCount: number
  /** 送达时间（yyyy-MM-dd HH:mm:ss） */
  deliveredAt: string | null
  /** 创建时间（yyyy-MM-dd HH:mm:ss） */
  createTime: string | null
}

/** M1 门户包 GET /api/portal/bus/stats 统计胶囊（除「今日」外均为全时段口径） */
export interface PortalBusStats {
  /** 今日进线 */
  todayTotal: number
  /** 已投递（status=1） */
  delivered: number
  /** 待投（status=0） */
  pending: number
  /** 失败（其余状态合计） */
  failed: number
  /** 热门主题 top6（全时段计数，count 降序；bus-ui-v2） */
  hotTopics: PortalTopicCount[]
  /** 全部主题计数（筛选下拉数据源，count 降序、同数按 topic 升序；bus-ui-v2） */
  topics: PortalTopicCount[]
}

/** 主题计数项（bus-ui-v2） */
export interface PortalTopicCount {
  /** 场景标签 */
  topic: string
  /** 消息条数（全时段） */
  count: number
}
