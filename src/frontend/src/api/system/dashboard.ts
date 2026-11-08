import { get } from '../request'
import type { ApiResponse } from '@/types/api'

/** 仪表盘统计卡片数据 */
export interface DashboardStats {
  /** 用户总数 */
  userCount?: number
  /** 角色总数 */
  roleCount?: number
  /** 菜单总数 */
  menuCount?: number
  /** 在线人数 */
  onlineCount?: number
  /** 图表数据：近 7 天访问趋势 */
  visitTrend?: { date: string; count: number }[]
  /** 图表数据：各模块访问量 */
  moduleStat?: { name: string; value: number }[]
  [key: string]: any
}

/**
 * 获取仪表盘统计数据（GET，需登录）
 */
export function getDashboardStats(): Promise<ApiResponse<DashboardStats>> {
  return get<DashboardStats>('/system/dashboard/stats')
}
