import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
  type AxiosResponse
} from 'axios'
import type { ApiResponse } from '@/types/api'

/** localStorage 中存储 token 的 key */
export const ACCESS_TOKEN_KEY = 'accessToken'
export const REFRESH_TOKEN_KEY = 'refreshToken'

/** 刷新 token 接口路径（用于识别，避免对其再次触发刷新逻辑） */
// 注意：这是 service 拦截器中 config.url 的匹配值，不含 baseURL 前缀
const REFRESH_URL = '/system/user/refresh-token'
/** doRefresh() 使用原始 axios（绕过拦截器），需要写完整路径含 /api 前缀 */
const REFRESH_URL_FULL = '/api/system/user/refresh-token'

// 创建 axios 实例
const service: AxiosInstance = axios.create({
  // 所有 API 请求统一走 /api 前缀，开发环境由 Vite 代理 rewrite 到后端
  // 生产环境由 Nginx 反代 /api → 后端
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8'
  }
})

/* ---------------- 请求拦截器：注入 Authorization ---------------- */
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 从 localStorage 读取 accessToken（后端要求直接传 token 值，不加 Bearer 前缀）
    const token = localStorage.getItem(ACCESS_TOKEN_KEY)
    if (token && config.headers) {
      config.headers.Authorization = token
    }
    return config
  },
  (error) => Promise.reject(error)
)

/* ---------------- 响应拦截器：处理 ResultVO 统一格式 ---------------- */
// 是否正在刷新 token（防止并发刷新）
let isRefreshing = false
// 刷新期间被挂起的请求队列，刷新成功后依次重放
let pendingQueue: Array<(token: string) => void> = []

/**
 * 跳转到登录页并清理本地凭证
 */
function redirectToLogin() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  // 使用 location 强制刷新，确保 store/router 状态彻底重置
  if (location.pathname !== '/login') {
    location.href = '/login'
  }
}

/**
 * 使用 refreshToken 刷新 accessToken
 * @returns 新的 accessToken，失败返回 null
 */
async function doRefresh(): Promise<string | null> {
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
  if (!refreshToken) return null
  try {
    // 使用原始 axios 直接调用刷新接口，绕过业务拦截器，避免循环
    const resp = await axios.post<ApiResponse<{ accessToken: string; refreshToken?: string }>>(
      REFRESH_URL_FULL,
      { refreshToken },
      { baseURL: '', timeout: 15000 }
    )
    const body = resp.data
    if (body && body.success && body.data && body.data.accessToken) {
      const newToken = body.data.accessToken
      localStorage.setItem(ACCESS_TOKEN_KEY, newToken)
      // 若后端返回了新的 refreshToken，一并更新
      if (body.data.refreshToken) {
        localStorage.setItem(REFRESH_TOKEN_KEY, body.data.refreshToken)
      }
      return newToken
    }
    return null
  } catch (e) {
    return null
  }
}

service.interceptors.response.use(
  async (response: AxiosResponse<ApiResponse>) => {
    const body = response.data
    // 兼容非标准响应（如文件下载直接返回二进制）
    if (body === null || typeof body !== 'object' || typeof body.code === 'undefined') {
      return response as any
    }

    const config = response.config as InternalAxiosRequestConfig & { _retry?: boolean }

    // code=200 且 success=true 才算业务成功，直接返回 data 部分
    if (body.code === '200' && body.success === true) {
      return body as any
    }

    // code=401 表示未登录或 token 失效
    if (body.code === '401') {
      // 刷新接口本身返回 401，说明 refreshToken 也失效，直接跳登录
      if (config.url && config.url.includes(REFRESH_URL)) {
        redirectToLogin()
        return Promise.reject(new Error(body.msg || '登录已失效'))
      }
      // 已重试过仍失败，跳登录
      if (config._retry) {
        redirectToLogin()
        return Promise.reject(new Error(body.msg || '登录已失效'))
      }
      // 正在刷新则挂起当前请求
      if (isRefreshing) {
        return new Promise((resolve) => {
          pendingQueue.push((newToken: string) => {
            config.headers!.Authorization = newToken
            config._retry = true
            resolve(service.request(config))
          })
        })
      }
      // 发起刷新
      isRefreshing = true
      config._retry = true
      try {
        const newToken = await doRefresh()
        if (newToken) {
          // 重放队列中的请求
          pendingQueue.forEach((cb) => cb(newToken))
          pendingQueue = []
          // 重放当前请求
          config.headers!.Authorization = newToken
          return service.request(config)
        }
        // 刷新失败
        redirectToLogin()
        return Promise.reject(new Error(body.msg || '登录已失效'))
      } finally {
        isRefreshing = false
      }
    }

    // code=403 表示权限不足
    if (body.code === '403') {
      const errMsg = (body && body.msg) || '权限不足'
      import('element-plus').then((ep) => ep.ElMessage.error(errMsg))
      return Promise.reject(new Error(errMsg))
    }

    // code=500 或其它业务错误，弹出后端消息
    const errMsg = (body && body.msg) || '请求失败'
    // 延迟引入 ElMessage 以兼容按需导入
    import('element-plus').then((ep) => ep.ElMessage.error(errMsg))
    return Promise.reject(new Error(errMsg))
  },
  async (error: any) => {
    // HTTP 层错误处理
    const status = error?.response?.status
    const respMsg = error?.response?.data?.msg
    if (status === 401) {
      // 401 也尝试刷新一次
      const config = error.config as InternalAxiosRequestConfig & { _retry?: boolean }
      if (config && !config._retry && !config.url?.includes(REFRESH_URL)) {
        config._retry = true
        const newToken = await doRefresh()
        if (newToken) {
          config.headers!.Authorization = newToken
          return service.request(config)
        }
      }
      redirectToLogin()
      return Promise.reject(new Error(respMsg || '未授权'))
    }
    const msg = respMsg || error.message || '网络异常，请稍后重试'
    import('element-plus').then((ep) => ep.ElMessage.error(msg))
    return Promise.reject(new Error(msg))
  }
)

/* ---------------- 对外暴露的便捷方法 ---------------- */
/**
 * 发起 GET 请求
 * @param url 请求地址
 * @param params 查询参数
 * @param config 额外 axios 配置
 */
export function get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return service.get(url, { params, ...config })
}

/**
 * 发起 POST 请求
 * @param url 请求地址
 * @param data 请求体
 * @param config 额外 axios 配置
 */
export function post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return service.post(url, data, config)
}

/**
 * 发起 PUT 请求
 */
export function put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return service.put(url, data, config)
}

/**
 * 发起 DELETE 请求
 */
export function del<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return service.delete(url, { params, ...config })
}

/**
 * 发起 PATCH 请求（M4.1 新增：目标部分更新 PATCH /objectives/{oid}）
 */
export function patch<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return service.patch(url, data, config)
}

export default service
