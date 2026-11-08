<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  portalApi,
  type PortalLedger,
  type PortalLedgerType,
  type PortalAgent,
} from "@/api/portal";
import { loadNotebooks, notebooks } from "@/store/notebook";
import { renderMarkdown } from "@/composables/useMarkdown";

/**
 * 台账视图（M4.2：/ledger 聚合）
 * - agent 私域流水时间线：日期分组 + 类型色块 + title + 关联跳转
 * - 点击条目展开正文（手风琴：ledgerDetail 详情接口读 body，md 安全渲染）
 * - 聚合模式顶部 agent 切换器（agents() 取启用 agent 列表），点击切到该 agent 详情页台账 Tab
 * - 延续门户暗色风格 + 质感 token（零依赖 Element Plus，自写样式）
 */
const route = useRoute();
const router = useRouter();

const loading = ref(true);
const list = ref<PortalLedger[]>([]);

/* ===== 滚动分页加载（每页 20 条，IntersectionObserver 触发追加） ===== */
const PAGE_SIZE = 20;
/** 服务端总条数（角标/加载完成提示用） */
const total = ref(0);
/** 已加载页码（0 = 未加载） */
let page = 0;
const hasMore = ref(false);
const loadingMore = ref(false);
let scrollObserver: IntersectionObserver | null = null;

/** 是否为聚合模式（/ledger） */
const isAggregate = computed<boolean>(() => route.path === "/ledger");

const agentId = computed<string>(() =>
  isAggregate.value
    ? ""
    : typeof route.params.agentId === "string"
      ? route.params.agentId
      : "",
);

/** 单 agent 模式：当前 agent 展示名（取 notebooks 里匹配 id，回退空） */
const agentLabel = computed<string>(() => {
  const nb = notebooks.value.find((n) => String(n.id) === agentId.value);
  return nb?.name || "";
});

/* ===== 聚合模式：agent 切换器 ===== */
const agents = ref<PortalAgent[]>([]);

/* ===== 类型色块（契约 §1.4 色表） ===== */
const typeMeta: Record<
  PortalLedgerType,
  { label: string; color: string; soft: string }
> = {
  progress: {
    label: "任务推进",
    color: "#2eb872",
    soft: "rgba(46, 184, 114, 0.14)",
  },
  observation: {
    label: "巡检发现",
    color: "#e6a23c",
    soft: "rgba(230, 162, 60, 0.14)",
  },
  decision: {
    label: "拍板决策",
    color: "#9254de",
    soft: "rgba(146, 84, 222, 0.14)",
  },
  memo: { label: "备忘", color: "#409eff", soft: "rgba(64, 158, 255, 0.14)" },
  session: {
    label: "会话小结",
    color: "#909399",
    soft: "rgba(144, 147, 153, 0.14)",
  },
};

function typeInfo(type: PortalLedgerType): {
  label: string;
  color: string;
  soft: string;
} {
  return typeMeta[type] || typeMeta.session;
}

/** 按 yyyy-MM-dd 分组（倒序），组内时间倒序 */
interface LedgerGroup {
  date: string;
  items: PortalLedger[];
}
const groupedList = computed<LedgerGroup[]>(() => {
  const groups = new Map<string, PortalLedger[]>();
  for (const it of list.value) {
    const date = String(it.createTime || "").slice(0, 10) || "未知日期";
    const arr = groups.get(date) || [];
    arr.push(it);
    groups.set(date, arr);
  }
  return [...groups.entries()]
    .map(([date, items]) => ({
      date,
      items: items.sort((a, b) =>
        String(b.createTime).localeCompare(String(a.createTime)),
      ),
    }))
    .sort((a, b) => b.date.localeCompare(a.date));
});

function formatTime(timeStr: string): string {
  const s = String(timeStr || "");
  return s.length >= 19 ? s.slice(11, 19) : s;
}

/** 关联跳转：refObjectiveId 有值 → 跳该 agent 详情页目标 Tab（旧 /agents/{id}/objectives 已 redirect） */
function refTarget(
  it: PortalLedger,
  agentName?: string,
): { path: string } | null {
  if (it.refObjectiveId && (it.agentId || agentId.value)) {
    return { path: `/agents/${it.agentId ?? agentId.value}?tab=objectives` };
  }
  return null;
}

