import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { THEMES, findTheme, DEFAULT_THEME_ID, type Theme, type LayoutMode } from '@/assets/css/themes'

/**
 * 主题 store
 *
 * 维护当前主题 id 和布局风格（dark/light），并对外提供切换能力。
 * 实际 CSS 变量注入由 applyTheme() 完成，本 store 只负责状态层。
 */
export const useThemeStore = defineStore('theme', () => {
  // ===== state =====
  /** 当前主题 id */
  const currentId = ref<string>(DEFAULT_THEME_ID)
  /** 当前布局风格：dark（暗色 header/sidebar）/ light（明色） */
  const layoutMode = ref<LayoutMode>('dark')

  // ===== getters =====
  /** 当前主题完整对象 */
  const current = computed<Theme>(() => findTheme(currentId.value))
  /** 全部可选主题 */
  const themes = computed<Theme[]>(() => THEMES)
  /** 当前 header/sidebar 配色（根据 layoutMode 自动选取） */
  const layout = computed(() => {
    switch (layoutMode.value) {
      case 'light':
        return current.value.layoutLight
      case 'navy':
        return current.value.layoutNavy ?? current.value.layoutDark
      case 'tinted':
        return current.value.layoutTinted ?? current.value.layoutLight
      default:
        return current.value.layoutDark
    }
  })

  // ===== actions =====
  /**
   * 设置主题 id（仅修改状态，不直接写 CSS 变量）
   * 调用方需额外调用 applyTheme() 触发样式更新
   * @param id 主题 id
   */
  function setThemeId(id: string) {
    if (THEMES.some((t) => t.id === id)) {
      currentId.value = id
    }
  }

  /**
   * 设置布局风格（dark/light）
   * 调用方需额外调用 applyTheme() 触发样式更新
   * @param mode 布局风格
   */
  function setLayoutMode(mode: LayoutMode) {
    layoutMode.value = mode
  }

  return {
    // state
    currentId,
    layoutMode,
    // getters
    current,
    themes,
    layout,
    // actions
    setThemeId,
    setLayoutMode
  }
})
