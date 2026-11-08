<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  portalApi,
  type PortalBusMessage,
  type PortalBusStats,
  type PortalTopicCount
} from '@/api/portal'

/**
 * 消息流转页（/bus，bus-ui-v2 按样张 messages_v1 重做）
 * - 工具栏一行：主题下拉（带条数）/类型/通道/状态/时间（默认今日）/关键词/重置 + 右侧共 N 条
 * - 热门主题 chips 行（hotTopics top6）：点击筛选、再点取消，与下拉联动高亮
 * - 表列：时间/类型/来源→目标（22px 头像+名，→ 分隔）/主题 chip/标题/通道 tag/状态/重试
 *   通道：api=「API 直调」绿 / ws=「WS」灰 / NULL=「—」（历史行）
 * - 头像来源：/api/portal/agents 建 id→{name,avatar} 映射（两页共用口径，不复制文件）
 * - 统计胶囊保持：今日进线/已投递/待投/失败（进页一次，失败静默 0 值）
 * - 移动端不横向溢出（元素级=0，v4.25/v4.29 口径）：卡片三行 flex wrap+order，>=720px 切 8 列网格
 * - 风格对齐门户白卡语言（零依赖、零 emoji、禁 lucide）
 */
const PAGE_SIZE = 20

/** 统计胶囊 + 主题计数（进页一次；接口失败保持 0 值/空数组展示） */
const stats = ref<PortalBusStats>({
  todayTotal: 0,
  delivered: 0,
  pending: 0,
  failed: 0,
  hotTopics: [],
  topics: []
})

