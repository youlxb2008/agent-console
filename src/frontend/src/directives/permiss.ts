import type { Directive, DirectiveBinding } from 'vue'
import type { usePermissStore } from '@/store/permiss'

/**
 * 创建 v-permiss 自定义指令
 * 根据当前用户权限码控制元素的显示/隐藏：
 *   v-permiss="'system:user'"        单个权限码
 *   v-permiss="['system:user','a']"  多个权限码（满足其一即显示）
 * @param permissStore 权限 store 实例
 */
export function createPermissDirective(permissStore: ReturnType<typeof usePermissStore>): Directive {
  return {
    mounted(el: HTMLElement, binding: DirectiveBinding) {
      const { value } = binding
      if (!value) return
      // 统一成数组处理
      const codes: string[] = Array.isArray(value) ? value : [String(value)]
      // 任一权限码命中则视为有权限
      const hasPerm = codes.some((c) => permissStore.has(c))
      if (!hasPerm) {
        // 无权限则从 DOM 中移除该元素
        el.parentNode?.removeChild(el)
      }
    }
  }
}
