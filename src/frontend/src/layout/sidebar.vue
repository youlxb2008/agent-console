<!--
  侧边栏（升级版）
  - 深色主题：背景 #0f172a（slate-900），激活高亮靠背景+渐变指示条
  - hover：浅紫背景
  - active：渐变指示条 + 浅紫背景 + 白字
  - 子菜单图标展开/折叠旋转
  - 滚动条样式美化
-->
<template>
  <div class="sidebar" :class="{ collapsed: sidebar.collapse }">
    <!-- 菜单主体 -->
    <el-menu
      class="sidebar-el-menu"
      :default-active="activeMenu"
      :collapse="sidebar.collapse"
      :collapse-transition="false"
      :unique-opened="true"
      @select="handleMenuSelect"
    >
      <!-- 首页：固定菜单项，始终显示在最顶部 -->
      <el-menu-item index="/dashboard">
        <Icon icon="mdi:view-dashboard" class="menu-icon" />
        <template #title>
          <span>首页</span>
        </template>
      </el-menu-item>

      <SidebarItem v-for="item in menuStore.menus" :key="item.id" :node="item" />
    </el-menu>

    <!-- 底部装饰条，折叠态下不显示 -->
    <div v-if="!sidebar.collapse" class="sidebar-footer">
      <span class="sidebar-footer-text">v1.0.0</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { useSidebarStore } from '@/store/sidebar'
import { useMenuStore } from '@/store/menu'
import SidebarItem from '@/components/SidebarItem.vue'

const sidebar = useSidebarStore()
const menuStore = useMenuStore()
const route = useRoute()
const router = useRouter()

/** 当前激活的菜单（依据路由 path 高亮） */
const activeMenu = computed(() => (route.meta?.activeMenu as string) || route.path)

/**
 * 统一处理菜单选中事件
 * - 外链(http开头) → 新窗口打开
 * - 普通路径 → router.push
 * - 目录节点(index 是纯数字) → 不跳转
 */
function handleMenuSelect(index: string) {
  if (/^https?:\/\//.test(index)) {
    window.open(index, '_blank')
    return
  }
  if (/^\d+$/.test(index)) return
  router.push(index)
}
</script>

<style scoped>
/* ============== 侧边栏根容器 ============== */
.sidebar {
  display: flex;
  flex-direction: column;
  position: absolute;
  left: 0;
  top: var(--na-header-height);
  bottom: 0;
  width: var(--na-sidebar-width);
  background: var(--na-bg-sidebar);
  /* 右侧 1px 分隔线：与 header 边框同色，保持视觉一致性 */
  border-right: 1px solid var(--na-border-header);
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
  z-index: 50;

  &.collapsed {
    width: var(--na-sidebar-collapse-width);
  }

  /* 顶部细分隔线 */
  &::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 1px;
    background: linear-gradient(to right, transparent, var(--na-border-sidebar), transparent);
  }
}

