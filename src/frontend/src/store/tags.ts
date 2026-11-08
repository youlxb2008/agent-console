import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { RouteLocationNormalized } from 'vue-router'

/** 标签页项结构 */
export interface TagItem {
  /** 标签标题（取自路由 meta.title 或路由 name） */
  title: string
  /** 路由完整路径，作为唯一标识 */
  path: string
  /** 路由 name，用于 keep-alive 缓存匹配 */
  name: string
  /** 是否固定标签（不可关闭，如首页） */
  affix?: boolean
}

/**
 * 标签页 store（Composition API 风格）
 * 管理多标签页与 keep-alive 缓存视图列表
 */
export const useTagsStore = defineStore('tags', () => {
  // ===== state =====
  // 已打开的标签页列表
  const tags = ref<TagItem[]>([])
  // 需要缓存的组件 name 列表（供 keep-alive :include 使用）
  const cachedViews = ref<string[]>([])

  // ===== actions =====
  /**
   * 添加标签页（已存在则跳过）
   * @param route 当前路由
   * @param title 标题（默认取 meta.title）
   */
  function addTag(route: RouteLocationNormalized, title?: string) {
    const name = String(route.name || '')
    const path = route.path
    // 路由 name 或 path 缺失则不纳入标签
    if (!name || !path) return
    // 首页/登录等无 name 的路由不纳入标签
    if (['login', '403', '404'].includes(name)) return
    // 已存在则跳过
    const exist = tags.value.find((t) => t.path === path)
    if (exist) return
    const tagTitle = title || (route.meta?.title as string) || name
    const affix = !!(route.meta?.affix as boolean)
    tags.value.push({ title: tagTitle, path, name, affix })
    // 加入缓存列表（路由 meta.keepAlive 为 false 时不缓存）
    if (route.meta?.keepAlive !== false && !cachedViews.value.includes(name)) {
      cachedViews.value.push(name)
    }
  }

  /**
   * 关闭指定标签页，返回下一个应激活的标签路径
   * @param path 要关闭的标签路径
   * @returns 下一个应激活的标签路径，无则返回 null
   */
  function removeTag(path: string): string | null {
    const idx = tags.value.findIndex((t) => t.path === path)
    if (idx === -1) return null
    const removed = tags.value[idx]
    // 固定标签不允许关闭
    if (removed.affix) return null
    tags.value.splice(idx, 1)
    // 同步移除缓存
    cachedViews.value = cachedViews.value.filter((n) => n !== removed.name)
    // 返回相邻标签用于激活
    if (tags.value.length === 0) return null
    const next = tags.value[idx] || tags.value[idx - 1]
    return next ? next.path : null
  }

  /** 关闭其它标签，仅保留当前与固定标签 */
  function closeOthers(path: string) {
    tags.value = tags.value.filter((t) => t.path === path || t.affix)
    cachedViews.value = tags.value.map((t) => t.name)
  }

  /** 关闭所有非固定标签，返回应激活的标签路径 */
  function closeAll(): string | null {
    tags.value = tags.value.filter((t) => t.affix)
    cachedViews.value = tags.value.map((t) => t.name)
    return tags.value.length ? tags.value[tags.value.length - 1].path : null
  }

  /** 清空（退出登录） */
  function clear() {
    tags.value = []
    cachedViews.value = []
  }

  // 暴露 state、actions（保持对外 API 不变）
  return {
    // state
    tags,
    cachedViews,
    // actions
    addTag,
    removeTag,
    closeOthers,
    closeAll,
    clear
  }
})
