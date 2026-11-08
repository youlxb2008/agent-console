import { get, post } from '../request'
import type { ApiResponse, PageResponse, PageQuery } from '@/types/api'

/** 角色实体 */
export interface SysRole {
  id?: number | string
  name?: string
  code?: string
  remark?: string
  status?: number
  /** 角色拥有的菜单 ID 列表 */
  menuIds?: (number | string)[]
  /** 排序 */
  sort?: number
  createTime?: string
  [key: string]: any
}

/** 角色查询参数 */
export interface RoleQuery extends PageQuery {
  name?: string
  code?: string
  status?: number
}

/**
 * 获取角色列表（POST，需 system:role 权限）
 */
export function getRoleList(data: RoleQuery): Promise<ApiResponse<PageResponse<SysRole>>> {
  return post<PageResponse<SysRole>>('/system/role/list', data)
}

/**
 * 新增角色
 */
export function addRole(data: SysRole): Promise<ApiResponse> {
  return post('/system/role/add', data)
}

/**
 * 修改角色
 */
export function updateRole(data: SysRole): Promise<ApiResponse> {
  return post('/system/role/update', data)
}

/**
 * 删除角色
 */
export function deleteRole(id: number | string): Promise<ApiResponse> {
  return get('/system/role/delete', { id })
}

/**
 * 根据角色 ID 获取角色详情（含已分配菜单 ID 列表）
 * @param id 角色 ID
 */
export function getRoleDetail(id: number | string): Promise<ApiResponse<SysRole>> {
  return get<SysRole>('/system/role/getById', { id })
}

/**
 * 分配角色菜单权限（全量覆盖）
 * @param roleId 角色 ID
 * @param menuIds 菜单 ID 列表
 */
export function assignRoleMenus(roleId: number | string, menuIds: (number | string)[]): Promise<ApiResponse> {
  return post('/system/role/assignMenus', { roleId, menuIds })
}
