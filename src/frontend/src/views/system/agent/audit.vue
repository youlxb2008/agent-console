<!--
  审计查询（M1.3）
  ----------------
  - 分页 + 筛选：principal / action / result / 时间范围
  - action 用 tag 颜色区分（key.create / key.verify / note.write 等）
  - result 用 tag 颜色区分（success / denied / error）
  - 权限码：agent:audit:list
-->
<template>
  <div class="na-page">
    <PageHeader
      title="审计日志"
      subtitle="查询 agent 域全部操作记录（append-only，不可删除）"
    />

    <div class="na-card">
      <!-- 搜索栏 -->
      <el-form
        inline
        class="audit-search"
        @submit.prevent
      >
        <el-form-item label="主体">
          <el-input
            v-model="query.principal"
            placeholder="agent 名 / 用户名"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="操作">
          <el-input
            v-model="query.action"
            placeholder="如 创建密钥 / 写入笔记"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="结果">
          <el-select
            v-model="query.result"
            placeholder="全部"
            clearable
            style="width: 130px"
          >
            <el-option label="成功" value="success" />
            <el-option label="拒绝" value="denied" />
            <el-option label="错误" value="error" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 360px"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            @click="handleSearch"
          >
            查询
          </el-button>
          <el-button @click="handleReset">
            重置
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 审计表格 -->
      <el-table
        v-loading="loading"
        :data="tableData"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column
          prop="principal"
          label="主体"
          min-width="120"
          align="center"
        />
        <el-table-column
          label="操作"
          min-width="150"
          align="center"
        >
          <template #default="{ row }">
            <el-tag :type="formatAction(row.action).type" size="small">
              {{ formatAction(row.action).text }}
              <span style="opacity: 0.6; font-size: 11px; margin-left: 4px;">{{ row.action }}</span>
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="resource"
          label="对象"
          min-width="180"
          align="center"
        >
          <template #default="{ row }">
            <span v-if="row.resource">{{ formatResource(row.resource) }}</span>
            <span
              v-else
              style="color: var(--el-text-color-secondary)"
            >-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="结果"
          width="100"
          align="center"
        >
          <template #default="{ row }">
            <el-tag :type="formatResult(row.result).type" size="small">
              {{ formatResult(row.result).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="clientIp"
          label="IP"
          min-width="120"
          align="center"
        >
          <template #default="{ row }">
            <span v-if="row.clientIp">{{ formatIp(row.clientIp) }}</span>
            <span
              v-else
              style="color: var(--el-text-color-secondary)"
            >-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="时间"
          min-width="160"
          align="center"
        >
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="audit-pagination">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="loadData"
          @size-change="handleSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import { getAuditList, type AuditLogItem } from '@/api/system/agent'

const loading = ref(false)
const tableData = ref<AuditLogItem[]>([])
const total = ref(0)

/** 查询条件（时间范围单独存放，提交时拆分 startTime/endTime） */
const query = reactive<{
  page: number
  size: number
  principal: string
  action: string
  result: string
}>({
  page: 1,
  size: 10,
  principal: '',
  action: '',
  result: ''
})

/** 时间范围（ISO-8601 字符串数组） */
const timeRange = ref<[string, string] | null>(null)

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const params: Record<string, any> = {
      page: query.page,
      size: query.size,
      principal: query.principal || undefined,
      action: query.action || undefined,
      result: query.result || undefined
    }
    if (timeRange.value && timeRange.value.length === 2) {
      params.startTime = timeRange.value[0]
      params.endTime = timeRange.value[1]
    }
    const res = await getAuditList(params)
    const page = res.data
    tableData.value = (page?.records || page?.list || []) as AuditLogItem[]
    // 后端分页 VO 字段为 count（MyBatis-Plus IPage），total 为 管理端 基线兼容兜底
    total.value = page?.count || page?.total || 0
  } catch {
    tableData.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.page = 1
  loadData()
}

function handleReset(): void {
  query.principal = ''
  query.action = ''
  query.result = ''
  timeRange.value = null
  query.page = 1
  loadData()
}

function handleSizeChange(): void {
  query.page = 1
  loadData()
}

/** 格式化时间 */
function formatTime(timeStr?: string | null): string {
  if (!timeStr) return ''
  try {
    return new Date(timeStr).toLocaleString('zh-CN')
  } catch {
    return timeStr
  }
}

/** action → 中文标签 + tag 颜色 */
function formatAction(action: string): { text: string; type: 'success' | 'danger' | 'warning' | 'primary' | 'info' } {
  const map: Record<string, string> = {
    'key.create': '创建密钥',
    'key.delete': '删除密钥',
    'key.revoke': '吊销密钥',
    'key.verify': '验证密钥',
    'note.write': '写入笔记',
    'note.delete': '删除笔记',
    'note.submit': '提交笔记',
    'agent.create': '创建 Agent',
    'agent.update': '更新 Agent',
    'agent.delete': '删除 Agent',
    'agent.suspend': '暂停 Agent',
    'agent.resume': '恢复 Agent'
  }
  const text = map[action] || action
  // 颜色分类
  if (action === 'key.create' || action === 'note.write' || action === 'agent.create' || action === 'agent.resume') return { text, type: 'success' }
  if (action === 'key.revoke' || action === 'key.delete' || action === 'note.delete' || action === 'agent.delete' || action === 'agent.suspend') return { text, type: 'danger' }
  if (action === 'key.verify' || action === 'note.submit') return { text, type: 'warning' }
  if (action.startsWith('agent.')) return { text, type: 'info' }
  return { text, type: 'primary' }
}

/** result → 中文标签 + tag 颜色 */
function formatResult(result: string): { text: string; type: 'success' | 'danger' | 'warning' | 'info' } {
  const map: Record<string, string> = {
    success: '成功',
    denied: '拒绝',
    error: '错误'
  }
  const text = map[result] || result
  if (result === 'success') return { text, type: 'success' }
  if (result === 'denied') return { text, type: 'danger' }
  if (result === 'error') return { text, type: 'warning' }
  return { text, type: 'info' }
}

/** 简化对象显示（API 路径 → 资源类型） */
function formatResource(resource: string | null | undefined): string {
  if (!resource) return '-'
  // API 路径简化
  if (resource.startsWith('/api/agent/admin/')) {
    const path = resource.replace('/api/agent/admin/', '')
    if (path.startsWith('keys')) return 'API Key'
    if (path.startsWith('agents')) return 'Agent'
    if (path.startsWith('notes')) return '笔记'
    if (path.startsWith('audit')) return '审计'
    return path
  }
  // nid 前缀显示为笔记 ID
  if (resource.startsWith('n_')) return `笔记 ${resource}`
  return resource
}

/** 简化 IP 显示 */
function formatIp(ip: string | null | undefined): string {
  if (!ip) return '-'
  // IPv6 本地地址简化
  if (ip === '0:0:0:0:0:0:0:1' || ip === '::1') return '127.0.0.1'
  return ip
}

// 首次加载
loadData()
</script>

<style scoped>
.audit-search {
  margin-bottom: 12px;
}

.audit-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
