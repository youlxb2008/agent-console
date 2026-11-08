import { get, post, put, del } from '../request'
import type { ApiResponse, PageQuery, PageResponse } from '@/types/api'

/**
 * agent 域管理端接口封装（契约 docs/接口契约/api.md §2）
 *
 * 后端分页返回 MyBatis-Plus IPage（records/total），
 * 这里在 PageResponse（list/total）基础上保留 records 字段，
 * useCrud 会优先取 list、回退取 records，两种结构均兼容。
 */

/** 管理端 agent 列表项（对应后端 AgentAdminVO） */
export interface AgentItem {
  id: number
  name: string
  displayName?: string
  /** 头像 URL（/uploads/... 相对路径；空=无头像，前端色块兜底） */
  avatar?: string | null
  description?: string
  /** 1=active 0=revoked（吊销，可随时重新启用；M5.4-R1 删除语义修正） */
  status: number
  /** active key 数（聚合自 t_api_key） */
  keyCount: number
  /** v3：关联笔记数（t_agent_resource 聚合，无主化后的关联语义） */
  noteCount: number
  /** 最近使用时间（所有 key 中 max(last_used_at)） */
  lastUsedAt?: string | null
  createTime?: string
}

/** agent 分页响应 */
export interface AgentPage extends PageResponse<AgentItem> {
  records?: AgentItem[]
}

/** 管理端 API Key 列表项（对应后端 ApiKeyAdminVO；含明文便于复制，永不返回 hash） */
export interface ApiKeyItem {
  id: number
  agentId: number
  /** 明文前缀（识别用，非完整 key） */
  keyPrefix: string
  /** 明文 key（历史 key 复制用；null=旧 key 未存明文，不可复制） */
  keyPlain?: string | null
  /** 权限列表（JSON 反序列化） */
  scopes: string[]
  expiresAt?: string | null
  lastUsedAt?: string | null
  createTime?: string
}

/** 审计日志项（对应后端 AuditLog） */
export interface AuditLogItem {
  id: number
  /** 操作主体（agent 名/用户名） */
  principal: string
  /** 操作类型：key.create / key.verify / note.write / note.delete ... */
  action: string
  /** 操作对象（nid / key id ...） */
  resource?: string
  /** 请求摘要 JSON（脱敏） */
  request?: string
  /** success / denied / error */
  result: string
  /** 客户端 IP */
  clientIp?: string
  createTime?: string
}

/** 审计分页响应 */
export interface AuditLogPage extends PageResponse<AuditLogItem> {
  records?: AuditLogItem[]
}

/** 创建 agent 请求（对应 AgentCreateReq；status 缺省 0=suspended 待审批） */
export interface AgentCreatePayload {
  name: string
  displayName?: string
  /** 头像 URL（可选，来自 /system/upload 返回值） */
  avatar?: string
  description?: string
  /** 1=active 0=suspended */
  status?: number
}

/** 更新 agent 请求（对应 AgentUpdateReq；name 唯一不可改） */
export interface AgentUpdatePayload {
  displayName?: string
  /** 头像 URL（空串=清除头像；undefined=不修改） */
  avatar?: string
  description?: string
  status?: number
}

/** 生成 API Key 请求（对应 KeyCreateReq） */
export interface KeyCreatePayload {
  agentId: number
  scopes: string[]
  /** 过期时间，null=永久 */
  expiresAt?: string | null
}

/** 生成 key 响应：明文 key 已落库，生成时即时展示，之后也可在列表中复制 */
export interface KeyCreateResult {
  keyId: number
  /** 明文 key（生成后落库，列表页可随时查看/复制） */
  key: string
  keyPrefix: string
  scopes: string[]
  expiresAt?: string | null
}

/** 修改 API Key 请求（对应 KeyUpdateReq；null=不修改，M5.4 新增） */
export interface KeyUpdatePayload {
  /** 权限列表（非空时整体覆盖） */
  scopes?: string[]
  /** 过期时间（null=不修改；不支持清空改永久，需改永久请删除后重新生成） */
  expiresAt?: string | null
}

/** agent 查询参数 */
export interface AgentQuery extends PageQuery {
  keyword?: string
}

/** 审计查询参数 */
export interface AuditQuery extends PageQuery {
  principal?: string
  action?: string
  result?: string
  /** ISO-8601，如 2026-08-27T00:00:00 */
  startTime?: string
  /** ISO-8601，如 2026-08-27T23:59:59 */
  endTime?: string
}

/* ==================== agent 管理 ==================== */

/** 分页列表（agent:agent:list） */
export function getAgentList(params: AgentQuery): Promise<ApiResponse<AgentPage>> {
  return get<AgentPage>('/agent/admin/agents', params)
}

/** 创建 agent（agent:agent:save；name 唯一） */
export function createAgent(data: AgentCreatePayload): Promise<ApiResponse<{ id: number; name: string; status: number }>> {
  return post('/agent/admin/agents', data)
}

/** 更新 agent（agent:agent:save；改 displayName/description/status，吊销/启用即刻生效） */
export function updateAgent(id: number, data: AgentUpdatePayload): Promise<ApiResponse<{ id: number; status: number }>> {
  return put(`/agent/admin/agents/${id}`, data)
}

// M5.4-R1：agent 删除接口已下线（agent 不支持删除，吊销走 updateAgent 的 status=0）

/* ==================== key 管理 ==================== */

/** key 列表（agent:key:list；展示 prefix/明文/scopes 等，永不返回 hash；key 改硬删除语义，无 status） */
export function getKeyList(agentId?: number): Promise<ApiResponse<ApiKeyItem[]>> {
  return get<ApiKeyItem[]>('/agent/admin/keys', { agentId })
}

/** 生成 key（agent:key:create；明文同时落库，历史 key 可随时查看/复制） */
export function createKey(data: KeyCreatePayload): Promise<ApiResponse<KeyCreateResult>> {
  return post<KeyCreateResult>('/agent/admin/keys', data)
}

/** 删除 key（agent:key:revoke；物理删除不可恢复，M5.4-R1 吊销概念下线） */
export function deleteAgentKey(id: number): Promise<ApiResponse<{ id: number }>> {
  return del(`/agent/admin/keys/${id}`)
}

/** 修改 key（agent:key:create；scopes/expiresAt null=不修改，已吊销 400，M5.4 新增） */
export function updateKey(id: number, data: KeyUpdatePayload): Promise<ApiResponse<{ id: number }>> {
  return put(`/agent/admin/keys/${id}`, data)
}

/**
 * 拉取 Skill 文档（agent:key:list；响应为 markdown 纯文本，不走 ResultVO 包装）
 *
 * 后端 produces=text/markdown，axios 以 responseType:'text' 接收；
 * request.ts 响应拦截器对「无 code 字段的非标准响应」直通（返回原始 AxiosResponse），
 * 因此这里取 res.data 即 markdown 字符串（类型上断言为 any 再收窄）。
 */
export async function fetchSkillDoc(): Promise<string> {
  const res = await get('/agent/admin/skill-doc', undefined, { responseType: 'text' })
  const md = (res as unknown as { data?: unknown })?.data
  return typeof md === 'string' ? md : String(res)
}

/* ==================== 审计查询 ==================== */

/** 审计分页查询（agent:audit:list） */
export function getAuditList(params: AuditQuery): Promise<ApiResponse<AuditLogPage>> {
  return get<AuditLogPage>('/agent/admin/audit', params)
}
