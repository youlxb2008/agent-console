<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { siteTitle, loadSite } from '@/store/site'
import { notebooks, notebooksLoading, loadNotebooks } from '@/store/notebook'
import {
  portalApi,
  type NoteSummary,
  type PortalAgent,
  type PortalActivityCurrent,
  type PortalLedger,
  type PortalObjective,
  type PortalNotebook,
} from '@/api/portal'

/**
 * 门户首页（/）v4.24「交付陈列与值守总览」
 * 形态基准：样张 home-v2b（真实数据静态页），本页按契约动态拉取
 * - 顶部横带：站点名 + 副标 + 行内 KPI 四值
 * - 值守条：agent 卡一排（状态胶囊 + 近 14 天回合 spark 柱，零事件日补 gap）
 * - 主区两栏：左「最新交付」跨笔记本 feed；右栏笔记本 / 进行中目标 / 最新台账三卡
 * - 分区渐进渲染：各分区独立加载，先到先渲染，单区失败静默降级为空态
 * - 时间一律绝对式 MM-DD HH:mm:ss（yyyy-MM-dd HH:mm:ss 直接 slice(5)，禁相对时间）
 */

/** 智能体列表（值守条 + 目标区数据源） */
const agents = ref<PortalAgent[]>([])
const agentsReady = ref(false)

/** 每 agent 当前状态（进页一次；失败静默该卡不显胶囊） */
const statuses = ref<Record<number, PortalActivityCurrent>>({})

/** 每 agent 近 14 天回合映射（statDate → turns；失败静默该卡全零柱） */
const turnMaps = ref<Record<number, Record<string, number>>>({})

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
const LAST_KEY = WINDOW_KEYS[WINDOW_KEYS.length - 1]

/** 界面时间：yyyy-MM-dd HH:mm:ss → MM-DD HH:mm:ss */
function fmtTime(t?: string | null): string {
  return t ? t.slice(5) : ''
}

/** agent 展示名 */
function agentName(a: PortalAgent): string {
  return a.displayName || a.name || `#${a.id}`
}

/** 头像兜底渐变：5 色 token 循环按 id 取模（无头像用） */
function avatarBg(id: number): string {
  const tones = ['var(--tone-blue)', 'var(--tone-teal)', 'var(--tone-green)', 'var(--tone-amber)', 'var(--tone-violet)']
  const c = tones[id % tones.length]
  return `linear-gradient(135deg, color-mix(in srgb, ${c} 72%, #ffffff), ${c})`
}

/** 笔记本缩略兜底渐变（logo 与 cover 均空用） */
function coverTone(id: number): string {
  const tones = ['var(--tone-blue)', 'var(--tone-teal)', 'var(--tone-green)', 'var(--tone-amber)', 'var(--tone-violet)']
  const c = tones[id % tones.length]
  return `linear-gradient(225deg, color-mix(in srgb, ${c} 72%, #ffffff), ${c})`
}

/* ===== spark 柱：API 零事件日不返回，前端按 statDate 补 gap 成连续 14 柱（参照 /agents 页 v4.21 逻辑） ===== */
interface SparkBar {
  key: string
  cls: string
  h: string
  title: string
}
function sparkBars(map: Record<string, number> | undefined): SparkBar[] {
  const vals = WINDOW_KEYS.map((k) => map?.[k] ?? 0)
  const peak = Math.max(...vals)
  return WINDOW_KEYS.map((k, i) => {
    const v = vals[i]
    if (v <= 0 || peak <= 0) {
      return { key: k, cls: 'zero', h: '2px', title: `${k} · 0 回合` }
    }
    return {
      key: k,
      cls: i === WINDOW_KEYS.length - 1 ? 'is-today' : '',
      h: `${Math.max(2, Math.round((v / peak) * 100))}%`,
      title: `${k} · ${v} 回合`,
    }
  })
}
function todayTurns(id: number): number {
  return turnMaps.value[id]?.[LAST_KEY] ?? 0
}
function totalTurns(id: number): number {
  const m = turnMaps.value[id]
  if (!m) return 0
  return WINDOW_KEYS.reduce((s, k) => s + (m[k] || 0), 0)
}

