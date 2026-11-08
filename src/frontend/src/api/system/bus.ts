import { get, post, put, del } from '../request'
import type { ApiResponse, PageQuery, PageResponse } from '@/types/api'

/**
 * 消息总线管理端接口封装（M1 bus-m1，后端 /system/bus）
 *
 * 权限点：bus:message:list（列表/详情/统计）、bus:message:retry（重投）、
 * bus:message:send（手动发消息）。
 */

/** 消息状态：0待投递/1已送达(协议层)/2已读(M2)/3失败 */
export const BUS_STATUS_TEXT: Record<number, string> = {
  0: '待投递',
  1: '已送达',
  2: '已读',
  3: '失败'
}

/** 消息列表行（对应后端 BusMessageVO） */
export interface BusMessageItem {
  id: number
  /** 幂等键（信封「消息:」字段） */
  messageId: string
  fromAgentId: number
  /** 生产者展示名 */
  fromAgentName?: string | null
  toAgentId: number
  /** 消费者展示名 */
  toAgentName?: string | null
  /** notify / alert */
  msgType: string
  topic: string
  title: string
  /** 消息体 JSON 字符串（可空） */
  payload?: string | null
  /** 会话标识（投递后回填） */
  sessionKey?: string | null
  /** 指定模型（空=收件人默认 preset） */
  model?: string | null
  /** 0待投递/1已送达/2已读/3失败 */
  status: number
  /** 实际投递通道（ws/api；bus-ui-v2 投递成功回填，历史行为 null） */
  channel?: string | null
  retryCount: number
  /** 最近一次失败原因（≤500 字符） */
  lastError?: string | null
  deliveredAt?: string | null
  createTime?: string | null
  updateTime?: string | null
}

/** 消息分页响应 */
export interface BusMessagePage extends PageResponse<BusMessageItem> {
  records?: BusMessageItem[]
}

/** 统计卡数据（对应后端 BusStatsVO） */
export interface BusStats {
  /** 今日进线 */
  todayTotal: number
  /** 待投递积压 */
  pendingCount: number
  /** 失败积压 */
  failedCount: number
  /** 平均投递耗时（秒，近 days 天已送达） */
  avgDeliverySeconds?: number | null
  days: number
}

/** 列表查询参数 */
export interface BusMessageQuery extends PageQuery {
  fromAgent?: number
  toAgent?: number
  status?: number
  type?: string
}

/** 手动发消息请求（对应后端 BusManualSendReq；走与生产 API 同一校验链） */
export interface BusManualSendPayload {
  fromAgentId: number
  toAgentId: number
  type: 'notify' | 'alert'
  topic: string
  title: string
  payload?: unknown
  sessionKey?: string
  model?: string
}

/** 分页列表（bus:message:list） */
export function getBusMessages(params: BusMessageQuery): Promise<ApiResponse<BusMessagePage>> {
  return get<BusMessagePage>('/system/bus/messages', params)
}

/** 详情（bus:message:list） */
export function getBusMessageDetail(id: number): Promise<ApiResponse<BusMessageItem>> {
  return get<BusMessageItem>(`/system/bus/messages/${id}`)
}

/** 手动重投（bus:message:retry；status∈{0,3}） */
export function retryBusMessage(id: number): Promise<ApiResponse<{ messageId?: string }>> {
  return put(`/system/bus/messages/${id}/retry`)
}

/** 手动发消息（bus:message:send；可任选 from 模拟生产者） */
export function sendBusManual(data: BusManualSendPayload): Promise<ApiResponse<{ messageId: string; status: number }>> {
  return post('/system/bus/messages', data)
}

/** 统计（bus:message:list） */
export function getBusStats(days = 7): Promise<ApiResponse<BusStats>> {
  return get<BusStats>('/system/bus/stats', { days })
}

/** 投递通道：ws（gateway 常连接）/ api（HTTP 直调 OpenAI 兼容 API） */
export type BusChannel = 'ws' | 'api'

/** 投递路由矩阵行（方向级，含自投 from=to；bus-ui-v2 放开；无配置行后端默认 ws） */
export interface BusRouteItem {
  fromAgentId: number
  fromAgentName: string
  toAgentId: number
  toAgentName: string
  channel: BusChannel
}

