import { get, post } from '../request'
import type { ApiResponse, PageResponse, PageQuery } from '@/types/api'

/** 定时任务实体（与后端 CronJob 字段对齐） */
export interface SysCron {
  id?: number | string
  /** 任务名称 */
  name?: string
  /** Cron 表达式（对应后端 cronExpr） */
  cronExpr?: string
  /** 处理器名称（对应后端 handlerClass） */
  handlerClass?: string
  /** 任务参数（JSON 字符串） */
  params?: string
  /** 1=启用 0=禁用 */
  status?: number
  createTime?: string
  [key: string]: any
}

/** 任务查询参数 */
export interface CronQuery extends PageQuery {
  name?: string
  status?: number
}

/** 执行日志实体（与后端 CronLog 字段对齐） */
export interface CronLog {
  id?: number | string
  jobId?: number | string
  /** 1=成功 0=失败 */
  status?: number
  /** 执行结果 */
  result?: string
  /** 耗时（毫秒） */
  duration?: number
  createTime?: string
  [key: string]: any
}

/** 处理器信息 */
export interface CronHandler {
  name: string
  description: string
}

/**
 * 获取定时任务列表
 */
export function getCronList(data: CronQuery): Promise<ApiResponse<PageResponse<SysCron>>> {
  return post<PageResponse<SysCron>>('/system/cron/list', data)
}

/**
 * 新增定时任务
 */
export function addCron(data: SysCron): Promise<ApiResponse> {
  return post('/system/cron/add', data)
}

/**
 * 修改定时任务
 */
export function updateCron(data: SysCron): Promise<ApiResponse> {
  return post('/system/cron/update', data)
}

/**
 * 删除定时任务
 */
export function deleteCron(id: number | string): Promise<ApiResponse> {
  return get('/system/cron/delete', { id })
}

/**
 * 立即执行一次任务
 */
export function runCron(id: number | string): Promise<ApiResponse<CronLog>> {
  return post<CronLog>('/system/cron/run', {}, { params: { id } })
}

/**
 * 获取任务执行日志
 */
export function getCronLogs(
  data: PageQuery & { jobId?: number | string; logStatus?: number }
): Promise<ApiResponse<PageResponse<CronLog>>> {
  return post<PageResponse<CronLog>>('/system/cron/logs', data)
}

/**
 * 切换定时任务状态（启用/暂停）
 */
export function toggleCronStatus(id: number | string): Promise<ApiResponse> {
  return post('/system/cron/toggleStatus', {}, { params: { id } })
}

/**
 * 清空指定任务的执行日志
 */
export function clearCronLogs(taskId: number | string): Promise<ApiResponse> {
  return post('/system/cron/clearLogs', {}, { params: { taskId } })
}

/**
 * 获取所有可用的处理器列表
 */
export function getCronHandlers(): Promise<ApiResponse<CronHandler[]>> {
  return get<CronHandler[]>('/system/cron/handlers')
}
