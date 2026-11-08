<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  portalApi,
  type PortalAgent,
  type PortalActivityCurrent,
  type PortalStatusCounts,
} from '@/api/portal'

/**
 * 智能体列表页（/agents）v5 值守面板
 * - 6 卡网格：启用 agent 卡 + 1 张全站汇总（进页一次拉目标/待办计数与近 14 天回合，5s 轮询当前状态）
 * - 每卡：头像/名称 + 状态胶囊 + 近 14 天回合柱状图（纯 CSS）+ 笔记/目标/待办读数 + 最近更新时间
 * - 时间一律 MM-DD HH:mm:ss（yyyy-MM-dd HH:mm:ss 直接 slice，禁相对时间）
 * - 点击卡片进入 /agents/{id}；键盘可达（Enter/Space）
 */
const router = useRouter()

/** agent 列表与加载态 */
const agents = ref<PortalAgent[]>([])
const loading = ref(true)

/** 某 agent 目标/待办状态计数（进页一次；接口失败对应读数显示 0） */
interface AgentCounts {
  objectives: PortalStatusCounts | null
  todos: PortalStatusCounts | null
}
const counts = ref<Record<number, AgentCounts>>({})

/** 每 agent 近 14 天回合映射（statDate → turns；进页一次，失败=图表空态） */
const turnMaps = ref<Record<number, Record<string, number>>>({})

/** 活动状态（5s 轮询 + visibilitychange，失败静默下轮重试） */
const statuses = ref<Record<number, PortalActivityCurrent>>({})
let statusTimer: number | null = null

