import { get, post } from '../request'
import type { ApiResponse, PageResponse, PageQuery } from '@/types/api'

/** 消息通知实体 */
export interface SysNotice {
  id?: number | string
  /** 通知标题 */
  title?: string
  /** 通知内容（支持 HTML） */
  content?: string
  /** 类型：1-系统通知 2-版本更新 3-维护公告 */
  type?: number
  /** 状态：1-已发布 0-草稿 */
  status?: number
  /** 发布人 */
  publisher?: string
  /** 备注 */
  remark?: string
  /** 创建时间 */
  createdAt?: string
  /** 接收者用户ID列表（非持久化，新增/修改时传递；空数组或undefined=全员广播） */
  recipientUserIds?: number[]
  [key: string]: any
}

/** 通知查询参数 */
export interface NoticeQuery extends PageQuery {
  title?: string
  type?: number
  status?: number
}

/**
 * 获取通知列表（POST，需 notice:view 权限）
 */
export function getNoticeList(data: NoticeQuery): Promise<ApiResponse<PageResponse<SysNotice>>> {
  return post<PageResponse<SysNotice>>('/system/notice/list', data)
}

/**
 * 新增通知（需 notice:create 权限）
 */
export function addNotice(data: SysNotice): Promise<ApiResponse> {
  return post('/system/notice/add', data)
}

/**
 * 修改通知（需 notice:update 权限）
 */
export function updateNotice(data: SysNotice): Promise<ApiResponse> {
  return post('/system/notice/update', data)
}

/**
 * 删除通知（需 notice:delete 权限）
 */
export function deleteNotice(id: number | string): Promise<ApiResponse> {
  return get('/system/notice/delete', { id })
}

/**
 * 获取某条通知的接收者 userId 列表（需 notice:update 权限）
 * 返回空数组表示全员广播，非空表示定向通知
 */
export function getNoticeRecipients(id: number | string): Promise<ApiResponse<number[]>> {
  return get<number[]>(`/system/notice/getRecipients/${id}`)
}

/** 我的通知数据结构（用户侧，含已读状态） */
export interface MyNotice {
  id: number
  /** 通知标题 */
  title: string
  /** 类型：1-系统通知 2-版本更新 3-维护公告 */
  type: number
  /** 通知内容（支持 HTML） */
  content: string
  /** 发布人 */
  publisher: string
  /** 创建时间 */
  createTime: string
  /** 是否已读 */
  isRead: boolean
}

/** 获取当前用户的未读通知数 */
export function getMyUnreadCount(): Promise<ApiResponse<number>> {
  return get<number>('/system/notice/myUnreadCount')
}

/** 获取当前用户的通知列表（带已读状态） */
export function getMyNotices(params: {
  curPage: number
  pageSize: number
}): Promise<ApiResponse<PageResponse<MyNotice>>> {
  return get<PageResponse<MyNotice>>('/system/notice/myNotices', params)
}

/** 标记单条已读 */
export function markNoticeRead(id: number | string): Promise<ApiResponse> {
  return post(`/system/notice/markRead/${id}`)
}

/** 标记全部已读 */
export function markAllNoticeRead(): Promise<ApiResponse> {
  return post('/system/notice/markAllRead')
}
