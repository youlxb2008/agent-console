<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  portalApi,
  type PortalObjective,
  type PortalAgent,
} from "@/api/portal";

/**
 * 目标聚合视图（/objectives）
 * - 全部 agent 私域目标聚合：按 agent 分组的目标卡片列表
 * - 数据：GET /api/portal/agents（启用 agent）→ 逐 agent GET objectivesByAgent
 *   （agent 数量少，前端聚合即可；后端无全局聚合接口，保持只读契约不动）
 * - 点击卡片 → /agents/{agentId}?tab=objectives（进详情页目标 Tab）
 * - 视觉延续 AgentDetailView 目标卡片（状态 chip / 周期 / 截止 / 进度条）+ 门户质感 token
 */
const router = useRouter();

const loading = ref(true);
/** agent 分组聚合结果（仅保留有目标的 agent 组） */
const groups = ref<{ agent: PortalAgent; items: PortalObjective[] }[]>([]);

/** 全局统计：进行中 / 已完成 / 总数 */
const stat = computed(() => {
  const all = groups.value.flatMap((g) => g.items);
  return {
    total: all.length,
    doing: all.filter((o) => o.status === 1).length,
    done: all.filter((o) => o.status === 2).length,
  };
});

/** 状态文案（契约：0未开始 1进行中 2已完成 3已放弃） */
function statusText(s: number): string {
  return ["未开始", "进行中", "已完成", "已放弃"][s] ?? "未知";
}
/** 状态 chip 样式类 */
function statusClass(s: number): string {
  return ["is-todo", "is-doing", "is-done", "is-drop"][s] ?? "is-todo";
}

/** agent 头像色块底色：5 色 token 循环（与列表页同一算法） */
const AVATAR_COLORS = ["#5b8def", "#2eb872", "#e6a23c", "#f56c6c", "#9a6ef5"];
function avatarColor(id: number): string {
  return AVATAR_COLORS[Math.abs(id) % AVATAR_COLORS.length];
}
/** 头像首字符（displayName 优先） */
function avatarChar(a: PortalAgent): string {
  return (a.displayName || a.notebookName || a.name || "?").slice(0, 1);
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const agents = await portalApi.agents();
    // 并发拉取各 agent 目标；单 agent 失败不阻塞整体（该组显示为空）
    const results = await Promise.all(
      agents.map(async (a) => {
        try {
          return { agent: a, items: await portalApi.objectivesByAgent(a.id) };
        } catch {
          return { agent: a, items: [] as PortalObjective[] };
        }
      }),
    );
    groups.value = results.filter((g) => g.items.length > 0);
  } finally {
    loading.value = false;
  }
}

function openAgent(agentId: number): void {
  void router.push({ path: `/agents/${agentId}`, query: { tab: "objectives" } });
}

onMounted(() => {
  void load();
});
</script>

<template>
  <div class="obj-page">
    <!-- 顶部：标题 + 统计 -->
    <div class="obj-head">
      <div class="head-main">
        <h1 class="head-title">目标</h1>
        <span class="head-sub">全部 agent 私域目标（聚合）</span>
      </div>
      <div v-if="!loading && stat.total" class="obj-stat-bar">
        <span class="stat-pill">共 {{ stat.total }}</span>
        <span class="stat-pill">进行中 {{ stat.doing }}</span>
        <span class="stat-pill stat-pill--done">已完成 {{ stat.done }}</span>
      </div>
    </div>

    <!-- 骨架 -->
    <div v-if="loading" class="skeleton-stack">
      <div v-for="i in 3" :key="i" class="skeleton-card" />
    </div>

    <!-- 分组列表 -->
    <template v-else-if="groups.length">
      <section v-for="g in groups" :key="g.agent.id" class="obj-group">
        <button type="button" class="group-head" @click="openAgent(g.agent.id)">
          <span
            class="group-avatar"
            :style="{ background: avatarColor(g.agent.id) }"
            aria-hidden="true"
            >{{ avatarChar(g.agent) }}</span
          >
          <span class="group-name">{{ g.agent.displayName || g.agent.notebookName }}</span>
          <span class="group-count">{{ g.items.length }} 个目标</span>
          <span class="group-go" aria-hidden="true">→</span>
        </button>

        <div class="group-body">
          <button
            v-for="o in g.items"
            :key="o.oid"
            type="button"
            class="obj-card"
            @click="openAgent(g.agent.id)"
          >
            <div class="card-top">
              <span class="card-title">{{ o.title }}</span>
              <span class="status-chip" :class="statusClass(o.status)">{{
                statusText(o.status)
              }}</span>
            </div>
            <div class="card-meta">
              <span v-if="o.period" class="meta-item">周期 {{ o.period }}</span>
              <span v-if="o.targetDate" class="meta-item">截止 {{ o.targetDate }}</span>
              <span v-if="!o.period && !o.targetDate" class="meta-item muted">无周期约束</span>
            </div>
            <div class="card-progress">
              <div class="progress-track">
                <div
                  class="progress-fill"
                  :class="{ 'is-done': o.status === 2 }"
                  :style="{ width: `${o.progress ?? 0}%` }"
                />
              </div>
              <span class="progress-num">{{ o.progress ?? 0 }}%</span>
            </div>
          </button>
        </div>
      </section>
    </template>

    <div v-else class="empty">暂无目标</div>
  </div>
