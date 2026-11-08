<!--
  记忆管理（M7.1 M2，菜单 3190 /agent/memory，组件 views/system/agent/memory）
  --------------------------------------------------------
  - 统计卡片：各 agent 活跃/归档/被取代计数（GET /memory/admin/memories/stats）
  - 分页表格：标题 / 归属 Agent / 类型 / 来源 / 状态 / weight / 命中次数 / 最后访问 / 更新时间 / 操作
  - 筛选：Agent 下拉 / 状态 / 类型 / 关键词（标题 LIKE）
  - 操作：详情抽屉（contentMd 全文 + superseded 链）、归档（1→2）、恢复（2→1）、删除（软删）
  - 权限码：memory:list / archive / restore / delete（m71-menu.sql）
  - agentId 走路径（RESTful 资源 ID 走路径约束）；未选 Agent 时不发请求返回空页
-->
<template>
  <div class="na-page">
    <PageHeader
      title="记忆管理"
      subtitle="管理 agent 私域长期记忆（类型/来源/生命周期/衰减权重）"
    />

    <!-- 统计卡片：各 agent 活跃/归档/被取代 -->
    <div class="stats-row">
      <div
        v-for="stat in stats"
        :key="stat.agentId"
        class="stat-card"
        :class="{ 'is-active': searchAgentId === stat.agentId }"
        @click="filterByAgent(stat.agentId)"
      >
        <div class="stat-head">
          <span class="stat-agent">{{ stat.agentName || `Agent #${stat.agentId}` }}</span>
          <span class="stat-total">{{ stat.totalCount }} 条</span>
        </div>
        <div class="stat-nums">
          <span class="stat-num is-1">活跃 {{ stat.activeCount }}</span>
          <span class="stat-num is-2">归档 {{ stat.archivedCount }}</span>
          <span class="stat-num is-3">被取代 {{ stat.supersededCount }}</span>
        </div>
      </div>
      <div v-if="!stats.length" class="stats-empty">暂无记忆数据</div>
    </div>

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :api="{ list: listApi }"
        :default-size="10"
      >
        <!-- 标题：主文字（首列） -->
        <template #column-title="{ row }">
          <span class="title-cell">{{ (row as MemoryItem).title }}</span>
        </template>

        <!-- 归属 Agent -->
        <template #column-agentName="{ row }">
          <span class="agent-cell">{{ (row as MemoryItem).agentName || '—' }}</span>
        </template>

        <!-- 类型 / 来源：克制文字标签 -->
        <template #column-memType="{ row }">
          <span class="type-tag">{{ memTypeText((row as MemoryItem).memType) }}</span>
        </template>
        <template #column-source="{ row }">
          <span class="source-tag" :class="`is-${(row as MemoryItem).source}`">
            {{ sourceText((row as MemoryItem).source) }}
          </span>
        </template>

        <!-- 状态 -->
        <template #column-status="{ row }">
          <span class="status-tag" :class="`is-${(row as MemoryItem).status}`">
            {{ statusText((row as MemoryItem).status) }}
          </span>
        </template>

        <!-- weight / 命中次数 -->
        <template #column-weight="{ row }">
          <span class="weight-cell">{{ (row as MemoryItem).weight ?? 0 }}</span>
          <span v-if="(row as MemoryItem).weightFloor" class="floor-badge">
            保底 {{ (row as MemoryItem).weightFloor }}
          </span>
        </template>
        <template #column-accessCount="{ row }">
          <span class="muted-cell">{{ (row as MemoryItem).accessCount ?? 0 }}</span>
        </template>

        <!-- 最后访问 -->
        <template #column-lastAccessedAt="{ row }">
          <span class="muted-cell">{{ (row as MemoryItem).lastAccessedAt || '—' }}</span>
        </template>

        <!-- 操作：详情 / 归档 / 恢复 / 删除 -->
        <template #column-operations="{ row }">
          <el-button
            v-permiss="'memory:list'"
            link
            type="primary"
            :icon="View"
            @click="openDetail(row as MemoryItem)"
          >
            详情
          </el-button>
          <el-button
            v-permiss="'memory:archive'"
            link
            type="primary"
            @click="openFloor(row as MemoryItem)"
          >
            保底
          </el-button>
          <el-button
            v-if="(row as MemoryItem).status === 1"
            v-permiss="'memory:archive'"
            link
            type="warning"
            @click="handleArchive(row as MemoryItem)"
          >
            归档
          </el-button>
          <el-button
            v-if="(row as MemoryItem).status === 2"
            v-permiss="'memory:restore'"
            link
            type="success"
            @click="handleRestore(row as MemoryItem)"
          >
            恢复
          </el-button>
          <el-button
            v-permiss="'memory:delete'"
            link
            type="danger"
            :icon="Delete"
            @click="handleDelete(row as MemoryItem)"
          >
            删除
          </el-button>
        </template>
      </CrudTable>
    </div>

    <!-- 详情抽屉：contentMd 全文 + superseded 链 -->
    <el-drawer
      v-model="drawerVisible"
      title="记忆详情"
      size="640px"
    >
      <div v-loading="contentLoading" class="mem-detail">
        <template v-if="detail">
          <div class="detail-head">
            <h3 class="detail-title">{{ detail.memory.title }}</h3>
            <div class="detail-meta">
              <span class="status-tag" :class="`is-${detail.memory.status}`">
                {{ statusText(detail.memory.status) }}
              </span>
              <span class="type-tag">{{ memTypeText(detail.memory.memType) }}</span>
              <span class="source-tag" :class="`is-${detail.memory.source}`">
                {{ sourceText(detail.memory.source) }}
              </span>
              <span class="muted-cell">weight {{ detail.memory.weight ?? 0 }}</span>
              <span class="muted-cell">命中 {{ detail.memory.accessCount ?? 0 }} 次</span>
            </div>
            <div v-if="detail.memory.tags" class="detail-tags">
              <el-tag
                v-for="tag in detail.memory.tags.split(',')"
                :key="tag"
                size="small"
                type="info"
                effect="plain"
              >
                {{ tag }}
              </el-tag>
            </div>
            <div v-if="detail.memory.refUrl" class="detail-ref">
              参考：
              <a :href="detail.memory.refUrl" target="_blank" rel="noopener">{{ detail.memory.refUrl }}</a>
            </div>
          </div>

          <!-- superseded 链 -->
          <div v-if="detail.chain.length > 1" class="detail-chain">
            <div class="chain-title">取代链（旧 → 新）</div>
            <div class="chain-nodes">
              <template v-for="(node, idx) in detail.chain" :key="node.id">
                <span class="chain-node" :class="{ 'is-latest': idx === detail.chain.length - 1 }">
                  #{{ node.id }} {{ node.title }}
                  <span class="chain-status">{{ statusText(node.status) }}</span>
                </span>
                <span v-if="idx < detail.chain.length - 1" class="chain-arrow">→</span>
              </template>
            </div>
            <div v-if="detail.latest" class="chain-latest">
              最新版：#{{ detail.latest.id }}（weight {{ detail.latest.weight ?? 0 }}）
            </div>
          </div>

          <div class="detail-md" v-html="renderedHtml" />
        </template>
        <el-empty v-else-if="!contentLoading" description="记忆不存在或已删除" />
      </div>
    </el-drawer>

    <!-- 保底线编辑（M7.1-M3a，轻量） -->
    <el-dialog v-model="floorVisible" title="调整权重保底线" width="420px">
      <div class="floor-tip">衰减重算时 weight = max(公式值, 保底线)；0 = 不保底。</div>
      <el-input-number v-model="floorValue" :min="0" :max="100" :step="5" step-strictly />
      <template #footer>
        <el-button @click="floorVisible = false">取消</el-button>
        <el-button type="primary" :loading="floorSaving" @click="saveFloor">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { View, Delete } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable from '@/components/CrudTable.vue'
