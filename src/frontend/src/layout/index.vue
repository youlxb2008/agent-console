<!--
  布局根组件
  - 三段式布局：header（顶部） + sidebar（左侧） + content-box（主区域）
  - 主区域内包含 tags（标签栏） + router-view（页面）
  - router-view 配合 keep-alive 缓存已打开的标签页
  - 不使用 el-container/el-aside，改用绝对定位
-->
<template>
  <div
    class="layout-wrapper"
    :class="{ 'layout-collapse': sidebarStore.collapse }"
  >
    <v-header />
    <v-sidebar />
    <div
      class="content-box"
      :class="{ 'content-collapse': sidebarStore.collapse }"
    >
      <v-tags />
      <div class="content">
        <router-view v-slot="{ Component }">
          <transition
            name="move"
            mode="out-in"
          >
            <keep-alive :include="tagsStore.cachedViews">
              <component :is="Component" />
            </keep-alive>
          </transition>
        </router-view>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useSidebarStore } from '@/store/sidebar'
import { useTagsStore } from '@/store/tags'
import vHeader from './header.vue'
import vSidebar from './sidebar.vue'
import vTags from './tags.vue'

const sidebarStore = useSidebarStore()
const tagsStore = useTagsStore()

/** 小屏断点：≤768px 自动折叠侧边栏 */
const MOBILE_BREAKPOINT = 768

/** 媒体查询监听器引用，用于卸载时移除 */
let mediaQuery: MediaQueryList | null = null

/** 媒体查询变化回调：小屏自动折叠，大屏恢复展开 */
function handleMediaChange(e: MediaQueryListEvent | MediaQueryList): void {
  if (e.matches) {
    // 小屏：自动折叠
    sidebarStore.setCollapse(true)
  }
  // 大屏：不自动展开（保留用户手动折叠状态）
}

onMounted(() => {
  // 初始化时如果是小屏，立即折叠
  mediaQuery = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT}px)`)
  handleMediaChange(mediaQuery)
  mediaQuery.addEventListener('change', handleMediaChange)
})

onUnmounted(() => {
  mediaQuery?.removeEventListener('change', handleMediaChange)
})
</script>

<style scoped>
.layout-wrapper {
  width: 100%;
  height: 100%;
  position: relative;
}
</style>
