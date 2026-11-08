import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 菜单节点结构（前端统一格式，与后端字段名解耦）
 */
export interface MenuNode {
  /** 菜单 ID */
  id: number | string
  /** 父级 ID */
  parentId?: number | string | null
  /** 菜单显示名称 */
  name: string
  /** 路由路径（一级菜单以 / 开头，子菜单相对路径） */
  path?: string
  /** 组件路径，相对于 src/views，如 'system/user/index' */
  component?: string
  /** 图标（Iconify 图标名或 Element 图标名） */
  icon?: string
  /** 菜单类型：1=目录 2=菜单 3=按钮 */
  type?: number
  /** 权限码（按钮型菜单使用） */
  perms?: string
  /** 路由跳转地址（外链） */
  redirect?: string
  /** 是否隐藏 */
  hidden?: boolean
  /** 是否需要缓存（keep-alive） */
  keepAlive?: boolean
  /** 排序 */
  sort?: number
  /** 状态：1-启用 0-禁用 */
  status?: number
  /** 子菜单 */
  children?: MenuNode[]
}

/**
 * 将后端返回的菜单数据转换为前端 MenuNode 格式
 * 后端字段：routePath, permissionKey, sortOrder, hidden(0/1)
 * 前端字段：path, perms, sort, hidden(boolean)
 */
function transformMenuNode(raw: any): MenuNode {
  return {
    id: raw.id,
    parentId: raw.parentId,
    name: raw.name || '',
    // 后端 routePath → 前端 path
    path: raw.routePath || raw.path || '',
    // 后端 component 可能带 views/ 前缀，统一去掉
    component: raw.component ? raw.component.replace(/^views\//, '').replace(/^\/views\//, '') : undefined,
    icon: raw.icon || undefined,
    type: raw.type,
    // 后端 permissionKey → 前端 perms
    perms: raw.permissionKey || raw.perms || undefined,
    redirect: raw.redirect || undefined,
    // 后端 hidden(0/1) → 前端 hidden(boolean)
    hidden: raw.hidden === 1 || raw.hidden === true,
    keepAlive: raw.keepAlive !== false,
    // 后端 sortOrder → 前端 sort
    sort: raw.sortOrder ?? raw.sort ?? 0,
    status: raw.status,
    children: raw.children?.length ? raw.children.map((c: any) => transformMenuNode(c)) : []
  }
}

/**
 * 菜单 store（Composition API 风格）
 * 维护从后端获取的菜单树，供侧边栏渲染与动态路由注册使用
 */
export const useMenuStore = defineStore('menu', () => {
  // ===== state =====
  // 完整菜单树
  const menus = ref<MenuNode[]>([])
  // 是否已加载（避免重复请求）
  const loaded = ref(false)

  // ===== actions =====
  /** 设置菜单树（自动转换后端字段名） */
  function setMenus(rawMenus: any[]) {
    menus.value = (rawMenus || []).map((m: any) => transformMenuNode(m))
    loaded.value = true
  }

  /** 清空菜单（退出登录） */
  function clear() {
    menus.value = []
    loaded.value = false
  }

  // 暴露 state、actions（保持对外 API 不变）
  return {
    // state
    menus,
    loaded,
    // actions
    setMenus,
    clear
  }
})