import {
  getMemoryList,
  getMemoryStats,
  getMemoryDetail,
  archiveMemory,
  restoreMemory,
  deleteMemory,
  updateWeightFloor,
  type MemoryItem,
  type MemoryDetail,
  type MemoryStats,
  type MemoryQuery
} from '@/api/system/memory'
import { loadAgentOptions, agentOptionLabel } from '@/api/system/note'
import type { AgentItem } from '@/api/system/agent'
import type { ApiResponse, PageResponse } from '@/types/api'

const crudRef = ref<any>(null)

/** 文案映射 */
function statusText(status: number): string {
  return ['未知', '活跃', '已归档', '已取代'][status] ?? '未知'
}
function memTypeText(memType: string): string {
  const map: Record<string, string> = {
    fact: '事实',
    decision: '决策',
    preference: '偏好',
    lesson: '教训',
    event: '事件'
  }
  return map[memType] ?? memType
}
function sourceText(source: string): string {
  const map: Record<string, string> = {
    dialogue: '对话',
    self: '自记',
    system: '系统'
  }
  return map[source] ?? source
}

/** 列定义 */
const columns = [
  { prop: 'title', label: '标题', minWidth: 200, slot: true },
  { prop: 'agentName', label: '归属 Agent', width: 130, slot: true },
  { prop: 'memType', label: '类型', width: 80, slot: true },
  { prop: 'source', label: '来源', width: 80, slot: true },
  { prop: 'status', label: '状态', width: 90, slot: true },
  { prop: 'weight', label: '权重', width: 80, slot: true },
  { prop: 'accessCount', label: '命中', width: 70, slot: true },
  { prop: 'lastAccessedAt', label: '最后访问', width: 160, slot: true },
  { prop: 'updateTime', label: '更新时间', minWidth: 160 },
  { prop: 'operations', label: '操作', width: 290, slot: true, fixed: 'right' as const }
]

