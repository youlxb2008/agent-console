<!--
  NoticeBell 消息通知入口组件
  - 放在 Header 右侧，显示未读数角标
  - 点击展开 Popover，加载我的通知列表
  - 支持标记已读、全部已读、查看详情、跳转通知管理页
  - 相对时间显示（刚刚、3分钟前、1小时前等）
-->
<template>
  <el-popover :width="420" trigger="click" v-model:visible="popoverVisible" @show="handleOpen">
    <template #reference>
      <div class="notice-bell">
        <el-badge :value="noticeStore.unreadCount" :hidden="noticeStore.unreadCount === 0" :max="99">
          <el-icon :size="20"><Bell /></el-icon>
        </el-badge>
      </div>
    </template>

    <!-- Popover 内容 -->
    <div class="notice-popover">
      <!-- 顶部标题栏 + 全部已读按钮 -->
      <div class="notice-header">
        <span class="notice-header-title">我的通知</span>
        <el-link v-if="noticeStore.unreadCount > 0" type="primary" :underline="false" @click="handleMarkAllRead"
          >全部已读</el-link
        >
      </div>

      <!-- 通知列表（可滚动） -->
      <el-scrollbar height="360px">
        <div v-loading="loading" class="notice-list">
          <div
            v-for="item in notices"
            :key="item.id"
            class="notice-item"
            :class="{ 'is-read': item.isRead }"
            @click="handleItemClick(item)"
          >
            <!-- 未读/已读圆点指示器 -->
            <div class="notice-dot"></div>
            <div class="notice-item-body">
              <div class="notice-item-title">{{ item.title }}</div>
              <div class="notice-item-time">{{ formatRelativeTime(item.createTime) }}</div>
            </div>
          </div>
          <!-- 空状态 -->
          <el-empty v-if="!loading && notices.length === 0" description="暂无通知" :image-size="60" />
        </div>
      </el-scrollbar>

      <!-- 底部查看更多链接 -->
      <div class="notice-footer">
        <el-link type="primary" :underline="false" @click="handleViewMore">查看更多</el-link>
      </div>
    </div>
  </el-popover>

  <!-- 通知详情 Dialog -->
  <el-dialog v-model="detailVisible" :title="detailData?.title || '通知详情'" width="600px">
    <div v-if="detailData" class="notice-detail">
      <!-- 详情元信息：类型标签 + 发布人 + 时间 -->
      <div class="notice-detail-meta">
        <el-tag :type="detailTagType" size="small">
          {{ detailTagLabel }}
        </el-tag>
        <span class="notice-detail-publisher">发布人：{{ detailData.publisher || '-' }}</span>
        <span class="notice-detail-time">{{ detailData.createTime }}</span>
      </div>
      <el-divider />
      <!-- 通知正文（支持 HTML） -->
      <div class="notice-detail-content" v-html="detailData.content" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { useNoticeStore } from '@/store/notice'
import { getMyNotices, markNoticeRead, markAllNoticeRead, type MyNotice } from '@/api/system/notice'
import type { PageResponse } from '@/types/api'

const noticeStore = useNoticeStore()
const router = useRouter()

/** Popover 显示/隐藏状态 */
const popoverVisible = ref(false)
/** 列表加载中 */
const loading = ref(false)
/** 通知列表数据 */
const notices = ref<MyNotice[]>([])

/** 详情 Dialog 显示状态 */
const detailVisible = ref(false)
/** 详情 Dialog 数据 */
const detailData = ref<MyNotice | null>(null)

/** 详情标签 type（限定为合法值，未知类型 fallback 到 info） */
const detailTagType = computed(() => (detailData.value && typeTagMap[detailData.value.type]?.type) || 'info')
/** 详情标签显示文本 */
const detailTagLabel = computed(() => (detailData.value && typeTagMap[detailData.value.type]?.label) || '未知')

/** 通知类型 → 标签样式映射（type 限定为 el-tag 合法值，避免 TS 报错） */
const typeTagMap: Record<number, { label: string; type: 'primary' | 'success' | 'warning' | 'danger' | 'info' }> = {
  1: { label: '系统通知', type: 'primary' },
  2: { label: '版本更新', type: 'success' },
  3: { label: '维护公告', type: 'warning' }
}

/** 轮询定时器 ID */
let pollTimer: ReturnType<typeof setInterval> | null = null
/** 轮询间隔（毫秒） */
const POLL_INTERVAL = 60_000 // 1 分钟

