/**
 * 主题应用工具函数
 *
 * 负责把 theme.palette 中的色值写入 document.documentElement 的 style，
 * 覆盖 variables.css 中的默认 CSS 变量。
 *
 * 应用策略：
 * - 直接覆盖 --el-color-primary 系列（Element Plus 组件直接生效）
 * - 同时覆盖 --na-primary 系列（自定义业务组件生效）
 * - 覆盖 --na-bg-sidebar-hover/active 等基于主色计算的透明色
 * - 覆盖 4 个功能色（success/warning/danger/info）的 --na-* 与 --el-*
 *
 * 注意：仅覆盖需要随主题变化的色值；文字色、边框色、布局尺寸等保持不动。
 */
import { THEMES, findTheme, type Theme, type LayoutMode, type LayoutColors } from '@/assets/css/themes'

/**
 * 当前布局风格（applyTheme 模块内维护的状态）
 * 切换主题时未显式传 layoutMode，则保持之前的布局风格
 */
let currentLayoutMode: LayoutMode = 'dark'

/**
 * 根据布局风格获取对应的 LayoutColors
 * navy/tinted 不存在时 fallback 到 dark
 */
function getLayoutColors(theme: Theme, mode: LayoutMode): LayoutColors {
  switch (mode) {
    case 'light':
      return theme.layoutLight
    case 'navy':
      return theme.layoutNavy ?? theme.layoutDark
    case 'tinted':
      return theme.layoutTinted ?? theme.layoutLight
    default:
      return theme.layoutDark
  }
}

/**
 * 将 hex 颜色与白色按 alpha 混合（用于生成 light-3 等中间色）
 * @param hex 原色（如 #6366f1）
 * @param alpha 白色混合比例（0~1，越大越浅）
 */
function mixWithWhite(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  const nr = Math.round(r + (255 - r) * alpha)
  const ng = Math.round(g + (255 - g) * alpha)
  const nb = Math.round(b + (255 - b) * alpha)
  return `#${nr.toString(16).padStart(2, '0')}${ng.toString(16).padStart(2, '0')}${nb.toString(16).padStart(2, '0')}`
}

/**
 * hex 转 rgba 字符串（用于带透明度的色值，如 sidebar hover/active 背景）
 * @param hex 原色
 * @param alpha 透明度 0~1
 */
