import { post } from './request'
import type { ApiResponse, LoginResult, CurrentUser } from '@/types/api'

/**
 * 用户登录
 * @param data 用户名密码
 * @returns 登录结果（token、用户基础信息、权限码）
 */
export function login(data: { username: string; password: string }): Promise<ApiResponse<LoginResult>> {
  return post<LoginResult>('/system/user/login', data)
}

/**
 * 退出登录
 * @param data 携带 refreshToken，后端使其失效
 */
export function logout(data: { refreshToken: string }): Promise<ApiResponse> {
  return post('/system/user/logout', data)
}

/**
 * 刷新 token
 * @param refreshToken 刷新令牌
 * @returns 新的 accessToken（可能含新的 refreshToken）
 */
export function refreshTokenApi(
  refreshToken: string
): Promise<ApiResponse<{ accessToken: string; refreshToken?: string }>> {
  return post('/system/user/refresh-token', { refreshToken })
}

/**
 * 获取当前登录用户详细信息（需 @RequiresUser）
 */
export function getCurrentUser(): Promise<ApiResponse<CurrentUser>> {
  // 后端约定为 POST 方式
  return post<CurrentUser>('/system/user/getCurrentUser', {})
}
