import { get, post, patch, del } from '../request'
import type { ApiResponse, PageQuery, PageResponse } from '@/types/api'

/**
 * 目标域管理端接口封装（M4.1，对应后端 ObjectiveAdminController）
 *
 * 后端分页返回 MyBatis-Plus IPage（records/total），
 * useCrud 会优先取 list、回退取 records，两种结构均兼容。
 */

/** 目标列表项（对应后端 ObjectiveVO） */
export interface ObjectiveItem {
  /** 对外引用键 o_xxx */
  oid: string
  /** 归属 agent id（管理端回填） */
  agentId?: number
  /** 归属 agent 展示名（display_name 优先回退 name） */
  agentName?: string
  /** 目标标题 */
  title: string
  /** 状态：0未开始 1进行中 2已完成 3已放弃 */
  status: number
  /** 周期：2026-Q4 / 2026 / 自由文本 */
  period?: string | null
  /** 截止日期 yyyy-MM-dd */
  targetDate?: string | null
  /** 进度 0-100 */
  progress: number
  /** 最近更新时间 */
  updateTime?: string
}

/** 目标详情（对应后端 ObjectiveDetailVO） */
export interface ObjectiveDetail extends ObjectiveItem {
  /** 详情 md 全文（`## 描述` + `## 子项` + `## 反思`） */
  contentMd: string
  /** checkbox 子项解析结果 */
  subItems: { done: boolean; text: string }[]
}

/** 目标分页响应 */
export interface ObjectivePage extends PageResponse<ObjectiveItem> {
  records?: ObjectiveItem[]
}

/** 目标查询参数 */
export interface ObjectiveQuery extends PageQuery {
  /** 按 agent 过滤 */
  agentId?: number
  /** 标题 LIKE 搜索 */
  keyword?: string
}

/** 新建请求（POST ?agentId=；title 必填） */
export interface ObjectiveCreatePayload {
  title: string
  period?: string
  /** yyyy-MM-dd */
  targetDate?: string
  /** 描述段 markdown */
  descMd?: string
}

/** 编辑请求（PATCH；全空 → 400，至少一项） */
export interface ObjectivePatchPayload {
  title?: string
  period?: string
  targetDate?: string
  status?: number
  descMd?: string
}

/** 目标分页（objective:list；keyword 搜 title / agentId 过滤） */
export function getObjectiveList(params: ObjectiveQuery): Promise<ApiResponse<ObjectivePage>> {
  return get<ObjectivePage>('/objective/admin/objectives', params)
}

/** 按 agent 查目标（objective:list；agent 管理页「目标」弹窗数据源） */
export function getAgentObjectives(agentId: number): Promise<ApiResponse<ObjectiveItem[]>> {
  return get<ObjectiveItem[]>(`/objective/admin/agents/${agentId}/objectives`)
}

/** 目标详情（objective:list；md 全文 + checkbox 子项） */
export function getObjectiveDetail(oid: string): Promise<ApiResponse<ObjectiveDetail>> {
  return get<ObjectiveDetail>(`/objective/admin/objectives/${oid}`)
}

/** 管理员代建目标（objective:create；agentId 走 query 必填） */
export function createObjective(agentId: number, data: ObjectiveCreatePayload): Promise<ApiResponse> {
  return post(`/objective/admin/objectives?agentId=${agentId}`, data)
}

/** 管理员代改目标（objective:edit；全空 → 400） */
export function updateObjective(oid: string, data: ObjectivePatchPayload): Promise<ApiResponse> {
  return patch(`/objective/admin/objectives/${oid}`, data)
}

/** 软删目标（objective:delete；is_deleted=1，md 保留，二次确认） */
export function deleteObjective(oid: string): Promise<ApiResponse> {
  return del(`/objective/admin/objectives/${oid}`)
}
