import { createApp } from 'vue'
import { createPinia } from 'pinia'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
// ElMessage / ElMessageBox / ElLoading 是函数式组件，DOM 直接挂到 body，
// 按需导入方案下样式不会自动注入，必须手动引入这几个组件的样式
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
import 'element-plus/es/components/notification/style/css'
import App from './App.vue'
import router, { setAuthenticated, registerDynamicRoutes } from './router'
import { useUserStore } from './store/user'
import { usePermissStore } from './store/permiss'
import { useMenuStore } from './store/menu'
import { getCurrentUser } from './api/auth'
import { getMyMenuTree } from './api/system/menu'
import { createPermissDirective } from './directives/permiss'
import { loadPublicSite } from './composables/useSiteSettings'

/**
 * 应用启动入口
 *
 * 启动流程（参照 NodeAdmin 模式）：
 * 1. 创建 Vue 实例并装载 Pinia
 * 2. 探测登录态：成功则写入 user/perms/menus 并注册动态路由
 * 3. app.use(router) —— 关键：在动态路由注册之后
 * 4. 全局注册图标组件、权限指令
 * 5. 挂载到 #app
 *
 * 关键时序：registerDynamicRoutes 必须在 app.use(router) 之前完成，
 * 这样首次导航时路由表已包含所有业务页面，守卫无需异步 addRoute。
 */
async function bootstrap(): Promise<void> {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)

  // 加载公开站点配置（含主题色），同步应用到 CSS 变量
  // 公开接口（无需登录）调用，先于登录态探测，避免登录页白屏闪烁
  await loadPublicSite()

  // 探测登录态（通过 localStorage 中是否有 token 判断）
  const token = localStorage.getItem('accessToken')
  if (token) {
    try {
      // 并行请求当前用户信息和当前用户可见菜单树
      const [userRes, menuRes] = await Promise.all([getCurrentUser(), getMyMenuTree()])

      const userData = userRes.data
      const menus = menuRes.data || []

      if (userData) {
        const userStore = useUserStore()
        const permissStore = usePermissStore()
        const menuStore = useMenuStore()

        // 写入用户详细信息
        userStore.setUser(userData)
        // 写入权限码
        permissStore.setPerms(userData.permissions || [])
        // 写入菜单树
        menuStore.setMenus(menus)

        // 关键：注册动态路由必须在 app.use(router) 之前完成！
        // 这样初次进入受保护路由时守卫能匹配到目标
        registerDynamicRoutes(router, menus)
        setAuthenticated(true)
      }
    } catch {
      // token 过期或无效，清除登录态
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      setAuthenticated(false)
    }
  }

  // 安装路由（此时动态路由已注册完成）
  app.use(router)

  // 全局注册 Element Plus 图标组件，便于模板中直接使用
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component as any)
  }

  // 注册自定义权限指令 v-permiss（依赖 permiss store，故在 pinia 安装后创建）
  const permissStore = usePermissStore()
  app.directive('permiss', createPermissDirective(permissStore))

  app.mount('#app')
}

bootstrap()