/** 列表数据与加载态 */
const loading = ref(true)
const list = ref<PortalBusMessage[]>([])
const page = ref(1)
const total = ref(0)
const totalPages = computed<number>(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

/* ===== 筛选状态（样张口径：topic/type/channel/status 空=全部；days 默认今日，0=全部） ===== */
const fTopic = ref('')
const fType = ref('')
const fCh = ref('')
const fSt = ref('')
const fDays = ref('1')
const fKw = ref('')

/** 头像映射：agentId → {name, avatar}（进页一次；失败回退行内名字/色块首字） */
const agentMap = ref<Record<number, { name: string; avatar: string | null }>>({})

/** 界面时间：yyyy-MM-dd HH:mm:ss → MM-DD HH:mm:ss */
function fmtTime(t?: string | null): string {
  return t ? t.slice(5, 21) : ''
}

/** 来源→目标展示名（映射优先，回退后端行名，再回退 #id） */
function agentLabel(rowName: string | null, id: number): string {
  return agentMap.value[id]?.name || rowName || `#${id}`
}

/** 头像兜底渐变：5 色 token 循环，按 agentId 取模稳定取色（AgentsView 同款口径） */
function avatarBg(id: number): string {
  const tones = [
    'var(--tone-blue)',
    'var(--tone-teal)',
    'var(--tone-green)',
    'var(--tone-amber)',
    'var(--tone-violet)'
  ]
  const c = tones[id % tones.length]
  return `linear-gradient(135deg, color-mix(in srgb, ${c} 72%, #ffffff), ${c})`
}

/** 头像首字符兜底 */
function avatarText(rowName: string | null, id: number): string {
  return agentLabel(rowName, id).slice(0, 1)
}

/** 状态徽标口径：0 待投=amber / 1 已投=green / 2 已读、3 失败=gray/red */
function statusMeta(status: number): { label: string; cls: string } {
  if (status === 0) return { label: '待投', cls: 'is-amber' }
  if (status === 1) return { label: '已投', cls: 'is-green' }
  return { label: status === 2 ? '已读' : '失败', cls: 'is-gray' }
}

/** 类型徽标：notify 蓝 / alert 浅红 / 其他（如 request）amber 兜底 */
function typeMeta(type: string): { label: string; cls: string } {
  if (type === 'alert') return { label: type, cls: 'is-alert' }
  if (type === 'notify') return { label: type, cls: 'is-notify' }
  return { label: type, cls: 'is-request' }
}

/** 通道 tag：api=「API 直调」绿 / ws=「WS」灰 / NULL=「—」（历史行） */
function channelMeta(channel: string | null): { label: string; cls: string } {
  if (channel === 'api') return { label: 'API 直调', cls: 'is-api' }
  if (channel === 'ws') return { label: 'WS', cls: 'is-ws' }
  return { label: '—', cls: 'is-none' }
}

/** 筛选下拉主题项（全部主题+条数，stats.topics count 降序） */
const topicOptions = computed<PortalTopicCount[]>(() => stats.value.topics || [])

/** 热门主题 chips（top6） */
const hotTopics = computed<PortalTopicCount[]>(() => stats.value.hotTopics || [])

async function loadStats(): Promise<void> {
  try {
    const res = await portalApi.busStats()
    if (res) stats.value = res
  } catch {
    /* 统计失败静默：胶囊保持 0 值、主题行空态，不阻塞列表 */
  }
}

async function loadAgents(): Promise<void> {
  try {
    const res = await portalApi.agents()
    const map: Record<number, { name: string; avatar: string | null }> = {}
    for (const a of res || []) {
      map[a.id] = { name: a.displayName || a.name, avatar: a.avatar || null }
    }
    agentMap.value = map
  } catch {
    /* 头像映射失败静默：回退后端行内名字 + 色块首字 */
  }
}

/** 组装列表查询参数（空值不下发；days '0'=全部不下发；status 映射 done=1/wait=0/fail=3） */
function buildParams(): Record<string, number | string> {
  const params: Record<string, number | string> = { page: page.value, size: PAGE_SIZE }
  if (fTopic.value) params.topic = fTopic.value
  if (fType.value) params.type = fType.value
  if (fCh.value) params.channel = fCh.value
  if (fSt.value === 'done') params.status = 1
  else if (fSt.value === 'wait') params.status = 0
  else if (fSt.value === 'fail') params.status = 3
  if (fDays.value !== '0' && fDays.value !== '') params.days = Number(fDays.value)
  const kw = fKw.value.trim()
  if (kw) params.keyword = kw
  return params
}

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const res = await portalApi.busMessages(buildParams())
    list.value = res?.list || []
    total.value = res?.count || 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 任一筛选变化：回到第 1 页重查 */
function applyFilters(): void {
  page.value = 1
  void loadList()
}

/** 关键词防抖（300ms，门户搜索同量级），避免逐键打接口 */
let kwTimer: number | null = null
function onKwInput(): void {
  if (kwTimer !== null) window.clearTimeout(kwTimer)
  kwTimer = window.setTimeout(() => {
    kwTimer = null
    applyFilters()
  }, 300)
}

/** 热门主题 chip 点击：再点取消；与下拉联动（同值高亮） */
function pickTopic(t: string): void {
  fTopic.value = fTopic.value === t ? '' : t
  applyFilters()
}

/** 重置：清空全部筛选，时间回默认「今日」 */
function resetFilters(): void {
  fTopic.value = ''
  fType.value = ''
  fCh.value = ''
  fSt.value = ''
  fDays.value = '1'
  fKw.value = ''
  applyFilters()
}

function goPage(p: number): void {
  if (p < 1 || p > totalPages.value || p === page.value) return
  page.value = p
  void loadList()
}

onMounted(() => {
  void loadStats()
  void loadAgents()
  void loadList()
})
</script>

<template>
  <div class="bus">
    <!-- 顶部：标题 + 统计胶囊 -->
    <div class="bus-head">
      <div class="head-main">
        <h1>消息流转</h1>
        <p>agent 消息总线总览（投递流水，匿名只读）</p>
      </div>
      <div class="bus-stats">
        <div class="stat">
          <span class="stat-label">今日</span>
          <span class="stat-num">{{ stats.todayTotal }}</span>
        </div>
        <div class="stat is-green">
          <span class="stat-label">已投递</span>
          <span class="stat-num">{{ stats.delivered }}</span>
        </div>
        <div class="stat is-amber">
          <span class="stat-label">待投</span>
          <span class="stat-num">{{ stats.pending }}</span>
        </div>
        <div class="stat is-gray">
          <span class="stat-label">失败</span>
          <span class="stat-num">{{ stats.failed }}</span>
        </div>
      </div>
    </div>

    <!-- 列表卡片：工具栏 / 热门主题 / 流水（DOM 序=桌面列序：时间/类型/来源→目标/主题/标题/通道/状态/重试） -->
    <div class="bus-panel">
      <!-- 工具栏一行 -->
      <div class="toolbar">
        <label for="bus-topic">主题</label>
        <select id="bus-topic" v-model="fTopic" class="sel wide" @change="applyFilters">
          <option value="">全部主题</option>
          <option v-for="t in topicOptions" :key="t.topic" :value="t.topic">
            {{ t.topic }}（{{ t.count }}）
          </option>
        </select>
        <label for="bus-type">类型</label>
        <select id="bus-type" v-model="fType" class="sel" @change="applyFilters">
          <option value="">全部</option>
          <option value="notify">notify</option>
          <option value="alert">alert</option>
          <option value="request">request</option>
        </select>
        <label for="bus-ch">通道</label>
        <select id="bus-ch" v-model="fCh" class="sel" @change="applyFilters">
          <option value="">全部</option>
          <option value="api">API 直调</option>
          <option value="ws">WS 常连接</option>
        </select>
        <label for="bus-st">状态</label>
        <select id="bus-st" v-model="fSt" class="sel" @change="applyFilters">
          <option value="">全部</option>
          <option value="done">已投递</option>
          <option value="wait">待投递</option>
          <option value="fail">失败</option>
        </select>
        <label for="bus-days">时间</label>
        <select id="bus-days" v-model="fDays" class="sel" @change="applyFilters">
          <option value="1">今日</option>
          <option value="3">近 3 天</option>
          <option value="7">近 7 天</option>
          <option value="0">全部</option>
        </select>
        <input
          v-model="fKw"
          type="text"
          class="kw"
          placeholder="搜索标题 / 主题关键词"
          @input="onKwInput"
        />
        <button type="button" class="btn-ghost" @click="resetFilters">重置</button>
        <span class="toolbar-total">共 {{ total }} 条</span>
      </div>

      <!-- 热门主题 chips（top6，点击筛选再点取消） -->
      <div v-if="hotTopics.length" class="hotbar">
        <span class="hl">热门主题</span>
        <button
          v-for="t in hotTopics"
          :key="t.topic"
          type="button"
          class="tchip"
          :class="{ on: fTopic === t.topic }"
          :title="`筛选主题 ${t.topic}`"
          @click="pickTopic(t.topic)"
        >
          {{ t.topic }}<span class="n">{{ t.count }}</span>
        </button>
      </div>

      <!-- 表头（>=720px 显示） -->
      <div class="row head-row">
        <span>时间</span>
        <span>类型</span>
        <span>来源 → 目标</span>
        <span>主题</span>
        <span>标题</span>
        <span>通道</span>
        <span>状态</span>
        <span>重试</span>
      </div>

      <div v-if="loading" class="rows-loading">
        <div v-for="i in 5" :key="i" class="skeleton-row" />
      </div>

      <template v-else-if="list.length">
        <div v-for="it in list" :key="it.id" class="row">
          <span class="c-time">{{ fmtTime(it.createTime) }}</span>
          <span class="c-type">
            <i class="type-badge" :class="typeMeta(it.msgType).cls">{{
              typeMeta(it.msgType).label
            }}</i>
          </span>
          <span
            class="c-route"
            :title="`来源 #${it.fromAgentId} → 目标 #${it.toAgentId}`"
          >
            <span class="pair-av" :style="agentMap[it.fromAgentId]?.avatar ? undefined : { background: avatarBg(it.fromAgentId) }">
              <img
                v-if="agentMap[it.fromAgentId]?.avatar"
                :src="agentMap[it.fromAgentId]!.avatar!"
                :alt="agentLabel(it.fromAgentName, it.fromAgentId)"
              />
              <template v-else>{{ avatarText(it.fromAgentName, it.fromAgentId) }}</template>
            </span>
            <span class="pair-name">{{ agentLabel(it.fromAgentName, it.fromAgentId) }}</span>
            <span class="route-arrow" aria-hidden="true">→</span>
            <span class="pair-av" :style="agentMap[it.toAgentId]?.avatar ? undefined : { background: avatarBg(it.toAgentId) }">
              <img
                v-if="agentMap[it.toAgentId]?.avatar"
                :src="agentMap[it.toAgentId]!.avatar!"
                :alt="agentLabel(it.toAgentName, it.toAgentId)"
              />
              <template v-else>{{ avatarText(it.toAgentName, it.toAgentId) }}</template>
            </span>
            <span class="pair-name">{{ agentLabel(it.toAgentName, it.toAgentId) }}</span>
          </span>
          <span class="c-topic">
            <span class="topic-chip" :title="it.topic || ''">{{ it.topic || '—' }}</span>
          </span>
          <span class="c-title" :title="it.title || ''">{{ it.title || '—' }}</span>
          <span class="c-channel">
            <i class="channel-tag" :class="channelMeta(it.channel).cls">{{
              channelMeta(it.channel).label
            }}</i>
          </span>
          <span class="c-status">
            <i class="status-badge" :class="statusMeta(it.status).cls">{{
              statusMeta(it.status).label
            }}</i>
          </span>
          <span class="c-retry">
            <i v-if="it.retryCount > 0" class="retry-badge">重试 {{ it.retryCount }}</i>
            <template v-else>-</template>
          </span>
        </div>
      </template>

      <div v-else class="empty">没有符合条件的消息，试试清空筛选或切换主题</div>

      <!-- 分页器 -->
      <div v-if="!loading && total > 0" class="bus-pager">
        <button
          type="button"
          class="pg-btn"
          :disabled="page <= 1"
          @click="goPage(page - 1)"
        >
          上一页
        </button>
        <span class="pg-info">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
        <button
          type="button"
          class="pg-btn"
          :disabled="page >= totalPages"
          @click="goPage(page + 1)"
        >
          下一页
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ============ /bus 消息流转（bus-ui-v2） ============ */
/* 移动端默认：统计 4 等分 + 工具栏 wrap + 行卡片三行（flex wrap + order）；>=720px 切 8 列网格 + 表头 */
.bus {
  max-width: var(--container-max);
  margin: 0 auto;
  padding: 22px var(--container-pad) 8px;
  min-height: 100%;
  display: flex;
  flex-direction: column;
}
.bus-head {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 14px;
}
.head-main h1 {
  margin: 0;
  font-size: 23px;
  font-weight: 700;
  letter-spacing: -0.3px;
}
.head-main p {
  margin: 5px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}

/* 统计：移动端 4 等分一行（label 上、mono 数字下），>=720px 回横排胶囊 */
.bus-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  width: 100%;
}
.stat {
  padding: 7px 4px 6px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-panel);
  box-shadow: var(--tx-panel-shadow);
  text-align: center;
}
.stat-label {
  display: block;
  font-size: 10.5px;
  color: var(--text-muted);
  margin-bottom: 1px;
}
.stat-num {
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
}
.stat.is-green .stat-num {
  color: #2eb872;
}
.stat.is-amber .stat-num {
  color: #d48806;
}
.stat.is-gray .stat-num {
  color: var(--text-secondary);
}

