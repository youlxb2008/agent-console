/**
 * 站点配置 Composable
 *
 * 提供加载公开配置的能力，在路由守卫和启动时使用。
 * 加载失败时静默处理，使用 store 中的默认值。
 * 加载成功后会同步应用主题色和布局风格。
 *
 * 主题优先级（从高到低）：
 * 1. 后端 site_theme / site_layout_mode 配置项（已持久化的值）
 * 2. localStorage 暂存（用户实时切换但未保存）
 * 3. DEFAULT_THEME_ID / 'dark' 默认值
 */
import { useSiteStore } from '@/store/site'
import { useThemeStore } from '@/store/theme'
import { applyTheme } from '@/composables/useApplyTheme'
import { DEFAULT_THEME_ID, type LayoutMode } from '@/assets/css/themes'
import { getSiteConfig } from '@/api/system/site'

/** localStorage 中暂存主题的 key */
const LOCAL_THEME_KEY = 'site_theme_draft'
/** localStorage 中暂存布局风格的 key */
const LOCAL_LAYOUT_KEY = 'site_layout_draft'

/**
 * 从公开接口加载站点配置并写入 store
 * 失败时静默处理（使用默认值），不影响应用启动
 */
export async function loadPublicSite(): Promise<void> {
  try {
    const res = await getSiteConfig()
    // 解析响应数据：res 是 ApiResponse，data 是后端 key-value Map
    // 后端 key 格式：site_title / site_theme / site_login_bg / site_layout_mode
    // SiteConfig 接口格式：title / theme / loginBg / layoutMode
    const data = res.data
    if (data) {
      const store = useSiteStore()
      // 后端 key → SiteConfig 字段映射
      store.setSite({
        title: data.site_title || '',
        subtitle: data.site_subtitle || '',
        logo: data.site_logo || '',
        loginBg: data.site_login_bg || '',
        theme: data.site_theme || '',
        layoutMode: data.site_layout_mode || '',
        copyright: data.site_copyright || '',
        icp: data.site_icp || ''
      })
      // 同步应用主题色 + 布局风格
      const themeStore = useThemeStore()
      // 优先级：后端配置 > localStorage 暂存 > 默认值
      const backendTheme = data.site_theme
      const localTheme = localStorage.getItem(LOCAL_THEME_KEY)
      const themeId = backendTheme || localTheme || DEFAULT_THEME_ID
      // 布局风格同理
      const backendLayout = data.site_layout_mode
      const localLayout = localStorage.getItem(LOCAL_LAYOUT_KEY) as LayoutMode | null
      const layoutMode: LayoutMode = (backendLayout || localLayout || 'dark') as LayoutMode
      themeStore.setThemeId(themeId)
      themeStore.setLayoutMode(layoutMode)
      applyTheme(themeId, layoutMode)
    }
  } catch {
    // 加载失败使用默认配置，不影响页面正常访问
    const localTheme = localStorage.getItem(LOCAL_THEME_KEY)
    const localLayout = localStorage.getItem(LOCAL_LAYOUT_KEY) as LayoutMode | null
    if (localTheme) {
      applyTheme(localTheme, (localLayout as LayoutMode) || 'dark')
    }
  }
}

/**
 * 把当前选中的主题暂存到 localStorage
 * 用于"实时预览"未保存时，刷新页面也能恢复主题
 * @param themeId 主题 id
 */
export function stashThemeDraft(themeId: string): void {
  try {
    localStorage.setItem(LOCAL_THEME_KEY, themeId)
  } catch {
    // localStorage 写入失败（隐私模式等）静默处理
  }
}

/**
 * 把当前布局风格暂存到 localStorage
 * @param mode 布局风格
 */
export function stashLayoutDraft(mode: LayoutMode): void {
  try {
    localStorage.setItem(LOCAL_LAYOUT_KEY, mode)
  } catch {
    // 静默处理
  }
}

/**
 * 清除主题和布局暂存
 * 调用时机：用户成功保存配置后（后端已持久化），不再需要 localStorage 兜底
 */
export function clearThemeDraft(): void {
  try {
    localStorage.removeItem(LOCAL_THEME_KEY)
    localStorage.removeItem(LOCAL_LAYOUT_KEY)
  } catch {
    // 静默处理
  }
}