/** 组件挂载时获取未读数 + 启动轮询 */
onMounted(() => {
  noticeStore.refreshUnreadCount()
  pollTimer = setInterval(() => {
    noticeStore.refreshUnreadCount()
  }, POLL_INTERVAL)
})

/** 组件卸载时清除轮询 */
onUnmounted(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})

/** Popover 展开时加载通知列表 */
async function handleOpen() {
  loading.value = true
  try {
    const res = await getMyNotices({ curPage: 1, pageSize: 20 })
    // 后端返回 PageResponse<MyNotice>，包含 list/total/page/size
    const pageData: PageResponse<MyNotice> = res.data || { list: [], total: 0, page: 1, size: 20 }
    notices.value = pageData.list || []
  } catch {
    // 静默处理，不影响用户体验
  } finally {
    loading.value = false
  }
}

/**
 * 点击某条通知
 * - 未读时先标记已读，再打开详情
 * - 已读时直接打开详情
 */
async function handleItemClick(item: MyNotice) {
  if (!item.isRead) {
    try {
      await markNoticeRead(item.id)
      item.isRead = true
      noticeStore.decrementUnread()
    } catch {
      // 标记失败不影响查看
    }
  }
  detailData.value = item
  detailVisible.value = true
}

/** 全部已读 */
async function handleMarkAllRead() {
  try {
    await markAllNoticeRead()
    // 将列表中所有通知标记为已读
    notices.value.forEach((n) => {
      n.isRead = true
    })
    noticeStore.resetUnread()
    ElMessage.success('已全部标记为已读')
  } catch {
    ElMessage.error('操作失败')
  }
}

/** 查看更多：关闭 Popover 并跳转通知管理页 */
function handleViewMore() {
  popoverVisible.value = false
  router.push('/system/notice')
}

/**
 * 相对时间格式化
 * - 1分钟内 → 刚刚
 * - 60分钟内 → X 分钟前
 * - 24小时内 → X 小时前
 * - 7天内 → X 天前
 * - 超过7天 → 显示日期
 */
function formatRelativeTime(timeStr: string): string {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  // 超过7天显示日期
  return timeStr.substring(0, 10)
}
</script>

<style scoped>
/* 铃铛图标容器（颜色跟随 header 文字色，兼容 4 种布局风格） */
.notice-bell {
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  /* padding 为 hover 高亮区域留出呼吸空间，避免背景贴紧图标 */
  padding: 6px 10px;
  border-radius: 6px;
  /* 跟随 header 文字色：暗色=白字、明色=深字、深蓝=浅灰白、主题色=主色深色 */
  color: var(--na-text-header);
  transition: var(--na-transition-fast);
}

.notice-bell:hover {
  background: var(--na-bg-sidebar-hover);
}

/* 让 el-badge 的 sup 角标不影响图标垂直居中 */
.notice-bell :deep(.el-badge) {
  display: flex;
  align-items: center;
}

.notice-bell :deep(.el-badge__content) {
  /* 保留组件默认的右上角定位，仅缩小角标，避免数字覆盖铃铛图标。 */
  transform: translateY(-50%) translateX(100%) scale(0.85);
  transform-origin: center;
}

/* Popover 内容区（抵消 el-popover 默认内边距） */
.notice-popover {
  margin: -12px;
}

/* 顶部标题栏 */
.notice-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.notice-header-title {
  font-weight: 600;
  font-size: 15px;
}

/* 通知列表 */
.notice-list {
  padding: 8px 12px;
  min-height: 100px;
}

/* 单条通知项 */
.notice-item {
  display: flex;
  gap: 12px;
  padding: 12px 8px;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.2s;
}

.notice-item:hover {
  background: var(--el-fill-color-light);
}

/* 未读/已读圆点 */
.notice-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-danger);
  margin-top: 6px;
  flex-shrink: 0;
}

/* 已读状态：圆点变灰、标题变普通 */
.notice-item.is-read .notice-dot {
  background: var(--el-color-info-light-5);
}

.notice-item.is-read .notice-item-title {
  color: var(--el-text-color-secondary);
  font-weight: normal;
}

/* 通知项正文区域 */
.notice-item-body {
  flex: 1;
  min-width: 0;
}

.notice-item-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice-item-time {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

/* 底部查看更多 */
.notice-footer {
  text-align: center;
  padding: 12px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

/* 详情 Dialog 元信息 */
.notice-detail-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

/* 详情正文 */
.notice-detail-content {
  line-height: 1.7;
  word-break: break-word;
}
</style>