/* 列表面板：min-width:0 防 flex 子项 min-content 撑爆（v4.25 同路） */
.bus-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  box-shadow: var(--tx-panel-shadow);
  overflow: hidden;
  min-width: 0;
}

/* 工具栏：一行放下六个筛选 + 重置 + 共 N 条（移动端 wrap 两行，元素级零溢出） */
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}
.toolbar label {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}
.sel {
  font-size: 12.5px;
  color: var(--text-main);
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  padding: 4px 6px;
  background: var(--bg-panel);
  max-width: 100%;
  cursor: pointer;
}
.sel:focus {
  outline: none;
  border-color: var(--accent);
}
.sel.wide {
  min-width: 150px;
  max-width: 230px;
}
.kw {
  font-size: 12.5px;
  color: var(--text-main);
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  padding: 4px 9px;
  background: var(--bg-panel);
  width: 190px;
  max-width: 100%;
}
.kw:focus {
  outline: none;
  border-color: var(--accent);
}
.kw::placeholder {
  color: var(--text-muted);
}
.btn-ghost {
  font-size: 12px;
  padding: 4.5px 12px;
  border-radius: 6px;
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
}
.btn-ghost:hover {
  color: var(--accent);
  border-color: var(--accent);
}
.toolbar-total {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  white-space: nowrap;
}

