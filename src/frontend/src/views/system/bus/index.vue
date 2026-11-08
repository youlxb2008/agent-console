<!--
  消息总线（M1 bus-m1，菜单 3196 /system/bus，组件 views/system/bus/index）
  --------------------------------------------------------
  - 统计卡 4：今日进线 / 待投递 / 投递失败 / 平均投递耗时
  - 列表：时间(MM-DD HH:mm:ss) / 来源→目标 / type 徽标(notify 灰 alert 红) / topic / 标题 /
          status 徽标 / 模型 / 重试 / 耗时；alert 行浅红底；失败行 last_error 悬浮
  - 行操作：详情 / 重投（status 0/3，二次确认，bus:message:retry）
  - 详情弹窗：信封预览（所见即收件人所见）+ payload JSON + 时间线 + sessionKey 复制
  - 手动发消息（bus:message:send）：来源(任选模拟)/目标/type/topic/标题/payload/sessionKey/model
  - 时间显示一律 MM-DD HH:mm:ss 带日期；零 emoji
-->
<template>
  <div class="na-page">
    <PageHeader
      title="消息总线"
      subtitle="agent 间告知类消息统一通路（Redis Stream 投递 + MySQL 终态留痕）"
    />

    <!-- 统计卡 4 -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-label">今日进线</div>
        <div class="stat-value">{{ stats?.todayTotal ?? 0 }}</div>
      </div>
      <div class="stat-card" :class="{ 'is-warn': (stats?.pendingCount ?? 0) > 0 }" @click="filterStatus(0)">
        <div class="stat-label">待投递</div>
        <div class="stat-value">{{ stats?.pendingCount ?? 0 }}</div>
      </div>
      <div class="stat-card" :class="{ 'is-danger': (stats?.failedCount ?? 0) > 0 }" @click="filterStatus(3)">
        <div class="stat-label">投递失败</div>
        <div class="stat-value">{{ stats?.failedCount ?? 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">平均投递耗时</div>
        <div class="stat-value">
          {{ stats?.avgDeliverySeconds != null ? stats.avgDeliverySeconds.toFixed(1) + 's' : '—' }}
        </div>
      </div>
    </div>

    <!-- 投递路由（bus-ui-v2）：发件方维度 + 点即存（样张 route_v2_final.html） -->
    <div class="na-card route-card">
      <div class="route-head">
        <div>
          <div class="route-title">投递路由</div>
          <div class="route-sub">
            按<b>发件方</b>维度配置：左侧选择发件方，右侧逐个设定它发往各收件方的通道，也可用「统一设置」一次设定全部。点击即保存并即时生效，下一条消息按新通道投递；未配置的收发组合默认走 WS。头像取自智能体档案，路由页直接引用，不另存一份。
          </div>
        </div>
      </div>
      <div class="route-layout">
        <!-- 左：发件方列表 -->
        <div class="sender-list">
          <div class="sender-label">发件方</div>
          <div
            v-for="s in routeSenders"
            :key="s.agentId"
            class="sender-item"
            :class="{ on: s.agentId === curSenderId, muted: !s.live }"
            @click="curSenderId = s.agentId"
          >
            <img v-if="agentAvatar(s.agentId)" class="sender-ava" :src="agentAvatar(s.agentId) || ''" alt="">
            <span v-else class="sender-ava sender-ava-fallback">{{ agentName(s.agentId).slice(0, 1) }}</span>
            <span class="sender-name">{{ agentName(s.agentId) }}</span>
            <span class="sbadge">{{ s.live ? 'API ' + s.apiCount + '/' + s.apiTotal : '无实例' }}</span>
          </div>
        </div>
        <!-- 右：统一设置 + 方向表 -->
        <div class="route-panel">
          <div class="unify-bar">
            <span>统一设置（当前发件方的全部收件方）：</span>
            <el-button v-permiss="'bus:message:send'" size="small" type="primary" :loading="unifyLoading" @click="unifyAll('api')">
              全部设为 API
            </el-button>
            <el-button v-permiss="'bus:message:send'" size="small" :loading="unifyLoading" @click="unifyAll('ws')">
              全部设为 WS
            </el-button>
            <el-button v-permiss="'bus:message:send'" size="small" text type="info" :loading="unifyLoading" @click="restoreDefault()">
              恢复默认（WS）
            </el-button>
            <span class="unify-hint">批量动作会覆盖下方逐项选择</span>
          </div>
          <el-table :data="curReceiverRows" size="small">
            <el-table-column label="收件方" min-width="190">
              <template #default="{ row }">
                <span class="aname">
                  <img v-if="agentAvatar(row.toAgentId)" class="cell-ava" :src="agentAvatar(row.toAgentId) || ''" alt="">
                  <span v-else class="cell-ava cell-ava-fallback">{{ agentName(row.toAgentId).slice(0, 1) }}</span>
                  <span>{{ agentName(row.toAgentId) }}</span>
                  <span v-if="row.self" class="rtag self">自投</span>
                  <span v-if="!row.live" class="rtag gray">无实例</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="投递通道" width="150">
              <template #default="{ row }">
                <span v-if="!row.live" class="muted-cell">—</span>
                <div v-else class="seg">
                  <button
                    type="button"
                    :class="{ on: row.channel === 'ws' }"
                    @click="setChannel(row as RouteRow, 'ws')"
                  >
                    WS
                  </button>
                  <button
                    type="button"
                    :class="{ on: row.channel === 'api', dis: !row.apiOk }"
                    :disabled="!row.apiOk"
                    :title="row.apiOk ? '' : '「' + agentName(row.toAgentId) + '」未配置 API 端点（BUS_API_TARGET_' + row.toAgentId + '），已保持 WS'"
                    @click="setChannel(row as RouteRow, 'api')"
                  >
                    API
                  </button>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="API 端点" min-width="190">
              <template #default="{ row }">
                <span v-if="!row.live" class="ep-no">—</span>
                <template v-else-if="row.apiOk">
                  <div><span class="ep-ok">● 已配置</span></div>
                  <div class="ep-host">{{ row.apiBaseUrl }}</div>
                </template>
                <span v-else class="ep-no">未配置</span>
              </template>
            </el-table-column>
            <el-table-column label="近 24 小时" width="100">
              <template #default="{ row }">
                <span v-if="row.count24h > 0" class="stat24">{{ row.count24h }} 条</span>
                <span v-else class="ep-no">无消息</span>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="210">
              <template #default="{ row }">
                <span v-if="!row.live" class="route-note">不参与投递</span>
                <span v-else-if="row.self" class="route-note">自投消息不参与熔断监控</span>
                <span v-else-if="row.channel === 'api'" class="route-note">HTTP 直调，会话落 <code>api:</code> 前缀，侧栏自动隐藏</span>
                <span v-else-if="!row.apiOk" class="route-note warn">先补 BUS_API_TARGET_{{ row.toAgentId }} 后可选 API</span>
                <span v-else class="route-note">端点就绪，可切 API</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="route-footnote">
            <b>交互规则：</b>① 点击 WS / API 即保存生效，不再需要「保存 / 批量保存」按钮；② 收件方未配置 API 端点时，API 选项置灰——防止「选了 API 却被静默降级 WS」的误配置；③ 无运行实例的 agent 整行置灰不参与投递；④ 自投（发给自己）可配置，自投消息不参与熔断监控；⑤ API 直投的会话以 <code>api:</code> 前缀落库，侧栏按机器会话自动隐藏，不产生新话题。
          </div>
        </div>
      </div>
    </div>

    <!-- API 端点配置状态（bus-ui-v2；只读展示，对应 BUS_API_TARGET_* env） -->
    <div class="na-card route-card">
      <div class="route-head">
        <div>
          <div class="route-title">API 端点配置状态</div>
          <div class="route-sub">
            对应服务端环境变量 <code>BUS_API_TARGET_*</code>，此处只读展示；新增或修改端点由部署流程维护。路由选择了 API 但端点缺失时，自动降级 WS 并在消息上留痕 last_error。
          </div>
        </div>
      </div>
      <el-table :data="deliveryTargets" size="small">
        <el-table-column label="Agent" min-width="180">
          <template #default="{ row }">
            <span class="aname">
              <img v-if="agentAvatar(row.agentId)" class="cell-ava" :src="agentAvatar(row.agentId) || ''" alt="">
              <span v-else class="cell-ava cell-ava-fallback">{{ agentName(row.agentId).slice(0, 1) }}</span>
              <span>{{ agentName(row.agentId) }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="端点地址" min-width="250">
          <template #default="{ row }">
            <template v-if="row.apiConfigured">
              <span class="ep-ok">● 已配置</span>
              <span class="ep-host">{{ row.apiBaseUrl }}</span>
            </template>
            <span v-else-if="row.wsConfigured" class="ep-no">未配置</span>
            <span v-else class="ep-no">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span v-if="!row.wsConfigured && !row.apiConfigured" class="epill no">无实例</span>
            <span v-else-if="row.apiConfigured" class="epill ok">正常</span>
            <span v-else class="epill plan">待配置</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">
            <span v-if="!row.wsConfigured && !row.apiConfigured" class="route-note">无运行实例，不参与投递</span>
            <span v-else-if="row.apiConfigured" class="route-note">端点就绪，路由可切 API</span>
            <span v-else class="route-note">未配置 API 端点，仅可 WS</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :api="{ list: listApi }"
        :default-size="10"
        :row-class-name="rowClassName"
      >
        <template #toolbar>
          <el-button
            v-permiss="'bus:message:send'"
            type="primary"
            @click="openSendDialog"
          >
            手动发消息
          </el-button>
        </template>

        <!-- 时间 -->
        <template #column-createTime="{ row }">
          <span class="muted-cell">{{ fmtTime((row as BusMessageItem).createTime) }}</span>
        </template>

        <!-- 来源→目标 -->
        <template #column-route="{ row }">
          <span class="route-cell">
            {{ (row as BusMessageItem).fromAgentName || ('agent:' + (row as BusMessageItem).fromAgentId) }}
            <span class="route-arrow">→</span>
            {{ (row as BusMessageItem).toAgentName || ('agent:' + (row as BusMessageItem).toAgentId) }}
          </span>
        </template>

        <!-- type 徽标：notify 灰 / alert 红 -->
        <template #column-msgType="{ row }">
          <span class="type-tag" :class="`is-${(row as BusMessageItem).msgType}`">
            {{ (row as BusMessageItem).msgType }}
          </span>
        </template>

        <!-- 标题（失败行悬浮 last_error 全文） -->
        <template #column-title="{ row }">
          <span
            class="title-cell"
            :title="(row as BusMessageItem).status === 3 ? (row as BusMessageItem).lastError || '' : ''"
          >
            {{ (row as BusMessageItem).title }}
          </span>
          <div
            v-if="(row as BusMessageItem).status === 3 && (row as BusMessageItem).lastError"
            class="error-line"
            :title="(row as BusMessageItem).lastError || ''"
          >
            {{ (row as BusMessageItem).lastError }}
          </div>
        </template>

        <!-- status 徽标 -->
        <template #column-status="{ row }">
          <span class="status-tag" :class="`is-${(row as BusMessageItem).status}`">
            {{ statusText((row as BusMessageItem).status) }}
          </span>
        </template>

        <!-- 模型 -->
        <template #column-model="{ row }">
          <span class="muted-cell">{{ (row as BusMessageItem).model || '默认' }}</span>
        </template>

        <!-- 重试次数 -->
        <template #column-retryCount="{ row }">
          <span class="muted-cell" :class="{ 'retry-hot': (row as BusMessageItem).retryCount > 0 }">
            {{ (row as BusMessageItem).retryCount ?? 0 }}
          </span>
        </template>

        <!-- 耗时 -->
        <template #column-cost="{ row }">
          <span class="muted-cell">{{ fmtCost(row as BusMessageItem) }}</span>
        </template>

        <!-- 操作 -->
        <template #column-operations="{ row }">
          <el-button link type="primary" @click="openDetail(row as BusMessageItem)">
            详情
          </el-button>
          <el-button
            v-if="(row as BusMessageItem).status === 0 || (row as BusMessageItem).status === 3"
            v-permiss="'bus:message:retry'"
            link
            type="warning"
            @click="handleRetry(row as BusMessageItem)"
          >
            重投
          </el-button>
        </template>
      </CrudTable>
    </div>

    <!-- 详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      title="消息详情"
      width="720px"
      destroy-on-close
    >
      <div v-if="detail" class="detail-wrap">
        <div class="detail-meta">
          <div class="meta-line"><span class="meta-label">消息 ID</span><span class="mono">{{ detail.messageId }}</span></div>
          <div class="meta-line"><span class="meta-label">来源 / 目标</span>
            <span>{{ detail.fromAgentName || detail.fromAgentId }} → {{ detail.toAgentName || detail.toAgentId }}</span></div>
          <div class="meta-line"><span class="meta-label">状态</span>
            <span class="status-tag" :class="`is-${detail.status}`">{{ statusText(detail.status) }}</span>
            <span class="meta-sub">重试 {{ detail.retryCount ?? 0 }} 次</span></div>
          <div class="meta-line"><span class="meta-label">会话 / 模型</span>
            <span class="mono">{{ detail.sessionKey || '（投递时自动新建）' }}</span>
            <el-button
              v-if="detail.sessionKey"
              link
              type="primary"
              @click="copySessionKey"
            >
              复制
            </el-button>
            <span class="meta-sub">{{ detail.model || '收件人默认 preset' }}</span></div>
        </div>

        <div class="section-title">信封预览（所见即收件人所见）</div>
        <pre class="envelope-box">{{ envelopePreview }}</pre>

        <div class="section-title">payload JSON</div>
        <pre class="payload-box">{{ payloadPretty || '（无 payload）' }}</pre>

        <div class="section-title">投递时间线</div>
        <div class="timeline">
          <div class="timeline-item">
            <span class="tl-dot tl-create"></span>
            <span class="tl-time">{{ fmtTime(detail.createTime) }}</span>
            <span>创建入队（status=0）</span>
          </div>
          <div v-if="(detail.retryCount ?? 0) > 0" class="timeline-item">
            <span class="tl-dot tl-retry"></span>
            <span class="tl-time">—</span>
            <span>重试 {{ detail.retryCount }} 次<span v-if="detail.lastError">：{{ detail.lastError }}</span></span>
          </div>
          <div v-if="detail.status === 1" class="timeline-item">
            <span class="tl-dot tl-ok"></span>
            <span class="tl-time">{{ fmtTime(detail.deliveredAt) }}</span>
            <span>送达（协议层，WS send 成功）</span>
          </div>
          <div v-else-if="detail.status === 3" class="timeline-item">
            <span class="tl-dot tl-fail"></span>
            <span class="tl-time">{{ fmtTime(detail.updateTime) }}</span>
            <span>投递失败终态（重试耗尽）</span>
          </div>
          <div v-else-if="detail.status === 0" class="timeline-item">
            <span class="tl-dot tl-wait"></span>
            <span class="tl-time">—</span>
            <span>等待投递（dispatcher 消费中 / 补偿扫表兜底）</span>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 手动发消息弹窗 -->
    <el-dialog
      v-model="sendVisible"
      title="手动发消息（演练 / e2e 工具，走同一校验链）"
      width="640px"
      destroy-on-close
    >
      <el-form :model="sendForm" label-width="100px">
        <el-form-item label="来源" required>
          <el-select v-model="sendForm.fromAgentId" placeholder="任选 agent 模拟生产者" style="width: 100%">
            <el-option v-for="a in agentOptions" :key="a.value" :label="a.label" :value="a.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标" required>
          <el-select v-model="sendForm.toAgentId" placeholder="选择目标 agent" style="width: 100%">
            <el-option v-for="a in agentOptions" :key="a.value" :label="a.label" :value="a.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="sendForm.type">
            <el-radio value="notify">notify（告知）</el-radio>
            <el-radio value="alert">alert（告警）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="场景 topic" required>
          <el-input v-model="sendForm.topic" placeholder="如 alpha-exception / manual-drill" maxlength="100" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="sendForm.title" maxlength="255" />
        </el-form-item>
        <el-form-item label="payload">
          <el-input
            v-model="sendForm.payloadText"
            type="textarea"
            :rows="4"
            placeholder='JSON 对象或留空，如 {"detail":"..."}（≤8KB）'
          />
        </el-form-item>
        <el-form-item label="sessionKey">
          <el-input v-model="sendForm.sessionKey" placeholder="可选，bus-* 前缀；留空自动新建一次性会话" maxlength="60" />
        </el-form-item>
        <el-form-item label="model">
          <el-input v-model="sendForm.model" placeholder="可选，须在目标白名单内；留空用收件人默认" maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sendVisible = false">取消</el-button>
        <el-button type="primary" :loading="sendLoading" @click="submitSend">发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable from '@/components/CrudTable.vue'
import {
  getBusMessages,
  getBusMessageDetail,
  retryBusMessage,
  sendBusManual,
  getBusStats,
  getBusRoutes,
  saveBusRoute,
  saveBusRoutes,
  clearBusRoutes,
  getDeliveryTargets,
  getRouteStats,
  BUS_STATUS_TEXT,
  type BusMessageItem,
  type BusStats,
  type BusRouteItem,
  type BusChannel,
  type BusDeliveryTarget
} from '@/api/system/bus'
import { loadAgentOptions, agentOptionLabel } from '@/api/system/note'
import type { ApiResponse, PageResponse } from '@/types/api'

const crudRef = ref<any>(null)

/* ===== 文案与格式 ===== */
function statusText(status: number): string {
  return BUS_STATUS_TEXT[status] ?? '未知'
}

/** 时间显示一律 MM-DD HH:mm:ss（后端 yyyy-MM-dd HH:mm:ss，截去年份段） */
function fmtTime(value?: string | null): string {
  if (!value) return '—'
  return value.length >= 16 ? value.slice(5) : value
}

/** 耗时 = delivered_at - create_time（秒；未送达显示 —） */
function fmtCost(row: BusMessageItem): string {
  if (!row.deliveredAt || !row.createTime) return '—'
  const ms = new Date(row.deliveredAt.replace(' ', 'T')).getTime()
    - new Date(row.createTime.replace(' ', 'T')).getTime()
  if (Number.isNaN(ms) || ms < 0) return '—'
  return (ms / 1000).toFixed(1) + 's'
}

/** alert 行浅红底 */
function rowClassName({ row }: { row: BusMessageItem; rowIndex: number }): string {
  return row.msgType === 'alert' ? 'bus-row-alert' : ''
}

/* ===== 列定义 ===== */
const columns = [
  { prop: 'createTime', label: '时间', width: 130, slot: true },
  { prop: 'route', label: '来源→目标', width: 170, slot: true },
  { prop: 'msgType', label: '类型', width: 80, slot: true },
  { prop: 'topic', label: '主题', width: 140 },
  { prop: 'title', label: '标题', minWidth: 200, slot: true },
  { prop: 'status', label: '状态', width: 90, slot: true },
  { prop: 'model', label: '模型', width: 130, slot: true },
  { prop: 'retryCount', label: '重试', width: 70, slot: true },
  { prop: 'cost', label: '耗时', width: 90, slot: true },
  { prop: 'operations', label: '操作', width: 130, slot: true, fixed: 'right' as const }
]

/* ===== 搜索项 ===== */
const searchItems = reactive([
  { prop: 'fromAgent', label: '来源', type: 'select' as const, placeholder: '全部来源', clearable: true, options: [] as { label: string; value: any }[] },
  { prop: 'toAgent', label: '目标', type: 'select' as const, placeholder: '全部目标', clearable: true, options: [] as { label: string; value: any }[] },
  {
    prop: 'type',
    label: '类型',
    type: 'select' as const,
    placeholder: '全部类型',
    clearable: true,
    options: [
      { label: 'notify', value: 'notify' },
      { label: 'alert', value: 'alert' }
    ]
  },
  {
    prop: 'status',
    label: '状态',
    type: 'select' as const,
    placeholder: '全部状态',
    clearable: true,
    options: [
      { label: '待投递', value: 0 },
      { label: '已送达', value: 1 },
      { label: '已读', value: 2 },
      { label: '失败', value: 3 }
    ]
  }
])

/** 列表接口包装（query: page/size + 筛选） */
async function listApi(query: Record<string, any>): Promise<ApiResponse<PageResponse<BusMessageItem>>> {
  return getBusMessages(query as any)
}

/* ===== 统计卡 ===== */
const stats = ref<BusStats | null>(null)

async function loadStats(): Promise<void> {
  const res = await getBusStats(7)
  stats.value = res.data ?? null
}

/** 点统计卡 = 按状态过滤 */
function filterStatus(status: number): void {
  if (crudRef.value?.query) {
    crudRef.value.query.status = status
    crudRef.value.handleSearch()
  }
}

/* ===== agent 下拉 ===== */
const agentOptions = ref<{ label: string; value: any }[]>([])

async function loadOptions(): Promise<void> {
  const agents = await loadAgentOptions()
  const opts = agents.map((a) => ({ label: agentOptionLabel(a), value: a.id }))
  agentOptions.value = opts
  searchItems[0].options = opts
  searchItems[1].options = opts
}

/* ===== 详情弹窗 ===== */
const detailVisible = ref(false)
const detail = ref<BusMessageItem | null>(null)

async function openDetail(row: BusMessageItem): Promise<void> {
  const res = await getBusMessageDetail(row.id)
  detail.value = res.data ?? row
  detailVisible.value = true
}

/** 信封预览（与后端 BusEnvelopeFormatter §7 格式一致，所见即收件人所见） */
const envelopePreview = computed(() => {
  const d = detail.value
  if (!d) return ''
  const first = `[来源:${d.fromAgentName || d.fromAgentId}(agent:${d.fromAgentId})`
    + `|类型:${d.msgType}|主题:${d.topic}|消息:${d.messageId}|模型:${d.model || '默认'}]`
  let text = first + '\n' + (d.title || '')
  if (d.payload) {
    text += '\n' + d.payload
  }
  return text
})

/** payload JSON 格式化（解析失败显示原文） */
const payloadPretty = computed(() => {
  const raw = detail.value?.payload
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

async function copySessionKey(): Promise<void> {
  const key = detail.value?.sessionKey
  if (!key) return
  try {
    await navigator.clipboard.writeText(key)
    ElMessage.success('已复制 sessionKey')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

/* ===== 重投（二次确认） ===== */
async function handleRetry(row: BusMessageItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认重投该消息？重投后重试计数清零并重新入队（当前状态：${statusText(row.status)}）。`,
      '重投确认',
      { confirmButtonText: '确认重投', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  await retryBusMessage(row.id)
  ElMessage.success('已重入投递队列')
  crudRef.value?.reload()
  loadStats()
}

/* ===== 手动发消息 ===== */
const sendVisible = ref(false)
const sendLoading = ref(false)
const sendForm = reactive({
  fromAgentId: undefined as number | undefined,
  toAgentId: undefined as number | undefined,
  type: 'notify' as 'notify' | 'alert',
  topic: '',
  title: '',
  payloadText: '',
  sessionKey: '',
  model: ''
})

function openSendDialog(): void {
  sendForm.fromAgentId = undefined
  sendForm.toAgentId = undefined
  sendForm.type = 'notify'
  sendForm.topic = ''
  sendForm.title = ''
  sendForm.payloadText = ''
  sendForm.sessionKey = ''
  sendForm.model = ''
  sendVisible.value = true
}

async function submitSend(): Promise<void> {
  if (!sendForm.fromAgentId || !sendForm.toAgentId) {
    ElMessage.warning('请选择来源与目标')
    return
  }
  if (!sendForm.topic || !sendForm.title) {
    ElMessage.warning('请填写场景 topic 与标题')
    return
  }
  let payload: unknown = undefined
  if (sendForm.payloadText && sendForm.payloadText.trim()) {
    try {
      payload = JSON.parse(sendForm.payloadText)
    } catch {
      ElMessage.warning('payload 须为合法 JSON')
      return
    }
  }
  sendLoading.value = true
  try {
    await sendBusManual({
      fromAgentId: sendForm.fromAgentId,
      toAgentId: sendForm.toAgentId,
      type: sendForm.type,
      topic: sendForm.topic,
      title: sendForm.title,
      payload,
      sessionKey: sendForm.sessionKey || undefined,
      model: sendForm.model || undefined
    })
    ElMessage.success('已入队')
    sendVisible.value = false
    crudRef.value?.reset()
    loadStats()
  } finally {
    sendLoading.value = false
  }
}

onMounted(() => {
  loadStats()
  loadOptions()
  void loadAgentMeta().finally(() => loadRoutes())
})

/* ===== 投递路由（bus-ui-v2：发件方维度 + 点即存，样张 route_v2_final.html）===== */
/** 方向行（矩阵行 + 端点/无实例/自投/近 24h 派生字段） */
interface RouteRow extends BusRouteItem {
  /** 自投方向（from = to） */
  self: boolean
  /** 收件方有运行实例（ws/api 任一已配置） */
  live: boolean
  /** 收件方 API 端点已配置（false 时 API 选项置灰） */
  apiOk: boolean
  /** 收件方 API 端点 baseUrl（只读展示，不含 apiKey） */
  apiBaseUrl: string | null
  /** 方向近 24h 消息条数（无 = 0，显「无消息」） */
  count24h: number
}
const routeRows = ref<RouteRow[]>([])
const deliveryTargets = ref<BusDeliveryTarget[]>([])
/** 当前发件方（默认管理员 agent alpha，id=1；载入后若不存在回落第一行） */
const curSenderId = ref<number>(1)
/** id → {name, avatar} 映射（智能体档案；头像不另存一份） */
const agentMeta = ref<Map<number, { name: string; avatar: string | null }>>(new Map())
const unifyLoading = ref(false)

/** 头像/名字来源：管理端 agent 分页接口（与发送弹窗下拉同源） */
async function loadAgentMeta(): Promise<void> {
  const agents = await loadAgentOptions()
  const m = new Map<number, { name: string; avatar: string | null }>()
  agents.forEach((a) => m.set(a.id, { name: a.displayName || a.name, avatar: a.avatar ?? null }))
  agentMeta.value = m
}

function agentName(id: number): string {
  return agentMeta.value.get(id)?.name || 'agent:' + id
}

function agentAvatar(id: number): string | null {
  return agentMeta.value.get(id)?.avatar || null
}

/** 矩阵 + 端点可用性 + 方向计数一次拉齐，派生 RouteRow */
async function loadRoutes(): Promise<void> {
  try {
    const [routesRes, targetsRes, statsRes] = await Promise.all([
      getBusRoutes(),
      getDeliveryTargets(),
      getRouteStats()
    ])
    deliveryTargets.value = targetsRes.data ?? []
    const targetMap = new Map(deliveryTargets.value.map((t) => [t.agentId, t]))
    const statMap = new Map(
      (statsRes.data ?? []).map((s) => [s.fromAgentId + '-' + s.toAgentId, s.count24h])
    )
    routeRows.value = (routesRes.data ?? []).map((r) => {
      const t = targetMap.get(r.toAgentId)
      return {
        ...r,
        self: r.fromAgentId === r.toAgentId,
        live: t ? t.wsConfigured || t.apiConfigured : false,
        apiOk: !!t?.apiConfigured,
        apiBaseUrl: t?.apiBaseUrl ?? null,
        count24h: statMap.get(r.fromAgentId + '-' + r.toAgentId) ?? 0
      }
    })
    if (!routeRows.value.some((r) => r.fromAgentId === curSenderId.value)) {
      curSenderId.value = routeRows.value[0]?.fromAgentId ?? 0
    }
  } catch {
    routeRows.value = []
    deliveryTargets.value = []
  }
}

/** 发件方列表（全部 agent；badge = API n/m，m 排除无实例） */
const routeSenders = computed(() => {
  const ids = new Set<number>()
  routeRows.value.forEach((r) => {
    ids.add(r.fromAgentId)
    ids.add(r.toAgentId)
  })
  deliveryTargets.value.forEach((t) => ids.add(t.agentId))
  agentMeta.value.forEach((_, id) => ids.add(id))
  return [...ids]
    .sort((a, b) => a - b)
    .map((id) => {
      const t = deliveryTargets.value.find((x) => x.agentId === id)
      const live = t ? t.wsConfigured || t.apiConfigured : false
      const mine = routeRows.value.filter((r) => r.fromAgentId === id)
      return {
        agentId: id,
        live,
        apiCount: mine.filter((r) => r.live && r.channel === 'api').length,
        apiTotal: mine.filter((r) => r.live).length
      }
    })
})

/** 当前发件方的收件方行（含自投；矩阵已保证每方向一行） */
const curReceiverRows = computed(() =>
  routeRows.value.filter((r) => r.fromAgentId === curSenderId.value)
)

/** 点即存：乐观更新，失败回滚（总线 bus-route-channel 惯例：保存即时生效） */
async function setChannel(row: RouteRow, ch: BusChannel): Promise<void> {
  if (!row.live) return
  if (ch === 'api' && !row.apiOk) {
    ElMessage.warning('「' + agentName(row.toAgentId) + '」未配置 API 端点，已保持 WS')
    return
  }
  if (row.channel === ch) return
  const prev = row.channel
  row.channel = ch
  try {
    await saveBusRoute({
      fromAgentId: row.fromAgentId,
      toAgentId: row.toAgentId,
      channel: ch
    })
    ElMessage.success(
      '已保存：' +
        agentName(row.fromAgentId) +
        ' → ' +
        agentName(row.toAgentId) +
        ' 走 ' +
        (ch === 'api' ? 'API（HTTP 直调）' : 'WS（gateway 常连接）') +
        '，下一条消息生效'
    )
  } catch {
    row.channel = prev
  }
}

/** 统一设置：循环 upsert 当前发件方全部 live 方向；api 模式跳过无端点收件方 */
async function unifyAll(mode: 'api' | 'ws'): Promise<void> {
  const sender = routeSenders.value.find((s) => s.agentId === curSenderId.value)
  if (sender && !sender.live) {
    ElMessage.warning('「' + agentName(curSenderId.value) + '」无运行实例，不参与投递')
    return
  }
  const rows = routeRows.value.filter((r) => r.fromAgentId === curSenderId.value && r.live)
  const apply: RouteRow[] = []
  const skip: string[] = []
  rows.forEach((r) => {
    if (mode === 'api') {
      if (r.apiOk) apply.push(r)
      else skip.push(agentName(r.toAgentId))
    } else {
      apply.push(r)
    }
  })
  if (!apply.length) {
    ElMessage.info('无可设置的方向')
    return
  }
  unifyLoading.value = true
  try {
    await saveBusRoutes(
      apply.map((r) => ({
        fromAgentId: r.fromAgentId,
        toAgentId: r.toAgentId,
        channel: mode as BusChannel
      }))
    )
    apply.forEach((r) => {
      r.channel = mode as BusChannel
    })
    ElMessage.success(
      '已保存：' +
        agentName(curSenderId.value) +
        ' 统一设为 ' +
        (mode === 'api' ? 'API' : 'WS') +
        '（' +
        apply.length +
        ' 项）' +
        (skip.length ? '；' + skip.join('、') + ' 无端点已跳过' : '')
    )
  } finally {
    unifyLoading.value = false
  }
}

/** 恢复默认：删除该发件方全部路由行（回落默认 ws） */
async function restoreDefault(): Promise<void> {
  const sender = routeSenders.value.find((s) => s.agentId === curSenderId.value)
  if (sender && !sender.live) {
    ElMessage.warning('「' + agentName(curSenderId.value) + '」无运行实例，不参与投递')
    return
  }
  unifyLoading.value = true
  try {
    await clearBusRoutes(curSenderId.value)
    routeRows.value
      .filter((r) => r.fromAgentId === curSenderId.value)
      .forEach((r) => {
        r.channel = 'ws'
      })
    ElMessage.success(
      '已恢复默认：' + agentName(curSenderId.value) + ' 的全部路由清空，按默认 WS 投递'
    )
  } finally {
    unifyLoading.value = false
  }
}
</script>

<style scoped>
/* 统计卡（沿用 memory.vue 统计卡风格） */
.stats-row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.stat-card {
  flex: 1;
  background: var(--na-bg-container, #fff);
  border: 1px solid var(--na-border-lighter, #e8e8e8);
  border-radius: 6px;
  padding: 14px 16px;
  cursor: default;
}

.stat-card.is-warn,
.stat-card.is-danger {
  cursor: pointer;
}

.stat-card.is-warn .stat-value {
  color: #e6a23c;
}

.stat-card.is-danger .stat-value {
  color: #f56c6c;
}

.stat-label {
  font-size: 12px;
  color: var(--na-text-secondary, #909399);
}

.stat-value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  color: var(--na-text-primary, #303133);
}

/* 单元格 */
.muted-cell {
  color: var(--na-text-secondary, #909399);
  font-size: 12px;
}

.route-cell {
  font-size: 12px;
}

.route-arrow {
  margin: 0 4px;
  color: var(--na-text-secondary, #909399);
}

.title-cell {
  font-weight: 500;
}

.error-line {
  margin-top: 2px;
  font-size: 11px;
  color: #f56c6c;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.retry-hot {
  color: #e6a23c;
  font-weight: 600;
}

/* 类型徽标：notify 灰 / alert 红 */
.type-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 18px;
}

.type-tag.is-notify {
  background: #f0f2f5;
  color: #606266;
}

.type-tag.is-alert {
  background: #fde2e2;
  color: #c45656;
}

/* 状态徽标 */
.status-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 18px;
}

.status-tag.is-0 {
  background: #ecf5ff;
  color: #409eff;
}

.status-tag.is-1 {
  background: #f0f9eb;
  color: #67c23a;
}

.status-tag.is-2 {
  background: #f0f2f5;
  color: #909399;
}

.status-tag.is-3 {
  background: #fde2e2;
  color: #c45656;
}

/* alert 行浅红底（el-table 行类） */
:deep(.el-table .bus-row-alert > td.el-table__cell) {
  background-color: #fdf3f2;
}

/* 详情弹窗 */
.detail-meta {
  margin-bottom: 12px;
}

.meta-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  flex-wrap: wrap;
}

.meta-label {
  width: 84px;
  color: var(--na-text-secondary, #909399);
  font-size: 12px;
  flex-shrink: 0;
}

.meta-sub {
  color: var(--na-text-secondary, #909399);
  font-size: 12px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.section-title {
  margin: 12px 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--na-text-primary, #303133);
}

.envelope-box,
.payload-box {
  margin: 0;
  padding: 10px 12px;
  background: #f7f8fa;
  border: 1px solid var(--na-border-lighter, #e8e8e8);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
}

/* ===== 投递路由（bus-ui-v2：发件方维度布局样式，样张 route_v2_final.html）===== */
.route-card {
  margin-bottom: 12px;
}

.route-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.route-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--na-text-primary, #303133);
}

.route-sub {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--na-text-secondary, #909399);
}

.route-layout {
  display: flex;
  gap: 18px;
}

.sender-list {
  width: 208px;
  flex-shrink: 0;
  border-right: 1px solid #ebeef5;
  padding-right: 12px;
}

.sender-label {
  font-size: 12px;
  color: #909399;
  padding: 2px 10px 8px;
}

.sender-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13.5px;
  color: var(--na-text-primary, #303133);
}

.sender-item:hover {
  background: #f5f7fa;
}

.sender-item.on {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}

.sender-item.muted {
  color: #c0c4cc;
}

.sender-ava {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  object-fit: cover;
  background: #fff;
  border: 1px solid #ebeef5;
  flex-shrink: 0;
  display: block;
}

.sender-ava-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #fff;
  background: #909399;
  border: none;
}

.sender-item.on .sender-ava-fallback {
  background: #409eff;
}

.sender-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sbadge {
  margin-left: auto;
  font-size: 11px;
  color: #909399;
  background: #f4f4f5;
  border-radius: 8px;
  padding: 1px 7px;
  white-space: nowrap;
}

.sender-item.on .sbadge {
  background: #d9ecff;
  color: #409eff;
}

.route-panel {
  flex: 1;
  min-width: 0;
}

.unify-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  background: #f8f9fb;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 7px 12px;
  font-size: 12.5px;
  color: #606266;
}

.unify-hint {
  color: #c0c4cc;
  font-size: 12px;
}

/* 点即存 segmented（WS | API） */
.seg {
  display: inline-flex;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
}

.seg button {
  border: 0;
  background: #fff;
  padding: 4px 15px;
  font-size: 12.5px;
  cursor: pointer;
  color: #606266;
}

.seg button + button {
  border-left: 1px solid #dcdfe6;
}

.seg button.on {
  background: #409eff;
  color: #fff;
}

.seg button.dis {
  color: #c0c4cc;
  background: #fafafa;
  cursor: not-allowed;
}

/* 名字 + 头像 + tag */
.aname {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.cell-ava {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  object-fit: cover;
  background: #fff;
  border: 1px solid #ebeef5;
  flex-shrink: 0;
  display: block;
}

.cell-ava-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #fff;
  background: #909399;
  border: none;
}

.rtag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  border: 1px solid;
  white-space: nowrap;
}

.rtag.self {
  color: #409eff;
  border-color: #d9ecff;
  background: #ecf5ff;
}

.rtag.gray {
  color: #909399;
  border-color: #ebeef5;
  background: #f4f4f5;
}

/* API 端点三态 */
.ep-ok {
  color: #67c23a;
  font-size: 12.5px;
}

.ep-no {
  color: #c0c4cc;
  font-size: 12.5px;
}

.ep-host {
  color: #909399;
  font-size: 11.5px;
  word-break: break-all;
}

.stat24 {
  font-size: 12.5px;
  color: #606266;
  white-space: nowrap;
}

.route-note {
  font-size: 12px;
  color: #909399;
}

.route-note.warn {
  color: #e6a23c;
}

.route-footnote {
  font-size: 12px;
  color: #909399;
  margin-top: 12px;
  line-height: 1.9;
  border-top: 1px dashed #ebeef5;
  padding-top: 10px;
}

.route-footnote b {
  color: #606266;
  font-weight: 600;
}

/* 端点配置状态 pill */
.epill {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 8px;
  border: 1px solid;
}

.epill.ok {
  color: #67c23a;
  background: #f0f9eb;
  border-color: #e1f3d8;
}

.epill.no {
  color: #909399;
  background: #f4f4f5;
  border-color: #ebeef5;
}

.epill.plan {
  color: #e6a23c;
  background: #fdf6ec;
  border-color: #faecd8;
}

@media (max-width: 900px) {
  .route-layout {
    flex-direction: column;
  }

  .sender-list {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid #ebeef5;
    padding-right: 0;
    padding-bottom: 8px;
  }
}

/* 时间线 */
.timeline {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.timeline-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
}

.tl-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  align-self: center;
}

.tl-create {
  background: #409eff;
}

.tl-retry {
  background: #e6a23c;
}

.tl-ok {
  background: #67c23a;
}

.tl-fail {
  background: #f56c6c;
}

.tl-wait {
  background: #c0c4cc;
}

.tl-time {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--na-text-secondary, #909399);
  flex-shrink: 0;
}
</style>
