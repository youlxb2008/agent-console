<!--
  顶部 Header（升级版）
  - 深色背景 slate-800 + 底阴影
  - Logo 区域：渐变图标方块 + 标题
  - 折叠按钮：图标 hover 有圆环高亮
  - 右侧：门户入口链接 + 消息铃铛 + 头像（hover 圆环）+ 用户名
-->
<template>
  <div class="header">
    <!-- Logo 区域 -->
    <div class="header-logo" :class="{ 'header-logo-collapse': sidebarStore.collapse }">
      <div class="header-logo-icon">
        <Icon :icon="siteStore.logo" class="header-logo-icon-svg" />
      </div>
      <span v-if="!sidebarStore.collapse" class="header-logo-text">{{ siteStore.title }}</span>
    </div>

    <!-- 折叠按钮 -->
    <div class="collapse-btn" @click="toggleCollapse">
      <el-icon v-if="sidebarStore.collapse" :size="18"><Expand /></el-icon>
      <el-icon v-else :size="18"><Fold /></el-icon>
    </div>

    <!-- 用户区域 -->
    <div class="header-right">
      <div class="header-user-con">
        <!-- 门户入口：新窗口打开门户首页（与笔记/笔记本页的跨应用跳转同一地址口径） -->
        <a class="portal-link" :href="portalHomeUrl" target="_blank" rel="noopener">
          <el-icon :size="14"><Position /></el-icon>
          <span>门户</span>
        </a>

        <NoticeBell />

        <!-- 头像：外层 wrapper 提供白色背景，避免透出 header 渐变产生灰边 -->
        <div class="user-avator-wrap">
          <el-avatar class="user-avator" :size="32" :src="avatarUrl">
            {{ avatarInitial }}
          </el-avatar>
        </div>

        <el-dropdown class="user-name" trigger="click" @command="handleCommand">
          <span class="el-dropdown-link">
            {{ userStore.nickname }}
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Icon } from '@iconify/vue'
import { Expand, Fold, ArrowDown, Position } from '@element-plus/icons-vue'
import { useSidebarStore } from '@/store/sidebar'
import { useUserStore } from '@/store/user'
import { usePermissStore } from '@/store/permiss'
import { useMenuStore } from '@/store/menu'
import { useTagsStore } from '@/store/tags'
import { useSiteStore } from '@/store/site'
import { useNoticeStore } from '@/store/notice'
import { resetRouter, setAuthenticated } from '@/router'
import NoticeBell from '@/components/NoticeBell.vue'

const sidebarStore = useSidebarStore()
const userStore = useUserStore()
const permissStore = usePermissStore()
const menuStore = useMenuStore()
const tagsStore = useTagsStore()
const siteStore = useSiteStore()
const noticeStore = useNoticeStore()
const router = useRouter()

noticeStore.refreshUnreadCount()

/**
 * 头像 URL：对相对路径拼接 API base URL，确保图片可正确加载
 * - /uploads/... 开头的路径需要拼接后端地址
 * - http/https 开头的完整 URL 直接使用
 * - 空值返回空字符串，el-avatar 会回退显示首字母
 */
const avatarUrl = computed(() => {
  const avatar = userStore.avatar
  if (!avatar) return ''
  if (avatar.startsWith('http')) return avatar
  // 相对路径（如 /uploads/avatar/xxx.jpg）拼接 API base URL
  const baseURL = import.meta.env.VITE_API_BASE_URL || ''
  return `${baseURL}${avatar}`
})

const avatarInitial = computed(() => {
  const name = userStore.nickname
  return name ? name.charAt(0).toUpperCase() : 'U'
})

/**
 * 门户首页地址：优先取 .env 的 VITE_PORTAL_BASE_URL（开发环境指向 12488）；
 * 生产同源部署时该变量留空 → 回退当前站点源（门户挂站点根 /）
 * （与笔记列表/笔记本列表页的跨应用跳转同一口径）
 */
const portalHomeUrl = computed(() => {
  const base = import.meta.env.VITE_PORTAL_BASE_URL || window.location.origin
  return `${base}/`
})

