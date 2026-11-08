<!--
  Dashboard 首页（静态版）
  ----------------
  - 欢迎横幅：渐变背景 + 问候语 + 日期
  - 4 张统计卡（渐变背景色卡 + 白色文字）
  - 系统信息 + 服务状态卡片
  - defineOptions({ name: 'Dashboard' })
-->
<template>
  <div class="dashboard">
    <!-- ===== 欢迎横幅 ===== -->
    <div class="welcome-banner">
      <div class="welcome-bg">
        <div class="welcome-orb welcome-orb-1" />
        <div class="welcome-orb welcome-orb-2" />
        <div class="welcome-orb welcome-orb-3" />
      </div>
      <div class="welcome-content">
        <div class="welcome-text">
          <h1 class="welcome-title">{{ greeting }}，{{ userStore.nickname }}</h1>
          <p class="welcome-sub">{{ todayDate }} · {{ siteStore.config.subtitle || '通用 Web 管理平台' }}</p>
        </div>
        <div class="welcome-stats">
          <div class="welcome-stat-item">
            <span class="welcome-stat-num">{{ stats.userCount }}</span>
            <span class="welcome-stat-label">用户</span>
          </div>
          <div class="welcome-stat-divider" />
          <div class="welcome-stat-item">
            <span class="welcome-stat-num">{{ stats.roleCount }}</span>
            <span class="welcome-stat-label">角色</span>
          </div>
          <div class="welcome-stat-divider" />
          <div class="welcome-stat-item">
            <span class="welcome-stat-num">{{ stats.menuCount }}</span>
            <span class="welcome-stat-label">菜单</span>
          </div>
          <div class="welcome-stat-divider" />
          <div class="welcome-stat-item">
            <span class="welcome-stat-num">{{ stats.onlineCount }}</span>
            <span class="welcome-stat-label">在线</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 4 张统计卡 ===== -->
    <el-row :gutter="16" class="stat-row">
      <el-col v-for="card in statCards" :key="card.key" :xs="12" :sm="12" :md="6" :lg="6">
        <div class="stat-card" :style="{ '--card-gradient': card.gradient }">
          <div class="stat-card-body">
            <div class="stat-card-top">
              <span class="stat-card-label">{{ card.label }}</span>
              <div class="stat-card-icon">
                <Icon :icon="card.icon" />
              </div>
            </div>
            <div class="stat-card-value">
              {{ statsValue(card.key) }}
            </div>
            <div class="stat-card-footer">
              <span class="stat-card-trend" :class="card.trendUp ? 'trend-up' : 'trend-down'">
                <Icon :icon="card.trendUp ? 'ph:trend-up' : 'ph:trend-down'" />
                {{ card.trend }}
              </span>
              <span class="stat-card-period">较上周</span>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ===== 系统信息 ===== -->
    <el-row :gutter="16" class="info-row">
      <el-col :xs="24" :sm="24" :md="12" :lg="12">
        <el-card shadow="hover" class="info-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">
                <Icon icon="ph:info" class="card-title-icon" />
                系统信息
              </span>
            </div>
          </template>
          <div class="info-grid">
            <div v-for="info in systemInfo" :key="info.label" class="info-item">
              <span class="info-label">{{ info.label }}</span>
              <span class="info-value">{{ info.value }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="12" :lg="12">
        <el-card shadow="hover" class="info-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">
                <Icon icon="ph:database" class="card-title-icon" />
                服务状态
              </span>
            </div>
          </template>
          <div class="service-list">
            <div v-for="svc in services" :key="svc.name" class="service-item">
              <div class="service-left">
                <span class="service-dot" :class="svc.ok ? 'dot-ok' : 'dot-err'" />
                <span class="service-name">{{ svc.name }}</span>
              </div>
              <span class="service-status" :class="svc.ok ? 'status-ok' : 'status-err'">
                {{ svc.ok ? '正常' : '异常' }}
              </span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, computed } from 'vue'
import { Icon } from '@iconify/vue'
import { getDashboardStats, type DashboardStats } from '@/api/system/dashboard'
import { useUserStore } from '@/store/user'
import { useSiteStore } from '@/store/site'

// keep-alive 需要 name 标识
defineOptions({ name: 'Dashboard' })

const userStore = useUserStore()
const siteStore = useSiteStore()

// ===== 欢迎横幅：根据当前时间生成问候语 =====
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

