/**
 * 门户端请求封装（原生 fetch，不引 axios——门户是轻量内容展示）
 *
 * v2：门户纯匿名只读（无登录态、无 token），401/403 由后端 R 体 code 表达。
 * 所有 API 走 /api 前缀：生产由 nginx 反代 /api → backend:8000，dev 由 vite proxy 转发。
 */

/** 后端统一响应体 R<T>：code/msg/success/data（success + code === "200" 视为成功） */
export interface ApiResult<T> {
  code: string
  msg: string
  success: boolean
  data: T
}

/** 业务/网络错误 */
export class ApiError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 发起 API 请求（匿名只读）
 * @param path 形如 /api/portal/site
 * @returns 后端 data 字段
 * @throws ApiError（业务失败/网络异常/HTTP 非 2xx）
 */
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers || {})
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  let res: Response
  try {
    res = await fetch(path, { ...options, headers })
  } catch {
    throw new ApiError('网络异常，请稍后重试')
  }

  let body: ApiResult<T>
  try {
    body = (await res.json()) as ApiResult<T>
  } catch {
    throw new ApiError(`服务响应异常（HTTP ${res.status}）`)
  }

  if (body.code !== '200' || body.success !== true) {
    throw new ApiError(body.msg || '请求失败')
  }
  return body.data
}
