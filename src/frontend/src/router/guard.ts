import type { Router, RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/store/user'
import { usePermissStore } from '@/store/permiss'
import { useSiteStore } from '@/store/site'
import type { MenuNode } from '@/store/menu'

/**
 * 全局认证标记
 *
 * - 不放在 Pinia store 中（避免持久化 / 刷新丢失问题）
 * - main.ts 启动时探测登录态，login.vue 登录成功后调用 setAuthenticated(true)
 * - 守卫据此判断是否已登录
 */
let authenticated = false

/** 设置全局认证标记 */
export function setAuthenticated(value: boolean): void {
  authenticated = value
}

/** 查询当前认证状态 */
export function isAuthenticated(): boolean {
  return authenticated
}

/**
 * 业务页面组件 glob 表
 *
 * 后端菜单中 component 字段为相对 views/ 的路径，
 * 用 import.meta.glob 动态加载对应组件。
 * 例：菜单 component = 'system/user/index' → '../views/system/user/index.vue'
 */
const viewModules = import.meta.glob('../views/**/*.vue')

/** 标记动态路由是否已注册，避免重复注册 */
let dynamicRoutesAdded = false

/**
 * 根据菜单组件路径定位到对应的 Vue 组件
 * @param component 菜单的 component 字段（相对 views/，不含扩展名）
 * @returns 异步组件构造函数；未找到时回退到 404
 */
function resolveComponent(component: string) {
  let key = component.startsWith('/')
    ? `../views${component}`
    : `../views/${component}`
  if (!key.endsWith('.vue')) key = `${key}.vue`
  const loader = viewModules[key]
  // 找不到时回退到 404，避免白屏
  return loader || viewModules['../views/404.vue']
}

/**
 * 递归把菜单树转成 Vue Router 路由配置
 * 仅菜单类型（type=2）且有 component 的节点参与路由注册
 * @param nodes 菜单树节点
 * @returns 路由配置数组
 */
function buildRoutesFromMenus(nodes: MenuNode[]): RouteRecordRaw[] {
  const result: RouteRecordRaw[] = []
  for (const node of nodes) {
    // 子菜单递归必须放在 continue 之前，否则父节点没有 component 时子节点也会被跳过
    if (node.children?.length) {
      result.push(...buildRoutesFromMenus(node.children))
    }

    // 跳过非菜单类型节点（目录 type=1、按钮 type=3 不注册路由）
    if (node.type !== 2) continue

    // 路径或组件缺失则跳过当前节点（仅跳过自身，不影响子节点）
    if (!node.path || !node.component) continue

    const route: RouteRecordRaw = {
      // 父路由作为 Layout 子路由挂载，因此剔除开头的斜杠
      // 例如 /system/user → system/user，Vue Router 自动拼到 Layout 的 / 下
      path: node.path.replace(/^\//, ''),
      name: `menu_${node.id}`,
      meta: { title: node.name, perm: node.perms, icon: node.icon },
      component: resolveComponent(node.component),
    }
    result.push(route)
  }
  return result
}

/**
 * 根据菜单注册动态路由到 Layout 下
 *
 * 关键：必须在 app.use(router) 之前调用，这样首次导航时路由表已完整。
 * 登录页也需要在 router.push 之前调用，确保守卫能匹配到目标路由。
 *
 * @param router Vue Router 实例
 * @param menus 当前用户的菜单树
 */
export function registerDynamicRoutes(router: Router, menus: MenuNode[]): void {
  if (dynamicRoutesAdded) return
  const routes = buildRoutesFromMenus(menus)
  for (const route of routes) {
    // 全部挂在 Layout 路由（name: 'layout'）之下
    router.addRoute('layout', route)
  }
  // 兜底路由必须在所有业务路由之后注册，
  // 否则 /:pathMatch(.*)* 通配符会优先于 addRoute 的动态路由匹配
  router.addRoute({
    path: '/:pathMatch(.*)*',
    name: 'NotFoundCatch',
    redirect: '/404'
  })
  dynamicRoutesAdded = true
}

/** 重置动态路由标记（登出 / 重新登录时调用） */
export function resetDynamicRoutes(): void {
  dynamicRoutesAdded = false
}

/**
 * 配置全局路由守卫
 *
 * 守卫规则（纯同步，无任何异步操作）：
 * 1. 设置页面标题
 * 2. 未登录访问受保护路由 → 跳转登录页
 * 3. 已登录访问登录页 → 重定向首页
 * 4. 路由级权限校验（meta.perm）
 * 5. 强制改密拦截
 * 6. 放行
 */
export function setupGuards(router: Router): void {
  router.beforeEach((to, _from, next) => {
    // 1. 同步页面标题
    const siteStore = useSiteStore()
    const siteTitle = siteStore.title || '管理端'
    const pageTitle = String(to.meta?.title || '')
    document.title = pageTitle ? `${pageTitle} - ${siteTitle}` : siteTitle

    // 2. 白名单路由（登录、403、404）无需认证
    const noAuth = to.meta?.noAuth as boolean | undefined
    if (noAuth) {
      // 已登录访问登录页 → 重定向首页
      if (authenticated && to.path === '/login') {
        next('/')
        return
      }
      next()
      return
    }

    // 3. 未登录 → 跳转登录页，记录回跳地址
    if (!authenticated) {
      next({ path: '/login', query: { redirect: to.fullPath } })
      return
    }

    // 4. 路由级权限校验（meta.perm 存在时才校验）
    const perm = to.meta?.perm as string | undefined
    if (perm) {
      const permissStore = usePermissStore()
      if (!permissStore.has(perm)) {
        next({ path: '/403', query: { perm } })
        return
      }
    }

    // 5. 强制改密拦截
    const userStore = useUserStore()
    if (userStore.mustChangePassword) {
      const allowedPaths = ['/profile/password', '/profile']
      if (!allowedPaths.some((p) => to.path.startsWith(p))) {
        import('element-plus').then((ep) => {
          ep.ElMessage.warning('请先修改初始密码后再继续操作')
        })
        next({ path: '/profile/password' })
        return
      }
    }

    // 6. 放行
    next()
  })

  // 进入路由后添加标签页
  router.afterEach((to) => {
    import('@/store/tags').then(({ useTagsStore }) => {
      useTagsStore().addTag(to)
    })
  })
}
