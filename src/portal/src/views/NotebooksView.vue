<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { notebooks, loadNotebooks, notebooksLoading } from '@/store/notebook'
import type { PortalNotebook } from '@/api/portal'

/**
 * 笔记本承接页（/notebooks）
 * - 顶部导航「笔记本」的专属落地页：全部启用笔记本的卡片网格
 * - 卡片缩小版：6 列密度网格（minmax(212px,1fr)），封面 + 名称 + 两行简介 + 单行卡脚
 * - 时间一律绝对式 MM-DD HH:mm:ss（v4.16 界面时间硬规则：必须带日期带秒）
 * - 点击进入笔记页 /notebooks/{id}（NotesView：目录树 + 笔记正文）
 * - hover 语言与智能体值守面板（AgentsView）一致：中性灰描边浮起，弱强调
 */
const router = useRouter()

/** 加载中（跟随全局 store，首屏为 true） */
const loading = computed<boolean>(() => notebooksLoading.value)

/** 汇总统计：笔记总数与协作者总数（页头右侧展示） */
const stat = computed(() => {
  const notes = notebooks.value.reduce((sum, n) => sum + (n.noteCount || 0), 0)
  const collaborators = notebooks.value.reduce((sum, n) => sum + (n.agentCount || 0), 0)
  return { notes, collaborators }
})

/** 界面时间：yyyy-MM-dd HH:mm:ss → MM-DD HH:mm:ss（slice(5) 保留秒，勿截到 16） */
function fmtTime(t: string | null): string {
  return t ? t.slice(5) : ''
}

/** 封面兜底渐变：5 色 token 循环，按 notebook.id 取模稳定取色；225° 渐变让首字符落在左下饱和深色区，白字对比度不丢 */
function coverTone(id: number): string {
  const tones = ['var(--tone-blue)', 'var(--tone-teal)', 'var(--tone-green)', 'var(--tone-amber)', 'var(--tone-violet)']
  const c = tones[id % tones.length]
  return `linear-gradient(225deg, color-mix(in srgb, ${c} 72%, #ffffff), ${c})`
}

/** 封面首字符（无 cover 图时兜底） */
function coverText(n: PortalNotebook): string {
  return (n.name || '?').slice(0, 1)
}

/** 打开笔记本：/notebooks/{id}（M3.8 RESTful 路径） */
function openNotebook(n: PortalNotebook): void {
  void router.push(`/notebooks/${n.id}`)
}

onMounted(() => {
  // 列表走全局 store（幂等：若顶栏/首页已加载过则复用，不重复请求）
  void loadNotebooks()
})
</script>

<template>
  <div class="nb-page">
    <!-- 页头：标题 + 副标题 + 汇总统计 -->
    <div class="page-head">
      <div class="head-main">
        <h1 class="head-title">笔记本</h1>
        <span class="head-sub">团队知识沉淀，按笔记本归集</span>
      </div>
      <div v-if="notebooks.length" class="head-stats">
        <span class="head-chip"><b>{{ notebooks.length }}</b> 本</span>
        <span class="head-chip"><b>{{ stat.notes }}</b> 篇笔记</span>
        <span class="head-chip"><b>{{ stat.collaborators }}</b> 位协作者</span>
      </div>
    </div>

    <!-- 卡片网格 -->
    <div class="grid-wrap">
      <!-- 加载骨架：3 张占位卡 -->
      <div v-if="loading && !notebooks.length" class="nb-grid">
        <div v-for="i in 3" :key="i" class="nb-card skeleton-card" />
      </div>

      <div v-else-if="notebooks.length" class="nb-grid">
        <div
          v-for="n in notebooks"
          :key="n.id"
          class="nb-card"
          role="button"
          tabindex="0"
          @click="openNotebook(n)"
          @keydown.enter="openNotebook(n)"
        >
          <!-- 卡片头：封面（cover 真实图优先，无图回退渐变色块 + 首字符） -->
          <div class="card-cover">
            <img v-if="n.cover" :src="n.cover" class="cover-img" :alt="n.name" />
            <div v-else class="cover-block" :style="{ background: coverTone(n.id) }">{{ coverText(n) }}</div>
          </div>

          <div class="card-body">
            <div class="card-name">{{ n.name }}</div>
            <div v-if="n.description" class="card-desc">{{ n.description }}</div>
            <div v-else class="card-desc card-desc--empty">暂无简介</div>
          </div>

          <!-- 卡片脚：单行，统计在左，右侧时间/进入提示 hover 交换 -->
          <div class="card-foot">
            <span class="foot-stat"><b>{{ n.noteCount }}</b> 篇 · <b>{{ n.agentCount }}</b> 位</span>
            <span class="foot-swap">
              <span class="foot-time">{{ n.lastNoteAt ? fmtTime(n.lastNoteAt) : '暂无动态' }}</span>
              <span class="foot-go">查看笔记 →</span>
            </span>
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <div v-else-if="!loading" class="empty">暂无笔记本</div>
    </div>
  </div>