/** 搜索项：Agent 下拉（动态 options）/ 状态 / 类型 / 关键词 */
const searchItems = reactive([
  { prop: 'agentId', label: 'Agent', type: 'select' as const, placeholder: '请选择 Agent', clearable: true, options: [] as { label: string; value: any }[] },
  {
    prop: 'status',
    label: '状态',
    type: 'select' as const,
    placeholder: '全部状态',
    clearable: true,
    options: [
      { label: '活跃', value: 1 },
      { label: '已归档', value: 2 },
      { label: '已取代', value: 3 }
    ]
  },
  {
    prop: 'memType',
    label: '类型',
    type: 'select' as const,
    placeholder: '全部类型',
    clearable: true,
    options: [
      { label: '事实', value: 'fact' },
      { label: '决策', value: 'decision' },
      { label: '偏好', value: 'preference' },
      { label: '教训', value: 'lesson' },
      { label: '事件', value: 'event' }
    ]
  },
  { prop: 'keyword', label: '关键词', type: 'input' as const, placeholder: '标题关键词' }
])

/** 列表接口包装：agentId 走路径；未选 Agent 时不发请求返回空页 */
async function listApi(query: Record<string, any>): Promise<ApiResponse<PageResponse<MemoryItem>>> {
  const agentId = query.agentId as number | undefined
  if (!agentId) {
    const empty: PageResponse<MemoryItem> = { list: [], total: 0, page: 1, size: 10 }
    return { code: '200', msg: '查询成功', success: true, data: empty }
  }
  return getMemoryList(query as unknown as MemoryQuery)
}

/* ===== 统计卡片 ===== */
const stats = ref<MemoryStats[]>([])
const searchAgentId = ref<number | undefined>(undefined)

async function loadStats(): Promise<void> {
  const res = await getMemoryStats()
  stats.value = res.data ?? []
}

/** 点统计卡片 = 按 agent 过滤（写入 CrudTable 搜索条件并刷新） */
function filterByAgent(agentId: number): void {
  searchAgentId.value = agentId
  if (crudRef.value?.setSearch) {
    crudRef.value.setSearch({ agentId })
  } else if (crudRef.value?.refresh) {
    crudRef.value.refresh()
  }
}

/* ===== 详情抽屉 ===== */
const drawerVisible = ref(false)
const contentLoading = ref(false)
const detail = ref<MemoryDetail | null>(null)

const renderedHtml = computed(() => {
  if (!detail.value?.contentMd) return ''
  return DOMPurify.sanitize(marked.parse(detail.value.contentMd) as string)
})