/* 热门主题行 */
.hotbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 9px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-panel-2);
}
.hotbar .hl {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}
.tchip {
  font-size: 11.5px;
  padding: 2.5px 10px;
  border-radius: 999px;
  border: 1px solid var(--border-strong);
  background: var(--bg-panel);
  color: var(--text-secondary);
  cursor: pointer;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tchip:hover {
  color: var(--accent);
  border-color: var(--accent);
}
.tchip.on {
  background: rgba(64, 158, 255, 0.12);
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.tchip .n {
  color: var(--text-muted);
  margin-left: 4px;
  font-family: var(--font-mono);
  font-size: 10.5px;
}
.tchip.on .n {
  color: var(--accent);
}

/* 行：移动端卡片三行（flex wrap + order 重排），>=720px 切网格 */
.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px 8px;
  padding: 11px 13px;
  border-bottom: 1px solid var(--border);
  min-width: 0;
  font-size: 13px;
}
.row:last-child {
  border-bottom: none;
}
.head-row {
  display: none;
}

/* 第一行：类型 + 来源→目标（头像对） + 状态 */
.c-type {
  order: 1;
  line-height: 1;
}
.c-route {
  order: 2;
  flex: 1 1 auto;
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-main);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
}
.pair-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.route-arrow {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 400;
  flex: 0 0 auto;
}
.pair-av {
  flex: 0 0 auto;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  border: 1px solid var(--border);
  background: var(--bg-panel-2);
}
.pair-av img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.c-status {
  order: 3;
  margin-left: auto;
}