</template>

<style scoped>
/* ============ 页面容器：与门户其他页同一宽度基准 ============ */
.nb-page {
  max-width: var(--container-max);
  margin: 0 auto;
  padding: 0 var(--container-pad) 40px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* ============ 页头：标题 + 汇总 chip ============ */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 0 4px;
  flex-wrap: wrap;
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
.head-stats {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.head-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg-panel);
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.head-chip b {
  font-size: 15px;
  color: var(--accent);
  font-family: var(--font-mono);
}

/* ============ 卡片网格：缩小版 6 列密度（212px 基准）============ */
.nb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(212px, 1fr));
  gap: 14px;
}
@media (max-width: 1180px) {
  .nb-grid {
    grid-template-columns: repeat(auto-fill, minmax(196px, 1fr));
  }
}
@media (max-width: 640px) {
  .nb-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.nb-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  box-shadow: var(--tx-panel-shadow);
  cursor: pointer;
  overflow: hidden;
  transition: border-color 0.16s ease, transform 0.16s ease, box-shadow 0.16s ease;
}
.nb-card:hover {
  border-color: var(--border-strong);
  transform: translateY(-2px);
  box-shadow: var(--tx-panel-shadow), 0 8px 20px rgba(16, 24, 40, 0.1);
}
.nb-card:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

/* 卡片头：封面区（16:9 完整展示，contain 图或首字符色块） */
.card-cover {
  aspect-ratio: 16 / 9;
  position: relative;
  overflow: hidden;
  background: var(--bg-panel-2);
}
.cover-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}
.cover-block {
  width: 100%;
  height: 100%;
  /* 首字符右下沉降，避免机械居中的呆板感 */
  display: flex;
  align-items: flex-end;
  justify-content: flex-start;
  padding: 10px 14px;
  color: #fff;
  font-size: 30px;
  font-weight: 800;
}

/* 卡片主体：名称 + 简介（两行定高防跳动） */
.card-body {
  flex: 1;
  padding: 10px 12px 8px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.card-name {
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.2px;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.card-desc {
  font-size: 11.5px;
  color: var(--text-secondary);
  line-height: 1.55;
  /* 两行截断 + 定高，防无简介卡跳变 */
  min-height: 35px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-desc--empty {
  color: var(--divider);
}

/* 卡片脚：单行（统计 + 时间/进入提示 hover 交换） */
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px 10px;
  border-top: 1px solid var(--divider);
}
.foot-stat {
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
}
.foot-stat b {
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 700;
}
.foot-swap {
  position: relative;
  display: block;
  height: 15px;
}
.foot-time {
  position: absolute;
  right: 0;
  top: 0;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-muted);
  white-space: nowrap;
  transition: opacity 0.14s ease;
}
.foot-go {
  position: absolute;
  right: 0;
  top: 0;
  font-size: 11px;
  color: var(--accent);
  white-space: nowrap;
  opacity: 0;
  transition: opacity 0.14s ease;
}
/* 仅真实悬停设备做时间/提示交换（触屏不抢点击） */
@media (hover: hover) {
  .nb-card:hover .foot-time {
    opacity: 0;
  }
  .nb-card:hover .foot-go {
    opacity: 1;
  }
}

/* ============ 骨架 / 空态 ============ */
.skeleton-card {
  min-height: 205px;
  background: linear-gradient(90deg, var(--bg-panel) 0%, var(--bg-panel-2) 50%, var(--bg-panel) 100%);
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
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
</style>
