import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 权限码 store（Composition API 风格）
 * 维护当前登录用户拥有的权限码列表，并提供 v-permiss 指令所需的校验方法
 */
export const usePermissStore = defineStore('permiss', () => {
  // ===== state =====
  // 权限码列表，如 ['system:user', 'system:role:add']
  const perms = ref<string[]>([])

  // ===== getters =====
  /**
   * 判断是否拥有指定权限码
   * 说明：原 Options API 中是返回函数的 getter（has(code) 调用形式），
   * 在 setup store 中以普通函数暴露，对外调用方式 permissStore.has(code) 保持不变
   * @param code 权限码
   * @returns true 表示拥有该权限
   */
  function has(code: string): boolean {
    return perms.value.includes(code)
  }

  // ===== actions =====
  /** 设置权限码列表（登录成功后调用） */
  function setPerms(newPerms: string[]) {
    perms.value = Array.isArray(newPerms) ? newPerms : []
  }

  /** 清空权限码（退出登录时调用） */
  function clear() {
    perms.value = []
  }

  // 暴露 state、getters、actions（保持对外 API 不变）
  return {
    // state
    perms,
    // getters
    has,
    // actions
    setPerms,
    clear
  }
})