/* ===== 状态胶囊：running → 工具名 · Ns（工具名空显「工作中」）；否则空闲 ===== */
function stateText(id: number): string {
  const s = statuses.value[id]
  if (!s) return ''
  if (s.state === 'running') {
    const secs = Math.max(1, Math.round((s.sinceMs ?? 0) / 1000))
    return `${s.toolName || '工作中'} · ${secs}s`
  }
  return '空闲'
}
function isRunning(id: number): boolean {
  return statuses.value[id]?.state === 'running'
}

/* ===== 最新交付 feed：7 本笔记本 recentNotes 合并去重按更新时间倒序取 22 条 ===== */
interface FeedRow extends NoteSummary {
  notebookId: number
  notebookName: string
  logo: string | null
  cover: string | null
}
const feed = ref<FeedRow[]>([])
const feedReady = ref(false)
/** feed 与右栏笔记本卡共用：store 列表按最近更新倒序前 7 本 */
const feedNbs = computed<PortalNotebook[]>(() => {
  return [...notebooks.value]
    .sort((a, b) => String(b.lastNoteAt || '').localeCompare(String(a.lastNoteAt || '')))
    .slice(0, 7)
})
const feedSub = computed(() =>
  feedNbs.value.length ? `跨 ${feedNbs.value.length} 本笔记本 · 按更新时间` : '',
)
/** 贡献者串：displayName 顿号连接 */
function contribOf(r: FeedRow): string {
  return (r.contributors || []).map((c) => c.displayName).join('、')
}
async function loadFeed(): Promise<void> {
  await loadNotebooks()
  const nbs = feedNbs.value
  if (nbs.length) {
    const lists = await Promise.all(
      nbs.map(async (nb): Promise<FeedRow[]> => {
        try {
          const rows = (await portalApi.recentNotes({ notebookId: nb.id, limit: 10 })) || []
          return rows.map((r) => ({
            ...r,
            contributors: r.contributors || [],
            notebookId: nb.id,
            notebookName: nb.name,
            logo: nb.logo ?? null,
            cover: nb.cover ?? null,
          }))
        } catch {
          return []
        }
      }),
    )
    const merged = new Map<string, FeedRow>()
    for (const list of lists) {
      for (const row of list) {
        if (!merged.has(row.nid)) merged.set(row.nid, row)
      }
    }
    feed.value = [...merged.values()]
      .sort((a, b) => String(b.updateTime || '').localeCompare(String(a.updateTime || '')))
      .slice(0, 22)
  }
  feedReady.value = true
}

/* ===== 进行中目标：各 agent 合并 status=1，按更新时间倒序取 7 条 ===== */
interface ObjRow {
  oid: string
  agentId: number
  title: string
  progress: number
  period: string
  avatar: string | null
  updateTime: string
}
const objs = ref<ObjRow[]>([])
const objsTotal = ref(0)
const objsReady = ref(false)
/** 进度口径兼容：0 < p <= 1 先乘 100，再钳 0~100 */
function pctOf(p: number): number {
  let v = p
  if (v > 0 && v <= 1) v = v * 100
  return Math.min(100, Math.max(0, Math.round(v)))
}
async function loadObjectives(list: PortalAgent[]): Promise<void> {
  if (list.length) {
    const groups = await Promise.all(
      list.map(async (a): Promise<ObjRow[]> => {
        try {
          const rows = (await portalApi.objectivesByAgent(a.id)) || []
          return rows
            .filter((o: PortalObjective) => o.status === 1)
            .map((o) => ({
              oid: o.oid,
              agentId: a.id,
              title: o.title,
              progress: pctOf(o.progress ?? 0),
              period: o.period || '',
              avatar: a.avatar ?? null,
              updateTime: o.updateTime || '',
            }))
        } catch {
          return []
        }
      }),
    )
    const flat = groups.flat()
    objsTotal.value = flat.length
    objs.value = flat
      .sort((x, y) => y.updateTime.localeCompare(x.updateTime))
      .slice(0, 7)
  }
  objsReady.value = true
}

