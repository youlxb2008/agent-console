/**
 * 后端统一响应格式（对应 Java ResultVO）
 * @template T - data 字段的数据类型
 */
export interface ApiResponse<T = any> {
  /** 业务数据 */
  data: T
  /** 业务状态码：200=成功, 401=未登录/token失效, 403=权限不足, 500=失败 */
  code: string
  /** 提示消息 */
  msg: string
  /** 是否成功（code === '200'） */
  success: boolean
}

/**
 * 分页查询参数（通用）
 */
export interface PageQuery {
  /** 当前页码，从 1 开始 */
  page?: number
  /** 每页条数 */
  size?: number
  /** 其它动态查询字段 */
  [key: string]: any
}

/**
 * 分页响应结构（通用）
 * @template T - 列表元素类型
 */
export interface PageResponse<T = any> {
  /** 当前页数据列表 */
  list: T[]
  /** 总记录数 */
  total: number
  /** 总记录数（MyBatis-Plus IPage 字段名，后端分页 VO 实际返回该字段；68655f8 分页 total 取值修正配套声明） */
  count?: number
  /** 当前页码 */
  page: number
  /** 每页条数 */
  size: number
}

/**
 * 登录响应数据
 */
export interface LoginResult {
  accessToken: string
  refreshToken: string
  nickname: string
  /** 用户头像 URL（相对路径，如 /uploads/avatar/xxx.jpg） */
  avatar?: string
  /** 角色编码列表 */
  roles: string[]
  /** 权限码列表，如 ['system:user','system:role'] */
  permissions: string[]
  /** 是否需要强制修改密码 */
  mustChangePassword?: boolean
}

/**
 * 当前登录用户信息
 */
export interface CurrentUser {
  id?: number | string
  username?: string
  nickname?: string
  avatar?: string
  roles?: string[]
  permissions?: string[]
  [key: string]: any
}
