import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import type { CurrentUser, LoginResult } from '@/types/api'
import { logout as logoutApi } from '@/api/auth'

/** localStorage 存储 token 的 key */
const ACCESS_TOKEN_KEY = 'accessToken'
const REFRESH_TOKEN_KEY = 'refreshToken'

/**
 * 用户 store（Composition API 风格）
 * 负责登录态、token 与用户信息的管理
 */
export const useUserStore = defineStore('user', () => {
  // ===== state =====
  // 从 localStorage 初始化，保证刷新页面后仍保持登录态
  const accessToken = ref(localStorage.getItem(ACCESS_TOKEN_KEY) || '')
  const refreshToken = ref(localStorage.getItem(REFRESH_TOKEN_KEY) || '')
  // 当前用户信息（昵称、角色、权限等）
  const userInfo = ref<CurrentUser>({} as CurrentUser)
  // 是否已完成动态路由注册（防止重复注册）
  const authenticated = ref(false)
  // 是否需要强制修改密码
  const mustChangePassword = ref(false)

  // ===== getters =====
  /** 是否已登录（依据是否存在 accessToken） */
  const isLogin = computed(() => !!accessToken.value)
  /** 用户昵称 */
  const nickname = computed(() => userInfo.value.nickname || '管理员')
  /** 用户头像 */
  const avatar = computed(() => userInfo.value.avatar || '')
  /** 角色编码列表 */
  const roles = computed(() => userInfo.value.roles || [])
  /** 是否需要强制修改密码 */
  const needChangePassword = computed(() => mustChangePassword.value)

  // ===== actions =====
  /**
   * 保存登录返回的 token 与用户基础信息
   * @param result 登录接口返回数据
   */
  function setLoginInfo(result: LoginResult) {
    accessToken.value = result.accessToken
    refreshToken.value = result.refreshToken
    // 持久化到 localStorage，供 axios 拦截器读取
    localStorage.setItem(ACCESS_TOKEN_KEY, result.accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, result.refreshToken)
    // 缓存用户基础信息（详细信息可在 getCurrentUser 后补充）
    userInfo.value = {
      nickname: result.nickname,
      avatar: result.avatar,
      roles: result.roles,
      permissions: result.permissions
    }
    // 记录是否需要强制修改密码
    // 【临时关闭强制改密】业务要求暂不拦截初始密码登录，一律置 false，
    // 前端 guard.ts 的路由拦截、login.vue 的跳转与提示、password.vue 的引导卡片随之全部失效。
    // 后续如需恢复，改回：mustChangePassword.value = result.mustChangePassword || false
    mustChangePassword.value = false
  }

  /** 单独更新 token（刷新 token 场景） */
  function setTokens(newAccessToken: string, newRefreshToken?: string) {
    accessToken.value = newAccessToken
    localStorage.setItem(ACCESS_TOKEN_KEY, newAccessToken)
    if (newRefreshToken) {
      refreshToken.value = newRefreshToken
      localStorage.setItem(REFRESH_TOKEN_KEY, newRefreshToken)
    }
  }

  /** 设置/更新用户详细信息 */
  function setUser(info: CurrentUser) {
    userInfo.value = { ...userInfo.value, ...info }
  }

  /** 标记动态路由已注册 */
  function setAuthenticated(val: boolean) {
    authenticated.value = val
  }

  /**
   * 退出登录：调用后端登出接口并清理本地状态
   */
  async function logout() {
    try {
      // 通知后端使 refreshToken 失效（失败不阻塞本地清理）
      await logoutApi({ refreshToken: refreshToken.value })
    } catch (e) {
      // 忽略登出接口错误
    } finally {
      clear()
    }
  }

  /** 清理所有本地登录状态 */
  function clear() {
    accessToken.value = ''
    refreshToken.value = ''
    userInfo.value = {} as CurrentUser
    authenticated.value = false
    mustChangePassword.value = false
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
  }

  // 暴露 state、getters、actions（保持对外 API 不变）
  return {
    // state
    accessToken,
    refreshToken,
    userInfo,
    authenticated,
    mustChangePassword,
    // getters
    isLogin,
    nickname,
    avatar,
    roles,
    needChangePassword,
    // actions
    setLoginInfo,
    setTokens,
    setUser,
    setAuthenticated,
    logout,
    clear
  }
})