function hexToRgba(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/**
 * 把主题应用到 document.documentElement，覆盖 CSS 变量
 *
 * @param themeOrId 主题对象（或主题 id，未找到则用 indigo）
 * @param layoutMode 布局风格 dark/light（影响 header/sidebar 配色）
 *                   不传则用上次设置的值（默认 dark）
 */
export function applyTheme(themeOrId: Theme | string, layoutMode?: LayoutMode): void {
  const theme = typeof themeOrId === 'string' ? findTheme(themeOrId) : themeOrId
  const palette = theme.palette
  // 确定布局风格：优先用传入的，否则读全局 __layoutMode（applyTheme 内部维护）
  const mode = layoutMode ?? currentLayoutMode
  const layout = getLayoutColors(theme, mode)
  currentLayoutMode = mode
  const root = document.documentElement

  // 计算 primary 的中间色阶（Element Plus 用 light-1~9，dark-2）
  const primaryLight1 = mixWithWhite(palette.primary, 0.1)
  const primaryLight2 = palette.primaryHover
  const primaryLight3 = mixWithWhite(palette.primary, 0.2)
  const primaryLight4 = mixWithWhite(palette.primary, 0.3)
  const primaryLight5 = mixWithWhite(palette.primary, 0.4)
  const primaryLight6 = mixWithWhite(palette.primary, 0.5)
  const primaryLight7 = mixWithWhite(palette.primary, 0.6)
  const primaryLight8 = palette.primaryLight8
  const primaryLight9 = palette.primaryLight9
  const primaryDark2 = palette.primaryActive

  // ---- 业务变量 --na-* ----
  root.style.setProperty('--na-primary', palette.primary)
  root.style.setProperty('--na-primary-hover', palette.primaryHover)
  root.style.setProperty('--na-primary-active', palette.primaryActive)
  /* primary 浅色变体（用于 tags 非激活态、菜单背景等需要主题色但不抢戏的场景） */
  root.style.setProperty('--na-primary-light-8', palette.primaryLight8)
  root.style.setProperty('--na-primary-light-9', palette.primaryLight9)
  /* 渐变跟随主题
   * 用 primary（主色）→ primaryActive（按下色，深一档）形成深色渐变
   * 保证绿色/橙色等中等明度色系下也能看到明显的深度变化
   * primaryActive 是已精心调过明度的"深色版本"，比 mixWithWhite 算法
   * 给的明度差更大，避免渐变看起来像单色 */
  root.style.setProperty(
    '--na-primary-gradient',
    `linear-gradient(135deg, ${palette.primary} 0%, ${palette.primaryActive} 100%)`
  )
  /* hover 态：主色 → 更亮（primaryHover），hover 时反向渐变到浅色，提示"被点击" */
  root.style.setProperty(
    '--na-primary-gradient-hover',
    `linear-gradient(135deg, ${palette.primary} 0%, ${palette.primaryHover} 100%)`
  )
  // sidebar 半透明背景（基于主色 + 透明度，主题决定色，layout 决定 alpha 强度）
  root.style.setProperty('--na-bg-sidebar-hover', layout.hoverBg)
  root.style.setProperty('--na-bg-sidebar-active', layout.activeBg)
  // header/sidebar 整体配色（来自主题的 layout 配置）
  root.style.setProperty('--na-bg-header', layout.headerBg)
  root.style.setProperty('--na-text-header', layout.headerText)
  root.style.setProperty('--na-border-header', layout.headerBorder)
  root.style.setProperty('--na-bg-sidebar', layout.sidebarBg)
  root.style.setProperty('--na-text-sidebar', layout.sidebarText)
  root.style.setProperty('--na-text-sidebar-active', layout.sidebarTextActive)
  root.style.setProperty('--na-border-sidebar', layout.sidebarBorder)
  // 标签激活态阴影（跟随主题）
  root.style.setProperty('--na-shadow-tag-active', `0 4px 12px ${hexToRgba(palette.primary, 0.35)}`)
  // 布局风格（用于 CSS 中根据 mode 微调细节，如 divider 颜色等）
  root.dataset.naLayout = mode
  // 功能色（保持与 variables.css 一致）
  root.style.setProperty('--na-success', palette.success)
  root.style.setProperty('--na-warning', palette.warning)
  root.style.setProperty('--na-danger', palette.danger)
  root.style.setProperty('--na-info', palette.info)

  // ---- Element Plus 变量 --el-color-primary 系列 ----
  root.style.setProperty('--el-color-primary', palette.primary)
  root.style.setProperty('--el-color-primary-light-1', primaryLight1)
  root.style.setProperty('--el-color-primary-light-2', primaryLight2)
  root.style.setProperty('--el-color-primary-light-3', primaryLight3)
  root.style.setProperty('--el-color-primary-light-4', primaryLight4)
  root.style.setProperty('--el-color-primary-light-5', primaryLight5)
  root.style.setProperty('--el-color-primary-light-6', primaryLight6)
  root.style.setProperty('--el-color-primary-light-7', primaryLight7)
  root.style.setProperty('--el-color-primary-light-8', primaryLight8)
  root.style.setProperty('--el-color-primary-light-9', primaryLight9)
  root.style.setProperty('--el-color-primary-dark-2', primaryDark2)

  // ---- Element Plus 4 个功能色变量 + error 别名 ----
  root.style.setProperty('--el-color-success', palette.success)
  root.style.setProperty('--el-color-warning', palette.warning)
  root.style.setProperty('--el-color-danger', palette.danger)
  root.style.setProperty('--el-color-info', palette.info)
  // error 与 danger 保持一致（el-form-item 校验、MessageBox error 等用 error）
  root.style.setProperty('--el-color-error', palette.danger)
  // error light/dark 变体（基于 danger 计算）
  root.style.setProperty('--el-color-error-light-3', mixWithWhite(palette.danger, 0.2))
  root.style.setProperty('--el-color-error-light-5', mixWithWhite(palette.danger, 0.4))
  root.style.setProperty('--el-color-error-light-7', mixWithWhite(palette.danger, 0.6))
  root.style.setProperty('--el-color-error-light-8', mixWithWhite(palette.danger, 0.8))
  root.style.setProperty('--el-color-error-light-9', mixWithWhite(palette.danger, 0.9))
  // error-dark-2 用 danger-dark-2 的近似值（dangerDark = #e11d48）
  root.style.setProperty('--el-color-error-dark-2', palette.danger)

  // ---- 4 个功能色 light/dark 变体同步覆盖 ----
  // success
  root.style.setProperty('--el-color-success-light-3', mixWithWhite(palette.success, 0.2))
  root.style.setProperty('--el-color-success-light-5', mixWithWhite(palette.success, 0.4))
  root.style.setProperty('--el-color-success-light-7', mixWithWhite(palette.success, 0.6))
  root.style.setProperty('--el-color-success-light-8', mixWithWhite(palette.success, 0.8))
  root.style.setProperty('--el-color-success-light-9', mixWithWhite(palette.success, 0.9))
  root.style.setProperty('--el-color-success-dark-2', palette.success)
  // warning
  root.style.setProperty('--el-color-warning-light-3', mixWithWhite(palette.warning, 0.2))
  root.style.setProperty('--el-color-warning-light-5', mixWithWhite(palette.warning, 0.4))
  root.style.setProperty('--el-color-warning-light-7', mixWithWhite(palette.warning, 0.6))
  root.style.setProperty('--el-color-warning-light-8', mixWithWhite(palette.warning, 0.8))
  root.style.setProperty('--el-color-warning-light-9', mixWithWhite(palette.warning, 0.9))
  root.style.setProperty('--el-color-warning-dark-2', palette.warning)
  // danger
  root.style.setProperty('--el-color-danger-light-3', mixWithWhite(palette.danger, 0.2))
  root.style.setProperty('--el-color-danger-light-5', mixWithWhite(palette.danger, 0.4))
  root.style.setProperty('--el-color-danger-light-7', mixWithWhite(palette.danger, 0.6))
  root.style.setProperty('--el-color-danger-light-8', mixWithWhite(palette.danger, 0.8))
  root.style.setProperty('--el-color-danger-light-9', mixWithWhite(palette.danger, 0.9))
  root.style.setProperty('--el-color-danger-dark-2', palette.danger)
  // info
  root.style.setProperty('--el-color-info-light-3', mixWithWhite(palette.info, 0.2))
  root.style.setProperty('--el-color-info-light-5', mixWithWhite(palette.info, 0.4))
  root.style.setProperty('--el-color-info-light-7', mixWithWhite(palette.info, 0.6))
  root.style.setProperty('--el-color-info-light-8', mixWithWhite(palette.info, 0.8))
  root.style.setProperty('--el-color-info-light-9', mixWithWhite(palette.info, 0.9))
  root.style.setProperty('--el-color-info-dark-2', palette.info)
}

/**
 * 重置主题到 CSS 默认值（即清除 documentElement.style 上覆盖的 CSS 变量，
 * 让 variables.css 中的静态值生效）。主要用于主题被删除等异常场景。
 */
export function resetTheme(): void {
  document.documentElement.style.removeProperty('--na-primary')
  document.documentElement.style.removeProperty('--na-primary-hover')
  document.documentElement.style.removeProperty('--na-primary-active')
  document.documentElement.style.removeProperty('--na-primary-light-8')
  document.documentElement.style.removeProperty('--na-primary-light-9')
  document.documentElement.style.removeProperty('--na-primary-gradient')
  document.documentElement.style.removeProperty('--na-primary-gradient-hover')
  document.documentElement.style.removeProperty('--na-bg-sidebar-hover')
  document.documentElement.style.removeProperty('--na-bg-sidebar-active')
  document.documentElement.style.removeProperty('--na-bg-header')
  document.documentElement.style.removeProperty('--na-text-header')
  document.documentElement.style.removeProperty('--na-border-header')
  document.documentElement.style.removeProperty('--na-bg-sidebar')
  document.documentElement.style.removeProperty('--na-text-sidebar')
  document.documentElement.style.removeProperty('--na-text-sidebar-active')
  document.documentElement.style.removeProperty('--na-border-sidebar')
  document.documentElement.style.removeProperty('--na-shadow-tag-active')
  document.documentElement.style.removeProperty('--na-success')
  document.documentElement.style.removeProperty('--na-warning')
  document.documentElement.style.removeProperty('--na-danger')
  document.documentElement.style.removeProperty('--na-info')
  document.documentElement.style.removeProperty('--el-color-primary')
  for (let i = 1; i <= 9; i++) {
    document.documentElement.style.removeProperty(`--el-color-primary-light-${i}`)
  }
  document.documentElement.style.removeProperty('--el-color-primary-dark-2')
  document.documentElement.style.removeProperty('--el-color-success')
  document.documentElement.style.removeProperty('--el-color-warning')
  document.documentElement.style.removeProperty('--el-color-danger')
  document.documentElement.style.removeProperty('--el-color-info')
}

/**
 * 兜底：从默认主题开始应用（用于应用启动、登录前等场景）
 */
export function applyDefaultTheme(): void {
  applyTheme(THEMES[0])
}