/* ===== 14 天窗口：浏览器本地今天为末日往前 14 天（北京用户即北京时间） ===== */
function dayKey(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const WINDOW_KEYS: string[] = (() => {
  const now = new Date()
  const keys: string[] = []
  for (let i = 13; i >= 0; i--) {
    keys.push(dayKey(new Date(now.getFullYear(), now.getMonth(), now.getDate() - i)))
  }
  return keys
})()

/** 界面时间：yyyy-MM-dd HH:mm:ss → MM-DD HH:mm:ss */
function fmtTime(t?: string | null): string {
  return t ? t.slice(5) : ''
}

/** 头像兜底渐变：5 色 token 循环，按 agent.id 取模稳定取色（无头像 agent 用） */
function avatarBg(id: number): string {
  const tones = ['var(--tone-blue)', 'var(--tone-teal)', 'var(--tone-green)', 'var(--tone-amber)', 'var(--tone-violet)']
  const c = tones[id % tones.length]
  return `linear-gradient(135deg, color-mix(in srgb, ${c} 72%, #ffffff), ${c})`
}

/** 头像首字符：展示名优先，回退 name */
function avatarText(a: PortalAgent): string {
  return (a.displayName || a.name || '?').slice(0, 1)
}

/** 进入智能体详情页 */
function openAgent(a: PortalAgent): void {
  void router.push(`/agents/${a.id}`)
}

/* ===== 状态胶囊（v5 起纯文字，原 emoji 徽标已删） ===== */
/** running → 「工具名 · N秒」（N = max(1, round(sinceMs/1000))）；无数据返回空（不渲染胶囊） */
function stateText(a: PortalAgent): string {
  const s = statuses.value[a.id]
  if (!s) return ''
  if (s.state === 'running') {
    const secs = Math.max(1, Math.round((s.sinceMs ?? 0) / 1000))
    return `${s.toolName || '执行中'} · ${secs}秒`
  }
  return '空闲'
}
function isRunning(a: PortalAgent): boolean {
  return statuses.value[a.id]?.state === 'running'
}

/* ===== 柱状图（纯 CSS div，零图表库） ===== */
interface BarVm {
  key: string
  cls: string
  height: string
  title: string
}
/** 生成 14 根柱：零值=3px 灰柱，末日=accent，柱高=max(6, round(v/peak*100))% */
function barsOf(map: Record<string, number> | undefined): BarVm[] {
  const vals = WINDOW_KEYS.map((k) => map?.[k] ?? 0)
  const peak = Math.max(...vals)
  return WINDOW_KEYS.map((k, i) => {
    const v = vals[i]
    if (v <= 0 || peak <= 0) {
      return { key: k, cls: 'zero', height: '3px', title: `${k} · 0 回合` }
    }
    const h = Math.max(6, Math.round((v / peak) * 100))
    return {
      key: k,
      cls: i === WINDOW_KEYS.length - 1 ? 'today' : '',
      height: `${h}%`,
      title: `${k} · ${v} 回合`,
    }
  })
}
/** 整窗是否有活动（决定图表空态文案与峰值显隐） */
function hasActivity(map: Record<string, number> | undefined): boolean {
  return WINDOW_KEYS.some((k) => (map?.[k] ?? 0) > 0)
}
/** 峰值 */
function peakOf(map: Record<string, number> | undefined): number {
  return Math.max(...WINDOW_KEYS.map((k) => map?.[k] ?? 0))
}

/* ===== 页头汇总行与全站汇总卡 ===== */
const totalNotes = computed<number>(() => agents.value.reduce((s, a) => s + (a.noteCount || 0), 0))
const runningCount = computed<number>(
  () => agents.value.filter((a) => statuses.value[a.id]?.state === 'running').length,
)
/** 全站每日回合求和 */
const totalTurnMap = computed<Record<string, number>>(() => {
  const out: Record<string, number> = {}
  for (const k of WINDOW_KEYS) out[k] = 0
  for (const map of Object.values(turnMaps.value)) {
    for (const k of WINDOW_KEYS) out[k] += map?.[k] ?? 0
  }
  return out
})
/** 各 agent lastNoteAt 最大值（同格式字典序即时间序） */
const latestNoteAt = computed<string>(() => {
  let latest = ''
  for (const a of agents.value) {
    if (a.lastNoteAt && a.lastNoteAt > latest) latest = a.lastNoteAt
  }
  return latest
})
const headSummary = computed<string>(() => {
  if (loading.value || !agents.value.length) return ''
  const parts = [`${agents.value.length} 位`, `笔记合计 ${totalNotes.value}`, `工作中 ${runningCount.value}`]
  const t = fmtTime(latestNoteAt.value)
  if (t) parts.push(`最近更新 ${t}`)
  return parts.join(' · ')
})

/** 读数：目标 / 待办 total（失败静默 → 0） */
function objectiveTotal(a: PortalAgent): number {
  return counts.value[a.id]?.objectives?.total ?? 0
}
function todoTotal(a: PortalAgent): number {
  return counts.value[a.id]?.todos?.total ?? 0
}

/* ===== 数据加载 ===== */
/** 进页一次性拉每 agent 三端点；单请求失败静默（读数 0 / 图表空态），页面不白屏 */
async function loadStatics(): Promise<void> {
  await Promise.all(
    agents.value.map(async (a) => {
      const [stats, obj, todo] = await Promise.all([
        portalApi.agentActivityStats(a.id).catch(() => null),
        portalApi.objectivesStatusCountsByAgent(a.id).catch(() => null),
        portalApi.todosStatusCountsByAgent(a.id).catch(() => null),
      ])
      if (stats?.list?.length) {
        const map: Record<string, number> = {}
        for (const row of stats.list) map[row.statDate] = row.turns
        turnMaps.value[a.id] = map
      }
      counts.value[a.id] = { objectives: obj, todos: todo }
    }),
  )
}

/** 拉全部 agent 当前状态（并发逐个请求，单个失败静默跳过） */
async function loadStatuses(): Promise<void> {
  if (document.visibilityState !== 'visible' || !agents.value.length) return
  const pairs = await Promise.all(
    agents.value.map(async (a) => {
      try {
        return [a.id, await portalApi.agentActivityCurrent(a.id)] as const
      } catch {
        return null
      }
    }),
  )
  const next: Record<number, PortalActivityCurrent> = {}
  for (const p of pairs) {
    if (p) next[p[0]] = p[1]
  }
  statuses.value = next
}

let onVisibility: (() => void) | null = null

onMounted(() => {
  void portalApi
    .agents()
    .then((res) => {
      agents.value = res || []
    })
    .catch(() => {
      agents.value = []
    })
    .finally(() => {
      loading.value = false
      void loadStatics()
      void loadStatuses()
    })
  /* 5s 轮询：页面可见才发请求（visibilitychange 切回时立即补一轮） */
  statusTimer = window.setInterval(() => void loadStatuses(), 5000)
  onVisibility = () => {
    if (document.visibilityState === 'visible') void loadStatuses()
  }
  document.addEventListener('visibilitychange', onVisibility)
})

onUnmounted(() => {
  if (statusTimer != null) window.clearInterval(statusTimer)
  statusTimer = null
  if (onVisibility) document.removeEventListener('visibilitychange', onVisibility)
  onVisibility = null
})
</script>

<template>
  <div class="ag">
    <!-- 页头：标题 + 副标题 + 全站汇总行 -->
    <div class="ag-head">
      <div>
        <h1>智能体</h1>
        <p>每个智能体拥有自己的目标与台账</p>
      </div>
      <span class="ag-sum">{{ headSummary }}</span>
    </div>

    <!-- 加载骨架：6 张占位卡 -->
    <section v-if="loading" class="ag-grid" aria-hidden="true">
      <div v-for="i in 6" :key="i" class="ag-card ag-skeleton" />
    </section>

    <!-- 值守网格：agent 卡 + 全站汇总 -->
    <section v-else-if="agents.length" class="ag-grid">
      <article
        v-for="a in agents"
        :key="a.id"
        class="ag-card"
        role="button"
        tabindex="0"
        :aria-label="`${a.displayName || a.name} 智能体`"
        @click="openAgent(a)"
        @keydown.enter="openAgent(a)"
        @keydown.space.prevent="openAgent(a)"
      >
        <div class="ag-c-head">
          <span class="ag-av" :style="a.avatar ? undefined : { background: avatarBg(a.id) }">
            <img v-if="a.avatar" :src="a.avatar" :alt="a.displayName || a.name" />
            <template v-else>{{ avatarText(a) }}</template>
          </span>
          <span class="ag-c-id">
            <b>{{ a.displayName || a.name }}</b>
            <i>{{ a.name }}</i>
          </span>
          <span v-if="stateText(a)" class="ag-state" :class="{ 'is-run': isRunning(a) }">
            <span class="dot"></span>
            <span class="txt">{{ stateText(a) }}</span>
          </span>
        </div>

        <div class="ag-chart">
          <div class="ag-chart-top">
            <span class="ag-chart-cap">
              {{ hasActivity(turnMaps[a.id]) ? '近 14 天 · 回合' : '近 14 天 · 回合 · 暂无活动' }}
            </span>
            <span v-if="hasActivity(turnMaps[a.id])" class="ag-chart-peak">
              峰值 {{ peakOf(turnMaps[a.id]) }}
            </span>
          </div>
          <div class="ag-bars">
            <i
              v-for="b in barsOf(turnMaps[a.id])"
              :key="b.key"
              :class="b.cls"
              :style="{ height: b.height }"
              :title="b.title"
            ></i>
          </div>
          <div class="ag-axis">
            <span>{{ WINDOW_KEYS[0].slice(5) }}</span>
            <span>{{ WINDOW_KEYS[WINDOW_KEYS.length - 1].slice(5) }}</span>
          </div>
        </div>

        <div class="ag-c-foot">
          <div class="ag-stats">
            <span class="ag-stat"><b>{{ a.noteCount }}</b><span>笔记</span></span>
            <span class="ag-stat"><b>{{ objectiveTotal(a) }}</b><span>目标</span></span>
            <span class="ag-stat"><b>{{ todoTotal(a) }}</b><span>待办</span></span>
          </div>
          <span class="ag-swap">
            <span class="ag-time">{{ fmtTime(a.lastNoteAt) }}</span>
            <span class="ag-go">目标与台账 →</span>
          </span>
        </div>
      </article>

      <!-- 全站汇总卡：不可点击，无 hover swap -->
      <article class="ag-card is-total">
        <div class="ag-c-head">
          <span class="ag-av is-icon">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
              <rect x="4" y="4" width="6.5" height="6.5" rx="1.6" stroke="currentColor" stroke-width="1.7" fill="none" />
              <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.6" stroke="currentColor" stroke-width="1.7" fill="none" />
              <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.6" stroke="currentColor" stroke-width="1.7" fill="none" />
              <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.6" stroke="currentColor" stroke-width="1.7" fill="none" />
            </svg>
          </span>
          <span class="ag-c-id">
            <b>全站汇总</b>
            <i>all agents</i>
          </span>
          <span v-if="runningCount > 0" class="ag-state is-run">
            <span class="dot"></span>
            <span class="txt">工作中 {{ runningCount }}</span>
          </span>
          <span v-else class="ag-state">
            <span class="dot"></span>
            <span class="txt">空闲</span>
          </span>
        </div>

        <div class="ag-chart">
          <div class="ag-chart-top">
            <span class="ag-chart-cap">
              {{ hasActivity(totalTurnMap) ? '近 14 天 · 全站回合' : '近 14 天 · 全站回合 · 暂无活动' }}
            </span>
            <span v-if="hasActivity(totalTurnMap)" class="ag-chart-peak">
              峰值 {{ peakOf(totalTurnMap) }}
            </span>
          </div>
          <div class="ag-bars">
            <i
              v-for="b in barsOf(totalTurnMap)"
              :key="b.key"
              :class="b.cls"
              :style="{ height: b.height }"
              :title="b.title"
            ></i>
          </div>
          <div class="ag-axis">
            <span>{{ WINDOW_KEYS[0].slice(5) }}</span>
            <span>{{ WINDOW_KEYS[WINDOW_KEYS.length - 1].slice(5) }}</span>
          </div>
        </div>

        <div class="ag-c-foot">
          <div class="ag-stats">
            <span class="ag-stat"><b>{{ agents.length }}</b><span>智能体</span></span>
            <span class="ag-stat"><b>{{ totalNotes }}</b><span>笔记合计</span></span>
            <span class="ag-stat"><b>{{ agents.length - runningCount }}</b><span>空闲</span></span>
          </div>
          <span class="ag-swap">
            <span class="ag-time">{{ fmtTime(latestNoteAt) }}</span>
          </span>
        </div>
      </article>
    </section>

    <!-- 空态：无启用 agent -->
    <div v-else class="ag-empty">暂无启用的智能体</div>
  </div>
</template>

<style scoped>
/* ============ /agents v5 值守面板（视觉基准：样张 agents-list-v5-20260929） ============ */
.ag {
  max-width: var(--container-max);
  margin: 0 auto;
  padding: 22px var(--container-pad) 8px;
  min-height: 100%;
  display: flex;
  flex-direction: column;
}
.ag-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  padding-bottom: 16px;
  flex: none;
}
.ag-head h1 {
  margin: 0;
  font-size: 23px;
  font-weight: 700;
  letter-spacing: -0.3px;
}
.ag-head p {
  margin: 5px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}