/* ===== 最新台账：聚合分页首页 6 条 ===== */
const ledgerRows = ref<PortalLedger[]>([])
const ledgerCount = ref(0)
const ledgerReady = ref(false)
/** 类型徽标映射：其他类型灰显原文 */
const LEDGER_BADGES: Record<string, { cls: string; label: string }> = {
  progress: { cls: 'p', label: '进展' },
  decision: { cls: 'd', label: '决定' },
  observation: { cls: 'o', label: '观察' },
  risk: { cls: 'r', label: '风险' },
  report: { cls: 'g', label: '报告' },
}
function ledgerBadge(t: string): { cls: string; label: string } {
  return LEDGER_BADGES[t] || { cls: 'n', label: t }
}
async function loadLedger(): Promise<void> {
  try {
    const page = await portalApi.ledgerPageAggregate({ page: 1, size: 6 })
    ledgerRows.value = page?.list || []
    ledgerCount.value = page?.count ?? 0
  } catch {
    ledgerRows.value = []
    ledgerCount.value = 0
  }
  ledgerReady.value = true
}

/* ===== 值守明细：每卡 current + 近 14 天 stats（单卡失败静默） ===== */
async function loadWatch(list: PortalAgent[]): Promise<void> {
  if (!list.length) return
  await Promise.all(
    list.map(async (a) => {
      const [cur, stats] = await Promise.all([
        portalApi.agentActivityCurrent(a.id).catch(() => null),
        portalApi.agentActivityStats(a.id, 14).catch(() => null),
      ])
      if (cur) statuses.value[a.id] = cur
      if (stats?.list?.length) {
        const map: Record<string, number> = {}
        for (const row of stats.list) map[row.statDate] = row.turns
        turnMaps.value[a.id] = map
      }
    }),
  )
}

/* ===== KPI 四值（分区就绪前显 —） ===== */
function kpiVal(ready: boolean, v: number): string | number {
  return ready ? v : '—'
}
const kpiNotebooks = computed(() => kpiVal(!notebooksLoading.value, notebooks.value.length))
const kpiNotes = computed(() =>
  kpiVal(!notebooksLoading.value, notebooks.value.reduce((s, n) => s + (n.noteCount || 0), 0)),
)
const kpiAgents = computed(() => kpiVal(agentsReady.value, agents.value.length))
const kpiLedger = computed(() => kpiVal(ledgerReady.value, ledgerCount.value))

onMounted(() => {
  void loadSite()
  void (async () => {
    let list: PortalAgent[] = []
    try {
      list = (await portalApi.agents()) || []
    } catch {
      list = []
    }
    agents.value = list
    agentsReady.value = true
    void loadWatch(list)
    void loadObjectives(list)
  })()
  void loadFeed()
  void loadLedger()
})
</script>

