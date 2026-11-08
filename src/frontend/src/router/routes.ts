import type { RouteRecordRaw } from 'vue-router'

// 布局组件（懒加载）
const Layout = () => import('@/layout/index.vue')

/**
 * 静态路由表
 *
 * - 业务页面由后端菜单驱动，通过 router.addRoute 动态注册（参照 NodeAdmin 模式）
 * - 这里只保留登录、错误页、Layout 容器以及 dashboard 占位
 * - meta.noAuth = true 的路由不需要登录即可访问
 */
export const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login.vue'),
    meta: { title: '登录', noAuth: true }
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/403.vue'),
    meta: { title: '无权限', noAuth: true }
  },
  {
    path: '/404',
    name: 'notFound',
    component: () => import('@/views/404.vue'),
    meta: { title: '页面不存在', noAuth: true }
  },
  {
    // 主布局容器：顶部 + 侧边栏 + 内容区
    path: '/',
    name: 'layout',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/system/dashboard/index.vue'),
        meta: { title: '首页', icon: 'mdi:view-dashboard', affix: true, keepAlive: true }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/system/profile/index.vue'),
        meta: { title: '个人资料', hidden: true }
      },
      {
        path: 'profile/password',
        name: 'ProfilePassword',
        component: () => import('@/views/system/profile/password.vue'),
        meta: { title: '修改密码', hidden: true }
      },
      {
        path: 'profile/devices',
        name: 'profile-devices',
        component: () => import('@/views/system/profile/devices.vue'),
        meta: { title: '在线设备' }
      },
      {
        path: 'system/tools',
        name: 'SystemTools',
        component: () => import('@/views/system/tools/index.vue'),
        meta: { title: '系统工具箱', perm: 'system:tools', icon: 'ph:wrench', hidden: true }
      },
      {
        path: 'system/bus',
        name: 'SystemBus',
        component: () => import('@/views/system/bus/index.vue'),
        meta: { title: '消息总线', perm: 'bus:message:list', icon: 'ph:envelope-simple', hidden: true }
      },
      {
        path: 'system/bus-debug',
        name: 'SystemBusDebug',
        component: () => import('@/views/system/bus/debug.vue'),
        meta: { title: '总线调试', perm: 'bus:message:list', icon: 'ph:bug', hidden: true }
      }
    ]
  }
]

/**
 * 静态路由 name 集合，用于 resetRouter 时区分动态路由
 */
export const STATIC_ROUTE_NAMES = staticRoutes.reduce<string[]>((acc, r) => {
  if (r.name) acc.push(r.name as string)
  // 收集子路由 name
  ;(r.children || []).forEach((c) => {
    if (c.name) acc.push(c.name as string)
  })
  return acc
}, [])
