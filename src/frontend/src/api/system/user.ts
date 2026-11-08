import { get, post } from '../request'
import type { ApiResponse, PageResponse, PageQuery } from '@/types/api'

/** 用户实体 */
export interface SysUser {
  id?: number | string
  username?: string
  nickname?: string
  password?: string
  avatar?: string
  email?: string
  phone?: string
  status?: number
  roleIds?: (number | string)[]
  /** 角色列表（后端返回时使用） */
  roles?: { id: number; name: string; code: string }[]
  remark?: string
  createTime?: string
  [key: string]: any
}

/** 用户查询参数 */
export interface UserQuery extends PageQuery {
  username?: string
  nickname?: string
  status?: number
}

/**
 * 获取用户列表（GET，需 system:user 权限）
 * @param params 查询与分页参数
 */
export function getUserList(params: UserQuery): Promise<ApiResponse<PageResponse<SysUser>>> {
  return get<PageResponse<SysUser>>('/system/user/list', params)
}

/**
 * 获取所有用户列表（不分页，GET，需 system:user 权限）
 * 用于选择器等场景，只返回 id/username/nickname 等基本信息
 */
export function getAllUsers(): Promise<ApiResponse<SysUser[]>> {
  return get<SysUser[]>('/system/user/listAll')
}

/**
 * 新增用户
 */
export function addUser(data: SysUser): Promise<ApiResponse> {
  return post('/system/user/add', data)
}

/**
 * 修改用户（管理端，需要 system:user 权限）
 */
export function updateUser(data: SysUser): Promise<ApiResponse> {
  return post('/system/user/update', data)
}

/**
 * 更新个人资料（当前用户自行编辑，只需登录）
 * 仅允许修改 nickname / email / phone / avatar
 */
export function updateProfile(data: Partial<Pick<SysUser, 'nickname' | 'email' | 'phone' | 'avatar'>>): Promise<ApiResponse> {
  return post('/system/user/updateProfile', data)
}

/**
 * 删除用户
 * @param id 用户 ID
 */
export function deleteUser(id: number | string): Promise<ApiResponse> {
  return get('/system/user/delete', { id })
}

/**
 * 根据用户 ID 获取用户详情（含角色列表）
 * @param id 用户 ID
 */
export function getUserById(id: number | string): Promise<ApiResponse<SysUser>> {
  return get<SysUser>('/system/user/getById', { id })
}

/**
 * 分配用户角色（全量覆盖）
 * @param userId 用户 ID
 * @param roleIds 角色 ID 列表
 */
export function assignUserRoles(userId: number | string, roleIds: (number | string)[]): Promise<ApiResponse> {
  return post('/system/user/assignRoles', { userId, roleIds })
}

/** 在线设备信息 */
export interface OnlineDevice {
  id: number
  deviceName: string
  ipAddress: string
  userAgent: string
  expiresAt: string
  createdAt: string
  current: boolean
}

/** 查询当前用户在线设备列表 */
export function getOnlineDevices() {
  return get<OnlineDevice[]>('/system/user/online-devices')
}

/** 踢下线指定设备 */
export function kickDevice(tokenId: number) {
  return post('/system/user/kick-device', { tokenId })
}