async function openDetail(row: MemoryItem): Promise<void> {
  drawerVisible.value = true
  contentLoading.value = true
  detail.value = null
  try {
    const res = await getMemoryDetail(row.id)
    detail.value = res.data ?? null
  } finally {
    contentLoading.value = false
  }
}

/* ===== 归档 / 恢复 / 删除 ===== */
async function handleArchive(row: MemoryItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认归档「${row.title}」？归档后可恢复。`, '归档记忆', {
      confirmButtonText: '确认归档',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await archiveMemory(row.id)
  ElMessage.success('已归档')
  crudRef.value?.refresh()
  loadStats()
}

async function handleRestore(row: MemoryItem): Promise<void> {
  await restoreMemory(row.id)
  ElMessage.success('已恢复')
  crudRef.value?.refresh()
  loadStats()
}

async function handleDelete(row: MemoryItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除后不可恢复（软删，索引同步移除）。确认删除「${row.title}」？`,
      '删除记忆',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }
  await deleteMemory(row.id)
  ElMessage.success('已删除')
  crudRef.value?.refresh()
  loadStats()
}

/* ===== 保底线编辑（M7.1-M3a） ===== */
const floorVisible = ref(false)
const floorSaving = ref(false)
const floorValue = ref(0)
const floorRow = ref<MemoryItem | null>(null)

function openFloor(row: MemoryItem): void {
  floorRow.value = row
  floorValue.value = row.weightFloor ?? 0
  floorVisible.value = true
}

async function saveFloor(): Promise<void> {
  if (!floorRow.value) return
  floorSaving.value = true
  try {
    await updateWeightFloor(floorRow.value.id, floorValue.value)
    ElMessage.success('已更新保底线')
    floorVisible.value = false
    crudRef.value?.refresh()
  } finally {
    floorSaving.value = false
  }
}

/* ===== 初始化 ===== */
onMounted(async () => {
  loadStats()
  const opts = await loadAgentOptions()
  const agentItem = searchItems.find((item) => item.prop === 'agentId')
  if (agentItem) {
    agentItem.options = opts.map((a: AgentItem) => ({ label: agentOptionLabel(a), value: a.id }))
  }
})
</script>

<style scoped>
.stats-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.stat-card {
  min-width: 200px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  cursor: pointer;
  transition: border-color 0.2s;
}

.stat-card:hover,
.stat-card.is-active {
  border-color: var(--el-color-primary);
}

.stat-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
}

.stat-agent {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.stat-total {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.stat-nums {
  display: flex;
  gap: 10px;
  font-size: 12px;
}

.stat-num.is-1 {
  color: var(--el-color-success);
}

.stat-num.is-2 {
  color: var(--el-color-warning);
}

.stat-num.is-3 {
  color: var(--el-color-info);
}

.stats-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  padding: 8px 0;
}

.title-cell {
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.agent-cell {
  color: var(--el-text-color-regular);
}

.type-tag,
.source-tag,
.status-tag {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
}

.status-tag.is-1 {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.status-tag.is-2 {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}

.status-tag.is-3 {
  background: var(--el-color-info-light-9);
  color: var(--el-color-info);
}

.source-tag.is-dialogue {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.floor-badge {
  margin-left: 4px;
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-radius: 4px;
}

.floor-tip {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.weight-cell {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.muted-cell {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

/* 详情抽屉 */
.mem-detail {
  padding: 0 4px;
}

.detail-head {
  margin-bottom: 16px;
}

.detail-title {
  margin: 0 0 8px;
  font-size: 17px;
  color: var(--el-text-color-primary);
}

.detail-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.detail-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.detail-ref {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.detail-chain {
  margin-bottom: 16px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.chain-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.chain-nodes {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 13px;
}

.chain-node {
  color: var(--el-text-color-regular);
}

.chain-node.is-latest {
  font-weight: 600;
  color: var(--el-color-primary);
}

.chain-status {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-left: 4px;
}

.chain-arrow {
  color: var(--el-text-color-placeholder);
}

.chain-latest {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.detail-md {
  line-height: 1.8;
  word-break: break-word;
}
</style>