<template>
  <div class="home">
    <!-- 顶部横带：站点名 + 副标 + KPI 四值 -->
    <header class="top">
      <h1>{{ siteTitle }}</h1>
      <span class="desc">多 Agent 协作门户 — 知识与交付陈列</span>
      <div class="kpi">
        <span><b>{{ kpiNotebooks }}</b>笔记本</span>
        <span><b>{{ kpiNotes }}</b>笔记</span>
        <span><b>{{ kpiAgents }}</b>智能体</span>
        <span><b>{{ kpiLedger }}</b>台账</span>
      </div>
    </header>

    <!-- 值守条：agent 卡一排 -->
    <section class="watch" aria-label="智能体值守">
      <template v-if="!agentsReady">
        <div v-for="i in 5" :key="i" class="wcard is-skel"></div>
      </template>
      <template v-else-if="agents.length">
        <router-link v-for="a in agents" :key="a.id" class="wcard" :to="`/agents/${a.id}`">
          <span class="wav" :style="a.avatar ? undefined : { background: avatarBg(a.id) }">
            <img v-if="a.avatar" :src="a.avatar" :alt="agentName(a)" />
            <template v-else>{{ agentName(a).slice(0, 1) }}</template>
          </span>
          <span class="wmain">
            <span class="wname">{{ agentName(a) }}</span>
            <span v-if="stateText(a.id)" class="st" :class="{ run: isRunning(a.id) }">
              <i class="dot"></i>{{ stateText(a.id) }}
            </span>
          </span>
          <span class="wspark">
            <span class="spark">
              <i
                v-for="b in sparkBars(turnMaps[a.id])"
                :key="b.key"
                :class="b.cls"
                :style="{ height: b.h }"
                :title="b.title"
              ></i>
            </span>
            <span class="wnum">今日 <b>{{ todayTurns(a.id) }}</b> · 14天 <b>{{ totalTurns(a.id) }}</b> 回合</span>
          </span>
        </router-link>
      </template>
      <div v-else class="empty">暂无启用的智能体</div>
    </section>

    <!-- 主区两栏 -->
    <div class="main">
      <!-- 左：最新交付 -->
      <section class="card" aria-label="最新交付">
        <div class="sect">
          <h2>最新交付</h2>
          <span class="sub">{{ feedSub }}</span>
          <router-link class="more" to="/notebooks">全部笔记 →</router-link>
        </div>
        <template v-if="!feedReady">
          <div v-for="i in 10" :key="i" class="skel-row"></div>
        </template>
        <template v-else-if="feed.length">
          <router-link
            v-for="r in feed"
            :key="r.nid"
            class="frow"
            :to="`/notebooks/${r.notebookId}/notes/${r.nid}`"
          >
            <span class="ftime">{{ fmtTime(r.updateTime) }}</span>
            <span class="fbadge">
              <img v-if="r.logo || r.cover" class="fb-img" :src="r.logo || r.cover || undefined" :alt="r.notebookName" />
              <span v-else class="fb-cb" :style="{ background: coverTone(r.notebookId) }"></span>
              <span class="fb-name">{{ r.notebookName }}</span>
            </span>
            <span class="ftitle" :title="r.title">{{ r.title }}</span>
            <span v-if="contribOf(r)" class="fmeta" :title="contribOf(r)">{{ contribOf(r) }}</span>
          </router-link>
        </template>
        <div v-else class="empty">暂无笔记</div>
      </section>

      <!-- 右栏三卡 -->
      <div class="stack">
        <!-- 笔记本 -->
        <section class="card" aria-label="笔记本">
          <div class="sect">
            <h2>笔记本</h2>
            <router-link class="more" to="/notebooks">管理 →</router-link>
          </div>
          <template v-if="notebooksLoading && !notebooks.length">
            <div v-for="i in 7" :key="i" class="skel-row"></div>
          </template>
          <template v-else-if="feedNbs.length">
            <router-link v-for="nb in feedNbs" :key="nb.id" class="nrow" :to="`/notebooks/${nb.id}`">
              <span class="nrow-img">
                <img v-if="nb.logo || nb.cover" :src="nb.logo || nb.cover || undefined" :alt="nb.name" />
                <span v-else class="nrow-cb" :style="{ background: coverTone(nb.id) }">{{ (nb.name || '?').slice(0, 1) }}</span>
              </span>
              <span class="nrow-name">{{ nb.name }}</span>
              <span class="nrow-meta"><b>{{ nb.noteCount }}</b> 篇 · {{ fmtTime(nb.lastNoteAt) }}</span>
            </router-link>
          </template>
          <div v-else class="empty">暂无笔记本</div>
        </section>

        <!-- 进行中目标 -->
        <section class="card" aria-label="进行中目标">
          <div class="sect">
            <h2>进行中目标</h2>
            <span v-if="objsReady" class="sub">{{ objsTotal }} 项</span>
          </div>
          <template v-if="!objsReady">
            <div v-for="i in 5" :key="i" class="skel-row"></div>
          </template>
          <template v-else-if="objs.length">
            <router-link
              v-for="o in objs"
              :key="o.oid"
              class="orow"
              :to="`/agents/${o.agentId}?tab=objectives`"
            >
              <span class="orow-av">
                <img v-if="o.avatar" :src="o.avatar" alt="" />
                <span v-else class="orow-cb" :style="{ background: avatarBg(o.agentId) }"></span>
              </span>
              <span class="orow-title" :title="o.title">{{ o.title }}</span>
              <span class="orow-bar"><i :style="{ width: o.progress + '%' }"></i></span>
              <span class="orow-pct">{{ o.progress }}%</span>
              <span v-if="o.period" class="orow-period">{{ o.period }}</span>
            </router-link>
          </template>
          <div v-else class="empty">暂无进行中目标</div>
        </section>

        <!-- 最新台账 -->
        <section class="card" aria-label="最新台账">
          <div class="sect">
            <h2>最新台账</h2>
            <span v-if="ledgerReady" class="sub">共 {{ ledgerCount }} 条</span>
            <router-link class="more" to="/ledger">全部 →</router-link>
          </div>
          <template v-if="!ledgerReady">
            <div v-for="i in 6" :key="i" class="skel-row"></div>
          </template>
          <template v-else-if="ledgerRows.length">
            <router-link
              v-for="w in ledgerRows"
              :key="w.lid"
              class="lrow"
              :to="w.agentId ? `/agents/${w.agentId}?tab=ledger` : '/ledger'"
            >
              <span class="ltime">{{ fmtTime(w.createTime) }}</span>
              <span class="lbadge" :class="'lb-' + ledgerBadge(w.type).cls">{{ ledgerBadge(w.type).label }}</span>
              <span v-if="w.agentName" class="lag">{{ w.agentName }}</span>
              <span class="ltitle" :title="w.title">{{ w.title }}</span>
            </router-link>
          </template>
          <div v-else class="empty">暂无台账</div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ============ 页面容器 ============ */
