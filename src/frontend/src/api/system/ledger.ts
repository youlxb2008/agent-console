import { get, post, patch, del } from '../request'
import type { ApiResponse, PageQuery } from '@/types/api'

/**
 * 台账域管理端接口封装（M4.2，对应后端 LedgerAdminController）
 *
 * 后端分页返回 BPage：{curPage, count, pageSize, list}（任务书口径对齐 objective 模块），
 * 详情见 views/system/ledger/index.vue 的 selfPaging 逻辑。
 */

/** 台账列表项（对应后端 LedgerVO；只读索引，不含正文） */
export interface LedgerItem {
  /** 对外引用键 l_xxx */
  lid: string
  /** 归属 agent id（管理端回填） */
  agentId?: number
  /** 归属 agent 展示名（display_name 优先回退 name） */
  agentName?: string
  /** 类型：progress/observation/decision/memo/session */
  type: string
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
  /** 创建时间 yyyy-MM-dd HH:mm:ss */
  createTime?: string
}

/** 台账详情（对应后端 LedgerDetailVO；详情接口读正文） */
export interface LedgerDetail extends LedgerItem {
  /** 正文全文（body_inline 优先；>500 字读 md） */
  body?: string | null
}

/** 台账分页响应（BPage：curPage/count/pageSize/list） */
export type LedgerType = 'progress' | 'observation' | 'decision' | 'memo' | 'session'

export interface LedgerPage {
  curPage: number
  count: number
  pageSize: number
  list: LedgerItem[]
}

/** 台账查询参数 */
export interface LedgerQuery extends PageQuery {
  /** 按 agent 过滤 */
  agentId?: number
  /** 按类型过滤 */
  type?: string
}

/** 记一笔 / 编辑请求 */
export interface LedgerPayload {
  /** 类型（5 种之一） */
  type: string
  /** 条目标题 */
  title: string
  /** 正文（可选；≤500 字 inline，>500 字写 md） */
  body?: string
  refTaskId?: string | null
  refRequirementId?: string | null
  refObjectiveId?: string | null
  refNoteNid?: string | null
}

/** 台账分页（ledger:list；agentId / type 筛选） */
export function getLedgerList(params: LedgerQuery): Promise<ApiResponse<LedgerPage>> {
  return get<LedgerPage>('/ledger/admin/ledger', params)
}

/** 台账详情（ledger:list；读正文） */
export function getLedgerDetail(lid: string): Promise<ApiResponse<LedgerDetail>> {
  return get<LedgerDetail>(`/ledger/admin/ledger/${lid}`)
}

/** 管理员代记（ledger:create；agentId 走 query 必填） */
export function createLedger(agentId: number, data: LedgerPayload): Promise<ApiResponse> {
  return post(`/ledger/admin/ledger?agentId=${agentId}`, data)
}

/** 管理员代改（ledger:edit；全空 → 400） */
export function updateLedger(lid: string, data: Partial<LedgerPayload>): Promise<ApiResponse> {
  return patch(`/ledger/admin/ledger/${lid}`, data)
}

/** 软删台账（ledger:delete；is_deleted=1，长正文 md 保留，二次确认） */
export function deleteLedger(lid: string): Promise<ApiResponse> {
  return del(`/ledger/admin/ledger/${lid}`)
}
