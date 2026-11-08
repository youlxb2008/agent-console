import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

/** 站点配置结构 */
export interface SiteConfig {
  /** 站点标题 */
  title: string
  /** 站点副标题/欢迎语 */
  subtitle: string
  /** Logo 图标（Iconify 图标名） */
  logo: string
  /** 登录页背景图路径 */
  loginBg: string
  /** 主题色 id（如 indigo/emerald/rose/amber/slate） */
  theme: string
  /** 布局风格：dark（暗色 header/sidebar）/ light（明色） */
  layoutMode: string
  /** 版权信息 */
  copyright: string
  /** 备案号 */
  icp: string
  /** 门户质感方案（aurora 柔光 / relief 浮雕 / grid 网格），下发给门户端只读应用 */
  portalTexture: string
  [key: string]: any
}

/** 默认站点配置 */
const DEFAULT_SITE: SiteConfig = {
  title: '管理端',
  subtitle: '通用 Web 管理平台',
  logo: 'mdi:view-dashboard',
  loginBg: '',
  theme: 'indigo',
  layoutMode: 'dark',
  copyright: `© ${new Date().getFullYear()} 管理端`,
  icp: '',
  portalTexture: 'aurora'
}

/**
 * 站点配置 store（Composition API 风格）
 * 维护全局站点信息（标题、Logo、版权、主题等）
 */
export const useSiteStore = defineStore('site', () => {
  // ===== state =====
  // 站点配置（初始化为默认值的副本，避免相互引用）
  const config = ref<SiteConfig>({ ...DEFAULT_SITE })
  // 是否已加载
  const loaded = ref(false)

  // ===== getters =====
  /** 站点标题 */
  const title = computed(() => config.value.title)
  /** Logo 图标 */
  const logo = computed(() => config.value.logo)
  /** 当前主题 id */
  const theme = computed(() => config.value.theme)

  // ===== actions =====
  /** 设置站点配置（合并默认值与当前配置） */
  function setSite(newConfig: Partial<SiteConfig>) {
    config.value = { ...DEFAULT_SITE, ...config.value, ...newConfig }
    loaded.value = true
    // 同步浏览器标题
    if (config.value.title) {
      document.title = config.value.title
    }
  }

  /** 重置为默认配置 */
  function reset() {
    config.value = { ...DEFAULT_SITE }
    loaded.value = false
  }

  // 暴露 state、getters、actions（保持对外 API 不变）
  return {
    // state
    config,
    loaded,
    // getters
    title,
    logo,
    theme,
    // actions
    setSite,
    reset
  }
})
