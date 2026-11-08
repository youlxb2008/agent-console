import { ref, computed } from 'vue'
import { portalApi, type PortalNotebook } from '@/api/portal'

/**
 * 笔记本列表与当前选择（模块级响应式，全局共享）
 * - 首页卡片（HomeView）：复用列表渲染，避免重复请求
 * - 笔记页信息卡（NotesView）：根据 selectNotebookId 取 currentNotebook
 * - 智能体详情页（AgentDetailView）：agent 展示名回退匹配
 */

/** 全部启用笔记本（含笔记数、最近更新时间、关联 agent 数） */
export const notebooks = ref<PortalNotebook[]>([])

/** 列表加载中（首次进入时为 true） */
export const notebooksLoading = ref(true)

/** 当前选中的 notebookId（字符串，M3.8 起与路由 params.notebookId 同步） */
export const selectNotebookId = ref('')

/** 已加载标志：防止多视图挂载时重复请求 */
let loaded = false

/** 当前笔记本对象（未选中或找不到时为 null） */
export const currentNotebook = computed<PortalNotebook | null>(() => {
  const id = selectNotebookId.value
  if (!id) return null
  return notebooks.value.find((n) => String(n.id) === id) || null
})

/** 拉取笔记本列表（幂等：已加载过则跳过，失败静默清空） */
export async function loadNotebooks(): Promise<void> {
  if (loaded) return
  notebooksLoading.value = true
  try {
    notebooks.value = (await portalApi.notebooks()) || []
    loaded = true
  } catch {
    notebooks.value = []
  } finally {
    notebooksLoading.value = false
  }
}

/** 下拉/卡片展示名：name 优先 */
export function notebookLabel(n: PortalNotebook): string {
  return n.name || `笔记本 ${n.id}`
}
