<!--
  递归菜单项组件
  - 接收一个菜单节点，递归渲染自身和子节点
  - 目录(type=1)有可见子节点 → el-sub-menu
  - 菜单(type=2)无子节点 → el-menu-item
  - 按钮(type=3)不渲染
  - 路由跳转由父级 ElMenu 的 @select 统一处理
-->
<template>
  <!-- 有可见子节点 → 子菜单目录 -->
  <el-sub-menu
    v-if="visible && hasVisibleChildren"
    :index="String(node.id)"
    popper-class="sidebar-menu-popper"
  >
    <template #title>
      <Icon
        v-if="node.icon"
        :icon="node.icon"
        class="menu-icon"
      />
      <span>{{ node.name }}</span>
    </template>
    <SidebarItem
      v-for="child in visibleChildren"
      :key="child.id"
      :node="child"
    />
  </el-sub-menu>

  <!-- 叶子菜单项（含外链，跳转由 @select 统一处理） -->
  <el-menu-item
    v-else-if="visible"
    :index="node.path || String(node.id)"
  >
    <Icon
      v-if="node.icon"
      :icon="node.icon"
      class="menu-icon"
    />
    <template #title>
      <span>{{ node.name }}</span>
    </template>
  </el-menu-item>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { MenuNode } from '@/store/menu'
import { usePermissStore } from '@/store/permiss'

const props = defineProps<{
  node: MenuNode
}>()

const permissStore = usePermissStore()

/**
 * 当前节点是否可见
 * - hidden 隐藏 → 不可见
 * - type=3（按钮）→ 不可见
 * - 目录(type=1)不做权限校验
 * - 菜单(type=2)若有 perms 则需通过权限校验
 */
const visible = computed(() => {
  const node = props.node
  if (node.hidden) return false
  if (node.type === 3) return false
  if (node.type !== 1 && node.perms && !permissStore.has(node.perms)) return false
  return true
})

/** 可见的子节点列表 */
const visibleChildren = computed(() => {
  return (props.node.children || []).filter((child) => {
    if (child.hidden) return false
    if (child.type === 3) return false
    if (child.type !== 1 && child.perms && !permissStore.has(child.perms)) return false
    return true
  })
})

/** 是否有可见子节点 */
const hasVisibleChildren = computed(() => visibleChildren.value.length > 0)
</script>
