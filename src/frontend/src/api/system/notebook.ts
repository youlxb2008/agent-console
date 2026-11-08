import { get, post, put, del } from '../request'
import type { ApiResponse, PageQuery, PageResponse } from '@/types/api'

/**
 * notebook 域管理端接口封装（v3.5 独立笔记本实体，契约 api.md v4.0 §2.6）
 *
 * 后端分页返回 MyBatis-Plus IPage（records/total），
 * 这里在 PageResponse（list/total）基础上保留 records 字段，
 * useCrud 会优先取 list、回退取 records，两种结构均兼容。
 */

/** 管理端笔记本列表项（对应后端 NotebookVO，软删行不返回） */
export interface NotebookItem {
  id: number
  /** 笔记本唯一业务名（未软删范围内唯一） */
  name: string
  /** 简介（门户卡片副文案） */
  description?: string
  /** 封面 URL（可空，列表/门户做首字母色块兜底） */
  cover?: string
  /** v4.17 logo URL（可空，笔记页左上角小标识，方图） */
  logo?: string
  /** v4.17 hero URL（可空，笔记页空态中央展示图，宽幅） */
  hero?: string
  /** 1=active 0=hidden */
  status: number
  /** 笔记数（t_note_meta.notebook_id 聚合） */
  noteCount: number
  /** 关联 agent 数（t_agent_notebook 聚合） */
  agentCount: number
  createTime?: string
  updateTime?: string
}

/** 笔记本分页响应 */
export interface NotebookPage extends PageResponse<NotebookItem> {
  records?: NotebookItem[]
}

/** 笔记本查询参数（name 模糊） */
export interface NotebookQuery extends PageQuery {
  keyword?: string
}

/** 新建/编辑请求（对应后端 NotebookSaveReq；name 唯一由后端校验） */
export interface NotebookPayload {
  name: string
  description?: string
  cover?: string
  /** v4.17 logo URL（可选） */
  logo?: string
  /** v4.17 hero URL（可选） */
  hero?: string
  /** 1=active 0=hidden，新建缺省 1 */
  status?: number
}

/** 笔记本下已关联 agent（GET /{id}/agents，对应后端 NotebookAgentVO） */
export interface NotebookAgentItem {
  agentId: number
  name: string
  displayName?: string
  /** 关联时间（t_agent_notebook.create_time） */
  linkTime?: string
}

/**
 * agent 已归属笔记本（GET /agents/{id}/notebooks）
 *
 * 后端实际返回 NotebookVO 数组，主键字段为 `id`（未软删笔记本）。
 * 为兼容设计文档的 `notebookId` 语义，读取时统一走
 * `item.id ?? item.notebookId`（本接口响应以 `id` 为准）。
 */
export interface AgentNotebookItem {
  /** 笔记本 id（后端 NotebookVO 实际返回字段） */
  id?: number
  /** 兼容语义的别名键（后端未返回，恒缺省） */
  notebookId?: number
  name: string
  /** 关联时间（后端当前未返回，展示兜底 '—'） */
  linkTime?: string
}

/* ==================== 笔记本 CRUD（notebook:list / create / edit / delete） ==================== */

/** 笔记本分页（notebook:list；含 noteCount/agentCount 聚合，过滤 is_deleted=0） */
export function getNotebookList(params: NotebookQuery): Promise<ApiResponse<NotebookPage>> {
  return get<NotebookPage>('/system/notebook/page', params)
}

/** 全量下拉（notebook:list；未软删，供笔记页过滤与穿梭框左侧） */
export function getAllNotebooks(): Promise<ApiResponse<NotebookItem[]>> {
  return get<NotebookItem[]>('/system/notebook/all')
}

/** 新建笔记本（notebook:create；name 唯一由后端校验） */
export function createNotebook(data: NotebookPayload): Promise<ApiResponse<{ id: number; name: string; status: number }>> {
  return post<{ id: number; name: string; status: number }>('/system/notebook', data)
}

/**
 * 编辑笔记本（notebook:edit；后端 PUT "" 需 body 带 id + name）
 * 状态行内切换亦走此接口（此时仅传 name + status）。
 */
export function updateNotebook(id: number, data: NotebookPayload): Promise<ApiResponse<{ id: number; name: string; status: number }>> {
  return put<{ id: number; name: string; status: number }>('/system/notebook', { id, ...data })
}

/** 软删笔记本（notebook:delete；name 改写释放原名 + 解除全部 agent 关联，笔记数据保留） */
export function deleteNotebook(id: number): Promise<ApiResponse> {
  return del(`/system/notebook/${id}`)
}

/* ==================== agent 关联（notebook:agent:list / notebook:agent:link） ==================== */

/** 笔记本下已关联 agent 列表（notebook:agent:list；含关联时间 linkTime） */
export function getNotebookAgents(id: number): Promise<ApiResponse<NotebookAgentItem[]>> {
  return get<NotebookAgentItem[]>(`/system/notebook/${id}/agents`)
}

/** 笔记本→agent 关联全量覆盖（notebook:agent:link；目标集合即最终态，空数组=清空） */
export function updateNotebookAgents(id: number, agentIds: number[]): Promise<ApiResponse> {
  return put(`/system/notebook/${id}/agents`, { agentIds })
}

/** agent 的所属笔记本列表（notebook:agent:list；agent 页「笔记本」弹窗数据源） */
export function getAgentNotebooks(agentId: number): Promise<ApiResponse<AgentNotebookItem[]>> {
  return get<AgentNotebookItem[]>(`/agent/admin/agents/${agentId}/notebooks`)
}

/** agent→笔记本关联全量覆盖（notebook:agent:link；body=notebookIds[]；单条解除=去掉该项后覆盖提交） */
export function updateAgentNotebooks(agentId: number, notebookIds: number[]): Promise<ApiResponse> {
  return put(`/agent/admin/agents/${agentId}/notebooks`, { notebookIds })
}