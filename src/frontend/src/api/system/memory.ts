import { get, post, put, del } from '../request'
import type { ApiResponse, PageQuery, PageResponse } from '@/types/api'

/**
 * 记忆域管理端接口封装（M7.1 M2，对应后端 MemoryAdminController /api/memory/admin/**）
 *
 * 后端分页返回 MyBatis-Plus IPage（records/total），
 * useCrud 会优先取 list、回退取 records，两种结构均兼容。
 * agentId 走路径（RESTful 资源 ID 走路径约束），其余筛选走 query。
 */

/** 记忆列表项（对应后端 MemoryVO） */
export interface MemoryItem {
  /** 对外引用键（数字 id） */
  id: number
  /** 归属 agent id */
  agentId: number
  /** 归属 agent 展示名（管理端回填） */
  agentName?: string
  /** 记忆类型：fact/decision/preference/lesson/event */
  memType: string
  /** 来源：dialogue/self/system */
  source: string
  /** 一句话摘要 */
  title: string
  /** 逗号分隔标签 */
  tags?: string | null
  /** AC 笔记深链指针 */
  refUrl?: string | null
  /** 生命周期：1=active 2=archived 3=superseded */
  status: number
  /** 取代本条的新记忆 id（status=3 时有值） */
  supersededBy?: number | null
  /** 衰减权重 */
  weight: number
  /** 权重保底线（0=不保底；M7.1-M3a） */
  weightFloor?: number | null
  /** 检索命中次数 */
  accessCount: number
  /** 最后命中时间 */
  lastAccessedAt?: string | null
  /** 最后确认时间 */
  confirmedAt?: string | null
  /** 创建时间 */
  createTime?: string
  /** 更新时间 */
  updateTime?: string
}

/** 记忆分页响应 */
export interface MemoryPage extends PageResponse<MemoryItem> {
  records?: MemoryItem[]
}

/** 记忆查询参数（agentId 拼路径，其余走 query） */
export interface MemoryQuery extends PageQuery {
  agentId: number
  status?: number
  memType?: string
  keyword?: string
}

/** 记忆统计（对应后端 MemoryStatsVO） */
export interface MemoryStats {
  agentId: number
  agentName?: string
  activeCount: number
  archivedCount: number
  supersededCount: number
  totalCount: number
}

/** superseded 链节点摘要 */
export interface MemoryChainNode {
  id: number
  title: string
  status: number
  supersededBy?: number | null
}

/** 记忆详情（对应后端 MemoryDetailVO） */
export interface MemoryDetail {
  memory: MemoryItem
  /** contentMd 全文 */
  contentMd: string
  /** superseded 链途经节点（含本条） */
  chain: MemoryChainNode[]
  /** 链尾最新版（本条即链尾时为 null） */
  latest?: MemoryItem | null
}

/** 按 agent 分页查询记忆（memory:list） */
export function getMemoryList(params: MemoryQuery): Promise<ApiResponse<MemoryPage>> {
  const { agentId, ...rest } = params
  return get<MemoryPage>(`/memory/admin/agents/${agentId}/memories`, rest)
}

/** 记忆统计（memory:list；统计卡片数据源） */
export function getMemoryStats(): Promise<ApiResponse<MemoryStats[]>> {
  return get<MemoryStats[]>('/memory/admin/memories/stats')
}

/** 记忆详情（memory:list；详情抽屉数据源，含 superseded 链） */
export function getMemoryDetail(id: number): Promise<ApiResponse<MemoryDetail>> {
  return get<MemoryDetail>(`/memory/admin/memories/${id}`)
}

/** 归档记忆（memory:archive；status 1→2） */
export function archiveMemory(id: number): Promise<ApiResponse<MemoryItem>> {
  return post<MemoryItem>(`/memory/admin/memories/${id}/archive`)
}

/** 恢复记忆（memory:restore；status 2→1） */
export function restoreMemory(id: number): Promise<ApiResponse<MemoryItem>> {
  return post<MemoryItem>(`/memory/admin/memories/${id}/restore`)
}

/** 删除记忆（memory:delete；软删 is_deleted=1） */
export function deleteMemory(id: number): Promise<ApiResponse> {
  return del(`/memory/admin/memories/${id}`)
}

/** 调整权重保底线（memory:archive；M7.1-M3a，0=不保底） */
export function updateWeightFloor(id: number, weightFloor: number): Promise<ApiResponse<MemoryItem>> {
  return put<MemoryItem>(`/memory/admin/memories/${id}/weight-floor`, { weightFloor })
}
