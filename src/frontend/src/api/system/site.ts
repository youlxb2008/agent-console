import { get, post } from '../request'
import type { ApiResponse } from '@/types/api'
import type { SiteConfig } from '@/store/site'

/**
 * 获取站点配置（GET，需登录）
 */
export function getSiteConfig(): Promise<ApiResponse<SiteConfig>> {
  return get<SiteConfig>('/system/site/list')
}

/**
 * 保存站点配置（POST）
 * @param data 站点配置
 */
export function saveSiteConfig(data: Partial<SiteConfig>): Promise<ApiResponse<SiteConfig>> {
  return post<SiteConfig>('/system/site/save', data)
}
