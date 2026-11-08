import { get, post } from '../request'
import type { ApiResponse, PageResponse, PageQuery } from '@/types/api'

/** 系统配置项实体（与后端 SiteOption 字段对齐） */
export interface SysOption {
  id?: number | string
  /** 配置项键名（如 site_title） */
  optionKey?: string
  /** 配置项键值 */
  optionValue?: string
  [key: string]: any
}

/** 配置项查询参数 */
export interface OptionQuery extends PageQuery {
  optionKey?: string
}

/**
 * 获取配置项列表（分页）
 */
export function getOptionList(data: OptionQuery): Promise<ApiResponse<PageResponse<SysOption>>> {
  return post<PageResponse<SysOption>>('/system/option/list', data)
}

/**
 * 新增配置项
 */
export function addOption(data: SysOption): Promise<ApiResponse> {
  return post('/system/option/add', data)
}

/**
 * 修改配置项
 */
export function updateOption(data: SysOption): Promise<ApiResponse> {
  return post('/system/option/update', data)
}

/**
 * 删除配置项
 */
export function deleteOption(id: number | string): Promise<ApiResponse> {
  return get('/system/option/delete', { id })
}
