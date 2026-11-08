import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getMyUnreadCount } from '@/api/system/notice'

/**
 * 通知 store（Composition API 风格）
 * 维护全局未读通知数，供 Header 铃铛组件和其它页面使用
 */
export const useNoticeStore = defineStore('notice', () => {
  // ===== state =====
  /** 未读通知数 */
  const unreadCount = ref(0)

  // ===== actions =====
  /**
   * 刷新未读数
   * - Header 挂载时调用一次
   * - 登录成功后调用
   */
  async function refreshUnreadCount() {
    try {
      const res = await getMyUnreadCount()
      unreadCount.value = res.data || 0
    } catch {
      // 静默失败，不影响主功能
    }
  }

  /** 未读数 -1（标记单条已读时调用） */
  function decrementUnread() {
    if (unreadCount.value > 0) {
      unreadCount.value--
    }
  }

  /** 未读数归零（标记全部已读时调用） */
  function resetUnread() {
    unreadCount.value = 0
  }

  // 暴露 state 和 actions
  return {
    unreadCount,
    refreshUnreadCount,
    decrementUnread,
    resetUnread
  }
})
