import { get } from '../request'
import type { ApiResponse } from '@/types/api'

/** 服务端点信息 */
export interface EndpointInfo {
  /** 端点名称 */
  name: string
  /** 端点访问地址（后端返回的完整 URL） */
  url: string
  /** 分类：link=外链卡片, status=状态页内嵌 */
  category: 'link' | 'status'
  /** 功能描述 */
  description: string
}

/**
 * 获取服务端点列表（GET，需 system:tools 权限）
 */
export function getEndpoints(): Promise<ApiResponse<EndpointInfo[]>> {
  return get<EndpointInfo[]>('/system/tools/endpoints')
}
