import { get, post, put, del } from '../request'
import type { ApiResponse } from '@/types/api'
import type { MenuNode } from '@/store/menu'

/**
 * 获取当前登录用户的可见菜单树（GET，仅需登录）
 * 用于侧边栏渲染与动态路由注册。
 * 后端只返回当前用户有权限、type≤2（目录/菜单）、启用状态的节点。
 */
export function getMyMenuTree(): Promise<ApiResponse<MenuNode[]>> {
  return get<MenuNode[]>('/system/menu/my')
}

/**
 * 获取菜单树（GET，需 system:menu 权限）
 * 返回全量权限树（含按钮），供菜单管理页使用。
 */
export function getMenuTree(): Promise<ApiResponse<MenuNode[]>> {
  return get<MenuNode[]>('/system/menu/tree')
}

/**
 * 获取菜单平铺列表（GET）
 */
export function getMenuList(): Promise<ApiResponse<MenuNode[]>> {
  return get<MenuNode[]>('/system/menu/list')
}

/**
 * 新增菜单（POST /system/menu）
 */
export function addMenu(data: Partial<MenuNode>): Promise<ApiResponse> {
  return post('/system/menu', data)
}

/**
 * 修改菜单（PUT /system/menu）
 */
export function updateMenu(data: Partial<MenuNode>): Promise<ApiResponse> {
  return put('/system/menu', data)
}

/**
 * 删除菜单（DELETE /system/menu/{id}）
 */
export function deleteMenu(id: number | string): Promise<ApiResponse> {
  return del(`/system/menu/${id}`)
}
