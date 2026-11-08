<!--
  系统工具箱页面
  ----------------
  - 顶部横幅：登录凭证提示（Druid / H2 / Swagger）
  - 左栏：快捷入口（Swagger UI / H2 控制台 / Druid 监控）
  - 右栏：服务状态（健康检查 / 应用信息）
  - 仅超管可见（权限码 system:tools）
-->
<template>
  <div class="system-tools">
    <!-- ====== 凭证提示（紧凑横幅） ====== -->
    <el-card shadow="never" class="banner-card">
      <div class="banner">
        <el-icon :size="18" color="var(--na-text-secondary)">
          <InfoFilled />
        </el-icon>
        <span class="banner-label">登录凭证：</span>
        <span class="banner-item"><b>Druid</b> admin / admin</span>
        <el-divider direction="vertical" />
        <span class="banner-item"><b>H2</b> sa / <i>（无密码）</i></span>
        <el-divider direction="vertical" />
        <span class="banner-item"><b>Swagger</b> 无需登录</span>
      </div>
    </el-card>

    <!-- ====== 快捷入口 + 服务状态 双列 ====== -->
    <el-row :gutter="16" class="main-row">
      <!-- 左：快捷入口 -->
      <el-col :xs="24" :md="14">
        <div class="section-header">
          <el-icon><Link /></el-icon>
          <span>快捷入口</span>
          <span class="section-count">{{ linkEndpoints.length }} 项</span>
        </div>
        <el-row :gutter="12">
          <el-col v-for="ep in linkEndpoints" :key="ep.name" :xs="24" :sm="12">
            <el-card shadow="hover" class="endpoint-card" @click="openLink(ep.url)">
              <div class="endpoint-inner">
                <div class="endpoint-icon" :class="`endpoint-icon-${getIconClass(ep.name)}`">
                  <el-icon :size="22">
                    <component :is="getIcon(ep.name)" />
                  </el-icon>
                </div>
                <div class="endpoint-info">
                  <div class="endpoint-name">{{ ep.name }}</div>
                  <div class="endpoint-desc">{{ ep.description }}</div>
                </div>
                <el-icon class="endpoint-arrow" color="var(--na-text-placeholder)">
                  <Right />
                </el-icon>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-col>

      <!-- 右：服务状态 -->
      <el-col :xs="24" :md="10">
        <div class="section-header">
          <el-icon><Monitor /></el-icon>
          <span>服务状态</span>
          <el-button size="small" text class="refresh-all" @click="refreshAll">
            <el-icon><Refresh /></el-icon>
            <span>刷新全部</span>
          </el-button>
        </div>
        <el-space direction="vertical" :size="12" fill style="width: 100%">
          <el-card v-for="ep in statusEndpoints" :key="ep.name" shadow="hover" class="status-card">
            <template #header>
              <div class="status-header">
                <span class="status-title">{{ ep.name }}</span>
                <el-button size="small" text :loading="statusData[ep.name]?.loading" @click="refreshStatus(ep)">
                  <el-icon><Refresh /></el-icon>
                </el-button>
              </div>
            </template>

            <!-- 加载中 -->
            <el-skeleton v-if="statusData[ep.name]?.loading && !statusData[ep.name]?.data" :rows="3" animated />

            <!-- 请求失败 -->
            <div v-else-if="statusData[ep.name]?.error" class="status-error">
              <el-tag type="danger">失败</el-tag>
              <span class="error-msg">{{ statusData[ep.name].error }}</span>
            </div>

            <!-- 数据展示 -->
            <div v-else class="status-content">
              <!-- 健康检查 -->
              <template v-if="ep.name === '健康检查'">
                <div class="health-top">
                  <el-tag :type="healthTagType" size="large" effect="dark" round>
                    {{ healthStatus }}
                  </el-tag>
                  <span class="health-hint">总体健康状态</span>
                </div>
                <div v-if="healthComponents" class="components-grid">
                  <div v-for="(val, key) in healthComponents" :key="key" class="comp-item">
                    <div class="comp-name">{{ String(key) }}</div>
                    <el-tag :type="val.status === 'UP' ? 'success' : 'danger'" size="small" effect="light">
                      {{ val.status }}
                    </el-tag>
                  </div>
                </div>
              </template>

              <!-- 应用信息 -->
              <template v-else-if="ep.name === '应用信息'">
                <div class="info-row">
                  <span class="info-label">名称</span>
                  <span class="info-value">{{ appInfo.app?.name || '-' }}</span>
                </div>
                <div class="info-row">
                  <span class="info-label">版本</span>
                  <span class="info-value">{{ appInfo.app?.version || '-' }}</span>
                </div>
                <div class="info-row">
                  <span class="info-label">描述</span>
                  <span class="info-value">{{ appInfo.app?.description || '-' }}</span>
                </div>
              </template>
            </div>
          </el-card>
        </el-space>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Right, Refresh, Link, Monitor, DataBoard, Setting, InfoFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getEndpoints, type EndpointInfo } from '@/api/system/tools'

// keep-alive 需要 name 标识
defineOptions({ name: 'SystemTools' })

/** 状态数据结构 */
interface StatusData {
  loading: boolean
  error?: string
  data?: any
}

const endpoints = ref<EndpointInfo[]>([])
const statusData = ref<Record<string, StatusData>>({})

const linkEndpoints = computed(() => endpoints.value.filter((e) => e.category === 'link'))
const statusEndpoints = computed(() => endpoints.value.filter((e) => e.category === 'status'))

const healthStatus = computed(() => statusData.value['健康检查']?.data?.status || '-')