function goAggregate(): void {
  void router.push("/ledger");
}

/* ===== 台账正文展开（手风琴：点击条目请求详情并展开，再点收起；与 AgentDetailView 同一交互） ===== */
/** 当前展开的台账 lid（单开模式） */
const expandedLid = ref("");
/** 详情正文缓存：lid → 渲染后 HTML（null=无正文） */
const bodyCache = ref(new Map<string, string | null>());
/** 加载中的 lid 集合（渲染骨架提示） */
const loadingLids = ref(new Set<string>());

/** 点击台账条目：切换展开/收起；首次展开时请求详情接口并缓存正文 */
async function toggleLedger(lid: string): Promise<void> {
  // 已展开 → 收起
  if (expandedLid.value === lid) {
    expandedLid.value = "";
    return;
  }
  expandedLid.value = lid;
  // 缓存未命中 → 请求详情（body 为 markdown，安全渲染后缓存 HTML）
  if (!bodyCache.value.has(lid)) {
    loadingLids.value.add(lid);
    try {
      const detail = await portalApi.ledgerDetail(lid);
      bodyCache.value.set(
        lid,
        detail?.body ? renderMarkdown(detail.body) : null,
      );
    } catch {
      bodyCache.value.set(lid, null);
    } finally {
      loadingLids.value.delete(lid);
    }
  }
}

/** 取某条台账的正文 HTML（未加载返回 undefined） */
function bodyHtml(lid: string): string | null | undefined {
  return bodyCache.value.get(lid);
}

async function loadList(): Promise<void> {
  loading.value = true;
  page = 0;
  hasMore.value = false;
  try {
    const res = isAggregate.value
      ? await portalApi.ledgerPageAggregate({ page: 1, size: PAGE_SIZE })
      : await portalApi.ledgerPageByAgent({
          agentId: agentId.value,
          page: 1,
          size: PAGE_SIZE,
        });
    list.value = res?.list || [];
    total.value = res?.count || 0;
    hasMore.value = list.value.length < total.value;
  } catch {
    list.value = [];
    total.value = 0;
    hasMore.value = false;
  } finally {
    loading.value = false;
  }
}

/** 追加下一页（哨兵进入视口触发，幂等） */
async function loadMore(): Promise<void> {
  if (loading.value || loadingMore.value || !hasMore.value) return;
  loadingMore.value = true;
  try {
    const res = isAggregate.value
      ? await portalApi.ledgerPageAggregate({ page: page + 1, size: PAGE_SIZE })
      : await portalApi.ledgerPageByAgent({
          agentId: agentId.value,
          page: page + 1,
          size: PAGE_SIZE,
        });
    list.value = [...list.value, ...(res?.list || [])];
    page += 1;
    total.value = res?.count || total.value;
    hasMore.value = list.value.length < total.value;
  } catch {
    hasMore.value = false;
  } finally {
    loadingMore.value = false;
    rearmSentinel();
  }
}

/** 哨兵函数 ref：v-if 重建元素后自动重新 observe */
let sentinelEl: Element | null = null;
function setSentinel(el: unknown): void {
  sentinelEl = el instanceof Element ? el : null;
  if (el instanceof Element && scrollObserver) {
    scrollObserver.observe(el);
  }
}

/** 加载结束后重新注册哨兵：哨兵若持续停留在视口内（相交状态未变化），observer 不会再次回调，unobserve + observe 强制重评以续载下一页 */
function rearmSentinel(): void {
  if (sentinelEl instanceof Element && scrollObserver) {
    scrollObserver.unobserve(sentinelEl);
    scrollObserver.observe(sentinelEl);
  }
}

onMounted(() => {
  // 页面级滚动：哨兵进入视口（预留 240px 缓冲）即加载下一页
  scrollObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) void loadMore();
      }
    },
    { rootMargin: "240px 0px" },
  );
  void loadNotebooks();
  if (isAggregate.value) {
    // 聚合模式：加载 agent 切换器
    void portalApi
      .agents()
      .then((res) => {
        agents.value = res || [];
      })
      .catch(() => {
        agents.value = [];
      });
  }
  void loadList();
});

onBeforeUnmount(() => {
  scrollObserver?.disconnect();
  scrollObserver = null;
});