/* ============== 菜单容器 ============== */
.sidebar-el-menu {
  /* 同步覆盖 Element Plus 菜单变量，避免子菜单在暗色/深蓝布局下回退为默认白底。 */
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--na-text-sidebar);
  --el-menu-hover-bg-color: var(--na-bg-sidebar-hover);
  --el-menu-active-color: var(--na-text-sidebar-active);

  flex: 1 1 auto;
  width: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  background: transparent !important;
  border-right: 0 !important;
  padding: 12px 0;

  &:not(.el-menu--collapse) {
    width: var(--na-sidebar-width);
    border-right: none;
  }

  /* ============== 菜单项公共样式 ============== */
  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    height: 44px;
    line-height: 44px;
    margin: 4px 12px;
    padding: 0 16px !important;
    border-radius: 8px;
    color: var(--na-text-sidebar);
    background: transparent !important;
    font-size: 14px;
    position: relative;
    transition: var(--na-transition);
  }

  :deep(.el-menu-item:hover),
  :deep(.el-sub-menu__title:hover) {
    background: var(--na-bg-sidebar-hover) !important;
    color: var(--na-text-sidebar-active) !important;
  }

  /* ============== 激活状态 ============== */
  :deep(.el-menu-item.is-active) {
    background: var(--na-bg-sidebar-active) !important;
    color: var(--na-text-sidebar-active) !important;
    font-weight: 500;

    &::before {
      content: '';
      position: absolute;
      left: -12px;
      top: 50%;
      transform: translateY(-50%);
      width: 3px;
      height: 18px;
      background: var(--na-primary-gradient);
      border-radius: 0 2px 2px 0;
      box-shadow: var(--na-shadow-tag-active);
    }
  }

  /* 子菜单展开后其 title 也高亮 */
  :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
    color: var(--na-text-sidebar-active) !important;
    background: var(--na-bg-sidebar-active) !important;
  }

  /* ============== 子菜单 ============== */
  /* 子菜单容器背景继承 sidebar，不加 background 覆盖 */
  :deep(.el-sub-menu .el-menu) {
    padding: 4px 0;
    margin: 0 12px;
    border-radius: 8px;
  }

  :deep(.el-sub-menu .el-menu-item) {
    margin: 2px 8px;
    padding-left: 16px !important;
    height: 38px;
    line-height: 38px;
    font-size: 13px;
    min-width: 0;
  }

  /* ============== 箭头旋转 ============== */
  :deep(.el-sub-menu__title .el-sub-menu__icon-arrow) {
    transition: transform 0.3s ease;
  }

  :deep(.el-sub-menu.is-active > .el-sub-menu__title .el-sub-menu__icon-arrow) {
    transform: rotate(90deg);
  }

  /* ============== 图标 ============== */
  :deep(.menu-icon) {
    flex: 0 0 18px;
    width: 18px;
    height: 18px;
    font-size: 18px;
    line-height: 18px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    margin-right: 10px !important;
    transition: transform 0.2s ease;
  }

  :deep(.el-menu-item:hover .menu-icon),
  :deep(.el-sub-menu__title:hover .menu-icon) {
    transform: scale(1.1);
  }

  /* ============== 折叠态 ============== */
  &.el-menu--collapse {
    :deep(.el-menu-item),
    :deep(.el-sub-menu__title) {
      display: flex !important;
      align-items: center !important;
      justify-content: center !important;
      margin: 6px 8px !important;
      padding: 0 !important;
      height: calc(var(--na-sidebar-collapse-width) - 12px) !important;
      line-height: 1 !important;
    }

    :deep(.menu-icon) {
      margin: 0 !important;
      width: calc(var(--na-sidebar-collapse-width) * 0.42) !important;
      height: calc(var(--na-sidebar-collapse-width) * 0.42) !important;
      font-size: calc(var(--na-sidebar-collapse-width) * 0.42) !important;
    }

    :deep(.el-menu-item.is-active::before) {
      left: -8px;
      height: 22px;
      width: 3px;
    }
  }

  /* ============== 滚动条 ============== */
  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: var(--na-border-sidebar);
    border-radius: 2px;

    &:hover {
      background: var(--na-bg-sidebar-hover);
    }
  }

  &::-webkit-scrollbar-track {
    background: transparent;
  }
}

/* 折叠态弹层里的菜单项：保持左对齐（弹层不在 sidebar-el-menu 内，用 :deep 穿透） */
:deep(.sidebar-menu-popper) {
  .el-menu-item,
  .el-sub-menu__title {
    display: flex !important;
    align-items: center !important;
    justify-content: flex-start !important;
    text-align: left !important;
  }
}

/* ============== 底部 ============== */
.sidebar-footer {
  flex-shrink: 0;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--na-text-sidebar);
  opacity: 0.4;
  border-top: 1px solid var(--na-border-sidebar);
  letter-spacing: 1px;
}

.sidebar-footer-text {
  background: linear-gradient(to right, var(--na-primary-hover), var(--na-primary-gradient));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  font-weight: 500;
}
</style>