/** 投递路由保存 payload */
export interface BusRouteSavePayload {
  fromAgentId: number
  toAgentId: number
  channel: BusChannel
}

/** 投递路由矩阵（bus:message:list；两两方向含自投，from/to 升序） */
export function getBusRoutes(): Promise<ApiResponse<BusRouteItem[]>> {
  return get<BusRouteItem[]>('/system/bus/routes')
}

/** 保存单条投递路由（bus:message:send；upsert，即时生效；自投方向允许） */
export function saveBusRoute(data: BusRouteSavePayload): Promise<ApiResponse<null>> {
  return put('/system/bus/routes', data)
}

/** 批量保存投递路由（bus:message:send；整表逐行 upsert，任一行非法整体不落库） */
export function saveBusRoutes(data: BusRouteSavePayload[]): Promise<ApiResponse<null>> {
  return put('/system/bus/routes/batch', data)
}

/** 恢复默认：删除某发件方全部路由行（bus:message:send；bus-ui-v2，删行即回落默认 ws） */
export function clearBusRoutes(fromAgentId: number): Promise<ApiResponse<null>> {
  return del(`/system/bus/routes/${fromAgentId}`)
}

/** 投递通道可用性行（bus-ui-v2 GET /system/bus/delivery-targets） */
export interface BusDeliveryTarget {
  agentId: number
  /** ws 通道已配置（BUS_TARGET_* 含该目标） */
  wsConfigured: boolean
  /** api 通道已配置（BUS_API_TARGET_* 含该目标） */
  apiConfigured: boolean
  /** api 目标 baseUrl（未配置 = null；仅 baseUrl 段，不含 apiKey） */
  apiBaseUrl?: string | null
}

/** 方向近 24h 消息计数行（bus-ui-v2 GET /system/bus/route-stats） */
export interface BusRouteStat {
  fromAgentId: number
  toAgentId: number
  count24h: number
}

/** 投递通道可用性（bus:message:list；两项均 false = 无实例） */
export function getDeliveryTargets(): Promise<ApiResponse<BusDeliveryTarget[]>> {
  return get<BusDeliveryTarget[]>('/system/bus/delivery-targets')
}

/** 方向近 24h 消息计数（bus:message:list） */
export function getRouteStats(): Promise<ApiResponse<BusRouteStat[]>> {
  return get<BusRouteStat[]>('/system/bus/route-stats')
}

// ==================== 总线调试（v4.37 bus-sessionkey-fix，仅管理端） ====================

/** 方向路由查询响应（from 固定为管理员 agent alpha，id=1） */
export interface BusDebugRoute {
  fromAgentId: number
  toAgentId: number
  /** 当前投递通道（无配置行 = 默认 ws） */
  channel: BusChannel
  /** 是否存在显式配置行（false = 未配置，默认 ws） */
  hasExplicitRoute: boolean
}

/** 调试发送响应（messageId / dbId / 预期落点预览） */
export interface BusDebugSendResult {
  messageId: string
  /** 消息表主键 id */
  dbId?: number | null
  /** 本条临时使用的通道 */
  channelTemp?: BusChannel
  /** 切换前的通道（无行时为默认 ws） */
  channelBefore?: string
  /** 还原后的通道（有行=原 channel，无行=ws） */
  routeRestoredTo?: string
  /** 剥前缀后的投递 chatId（落点） */
  expectedChatId?: string
  /** 预期 gateway 会话 key（websocket:<chatId>；bus-* 形态原样） */
  expectedSessionKey?: string
}

/** 查方向路由（bus:message:list；from 固定为管理员 agent alpha） */
export function getDebugRoute(toAgentId: number): Promise<ApiResponse<BusDebugRoute>> {
  return get<BusDebugRoute>('/system/bus/debug/route', { toAgentId })
}

/** 调试发送（bus:message:send；临时切路由→发送→还原） */
export function sendDebugMessage(data: {
  toAgentId: number
  sessionKey: string
  title?: string
  content: string
  channel: BusChannel
}): Promise<ApiResponse<BusDebugSendResult>> {
  return post<BusDebugSendResult>('/system/bus/debug/send', data)
}