</template>

<style scoped>
.obj-page {
  max-width: var(--container-max);
  margin: 0 auto;
  padding: 0 var(--container-pad) 40px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}

/* ============ 顶部 ============ */
.obj-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 0 8px;
  flex-wrap: wrap;
}
.head-main {
  flex: 1;
}
.head-title {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  color: var(--text-main);
}
.head-sub {
  font-size: 13px;
  color: var(--text-muted);
}
.obj-stat-bar {
  display: flex;
  gap: 8px;
}
.stat-pill {
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--accent-soft);
  border-radius: 999px;
  padding: 3px 10px;
  line-height: 20px;
}
.stat-pill--done {
  background: rgba(46, 184, 114, 0.14);
  color: #2eb872;
}

/* ============ agent 分组 ============ */
.obj-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  border: none;
  background: transparent;
  padding: 6px 4px;
  cursor: pointer;
  text-align: left;
  border-radius: 8px;
  transition: background 0.15s ease;
}
.group-head:hover {
  background: var(--bg-panel-2);
}
.group-avatar {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.group-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
}
.group-count {
  font-size: 12px;
  color: var(--text-muted);
}
.group-go {
  margin-left: auto;
  color: var(--text-muted);
  font-size: 14px;
  transition: color 0.15s ease, transform 0.15s ease;
}
.group-head:hover .group-go {
  color: var(--accent);
  transform: translateX(2px);
}
.group-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-left: 8px;
}

/* ============ 目标卡片（与 AgentDetailView 同视觉） ============ */
.obj-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  text-align: left;
  padding: 12px 14px;
  border: none;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s ease;
}
.obj-card:hover {
  background: var(--bg-panel-2);
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 窄屏：单行 ellipsis 的 min-content 仍会把 flex 行撑超视口，改两行折行截断 */
@media (max-width: 640px) {
  .card-title {
    white-space: normal;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }
}
.card-meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.meta-item {
  font-size: 12px;
  color: var(--text-muted);
}
.meta-item.muted {
  opacity: 0.7;
}

/* 状态 chip：4 态配色 */
.status-chip {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 999px;
  line-height: 18px;
  white-space: nowrap;
}
.status-chip.is-todo {
  color: #909399;
  background: rgba(144, 147, 153, 0.14);
}
.status-chip.is-doing {
  color: #409eff;
  background: rgba(64, 158, 255, 0.14);
}
.status-chip.is-done {
  color: #2eb872;
  background: rgba(46, 184, 114, 0.14);
}
.status-chip.is-drop {
  color: #f56c6c;
  background: rgba(245, 108, 108, 0.14);
}

/* 进度条 */
.card-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}
.progress-track {
  flex: 1;
  height: 5px;
  border-radius: 999px;
  background: var(--bg-panel-2);
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
  transition: width 0.3s ease;
}
.progress-fill.is-done {
  background: #2eb872;
}
.progress-num {
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  min-width: 34px;
  text-align: right;
}

.empty {
  padding: 60px 0;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
}
</style>