.ag-sum {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}

/* 网格：3 列起步，卡片高度随视口伸缩 */
.ag-grid {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-rows: minmax(212px, 1fr);
}
.ag-card {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 16px 18px 14px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--tx-panel-shadow);
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    background 0.16s ease;
}
.ag-card:hover {
  border-color: var(--border-strong);
  background: var(--bg-panel-2);
}
.ag-card:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
.ag-card.is-total {
  cursor: default;
  background: var(--bg-panel);
}
.ag-card.is-total:hover {
  border-color: var(--border);
  background: var(--bg-panel);
}

/* 卡头：头像 44px + 名称 + 状态胶囊 */
.ag-c-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}
.ag-av {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  overflow: hidden;
  flex: none;
  box-shadow: 0 0 0 1px var(--border);
  background: var(--bg-panel-2);
  color: var(--text-main);
  font-size: 19px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ag-av img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.ag-av.is-icon {
  color: var(--text-muted);
}
.ag-c-id {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ag-c-id b {
  font-size: 15.5px;
  font-weight: 700;
  letter-spacing: -0.2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ag-c-id i {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 状态胶囊：空闲灰 / 工作中绿（纯文字，零 emoji） */
.ag-state {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 24px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  font-size: 11.5px;
  color: var(--text-muted);
  background: var(--bg-panel-2);
  white-space: nowrap;
}
.ag-state .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.45;
  flex: none;
}
.ag-state.is-run {
  color: var(--text-secondary);
  border-color: color-mix(in srgb, var(--tone-green) 40%, var(--border));
  background: color-mix(in srgb, var(--tone-green) 8%, var(--bg-panel-2));
}
.ag-state.is-run .dot {
  background: var(--tone-green);
  opacity: 1;
}
.ag-state .txt {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: -0.2px;
}

/* 14 天柱状图：吃掉卡片剩余高度 */
.ag-chart {
  flex: 1;
  min-height: 96px;
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ag-chart-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  flex: none;
}
.ag-chart-cap {
  font-size: 11.5px;
  color: var(--text-muted);
}
.ag-chart-peak {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-muted);
}
.ag-bars {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: flex-end;
  gap: 5px;
}
.ag-bars i {
  flex: 1;
  min-width: 4px;
  max-width: 16px;
  border-radius: 2px 2px 0 0;
  background: var(--border-strong);
}
.ag-bars i.zero {
  background: var(--divider);
}
.ag-bars i.today {
  background: var(--accent);
}
.ag-axis {
  display: flex;
  justify-content: space-between;
  flex: none;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-muted);
  opacity: 0.8;
}

/* 三读数 + 底部时间 */
.ag-c-foot {
  flex: none;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--divider);
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}
.ag-stats {
  display: flex;
  gap: 22px;
}
.ag-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ag-stat b {
  font-family: var(--font-mono);
  font-size: 17px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.6px;
}
.ag-stat span {
  font-size: 11px;
  color: var(--text-muted);
}

