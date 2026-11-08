<!--
  标签栏（升级版）
  - 圆角药丸状，hover 显示关闭 ×
  - 激活态：渐变背景 + 阴影 + 白字
  - 横向滚动（标签多时）
  - 与 keep-alive 配合实现页面状态缓存
-->
<template>
  <div v-if="tagsStore.tags.length" class="tags">
    <div class="tags-scroll">
      <ul class="tags-list">
        <li
          v-for="(item, index) in tagsStore.tags"
          :key="item.path"
          class="tags-li"
          :class="{ active: isActive(item.path) }"
          @click="router.push(item.path)"
          @contextmenu.prevent="openContextMenu($event, item.path)"
        >
          <span class="tags-li-title">
            <span v-if="isActive(item.path)" class="tags-dot" />
            {{ item.title }}
          </span>
          <el-icon v-if="!item.affix" class="tags-close-icon" @click.stop="closeTags(index)">
            <Close />
          </el-icon>
        </li>
      </ul>
    </div>

    <div class="tags-close-box">
      <el-dropdown @command="handleTags">
        <el-button size="small" class="tags-btn">
          <el-icon><Operation /></el-icon>
          <span class="tags-btn-text">标签</span>
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="other">关闭其他</el-dropdown-item>
            <el-dropdown-item command="all">关闭所有</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import type { RouteLocationNormalized } from 'vue-router'
import { Close, ArrowDown, Operation } from '@element-plus/icons-vue'
import { useTagsStore } from '@/store/tags'

const route = useRoute()
const router = useRouter()
const tagsStore = useTagsStore()

/** 判断指定标签是否为当前激活状态 */
function isActive(path: string): boolean {
  return path === route.path
}

/**
 * 关闭指定索引的标签
 * - 若关闭的是当前标签则自动跳到相邻标签
 * - 列表清空则跳回首页
 */
function closeTags(index: number): void {
  const delItem = tagsStore.tags[index]
  if (delItem.affix) return
  const nextPath = tagsStore.removeTag(delItem.path)
  if (delItem.path === route.path) {
    if (nextPath) {
      router.push(nextPath)
    } else {
      router.push('/')
    }
  }
}

/**
 * 把指定路由加入标签列表
 * - 若已存在则跳过
 * - 超过 8 个时移除最早的，避免标签过多
 */
function setTags(target: RouteLocationNormalized): void {
  const exists = tagsStore.tags.some((item) => item.path === target.path)
  if (exists) return
  if (tagsStore.tags.length >= 8) {
    const firstNonAffix = tagsStore.tags.find((t) => !t.affix)
    if (firstNonAffix) {
      tagsStore.removeTag(firstNonAffix.path)
    }
  }
  tagsStore.addTag(target)
}

setTags(route)
onBeforeRouteUpdate((to) => {
  setTags(to)
})

/** 关闭全部标签，跳转首页 */
function closeAll(): void {
  const nextPath = tagsStore.closeAll()
  router.push(nextPath || '/')
}

/** 关闭其他标签，仅保留当前激活项 */
function closeOther(): void {
  tagsStore.closeOthers(route.path)
}

/** 下拉菜单命令处理 */
function handleTags(command: string): void {
  if (command === 'other') closeOther()
  else closeAll()
}

/** 右键菜单（暂未挂接真实菜单，预留接口） */
function openContextMenu(e: MouseEvent, path: string): void {
  // 实际可通过 el-popover 实现右键菜单，此处为占位
  console.debug('[tags] contextmenu', path, e)
}
</script>

<style scoped>
/* ============== 标签栏容器 ============== */
.tags {
  position: relative;
  height: var(--na-tags-height);
  background: var(--na-bg-container);
  border-bottom: 1px solid var(--na-border-extra-light);
  display: flex;
  align-items: center;
  padding-right: 110px;
}

/* ============== 滚动容器 ============== */
.tags-scroll {
  flex: 1;
  height: 100%;
  overflow-x: auto;
  overflow-y: hidden;
}

.tags-scroll::-webkit-scrollbar {
  height: 0;
}

.tags-list {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 100%;
  padding: 0 12px;
  list-style: none;
  margin: 0;
}

/* ============== 单个标签（药丸） ============== */
.tags-li {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 8px 0 12px;
  border-radius: var(--na-radius-tag);
  font-size: 12px;
  /* 非激活态：用主题色的最浅背景标识归属主题，配浅边框 */
  background: var(--na-primary-light-9);
  color: var(--na-text-regular);
  border: 1px solid var(--na-primary-light-8);
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
  transition: var(--na-transition);
}

.tags-li:hover {
  background: var(--na-primary-light-8);
  color: var(--na-primary);
  border-color: var(--na-primary-light-8);
  transform: translateY(-1px);
}

.tags-li-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: inherit;
  text-decoration: none;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 激活标签：渐变背景 + 白字 + 阴影 */
.tags-li.active {
  background: var(--na-primary-gradient);
  color: #ffffff !important;
  border-color: transparent;
  box-shadow: var(--na-shadow-tag-active);
  transform: translateY(-1px);
}

.tags-li.active .tags-li-title {
  color: #ffffff !important;
}

.tags-li.active:hover {
  background: var(--na-primary-gradient-hover);
}

/* 激活态左边的小圆点 */
.tags-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.95);
  box-shadow: 0 0 6px rgba(255, 255, 255, 0.8);
}

/* ============== 关闭 × ============== */
.tags-close-icon {
  margin-left: 6px;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  cursor: pointer;
  font-size: 12px;
  opacity: 0.6;
  transition: var(--na-transition-fast);
}

.tags-close-icon:hover {
  opacity: 1;
  background: rgba(0, 0, 0, 0.15);
}

.tags-li.active .tags-close-icon:hover {
  background: rgba(255, 255, 255, 0.25);
}

/* ============== 右侧操作按钮区 ============== */
.tags-close-box {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  padding: 0 12px;
  background: var(--na-bg-container);
  box-shadow: -8px 0 12px -4px rgba(0, 0, 0, 0.04);
  z-index: 10;
}

.tags-btn {
  background: transparent !important;
  border: none !important;
  color: var(--na-text-regular) !important;
  padding: 4px 10px !important;
  height: 28px !important;
  font-size: 12px !important;
}

.tags-btn:hover {
  background: var(--na-bg-hover) !important;
  color: var(--na-primary) !important;
}

.tags-btn-text {
  margin: 0 4px;
}
</style>