/** 格式化今日日期 */
const todayDate = computed(() => {
  const d = new Date()
  const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 ${weekdays[d.getDay()]}`
})

/**
 * 统计卡配置
 * 渐变背景色卡 + 趋势指标
 */
const statCards = [
  {
    key: 'userCount' as const,
    label: '用户总数',
    icon: 'ph:users',
    gradient: 'linear-gradient(135deg, #3b82f6, #6366f1)',
    trend: '+12%',
    trendUp: true
  },
  {
    key: 'roleCount' as const,
    label: '角色总数',
    icon: 'ph:shield-check',
    gradient: 'linear-gradient(135deg, #10b981, #14b8a6)',
    trend: '+3%',
    trendUp: true
  },
  {
    key: 'menuCount' as const,
    label: '菜单总数',
    icon: 'ph:list-dashes',
    gradient: 'linear-gradient(135deg, #f59e0b, #f97316)',
    trend: '-2%',
    trendUp: false
  },
  {
    key: 'onlineCount' as const,
    label: '在线人数',
    icon: 'ph:wifi-high',
    gradient: 'linear-gradient(135deg, #ec4899, #f43f5e)',
    trend: '+8%',
    trendUp: true
  }
]

/** 后端返回的统计数据 */
const stats = reactive<DashboardStats>({
  userCount: 0,
  roleCount: 0,
  menuCount: 0,
  onlineCount: 0
})

/**
 * 安全读取统计字段
 * @param key DashboardStats 字段名
 */
function statsValue(key: keyof DashboardStats): number {
  return stats[key] ?? 0
}

// ===== 系统信息 =====
const systemInfo = [
  { label: '系统版本', value: 'v2.0.0' },
  { label: '前端框架', value: 'Vue 3 + Vite' },
  { label: 'UI 组件', value: 'Element Plus' },
  { label: '后端框架', value: 'Spring Boot 3' },
  { label: '数据库', value: 'MySQL 8.0' },
  { label: '缓存', value: 'Redis 7' }
]

// ===== 服务状态 =====
const services = [
  { name: '应用服务', ok: true },
  { name: '数据库服务', ok: true },
  { name: '缓存服务', ok: true },
  { name: '文件存储', ok: true },
  { name: '消息队列', ok: false }
]

/**
 * 加载后端统计数据
 * 失败时静默保留 0，不影响首页渲染
 */
async function loadStats(): Promise<void> {
  try {
    const res = await getDashboardStats()
    const data = res.data
    if (data) Object.assign(stats, data)
  } catch (err) {
    // 仅日志输出，避免登录后首页弹错误吐司
    console.warn('[dashboard] 加载统计数据失败', err)
  }
}

onMounted(() => {
  loadStats()
})
</script>

<style scoped>
/* ===== 页面根容器 ===== */
.dashboard {
  padding: 4px;
}

/* ===== 欢迎横幅 ===== */
.welcome-banner {
  position: relative;
  border-radius: 12px;
  overflow: hidden;
  margin-bottom: 20px;
  background: var(--na-primary-gradient);
  padding: 32px 36px;
  min-height: 130px;
  display: flex;
  align-items: center;
}

/* 横幅背景层：动态光斑 */
.welcome-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.welcome-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(50px);
  opacity: 0.4;
}

.welcome-orb-1 {
  width: 200px;
  height: 200px;
  background: rgba(255, 255, 255, 0.3);
  top: -60px;
  right: 10%;
  animation: orb-float-1 12s ease-in-out infinite;
}

.welcome-orb-2 {
  width: 150px;
  height: 150px;
  background: rgba(129, 140, 248, 0.5);
  bottom: -40px;
  left: 20%;
  animation: orb-float-2 15s ease-in-out infinite;
}

.welcome-orb-3 {
  width: 100px;
  height: 100px;
  background: rgba(255, 255, 255, 0.25);
  top: 20px;
  left: 5%;
  animation: orb-float-3 10s ease-in-out infinite;
}

@keyframes orb-float-1 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(-30px, 20px);
  }
}

@keyframes orb-float-2 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(40px, -30px);
  }
}

@keyframes orb-float-3 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(20px, 30px);
  }
}

.welcome-content {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  flex-wrap: wrap;
  gap: 16px;
}

.welcome-title {
  color: #fff;
  font-size: 26px;
  font-weight: 700;
  margin: 0 0 6px;
  letter-spacing: 0.5px;
}

.welcome-sub {
  color: rgba(255, 255, 255, 0.85);
  font-size: 14px;
  margin: 0;
}

/* 横幅右侧统计 */
.welcome-stats {
  display: flex;
  align-items: center;
  gap: 20px;
  background: rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 12px;
  padding: 14px 24px;
}

.welcome-stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.welcome-stat-num {
  font-size: 22px;
  font-weight: 700;
  color: #fff;
}

.welcome-stat-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.8);
}

.welcome-stat-divider {
  width: 1px;
  height: 32px;
  background: rgba(255, 255, 255, 0.25);
}

/* ===== 统计卡 ===== */
.stat-row {
  margin-bottom: 20px;
}

.stat-card {
  background: var(--na-bg-container);
  border-radius: 12px;
  border: 1px solid var(--na-border-lighter);
  box-shadow: var(--na-shadow-card);
  transition: all 0.25s ease;
  overflow: hidden;
  position: relative;
}

/* 卡片顶部渐变细条：让卡片的"颜色感"显现 */
.stat-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 4px;
  background: var(--card-gradient);
  border-radius: 12px 12px 0 0;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--na-shadow-hover);
}

.stat-card-body {
  padding: 20px;
}

.stat-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.stat-card-label {
  font-size: 14px;
  color: var(--na-text-secondary);
  font-weight: 500;
}

.stat-card-icon {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  font-size: 22px;
  /* 图标块使用更明显的浅紫色背景，与白色卡片形成清晰对比 */
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

/* 让 iconify 图标显式继承 color（避免某些 icon 默认 svg fill 为 currentColor 后被父级覆盖） */
.stat-card-icon :deep(svg) {
  width: 1em;
  height: 1em;
  fill: currentColor;
}

.stat-card-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--na-text-primary);
  line-height: 1.2;
  margin-bottom: 8px;
}

.stat-card-footer {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stat-card-trend {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 13px;
  font-weight: 600;
}

.trend-up {
  color: var(--na-success);
}

.trend-down {
  color: var(--na-danger);
}

.stat-card-period {
  font-size: 12px;
  color: var(--na-text-placeholder);
}

/* ===== 卡片通用头部 ===== */
.info-card {
  border-radius: 12px;
  border: 1px solid var(--na-border-lighter);
  box-shadow: var(--na-shadow-card);
  height: 100%;
}

.info-card :deep(.el-card__header) {
  padding: 16px 20px;
  border-bottom-color: var(--na-border-lighter);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--na-text-primary);
}

.card-title-icon {
  font-size: 18px;
  color: var(--na-primary);
}

/* ===== 系统信息 ===== */
.info-row {
  margin-bottom: 20px;
}

/* 信息网格 */
.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 0;
  border-bottom: 1px solid var(--na-border-extra-light);
}

.info-item:nth-child(odd) {
  padding-right: 16px;
}

.info-item:nth-child(even) {
  padding-left: 16px;
  border-left: 1px solid var(--na-border-extra-light);
}

.info-item:nth-last-child(-n + 2) {
  border-bottom: none;
  padding-bottom: 0;
}

.info-item:nth-child(1),
.info-item:nth-child(2) {
  padding-top: 0;
}

.info-label {
  font-size: 12px;
  color: var(--na-text-placeholder);
}

.info-value {
  font-size: 14px;
  color: var(--na-text-primary);
  font-weight: 500;
}

/* 服务列表 */
.service-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.service-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid var(--na-border-extra-light);
}

.service-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.service-item:first-child {
  padding-top: 0;
}

.service-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.service-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot-ok {
  background: var(--na-success);
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.4);
}

.dot-err {
  background: var(--na-danger);
  box-shadow: 0 0 6px rgba(244, 63, 94, 0.4);
}

.service-name {
  font-size: 14px;
  color: var(--na-text-primary);
}

.service-status {
  font-size: 13px;
  font-weight: 500;
}

.status-ok {
  color: var(--na-success);
}

.status-err {
  color: var(--na-danger);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .welcome-banner {
    padding: 24px 20px;
  }

  .welcome-title {
    font-size: 20px;
  }

  .welcome-stats {
    padding: 10px 16px;
    gap: 14px;
  }

  .welcome-stat-num {
    font-size: 18px;
  }
}
</style>