const healthTagType = computed(() => {
  const s = healthStatus.value
  if (s === 'UP') return 'success'
  if (s === 'DOWN') return 'danger'
  return 'warning'
})

const healthComponents = computed(() => statusData.value['健康检查']?.data?.components || null)

const appInfo = computed(() => statusData.value['应用信息']?.data || {})

/**
 * 后端返回的完整 URL 转为前端相对路径（带 /api 前缀走 Vite proxy）
 */
function toRelativePath(fullUrl: string): string {
  try {
    const url = new URL(fullUrl)
    return '/api' + url.pathname + url.search + url.hash
  } catch {
    return fullUrl
  }
}

async function fetchEndpoints() {
  try {
    const res = await getEndpoints()
    endpoints.value = res.data || []
    statusEndpoints.value.forEach((e) => {
      statusData.value[e.name] = { loading: false }
    })
  } catch {
    ElMessage.error('获取服务端点失败')
  }
}

async function refreshStatus(ep: EndpointInfo) {
  statusData.value[ep.name] = { loading: true }
  try {
    const relativeUrl = toRelativePath(ep.url)
    const res = await fetch(relativeUrl)
    const data = await res.json()
    statusData.value[ep.name] = { loading: false, data }
  } catch (e: any) {
    statusData.value[ep.name] = { loading: false, error: e.message || '请求失败' }
  }
}

function refreshAll() {
  statusEndpoints.value.forEach((ep) => refreshStatus(ep))
}

function openLink(url: string) {
  window.open(url, '_blank')
}

/** 按名称返回图标组件 */
function getIcon(name: string) {
  if (name.includes('Swagger')) return DataBoard
  if (name.includes('H2')) return Monitor
  if (name.includes('Druid')) return Setting
  return Link
}

/** 按名称返回图标背景色 class 名（用主题色浅色背景，避免硬编码撞色） */
function getIconClass(name: string): string {
  if (name.includes('Swagger')) return 'indigo'
  if (name.includes('H2')) return 'emerald'
  if (name.includes('Druid')) return 'amber'
  return 'slate'
}

onMounted(async () => {
  await fetchEndpoints()
  refreshAll()
})
</script>

<style scoped>
.system-tools {
  padding: 0;
}

/* ====== 顶部凭证横幅 ====== */
.banner-card {
  margin-bottom: 16px;
  /* 横幅轻微主色染色，提示"这是一条信息条" */
  background: linear-gradient(to right, var(--na-primary-light-9), transparent 70%);
}
.banner-card :deep(.el-card__body) {
  padding: 10px 14px;
}
.banner {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.banner-label {
  color: var(--na-text-secondary);
}
.banner-item b {
  /* 凭证名（Druid/H2/Swagger）跟随主色 */
  color: var(--na-primary);
  margin-right: 4px;
  font-weight: 600;
  padding: 1px 6px;
  background: var(--na-primary-light-9);
  border-radius: 4px;
}

/* ====== 区块标题 ====== */
.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--na-text-primary);
}
.section-header .el-icon {
  color: var(--na-primary);
}
.section-count {
  font-size: 12px;
  font-weight: normal;
  color: var(--na-text-secondary);
  margin-left: auto;
  background: var(--na-bg-hover);
  padding: 2px 8px;
  border-radius: 10px;
}
.refresh-all {
  margin-left: auto;
}

/* ====== 快捷入口卡片 ====== */
.endpoint-card {
  margin-bottom: 12px;
  cursor: pointer;
  transition: transform 0.15s ease;
}
.endpoint-card:hover {
  transform: translateY(-2px);
}
.endpoint-card :deep(.el-card__body) {
  padding: 14px;
}
.endpoint-inner {
  display: flex;
  align-items: center;
  gap: 12px;
}
.endpoint-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  /* 兜底色（匹配 slate 主题），实际由下方 class 覆盖 */
  background: #f1f5f9;
  color: #475569;
  transition: transform 0.2s ease;
}

/* 5 个图标色变体（用主题色浅底 + 主题色深字，每个跟具体工具的品牌色协调） */
.endpoint-icon-indigo {
  background: #e0e7ff;
  color: #4f46e5;
}
.endpoint-icon-emerald {
  background: #d1fae5;
  color: #059669;
}
.endpoint-icon-amber {
  background: #fef3c7;
  color: #d97706;
}
.endpoint-icon-rose {
  background: #fecdd3;
  color: #e11d48;
}
.endpoint-icon-slate {
  background: #e2e8f0;
  color: #334155;
}

.endpoint-card:hover .endpoint-icon {
  transform: scale(1.08);
}
.endpoint-info {
  flex: 1;
  min-width: 0;
}
.endpoint-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--na-text-primary);
}
.endpoint-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.endpoint-arrow {
  flex-shrink: 0;
}

/* ====== 状态卡 ====== */
.status-card {
  margin-bottom: 0;
}
.status-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.status-title {
  font-weight: 500;
}
.status-error {
  display: flex;
  align-items: center;
  gap: 8px;
}
.error-msg {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* ====== 健康检查 ====== */
.health-top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.health-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.components-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.comp-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
.comp-name {
  font-size: 12px;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ====== 应用信息 ====== */
.info-row {
  display: flex;
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
}
.info-row:last-child {
  border-bottom: none;
}
.info-label {
  width: 60px;
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
}
.info-value {
  flex: 1;
  color: var(--el-text-color-primary);
  word-break: break-all;
}

.main-row {
  margin-bottom: 12px;
}
</style>
