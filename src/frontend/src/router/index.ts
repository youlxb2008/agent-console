import { createRouter, createWebHistory } from 'vue-router'
import { staticRoutes, STATIC_ROUTE_NAMES } from './routes'
import { setupGuards, setAuthenticated, registerDynamicRoutes, resetDynamicRoutes } from './guard'

// 创建路由实例，使用 HTML5 history 模式
// M1.4：管理端整体挂载到 /admin 子路径（nginx alias），路由 base 须同步，否则 history 模式 404
const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: staticRoutes,
  // 切换路由后滚动到顶部
  scrollBehavior: () => ({ top: 0 })
})

// 安装全局路由守卫
setupGuards(router)

/**
 * 重置路由：移除所有动态注册的路由，仅保留静态路由
 * 用于退出登录后清理动态路由
 */
export function resetRouter() {
  router.getRoutes().forEach((r) => {
    const name = r.name as string | undefined
    if (name && !STATIC_ROUTE_NAMES.includes(name) && name !== 'NotFoundCatch') {
      router.removeRoute(name)
    }
  })
  // 重置动态路由标记，允许下次登录重新注册
  resetDynamicRoutes()
}

export default router
export { setAuthenticated, registerDynamicRoutes, resetDynamicRoutes }