.home {
  max-width: var(--container-max);
  margin: 0 auto;
  padding: 22px var(--container-pad) 40px;
}

/* ============ 顶部横带 ============ */
.top {
  display: flex;
  align-items: baseline;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.top h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.2px;
  color: var(--text-main);
}
.top .desc {
  font-size: 13px;
  color: var(--text-muted);
}
.kpi {
  margin-left: auto;
  display: flex;
  gap: 22px;
  font-size: 13px;
  color: var(--text-secondary);
}
.kpi b {
  font-size: 16px;
  color: var(--text-main);
  font-weight: 700;
  margin-right: 4px;
  font-family: var(--font-mono);
}

/* ============ 值守条 ============ */
.watch {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.wcard {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  text-decoration: none;
  color: inherit;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}
.wcard:hover {
  border-color: var(--border-strong);
  box-shadow: 0 4px 14px rgba(var(--accent-rgb), 0.08);
}
.wav {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  overflow: hidden;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
}
.wav img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.wmain {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.wname {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--text-main);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.st {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  color: var(--text-muted);
  white-space: nowrap;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}
.st .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
  background: var(--border-strong);
}
.st.run {
  color: var(--tone-green);
}
.st.run .dot {
  background: var(--tone-green);
  animation: pulse 1.4s ease infinite;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
.wspark {
  flex: none;
  width: 132px;
}
.spark {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 32px;
}
.spark i {
  flex: 1;
  min-width: 2px;
  background: var(--border-strong);
  border-radius: 1.5px;
}
.spark i.zero {
  background: var(--divider);
}
.spark i.is-today {
  background: var(--accent);
}
.wnum {
  display: block;
  font-size: 10px;
  color: var(--text-muted);
  margin-top: 4px;
  white-space: nowrap;
}
.wnum b {
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-weight: 600;
}

/* ============ 主区两栏 ============ */
.main {
  display: grid;
  grid-template-columns: 1.55fr 1fr;
  gap: 14px;
  align-items: start;
}
.card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.sect {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.sect h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--text-main);
}
.sect .sub {
  font-size: 11.5px;
  color: var(--text-muted);
}
.sect .more {
  margin-left: auto;
  font-size: 12px;
  color: var(--accent);
  text-decoration: none;
  white-space: nowrap;
}
.sect .more:hover {
  text-decoration: underline;
}
.stack {
  display: grid;
  gap: 14px;
}

/* ============ 最新交付 feed ============ */
.frow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--divider);
  text-decoration: none;
  color: inherit;
}
.frow:last-child {
  border-bottom: 0;
}
.frow:hover {
  background: var(--bg-panel-2);
}
.ftime {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-muted);
  width: 92px;
  flex: none;
}
.fbadge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  min-width: 86px;
}
.fb-img {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  object-fit: cover;
  flex: none;
}
.fb-cb {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex: none;
  margin: 0 3px;
}
.fb-name {
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 64px;
}
.ftitle {
  flex: 1;
  min-width: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fmeta {
  font-size: 11px;
  color: var(--text-muted);
  flex: none;
  max-width: 80px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ============ 右栏：笔记本行 ============ */
.nrow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 16px;
  border-bottom: 1px solid var(--divider);
  text-decoration: none;
  color: inherit;
}
.nrow:last-child {
  border-bottom: 0;
}
.nrow:hover {
  background: var(--bg-panel-2);
}
.nrow-img {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  overflow: hidden;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.nrow-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.nrow-cb {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.nrow-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nrow-meta {
  font-size: 11px;
  color: var(--text-muted);
  flex: none;
}
.nrow-meta b {
  color: var(--text-secondary);
  font-family: var(--font-mono);
}

/* ============ 右栏：目标行 ============ */
.orow {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px;
  border-bottom: 1px solid var(--divider);
  text-decoration: none;
  color: inherit;
}
.orow:last-child {
  border-bottom: 0;
}
.orow:hover {
  background: var(--bg-panel-2);
}
.orow-av {
  width: 18px;
  height: 18px;
  border-radius: 5px;
  overflow: hidden;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.orow-av img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.orow-cb {
  width: 100%;
  height: 100%;
}
.orow-title {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.orow-bar {
  width: 64px;
  height: 5px;
  background: var(--divider);
  border-radius: 3px;
  flex: none;
  overflow: hidden;
}
.orow-bar i {
  display: block;
  height: 100%;
  background: var(--accent);
  border-radius: 3px;
}
.orow-pct {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-secondary);
  width: 32px;
  text-align: right;
  flex: none;
}
.orow-period {
  font-size: 10.5px;
  color: var(--text-muted);
  flex: none;
}

/* ============ 右栏：台账行 ============ */
.lrow {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px;
  border-bottom: 1px solid var(--divider);
  text-decoration: none;
  color: inherit;
}
.lrow:last-child {
  border-bottom: 0;
}
.lrow:hover {
  background: var(--bg-panel-2);
}
.ltime {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-muted);
  width: 86px;
  flex: none;
}
.lbadge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  flex: none;
  white-space: nowrap;
}
.lb-p {
  background: color-mix(in srgb, var(--tone-blue) 12%, var(--bg-panel));
  color: var(--tone-blue);
}
.lb-d {
  background: color-mix(in srgb, var(--tone-violet) 12%, var(--bg-panel));
  color: var(--tone-violet);
}
.lb-o {
  background: color-mix(in srgb, var(--tone-teal) 12%, var(--bg-panel));
  color: var(--tone-teal);
}
.lb-r {
  background: color-mix(in srgb, var(--tone-amber) 14%, var(--bg-panel));
  color: var(--tone-amber);
}
.lb-g {
  background: color-mix(in srgb, var(--tone-green) 12%, var(--bg-panel));
  color: var(--tone-green);
}
.lb-n {
  background: var(--bg-panel-2);
  color: var(--text-secondary);
}
.lag {
  font-size: 11px;
  color: var(--text-secondary);
  flex: none;
}
.ltitle {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ============ 骨架 / 空态 ============ */
.skel-row {
  height: 36px;
  margin: 0 0 0;
  border-bottom: 1px solid var(--divider);
  background: linear-gradient(90deg, var(--bg-panel) 0%, var(--bg-panel-2) 50%, var(--bg-panel) 100%);
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
}
.wcard.is-skel {
  height: 64px;
  border: 1px solid var(--border);
  background: linear-gradient(90deg, var(--bg-panel) 0%, var(--bg-panel-2) 50%, var(--bg-panel) 100%);
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
  padding: 32px 16px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

/* ============ 断点：≤1024 单栏 / ≤560 瘦身 ============ */
@media (max-width: 1024px) {
  .main {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 560px) {
  .top {
    gap: 10px;
  }
  .kpi {
    gap: 14px;
  }
  .kpi b {
    font-size: 14px;
  }
  .ftime {
    width: 84px;
  }
  .fmeta,
  .lag {
    display: none;
  }
  .fbadge {
    min-width: 0;
  }
}
</style>