watch(
  () => [route.path, route.params.agentId] as const,
  () => {
    void loadList();
  },
);
</script>

<template>
  <div class="led-page">
    <!-- 顶部：标题 + 聚合切换（按用户要求去除返回按钮） -->
    <div class="led-head">
      <div class="head-main">
        <h1 class="head-title">台账</h1>
        <span class="head-sub">
          <template v-if="isAggregate">全部 agent 私域流水（聚合）</template>
          <template v-else>{{ agentLabel || agentId }} · 私域流水</template>
        </span>
      </div>
      <button
        v-if="!isAggregate"
        class="agg-btn"
        type="button"
        @click="goAggregate"
      >
        查看全部台账
      </button>
    </div>

    <!-- 聚合模式：agent 切换器（跳转各 agent 详情页台账 Tab） -->
    <div v-if="isAggregate" class="led-switcher">
      <button
        v-for="a in agents"
        :key="a.id"
        type="button"
        class="switcher-chip"
        @click="router.push(`/agents/${a.id}?tab=ledger`)"
      >
        {{ a.displayName || a.name }}
      </button>
      <span v-if="!agents.length" class="switcher-empty">暂无 agent</span>
    </div>

    <!-- 时间线流 -->
    <div class="led-stream">
      <div v-if="loading" class="skeleton-stack">
        <div v-for="i in 3" :key="i" class="skeleton-card" />
      </div>

      <template v-else-if="list.length">
        <div v-for="group in groupedList" :key="group.date" class="led-day">
          <div class="led-day-head">
            <span class="led-day-date">{{ group.date }}</span>
            <span class="led-day-count">{{ group.items.length }} 条</span>
          </div>
          <div class="led-day-body">
            <div v-for="it in group.items" :key="it.lid" class="led-item-wrap">
              <!-- 条目行：点击展开/收起正文（手风琴） -->
              <div
                class="led-item"
                :class="{ 'is-expanded': expandedLid === it.lid }"
                role="button"
                tabindex="0"
                @click="toggleLedger(it.lid)"
                @keydown.enter="toggleLedger(it.lid)"
              >
                <span
                  class="type-dot"
                  :style="{ background: typeInfo(it.type).color }"
                />
                <div class="led-item-main">
                  <div class="led-item-title-row">
                    <span class="led-item-title">{{ it.title }}</span>
                    <span
                      class="type-badge"
                      :style="{
                        color: typeInfo(it.type).color,
                        background: typeInfo(it.type).soft,
                      }"
                      >{{ typeInfo(it.type).label }}</span
                    >
                  </div>
                  <div class="led-item-meta">
                    <span v-if="isAggregate" class="meta-agent">{{
                      it.agentName || "—"
                    }}</span>
                    <span class="meta-time">{{
                      formatTime(it.createTime)
                    }}</span>
                    <span v-if="it.refTaskId" class="ref-tag"
                      >任务 {{ it.refTaskId }}</span
                    >
                    <span v-if="it.refRequirementId" class="ref-tag"
                      >需求 {{ it.refRequirementId }}</span
                    >
                    <a
                      v-if="refTarget(it)"
                      class="ref-tag ref-link"
                      :href="refTarget(it)!.path"
                      @click.prevent="router.push(refTarget(it)!.path)"
                      >目标 {{ it.refObjectiveId }} →</a
                    >
                    <span v-else-if="it.refObjectiveId" class="ref-tag"
                      >目标 {{ it.refObjectiveId }}</span
                    >
                    <span v-if="it.refNoteNid" class="ref-tag"
                      >笔记 {{ it.refNoteNid }}</span
                    >
                  </div>
                </div>
                <!-- 展开指示箭头：展开时旋转 90° -->
                <span class="expand-caret" aria-hidden="true">›</span>
              </div>

              <!-- 展开区：正文（markdown 渲染）；加载中显示提示 -->
              <div v-if="expandedLid === it.lid" class="led-body">
                <div v-if="loadingLids.has(it.lid)" class="led-body-loading">
                  加载中…
                </div>
                <div
                  v-else-if="bodyHtml(it.lid)"
                  class="led-body-md"
                  v-html="bodyHtml(it.lid)"
                />
                <div v-else class="led-body-empty">（无正文，仅索引记录）</div>
              </div>
            </div>
          </div>
        </div>
      </template>

      <div v-else class="empty">暂无台账流水</div>

      <!-- 滚动加载哨兵 -->
      <div v-if="!loading && list.length" :ref="setSentinel" class="load-more">
        <span v-if="loadingMore" class="lm-loading"
          ><span class="lm-spinner" />加载中…</span
        >
        <span v-else-if="hasMore" class="lm-hint">滚动加载更多</span>
        <span v-else class="lm-done">已加载全部 {{ total }} 条</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.led-page {
  max-width: var(--container-max);
  margin: 0 auto;
  padding: 0 var(--container-pad) 40px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ============ 顶部 ============ */
.led-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 0 8px;
  flex-wrap: wrap;
}
.back-btn,
.agg-btn {
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.back-btn:hover,
.agg-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}
.head-main {
  flex: 1;
}
.head-title {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  color: var(--text-main);
  letter-spacing: -0.5px;
}
.head-sub {
  font-size: 13px;
  color: var(--text-muted);
}