/* 第二行：时间 + 主题 + 通道 + 重试 */
.c-time {
  order: 4;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.c-topic {
  order: 5;
  min-width: 0;
  max-width: 150px;
}
.topic-chip {
  display: inline-block;
  max-width: 100%;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-secondary);
  background: var(--bg-panel-2);
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: middle;
}
.c-channel {
  order: 6;
}
.c-retry {
  order: 7;
}

/* 第三行：标题整行（两行截断） */
.c-title {
  order: 8;
  flex: 1 1 100%;
  min-width: 0;
  color: var(--text-main);
  font-size: 13px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 徽标通用：类型 / 状态 / 通道 / 重试 */
.c-type,
.c-status,
.c-channel,
.c-retry {
  white-space: nowrap;
}
.type-badge,
.status-badge,
.channel-tag {
  display: inline-block;
  padding: 1.5px 8px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 600;
  font-style: normal;
}
.type-badge.is-notify {
  color: #409eff;
  background: rgba(64, 158, 255, 0.14);
}
.type-badge.is-alert {
  color: #f56c6c;
  background: rgba(245, 108, 108, 0.14);
}
.type-badge.is-request {
  color: #d48806;
  background: rgba(212, 136, 6, 0.14);
}
.status-badge.is-amber {
  color: #d48806;
  background: rgba(245, 158, 11, 0.14);
}
.status-badge.is-green {
  color: #2eb872;
  background: rgba(46, 184, 114, 0.14);
}
.status-badge.is-gray {
  color: var(--text-muted);
  background: var(--bg-panel-2);
}
/* 通道 tag：api=绿 / ws=灰 / NULL=「—」（is-none 无底色） */
.channel-tag {
  border-radius: 5px;
  font-weight: 500;
}
.channel-tag.is-api {
  color: #2eb872;
  background: rgba(46, 184, 114, 0.14);
}
.channel-tag.is-ws {
  color: var(--text-muted);
  background: var(--bg-panel-2);
}
.channel-tag.is-none {
  color: var(--text-muted);
  background: transparent;
}
/* 重试角标：仅 retryCount > 0 展示；无重试显示「-」 */
.retry-badge {
  display: inline-block;
  padding: 0 7px;
  border-radius: 999px;
  border: 1px solid var(--border-strong);
  font-size: 10.5px;
  font-style: normal;
  color: var(--text-secondary);
}
.c-retry {
  font-size: 12px;
  color: var(--text-muted);
}

/* 骨架 / 空态 */
.rows-loading {
  display: flex;
  flex-direction: column;
}
.skeleton-row {
  height: 36px;
  border-bottom: 1px solid var(--border);
  background: linear-gradient(
    90deg,
    var(--bg-panel) 0%,
    var(--bg-panel-2) 50%,
    var(--bg-panel) 100%
  );
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
}
@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
.empty {
  padding: 48px 16px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13.5px;
}

/* 分页器 */
.bus-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 12px 16px;
  border-top: 1px solid var(--border);
}
.pg-btn {
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  border-radius: 8px;
  padding: 5px 12px;
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.pg-btn:hover:not(:disabled) {
  color: var(--accent);
  border-color: var(--accent);
}
.pg-btn:disabled {
  opacity: 0.45;
  cursor: default;
}
.pg-info {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-muted);
}

