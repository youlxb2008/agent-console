<!--
  总线调试（v4.37 bus-sessionkey-fix，菜单 3200 /system/bus-debug，组件 views/system/bus/debug）
  --------------------------------------------------------
  - 目的：sessionKey 落点调试——dispatcher 已修「channel 前缀剥除」，本页可视化验证落点
  - 表单 4 项：目标 agent 下拉 / 会话（裸 uuid 或 websocket:uuid）/ title+内容 / 通道 radio
  - 通道 radio 旁显示该方向（from=1 管理员 agent alpha）当前 t_bus_route 值
  - 发送：临时切路由 → 总线发送（sessionKey 原样，落点靠 dispatcher 剥前缀）→ 后端 finally 还原
  - 结果面板：messageId / dbId / 预期落点预览（websocket:<剥前缀后的值>）/ 路由还原值
  - 最近 10 条总线消息（复用流转查询），发送后 0s/2s/5s 三次自动刷新看投递状态
  - 时间显示一律 MM-DD HH:mm:ss 带日期；零 emoji
-->
<template>
  <div class="na-page">
    <PageHeader
      title="总线调试"
      subtitle="sessionKey 落点调试工具：临时切换路由发送一条总线消息并自动还原（from 固定为管理员 agent alpha）"
    />

    <div class="debug-layout">
      <!-- 左：发送表单 -->
      <div class="na-card debug-form">
        <div class="form-title">发送调试消息</div>
        <el-form label-width="92px" label-position="left">
          <el-form-item label="目标 agent" required>
            <el-select v-model="form.toAgentId" placeholder="选择目标 agent" style="width: 100%" @change="refreshRoute">
              <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="agentOptionLabel(a)" />
            </el-select>
          </el-form-item>
          <el-form-item label="会话" required>
            <el-input
              v-model="form.sessionKey"
              placeholder="裸 uuid 或 websocket:uuid（channel 前缀自动剥离后落点）"
              clearable
            />
            <div class="field-hint">
              uuid 即 WebUI 聊天会话 id；bus-* 总线命名空间亦可（原样透传不剥前缀）
            </div>
          </el-form-item>
          <el-form-item label="标题">
            <el-input v-model="form.title" placeholder="可选，默认「总线调试」" clearable />
          </el-form-item>
          <el-form-item label="内容" required>
            <el-input v-model="form.content" type="textarea" :rows="4" placeholder="调试正文（payload 形态 {&quot;content&quot;: ...}）" />
          </el-form-item>
          <el-form-item label="通道" required>
            <div class="channel-row">
              <el-radio-group v-model="form.channel">
                <el-radio-button value="ws">ws（gateway 常连接）</el-radio-button>
                <el-radio-button value="api">api（HTTP 直调）</el-radio-button>
              </el-radio-group>
              <span class="route-now">
                该方向当前路由：
                <b :class="{ 'is-api': routeInfo?.channel === 'api' }">{{ routeInfo?.channel ?? '—' }}</b>
                <template v-if="routeInfo && !routeInfo.hasExplicitRoute">（未配置行，默认 ws）</template>
              </span>
            </div>
            <div class="field-hint">发送时临时切换为所选通道，发送完成后自动还原原路由（后端 finally 保证）</div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="sending" :disabled="!canSend" @click="submit">发送</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 右：结果面板 -->
      <div class="na-card debug-result">
        <div class="form-title">发送结果</div>
        <template v-if="result">
          <div class="result-grid">
            <div class="result-item">
              <div class="result-label">messageId</div>
              <div class="result-value mono">{{ result.messageId }}</div>
            </div>
            <div class="result-item">
              <div class="result-label">dbId</div>
              <div class="result-value">{{ result.dbId ?? '—' }}</div>
            </div>
            <div class="result-item">
              <div class="result-label">预期落点（chatId）</div>
              <div class="result-value mono">{{ result.expectedChatId ?? '—' }}</div>
            </div>
            <div class="result-item">
              <div class="result-label">预期 gateway 会话</div>
              <div class="result-value mono">{{ result.expectedSessionKey ?? '—' }}</div>
            </div>
            <div class="result-item">
              <div class="result-label">临时通道</div>
              <div class="result-value">{{ result.channelTemp ?? '—' }}</div>
            </div>
            <div class="result-item">
              <div class="result-label">路由已还原为</div>
              <div class="result-value">{{ result.routeRestoredTo ?? '—' }}</div>
            </div>
          </div>
          <div class="result-hint">
            投递状态见下方最近消息表（发送后自动刷新三次）；落点是否命中既有会话窗口由 prod gateway 侧验证
          </div>
        </template>
        <div v-else class="empty-hint">尚未发送</div>
      </div>
    </div>

    <!-- 最近 10 条总线消息 -->
    <div class="na-card recent-card">
      <div class="form-title">最近 10 条总线消息</div>
      <el-table :data="recent" size="small" :show-header="true">
        <el-table-column label="时间" width="130">
          <template #default="{ row }">
            <span class="muted-cell">{{ fmtTime((row as BusMessageItem).createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源 → 目标" min-width="150">
          <template #default="{ row }">
            {{ (row as BusMessageItem).fromAgentName || (row as BusMessageItem).fromAgentId }}
            →
            {{ (row as BusMessageItem).toAgentName || (row as BusMessageItem).toAgentId }}
          </template>
        </el-table-column>
        <el-table-column label="type" width="70">
          <template #default="{ row }">
            <span class="type-tag" :class="{ alert: (row as BusMessageItem).msgType === 'alert' }">
              {{ (row as BusMessageItem).msgType }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="topic" prop="topic" min-width="110" show-overflow-tooltip />
        <el-table-column label="标题" prop="title" min-width="140" show-overflow-tooltip />
        <el-table-column label="status" width="80">
          <template #default="{ row }">
            <span class="status-tag" :class="statusClass((row as BusMessageItem).status)">
              {{ statusText((row as BusMessageItem).status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="通道" width="60">
          <template #default="{ row }">{{ (row as BusMessageItem).channel || '—' }}</template>
        </el-table-column>
        <el-table-column label="重试" width="55">
          <template #default="{ row }">{{ (row as BusMessageItem).retryCount }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import {
  getBusMessages,
  getDebugRoute,
  sendDebugMessage,
  BUS_STATUS_TEXT,
  type BusMessageItem,
  type BusDebugSendResult,
  type BusDebugRoute
} from '@/api/system/bus'
import { loadAgentOptions, agentOptionLabel } from '@/api/system/note'
import type { AgentItem } from '@/api/system/agent'

const agents = ref<AgentItem[]>([])
const routeInfo = ref<BusDebugRoute | null>(null)

const form = reactive({
  toAgentId: undefined as number | undefined,
  sessionKey: '',
  title: '',
  content: '',
  channel: 'ws' as 'ws' | 'api'
})

const sending = ref(false)
const result = ref<BusDebugSendResult | null>(null)
const recent = ref<BusMessageItem[]>([])

const canSend = computed(
  () => !!form.toAgentId && !!form.sessionKey.trim() && !!form.content.trim() && !!form.channel
)

/** 选了目标 agent（或通道切换）后刷新该方向当前路由（from 固定 1 管理员 agent alpha） */
async function refreshRoute() {
  routeInfo.value = null
  if (!form.toAgentId) return
  try {
    const res = await getDebugRoute(form.toAgentId)
    routeInfo.value = res.data ?? null
  } catch {
    routeInfo.value = null
  }
}

async function submit() {
  if (!canSend.value || sending.value) return
  sending.value = true
  try {
    const res = await sendDebugMessage({
      toAgentId: form.toAgentId as number,
      sessionKey: form.sessionKey.trim(),
      title: form.title.trim() || undefined,
      content: form.content,
      channel: form.channel
    })
    result.value = res.data ?? null
    ElMessage.success('已入队（调试路由已还原）')
    refreshRecentWithRetry()
  } finally {
    sending.value = false
  }
}

/** 发送后 0s/2s/5s 各刷一次最近消息表，便于看到 status 流转 */
function refreshRecentWithRetry() {
  const delays = [0, 2000, 5000]
  delays.forEach((d) => setTimeout(loadRecent, d))
}

async function loadRecent() {
  try {
    const res = await getBusMessages({ page: 1, size: 10 })
    recent.value = (res.data?.records || res.data?.list || []) as BusMessageItem[]
  } catch {
    recent.value = []
  }
}

function statusText(status: number): string {
  return BUS_STATUS_TEXT[status] ?? '未知'
}

function statusClass(status: number): string {
  if (status === 1) return 'ok'
  if (status === 3) return 'fail'
  if (status === 0) return 'pending'
  return ''
}

/** 时间显示一律 MM-DD HH:mm:ss（后端 yyyy-MM-dd HH:mm:ss，截去年份段） */
function fmtTime(value?: string | null): string {
  if (!value) return '—'
  return value.length >= 16 ? value.slice(5) : value
}

onMounted(async () => {
  agents.value = await loadAgentOptions()
  await loadRecent()
})
</script>

<style scoped>
.debug-layout {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  align-items: flex-start;
}

.debug-form {
  flex: 1.2;
  min-width: 0;
}

.debug-result {
  flex: 1;
  min-width: 0;
}

.form-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--na-text-primary, #303133);
  margin-bottom: 12px;
}

.field-hint {
  font-size: 12px;
  color: var(--na-text-secondary, #909399);
  line-height: 1.5;
  margin-top: 2px;
  width: 100%;
}

.channel-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.route-now {
  font-size: 12px;
  color: var(--na-text-secondary, #909399);
}

.route-now b.is-api {
  color: #e6a23c;
}

.result-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.result-item {
  border: 1px solid var(--na-border-lighter, #e8e8e8);
  border-radius: 6px;
  padding: 8px 10px;
  min-width: 0;
}

.result-label {
  font-size: 12px;
  color: var(--na-text-secondary, #909399);
}

.result-value {
  margin-top: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--na-text-primary, #303133);
  word-break: break-all;
}

.result-value.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-weight: 500;
}

.result-hint,
.empty-hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--na-text-secondary, #909399);
  line-height: 1.6;
}

.recent-card .form-title {
  margin-bottom: 8px;
}

.muted-cell {
  color: var(--na-text-secondary, #909399);
  font-size: 12px;
}

.type-tag {
  font-size: 12px;
  color: var(--na-text-secondary, #909399);
}

.type-tag.alert {
  color: #f56c6c;
}

.status-tag {
  font-size: 12px;
}

.status-tag.ok {
  color: #67c23a;
}

.status-tag.fail {
  color: #f56c6c;
}

.status-tag.pending {
  color: #e6a23c;
}

@media (max-width: 900px) {
  .debug-layout {
    flex-direction: column;
  }

  .debug-form,
  .debug-result {
    width: 100%;
  }
}
</style>