/* ============ 聚合模式 agent 切换器 ============ */
.led-switcher {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.switcher-chip {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.switcher-chip:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}
.switcher-empty {
  color: var(--text-muted);
  font-size: 13px;
}

/* ============ 时间线流 ============ */
.led-stream {
  min-height: 200px;
}
.led-day {
  margin-bottom: 18px;
}
.led-day-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}
.led-day-date {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
  font-family: var(--font-mono);
}
.led-day-count {
  font-size: 12px;
  color: var(--text-muted);
}
.led-day-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
/* 条目外壳：条目行 + 展开区整体圆角包裹（展开时视觉成组） */
.led-item-wrap {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  overflow: hidden;
}
.led-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.led-item:hover,
.led-item:focus-visible {
  background: var(--bg-panel-2);
  outline: none;
}
/* 展开态：条目行底色微亮，与正文区形成层次 */
.led-item.is-expanded {
  background: var(--bg-panel-2);
  border-bottom: 1px solid var(--border);
}
.type-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 5px;
}
.led-item-main {
  flex: 1;
  min-width: 0;
}
.led-item-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.led-item-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
}
.type-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}
.led-item-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 6px;
}
.meta-agent {
  font-size: 12px;
  color: var(--text-secondary);
}
.meta-time {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}
.ref-tag {
  display: inline-block;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 20px;
  background: var(--bg-panel-2);
  color: var(--text-secondary);
  border: 1px solid var(--border);
  text-decoration: none;
}
.ref-link {
  color: var(--accent);
  border-color: var(--accent-soft);
  cursor: pointer;
}
.ref-link:hover {
  background: var(--accent-soft);
}

/* 展开指示箭头：默认右向 ›，展开时旋转 90° 向下 */
.expand-caret {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 16px;
  line-height: 1;
  margin-top: 2px;
  transition: transform 0.2s ease;
}
.led-item.is-expanded .expand-caret {
  transform: rotate(90deg);
  color: var(--accent);
}

/* 展开区：正文（markdown 渲染） */
.led-body {
  padding: 4px 16px 16px 40px;
  border-top: 1px dashed var(--border);
  animation: led-expand 0.2s ease;
}
@keyframes led-expand {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
.led-body-loading {
  padding: 16px 0;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}
.led-body-md {
  font-size: 13px;
  color: var(--text-main);
  line-height: 1.7;
}
.led-body-empty {
  padding: 12px 0;
  color: var(--text-muted);
  font-size: 12px;
}

/* ============ 骨架 / 空态 ============ */
.skeleton-stack {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.skeleton-card {
  height: 72px;
  border-radius: var(--radius-md);
  background: linear-gradient(
    90deg,
    var(--bg-panel) 0%,
    var(--bg-panel-2) 50%,
    var(--bg-panel) 100%
  );
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
  border: 1px solid var(--border);
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
  padding: 40px;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
  background: var(--bg-panel);
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
}

/* ============ 滚动加载哨兵 ============ */
.load-more {
  padding: 18px 0 6px;
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
}
.lm-hint {
  opacity: 0.75;
}
.lm-done {
  opacity: 0.6;
}
.lm-loading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.lm-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border);
  border-top-color: var(--accent, #409eff);
  border-radius: 50%;
  animation: lm-spin 0.8s linear infinite;
}
@keyframes lm-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