/* ============ >=720px：恢复 8 列网格 + 表头（时间/类型/来源→目标/主题/标题/通道/状态/重试） ============ */
@media (min-width: 720px) {
  .bus-head {
    flex-direction: row;
    align-items: flex-end;
    justify-content: space-between;
    gap: 20px;
    flex-wrap: wrap;
    padding-bottom: 16px;
  }
  .bus-stats {
    display: flex;
    gap: 10px;
    width: auto;
  }
  .stat {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    height: 30px;
    padding: 0 12px;
    border-radius: 999px;
    text-align: left;
  }
  .stat-label {
    display: inline;
    font-size: 12px;
    margin-bottom: 0;
  }
  .stat-num {
    font-size: 13px;
  }
  .head-row {
    display: grid;
    grid-template-columns:
      100px 62px minmax(178px, 1.15fr) minmax(110px, 0.75fr)
      minmax(150px, 1.6fr) 84px 52px 58px;
    gap: 10px;
    padding: 7px 16px;
    font-size: 11.5px;
    color: var(--text-muted);
    background: var(--bg-panel-2);
  }
  .row {
    display: grid;
    grid-template-columns:
      100px 62px minmax(178px, 1.15fr) minmax(110px, 0.75fr)
      minmax(150px, 1.6fr) 84px 52px 58px;
    gap: 10px;
    align-items: center;
    padding: 9px 16px;
  }
  /* 关键：清掉移动端 order 重排，grid 按 DOM 列序落位（v4.29 同款清理规则） */
  .row > * {
    order: 0;
  }
  .head-row > * {
    order: 0;
  }
  .row:not(.head-row):hover {
    background: var(--bg-panel-2);
  }
  .c-title {
    flex: 0 1 auto;
    display: block;
    -webkit-line-clamp: unset;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
  .c-topic {
    max-width: none;
  }
  .c-status {
    margin-left: 0;
  }
}
</style>