function toggleCollapse(): void {
  sidebarStore.toggle()
}

async function handleCommand(command: string): Promise<void> {
  if (command === 'logout') {
    userStore.logout().catch(() => {})
    permissStore.clear()
    menuStore.clear()
    tagsStore.clear()
    resetRouter()
    userStore.clear()
    setAuthenticated(false)
    ElMessage.success('已退出登录')
    router.push('/login')
  } else if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'password') {
    router.push('/profile/password')
  }
}
</script>

<style scoped>
.header {
  position: relative;
  box-sizing: border-box;
  width: 100%;
  height: var(--na-header-height);
  background-color: var(--na-bg-header);
  /* 文字色跟随布局风格：暗色=白字，明色=深字 */
  color: var(--na-text-header);
  font-size: 22px;
  z-index: 100;
  box-shadow: var(--na-shadow-header);
  display: flex;
  align-items: center;
}

/* ============== Logo 区 ============== */
.header-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  width: var(--na-sidebar-width);
  height: 100%;
  padding: 0 18px;
  /* Logo 区与 sidebar 同色系分割线 */
  border-right: 1px solid var(--na-border-header);
  overflow: hidden;
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  flex-shrink: 0;
}

.header-logo-collapse {
  width: var(--na-sidebar-collapse-width);
  justify-content: center;
  padding: 0;
}

/* 渐变图标方块 */
.header-logo-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--na-primary-gradient);
  border-radius: 8px;
  box-shadow: var(--na-shadow-tag-active);
  color: #fff;
  flex-shrink: 0;
}

.header-logo-icon-svg {
  font-size: 18px;
}

.header-logo-text {
  font-size: 16px;
  font-weight: 700;
  /* Logo 文字色跟随 header 文字色 */
  color: var(--na-text-header);
  white-space: nowrap;
  letter-spacing: 0.5px;
}

/* ============== 折叠按钮 ============== */
.collapse-btn {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 36px;
  width: 36px;
  margin-left: 12px;
  border-radius: 8px;
  cursor: pointer;
  /* 折叠按钮颜色跟随 header 文字色（半透明） */
  color: var(--na-text-header);
  opacity: 0.7;
  transition: var(--na-transition);
}

.collapse-btn:hover {
  /* hover 时用主色半透明背景 + 不透明文字 */
  background: var(--na-bg-sidebar-hover);
  opacity: 1;
}

/* ============== 右侧 ============== */
.header-right {
  margin-left: auto;
  padding-right: 24px;
}

.header-user-con {
  display: flex;
  height: var(--na-header-height);
  align-items: center;
  gap: 14px;
}

/* ============== 门户入口链接 ============== */
.portal-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  /* 文字色跟随 header 文字色（半透明弱化，hover 提亮） */
  color: var(--na-text-header);
  opacity: 0.75;
  text-decoration: none;
  transition: var(--na-transition-fast);
}

.portal-link:hover {
  background: var(--na-bg-sidebar-hover);
  opacity: 1;
}

/* ============== 头像 ============== */
.user-avator-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  line-height: 0;
}

.user-avator {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  background: var(--na-primary-gradient);
  color: #ffffff;
  font-weight: 600;
  font-size: 14px;
  text-align: center;
  vertical-align: middle !important;
  line-height: 32px !important;
}

/* 头像内部文字容器：去掉 Element Plus 默认的 vertical-align 偏移 */
.user-avator :deep(img),
.user-avator :deep(.el-avatar__inner) {
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  line-height: 1 !important;
  width: 100%;
  height: 100%;
}

.user-name {
  margin-left: 4px;
}

.el-dropdown-link {
  /* 下拉链接色跟随 header 文字色 */
  color: var(--na-text-header);
  cursor: pointer;
  display: flex;
  align-items: center;
  font-size: 14px;
  padding: 6px 10px;
  border-radius: 6px;
  transition: var(--na-transition-fast);
}

.el-dropdown-link:hover {
  background: var(--na-bg-sidebar-hover);
}
</style>