/* hover swap：时间淡出、「目标与台账」淡入；仅指针设备生效，触屏常显时间 */
.ag-swap {
  position: relative;
  display: block;
  height: 16px;
  text-align: right;
  min-width: 0;
}
.ag-time {
  position: absolute;
  right: 0;
  top: 0;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-muted);
  white-space: nowrap;
  transition: opacity 0.14s ease;
}
.ag-go {
  position: absolute;
  right: 0;
  top: 0;
  font-size: 12px;
  color: var(--accent);
  white-space: nowrap;
  opacity: 0;
  transition: opacity 0.14s ease;
}
@media (hover: hover) {
  .ag-card:not(.is-total):hover .ag-time {
    opacity: 0;
  }
  .ag-card:not(.is-total):hover .ag-go {
    opacity: 1;
  }
}

/* 骨架 / 空态 */
.ag-skeleton {
  cursor: default;
  background: linear-gradient(90deg, var(--bg-panel) 0%, var(--bg-panel-2) 50%, var(--bg-panel) 100%);
  background-size: 200% 100%;
  animation: ag-shimmer 1.4s infinite;
}
.ag-skeleton:hover {
  border-color: var(--border);
}
@keyframes ag-shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
.ag-empty {
  padding: 40px;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
  background: var(--bg-panel);
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
}

/* 断点：1180 两列 / 760 一列 */
@media (max-width: 1180px) {
  .ag-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .ag-grid {
    grid-template-columns: 1fr;
    grid-auto-rows: minmax(200px, auto);
  }
  .ag-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .ag-sum {
    white-space: normal;
  }
}
</style>
