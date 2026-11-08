import { get, post, put, del } from '../request'
import type { ApiResponse, PageQuery, PageResponse } from '@/types/api'
import type { AgentItem } from './agent'

/**
 * 笔记域管理端接口封装（v3 拆分自 api/system/agent.ts，对应后端 NoteAdminController）
 *
 * 后端分页返回 MyBatis-Plus IPage（records/total），
 * useCrud 会优先取 list、回退取 records，两种结构均兼容。
 */

/** agent 精简项（对应后端 AgentSimpleVO；贡献者/关联弹窗共用） */
export interface AgentSimpleItem {
  id: number
  /** agent 名（机器名） */
  name: string
  /** 展示名（display_name 优先回退 name；门户即笔记本名） */
  displayName: string
}

/** 管理端笔记列表项（v3：🔥 移除 agentId/agentName/filePath，✨ 增 dirPath/contributors） */
export interface AdminNoteItem {
  /** 对外引用键 n_xxx */
  nid: string
  /** 笔记标题 */
  title: string
  /** 目录路径（树依据，根目录为 ""） */
  dirPath: string
  /** 逗号分隔标签 */
  tags?: string | null
  /** 贡献者（全部关联 agent；列表渲染多个 tag，超 3 个显示 +N） */
  contributors?: AgentSimpleItem[]
  /** v3.5：所属笔记本 id（v4 直接 WHERE t_note_meta.notebook_id 过滤） */
  notebookId?: number
  /** v3.5：所属笔记本名（列表「所属笔记本」列展示；空兜底『无』） */
  notebookName?: string
  /** 最近更新时间（yyyy-MM-dd HH:mm:ss） */
  updateTime?: string
}

/** 管理端笔记全文预览（v3：agentName 单值 → contributors 多值） */
export interface AdminNoteContent {
  /** 对外引用键 */
  nid: string | null
  /** 标题 */
  title: string
  /** Markdown 原文 */
  contentMd: string
  /** 所在目录路径（根目录为 ""） */
  dirPath: string
  /** 贡献者（全部关联 agent） */
  contributors?: AgentSimpleItem[]
  /** 最近更新时间 */
  updateTime?: string
  /** 文件字节数 */
  sizeBytes?: number
}

/** 笔记分页响应 */
export interface AdminNotePage extends PageResponse<AdminNoteItem> {
  records?: AdminNoteItem[]
}

/** 笔记查询参数（agentId=按关联 agent 过滤，后端 JOIN t_agent_resource） */
export interface NoteQuery extends PageQuery {
  agentId?: number
  /** v3.5：按所属笔记本过滤（后端 D1 直接 WHERE t_note_meta.notebook_id） */
  notebookId?: number
  /** 标题 LIKE 搜索 */
  keyword?: string
}

/** agent 的笔记弹窗查询参数（GET /note/admin/agents/{agentId}/notes） */
export interface AgentNotesQuery extends PageQuery {
  keyword?: string
}

/* ==================== 笔记管理（agent:note:list / delete） ==================== */

/** 笔记索引分页（agent:note:list；迁自 /agent/admin/notes） */
export function getNoteList(params: NoteQuery): Promise<ApiResponse<AdminNotePage>> {
  return get<AdminNotePage>('/note/admin/notes', params)
}

/* ==================== 知识库索引（KB） ==================== */

/** KB 索引状态（GET /note/admin/kb/status） */
export interface KbStatus {
  /** 是否重建中（true=前端继续轮询） */
  rebuilding: boolean
  /** 本批总笔记数 */
  total: number
  /** 已完成数（含失败） */
  done: number
  /** 失败数 */
  failed: number
  /** 结束时汇总文案 */
  message: string
  /** 百炼 API Key 是否已配置（false=无语义检索/RAG，需在系统配置加 dashscope.api_key） */
  keyConfigured: boolean
  /** Lucene 全文索引是否可用 */
  luceneAvailable: boolean
}

/** KB 索引状态查询（agent:note:list） */
export function getKbStatus(): Promise<ApiResponse<KbStatus>> {
  return get<KbStatus>('/note/admin/kb/status')
}

/** KB 全量重建（agent:note:link；异步执行，轮询 getKbStatus 看进度） */
export function rebuildKb(): Promise<ApiResponse<null>> {
  return post<null>('/note/admin/kb/rebuild')
}

/** 笔记全文预览（agent:note:list；nid 定位读文件系统真相源，含 contributors） */
export function getNoteContent(nid: string): Promise<ApiResponse<AdminNoteContent>> {
  return get<AdminNoteContent>(`/note/admin/notes/${nid}/content`)
}

/** 物理删除笔记（agent:note:delete；文件+索引+全部关联行，审计 note.delete；不可恢复） */
export function deleteNote(nid: string): Promise<ApiResponse> {
  return del(`/note/admin/notes/${nid}`)
}

/** agent 关联笔记分页（agent:note:list；agent 管理页「笔记」弹窗数据源） */
export function getAgentNotes(agentId: number, params: AgentNotesQuery): Promise<ApiResponse<AdminNotePage>> {
  return get<AdminNotePage>(`/note/admin/agents/${agentId}/notes`, params)
}

/* ==================== 关联维护（agent:note:link，双向弹窗共用） ==================== */

/** 笔记已关联 agent 列表（agent:note:link；「关联 Agent」弹窗回显） */
export function getNoteAgents(nid: string): Promise<ApiResponse<AgentSimpleItem[]>> {
  return get<AgentSimpleItem[]>(`/note/admin/notes/${nid}/agents`)
}

/**
 * 笔记关联全量覆盖（agent:note:link；目标集合即最终态，空数组=清空全部关联；
 * 差集审计 note.link/note.unlink）
 */
export function updateNoteAgents(nid: string, agentIds: number[]): Promise<ApiResponse> {
  return put(`/note/admin/notes/${nid}/agents`, { agentIds })
}

/** 解除单条关联（agent:note:link；笔记与索引不动，审计 note.unlink） */
export function unlinkNoteAgent(nid: string, agentId: number): Promise<ApiResponse> {
  return del(`/note/admin/notes/${nid}/agents/${agentId}`)
}

/* ==================== 辅助 ==================== */

/** agent 下拉数据（分页接口取全量，供筛选/关联穿梭框；复用 agent.ts 接口） */
export function loadAgentOptions(): Promise<AgentItem[]> {
  // 延迟 import 规避与 agent.ts 的潜在循环依赖（当前无环，防御性写法）
  return import('./agent').then((m) => m.getAgentList({ page: 1, size: 1000 })).then(
    (res) => res.data?.records || res.data?.list || []
  ).catch(() => [] as AgentItem[])
}

/** agent 下拉 label：displayName 存在时「name（displayName）」，否则 name */
export function agentOptionLabel(a: { name: string; displayName?: string | null }): string {
  return a.displayName ? `${a.name}（${a.displayName}）` : a.name
}
