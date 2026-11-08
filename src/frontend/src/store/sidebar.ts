import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 侧边栏 store（Composition API 风格）
 * 控制侧边栏的折叠/展开状态
 */
export const useSidebarStore = defineStore('sidebar', () => {
  // ===== state =====
  // 是否折叠
  const collapse = ref(false)

  // ===== actions =====
  /** 切换折叠状态 */
  function toggle() {
    collapse.value = !collapse.value
  }

  /** 设置折叠状态 */
  function setCollapse(val: boolean) {
    collapse.value = val
  }

  // 暴露 state、actions（保持对外 API 不变）
  return {
    // state
    collapse,
    // actions
    toggle,
    setCollapse
  }
})
