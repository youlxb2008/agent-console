<script setup lang="ts">
import {
  ref,
  computed,
  onMounted,
  onBeforeUnmount,
  watch,
  nextTick,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { renderMarkdown, runMermaid } from "@/composables/useMarkdown";
import {
  portalApi,
  type PortalAgent,
  type PortalObjective,
  type PortalObjectiveDetail,
  type PortalLedger,
  type PortalLedgerType,
  type PortalMemory,
  type PortalNotebook,
  type PortalStatusCounts,
  type PortalTodo,
  type PortalActivity,
  type PortalActivitySession,
  type PortalActivityCurrent,
  type PortalActivityDailyStat,
} from "@/api/portal";
import { loadNotebooks, notebooks } from "@/store/notebook";
/**
 * 智能体详情页（/agents/{agentId}）
 * - 头部：头像色块 + 展示名 + 名称 + 统计（笔记/协作者/最近更新）
 * - Tab 六区（query 参数 ?tab=objectives|ledger|notebooks|todos|activity|memory 可深链）：
 *   - 目标：左侧目标卡片列表（进度条/状态/周期/截止）+ 右侧详情（md 安全渲染 + mermaid + 子项）
 *   - 台账：日期分组时间线（类型色块 + 标题 + 关联引用）
 *   - 待办：状态筛选 + 流式分页列表（M5.2）
 *   - 活动：实时活动流时间线（M6.1，10s 轮询）
 *   - 记忆：活跃记忆流式列表（M7.1-M3，memType 徽标 + md 正文，仅 status=1）
 *   - 笔记本：该 agent 名下已关联笔记本卡片网格（noteCount/最近更新；点击跳 /notebooks/{id}）
 * - 替代旧 /agents/{id}/objectives 与 /agents/{id}/ledger 两个独立页（旧路由 redirect 到本页）
 */
const route = useRoute();
const router = useRouter();
/* ===== agent 基础信息 ===== */
const agent = ref<PortalAgent | null>(null);
/** 流式加载哨兵观察器（目标/台账共用，按 dataset.action 分发翻页） */
let scrollObserver: IntersectionObserver | null = null;
const agentLoading = ref(true);
const agentId = computed<string>(() =>
  typeof route.params.agentId === "string" ? route.params.agentId : "",
);
/** 当前 agent 展示名（接口返回优先，回退 notebooks 匹配） */
const agentDisplayName = computed<string>(() => {
  if (agent.value) return agent.value.displayName || agent.value.name;
  const nb = notebooks.value.find((n) => String(n.id) === agentId.value);
  return nb?.name || agentId.value;
});
/** 头像兜底渐变：5 色 token 循环（与列表页同一算法）；color-mix 调浅做 135° 柔和对角渐变 */
function avatarBg(id: number | string): string {
  const n = Number(id) || 0;
  const tones = [
    "var(--tone-blue)",
    "var(--tone-teal)",
    "var(--tone-green)",
    "var(--tone-amber)",
    "var(--tone-violet)",
  ];
  const c = tones[n % tones.length];
  return `linear-gradient(135deg, color-mix(in srgb, ${c} 72%, #ffffff), ${c})`;
}
/** 头像首字符 */
function avatarText(): string {
  return agentDisplayName.value.slice(0, 1) || "?";
}
/* ===== 目标区 ===== */

/* ===== Tab 切换（query 同步，可深链） ===== */
/* v4.18：新增「概览」为默认 tab；六个既有深链取值行为一字不变，无值/非法值落概览 */
const activeTab = computed<
  | "overview"
  | "objectives"
  | "ledger"
  | "notebooks"
  | "todos"
  | "activity"
  | "memory"
>(() =>
  route.query.tab === "overview"
    ? "overview"
    : route.query.tab === "objectives"
      ? "objectives"
      : route.query.tab === "ledger"
        ? "ledger"
        : route.query.tab === "notebooks"
          ? "notebooks"
          : route.query.tab === "todos"
            ? "todos"
            : route.query.tab === "activity"
              ? "activity"
              : route.query.tab === "memory"
                ? "memory"
                : "overview",
);

/** 切 Tab：replace 避免污染历史栈 */
function switchTab(
  tab:
    | "overview"
    | "objectives"
    | "ledger"
    | "notebooks"
    | "todos"
    | "activity"
    | "memory",
): void {
  if (activeTab.value === tab) return;
  void router.replace({ path: route.path, query: { ...route.query, tab } });
}

const objLoading = ref(true);
/* ===== 笔记本区（该 agent 名下已关联的笔记本概览卡片网格） ===== */
const nbLoading = ref(true);
const agentNotebooks = ref<PortalNotebook[]>([]);

/** 加载该 agent 关联的笔记本概览（GET /api/portal/agents/{id}/notebooks；agent 不存在 → 404） */
async function loadNotebooksForAgent(): Promise<void> {
  nbLoading.value = true;
  try {
    agentNotebooks.value =
      (await portalApi.agentNotebooks(agentId.value)) || [];
  } catch {
    agentNotebooks.value = [];
  } finally {
    nbLoading.value = false;
  }
}

/** 跳到独立笔记本页（与 /notebooks 承接页卡片同源路由 /notebooks/{id}） */
function openNotebook(nb: PortalNotebook): void {
  void router.push(`/notebooks/${nb.id}`);
}

/** 笔记本封面兜底渐变：5 色 token 循环（与 /notebooks 列表页同一算法）；225° 渐变柔和过渡 */
function coverTone(id: number): string {
  const tones = [
    "var(--tone-blue)",
    "var(--tone-teal)",
    "var(--tone-green)",
    "var(--tone-amber)",
    "var(--tone-violet)",
  ];
  const c = tones[(Number(id) || 0) % tones.length];
  return `linear-gradient(225deg, color-mix(in srgb, ${c} 72%, #ffffff), ${c})`;
}

/** 相对时间：yyyy-MM-dd HH:mm:ss → 「刚刚 / N 分钟前 / 昨天 / N 天前」（与笔记本列表页同款） */
function relativeTime(t?: string | null): string {
  if (!t) return "";
  const d = new Date(t.replace(" ", "T"));
  const diff = Math.floor((Date.now() - d.getTime()) / 1000);
  if (diff < 60) return "刚刚";
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
  if (diff < 172800) return "昨天";
  return `${Math.floor(diff / 86400)} 天前`;
}

const objectives = ref<PortalObjective[]>([]);
const detail = ref<PortalObjectiveDetail | null>(null);
const detailLoading = ref(false);
const activeOid = ref("");
const detailEl = ref<HTMLElement | null>(null);
/* ===== 目标分页（流式加载：首屏 20 条，滚动到底自动加载下一页） ===== */
const OBJ_PAGE_SIZE = 20;
/** 目标总条数（Tab 角标 / 完成度口径，来自 BPage.count） */
const objTotal = ref(0);
/** 目标状态筛选：null=全部，0未开始 1进行中 2已完成 3已放弃（默认进 Tab 看「进行中」） */
const objStatusFilter = ref<number | null>(1);
/** 已加载到的页码（1 起，0=尚未加载） */
const objPage = ref(0);
/** 是否还有下一页 */
const objHasMore = ref(false);
/** 翻页加载中（与首屏骨架 objLoading 区分） */
const objLoadingMore = ref(false);
/** 状态文案 */
function statusText(status: number): string {
  return ["未开始", "进行中", "已完成", "已放弃"][status] ?? "未知";
}
/** 状态 chip 样式类 */
function statusClass(status: number): string {
  return `is-${["todo", "doing", "done", "drop"][status] ?? "todo"}`;
}
/**
 * 清洗目标 md 全文（解决造数模板导致的重复/空章节问题）：
 * 1) 剔除 task list 行（"- [x] xxx"）——子项已由结构化 subItems 渲染成 checkbox，全文再渲染一遍会重复
 * 2) 剔除空章节标题（如 "## 子项"、"## 反思" 后面没有正文，只渲染出孤零零的 h2）
 * 3) 压缩 3 个以上连续空行，避免删除内容后留下大片空白
 */
function cleanObjectiveMd(md: string): string {
  // ① 按行剔除 task list 行（兼容 - * + 三种列表符与 [x]/[ ] 两种状态）
  const withoutTasks = md.replace(/^[ \t]*[-*+][ \t]+\[[xX ]\][ \t]+.*$/gm, "");
  // ② 逐行扫描剔除"空章节"：标题行之后直到下一个标题/文末都没有实际内容 → 该标题整行剔除
  const lines = withoutTasks.split("\n");
  const kept: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    // 判断当前行是否为 markdown 标题（# ~ ######）
    if (/^#{1,6}\s+/.test(lines[i])) {
      // 向后探测：找到下一个标题前是否存在非空行
      let j = i + 1;
      let hasContent = false;
      while (j < lines.length && !/^#{1,6}\s+/.test(lines[j])) {
        if (lines[j].trim()) {
          hasContent = true;
          break;
        }
        j++;
      }
      // 空章节：跳过标题行及其后的空行（i 直接快进到探测位置）
      if (!hasContent) {
        i = j - 1;
        continue;
      }
    }
    kept.push(lines[i]);
  }
  // ③ 压缩连续空行并去首尾空白
  return kept
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}
/** 详情 md → 清洗后安全 HTML（mermaid 占位由 runMermaid 异步替换） */
const renderedHtml = computed<string>(() => {
  const md = detail.value?.contentMd || "";
  return md ? renderMarkdown(cleanObjectiveMd(md)) : "";
});
/** 目标统计：各状态计数（服务端全量口径，不受筛选影响；供筛选 chip 计数与 Tab 角标） */
const objStat = ref<PortalStatusCounts>({ total: 0, pending: 0, doing: 0, done: 0, dropped: 0 });
/** 拉取目标状态计数（进页/切 agent 时一次；切筛选不重拉 —— 计数是全量口径，与筛选无关） */
async function loadObjectiveStatusCounts(): Promise<void> {
  try {
    const res = await portalApi.objectivesStatusCountsByAgent(agentId.value);
    if (res) objStat.value = res;
  } catch {
    /* 静默：保留上次计数，列表不受影响 */
  }
}
/** 切目标状态筛选：重置列表并重新加载第 1 页（交互对齐待办 setTodoStatus） */
function setObjectStatus(status: number | null): void {
  if (objStatusFilter.value === status) return;
  objStatusFilter.value = status;
  void loadObjectives();
}
/** 选中目标 → 加载详情（md + mermaid） */
async function openDetail(oid: string): Promise<void> {
  if (activeOid.value === oid && detail.value) return;
  activeOid.value = oid;
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = (await portalApi.objectiveByOid(oid)) || null;
    // mermaid 图表需要在 DOM 挂载后再渲染
    await nextTick();
    if (detailEl.value) {
      void runMermaid(detailEl.value);
    }
  } catch {
    detail.value = null;
  } finally {
    detailLoading.value = false;
  }
}
/* ===== 台账区 ===== */
const ledgerLoading = ref(true);
const ledger = ref<PortalLedger[]>([]);
/* ===== 台账分页（流式加载：首屏 20 条，滚动到底自动加载下一页） ===== */
const LEDGER_PAGE_SIZE = 20;
/** 台账总条数（Tab 角标，来自 BPage.count） */
const ledgerTotal = ref(0);
/** 已加载到的页码（1 起，0=尚未加载） */
const ledgerPage = ref(0);
/** 是否还有下一页 */
const ledgerHasMore = ref(false);
/** 翻页加载中（与首屏骨架 ledgerLoading 区分） */
const ledgerLoadingMore = ref(false);
/** 台账类型色表（契约 §1.4：进度/巡检/决策/备忘/会话） */
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
/** 台账按 yyyy-MM-dd 分组（倒序），组内时间倒序 */
interface LedgerGroup {
  date: string;
  items: PortalLedger[];
}
const groupedLedger = computed<LedgerGroup[]>(() => {
  const groups = new Map<string, PortalLedger[]>();
  for (const it of ledger.value) {
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
/** 时间戳 → 仅 HH:mm:ss */
function formatTime(timeStr: string): string {
  const s = String(timeStr || "");
  return s.length >= 19 ? s.slice(11, 19) : s;
}
/* ===== 台账正文展开（手风琴：点击条目请求详情并展开，再点收起） ===== */
/** 当前展开的台账 lid（单开模式：同时只展开一条） */
const expandedLid = ref("");
/** 详情正文缓存：lid → 渲染后 HTML（空串=加载中哨兵暂不占位，null=无正文） */
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
/* ===== 数据加载 ===== */
async function loadAgent(): Promise<void> {
  agentLoading.value = true;
  try {
    const res = await portalApi.agents();
    // 从启用列表中匹配当前 agent（匹配不到则保留 null，头部回退展示 id）
    agent.value =
      (res || []).find((a) => String(a.id) === agentId.value) || null;
  } catch {
    agent.value = null;
  } finally {
    agentLoading.value = false;
  }
}
/* ===== 目标加载：首页 + 流式翻页（GET /objectives/page，BPage 包装） ===== */
async function loadObjectives(): Promise<void> {
  objLoading.value = true;
  try {
    const res = await portalApi.objectivesPageByAgent({
      agentId: agentId.value,
      status: objStatusFilter.value ?? undefined,
      page: 1,
      size: OBJ_PAGE_SIZE,
    });
    objectives.value = res?.list || [];
    objTotal.value = res?.count ?? objectives.value.length;
    objPage.value = 1;
    objHasMore.value = objectives.value.length < objTotal.value;
    // 默认选中第一个目标，右侧详情区不空
    if (objectives.value.length && !activeOid.value) {
      void openDetail(objectives.value[0].oid);
    }
  } catch {
    objectives.value = [];
    objTotal.value = 0;
    objPage.value = 0;
    objHasMore.value = false;
  } finally {
    objLoading.value = false;
  }
}
/** 滚动到底加载下一页目标（哨兵触发；加载中/无更多时幂等跳过） */
async function loadMoreObjectives(): Promise<void> {
  if (objLoading.value || objLoadingMore.value || !objHasMore.value) return;
  objLoadingMore.value = true;
  try {
    const res = await portalApi.objectivesPageByAgent({
      agentId: agentId.value,
      status: objStatusFilter.value ?? undefined,
      page: objPage.value + 1,
      size: OBJ_PAGE_SIZE,
    });
    const items = res?.list || [];
    objectives.value = [...objectives.value, ...items];
    objTotal.value = res?.count ?? objTotal.value;
    objPage.value += 1;
    objHasMore.value = objectives.value.length < objTotal.value;
  } catch {
    objHasMore.value = false;
  } finally {
    objLoadingMore.value = false;
    rearmSentinel();
  }
}
async function loadLedger(): Promise<void> {
  ledgerLoading.value = true;
  try {
    const res = await portalApi.ledgerPageByAgent({
      agentId: agentId.value,
      page: 1,
      size: LEDGER_PAGE_SIZE,
    });
    ledger.value = res?.list || [];
    ledgerTotal.value = res?.count ?? ledger.value.length;
    ledgerPage.value = 1;
    ledgerHasMore.value = ledger.value.length < ledgerTotal.value;
  } catch {
    ledger.value = [];
    ledgerTotal.value = 0;
    ledgerPage.value = 0;
    ledgerHasMore.value = false;
  } finally {
    ledgerLoading.value = false;
  }
}
/** 滚动到底加载下一页台账（哨兵触发；加载中/无更多时幂等跳过） */
async function loadMoreLedger(): Promise<void> {
  if (ledgerLoading.value || ledgerLoadingMore.value || !ledgerHasMore.value)
    return;
  ledgerLoadingMore.value = true;
  try {
    const res = await portalApi.ledgerPageByAgent({
      agentId: agentId.value,
      page: ledgerPage.value + 1,
      size: LEDGER_PAGE_SIZE,
    });
    const items = res?.list || [];
    ledger.value = [...ledger.value, ...items];
    ledgerTotal.value = res?.count ?? ledgerTotal.value;
    ledgerPage.value += 1;
    ledgerHasMore.value = ledger.value.length < ledgerTotal.value;
  } catch {
    ledgerHasMore.value = false;
  } finally {
    ledgerLoadingMore.value = false;
    rearmSentinel();
  }
}
/* ===== 待办区（M5.2：agent 私域 todolist，调研池迁入） ===== */
const todoLoading = ref(true);
const todos = ref<PortalTodo[]>([]);
/* ===== 待办分页（流式加载：首屏 20 条，滚动到底自动加载下一页） ===== */
const TODO_PAGE_SIZE = 20;
/** 待办总条数（当前筛选下，来自 BPage.count；供 hasMore 与「已加载全部」文案） */
const todoTotal = ref(0);
/** 待办统计：各状态计数（服务端全量口径，不受筛选影响；供 Tab 角标） */
const todoStat = ref<PortalStatusCounts>({ total: 0, pending: 0, doing: 0, done: 0, dropped: 0 });
/** 拉取待办状态计数（进页/切 agent 时一次；切筛选不重拉） */
async function loadTodoStatusCounts(): Promise<void> {
  try {
    const res = await portalApi.todosStatusCountsByAgent(agentId.value);
    if (res) todoStat.value = res;
  } catch {
    /* 静默：保留上次计数 */
  }
}
/** 已加载到的页码（1 起，0=尚未加载） */
const todoPage = ref(0);
/** 是否还有下一页 */
const todoHasMore = ref(false);
/** 翻页加载中（与首屏骨架 todoLoading 区分） */
const todoLoadingMore = ref(false);
/** 状态筛选：null=全部，0待办 1进行中 2已完成 3已放弃（默认进 Tab 看「待办」） */
const todoStatusFilter = ref<number | null>(0);
/** 待办状态文案（0 与 objective 不同：待办） */
function todoStatusText(status: number): string {
  return ["待办", "进行中", "已完成", "已放弃"][status] ?? "未知";
}
/** 待办状态 chip 样式类（沿用 objective 四态配色：todo/doing/done/drop） */
function todoStatusClass(status: number): string {
  return `is-${["todo", "doing", "done", "drop"][status] ?? "todo"}`;
}
/** 优先级徽标：P1（高）→ P5（低），P1/P2 红色系、P3 琥珀、P4/P5 灰 */
function priorityClass(priority: number): string {
  if (priority <= 2) return "is-high";
  if (priority === 3) return "is-mid";
  return "is-low";
}
/** 待办是否过期（M5.3：截止日期早于今天；当天不算过期） */
function isTodoOverdue(dueDate?: string | null): boolean {
  if (!dueDate) return false;
  const now = new Date();
  const today =
    `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-` +
    `${String(now.getDate()).padStart(2, "0")}`;
  return dueDate.slice(0, 10) < today;
}
function priorityLabel(priority: number): string {
  return `P${priority}`;
}
/** 标签串 → 数组（逗号分隔，trim） */
function tagList(tags?: string | null): string[] {
  if (!tags) return [];
  return tags
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean);
}
/** 时间戳 → 仅 HH:mm:ss */
function formatTodoTime(timeStr: string): string {
  const s = String(timeStr || "");
  return s.length >= 19 ? s.slice(11, 19) : s;
}
/** 活动时间戳 → 统一 MM-DD HH:mm:ss（14 天保留窗口内无年份歧义，任何时间都带日期辨新旧） */
function formatActivityTime(timeStr: string): string {
  const s = String(timeStr || "");
  return s.length >= 19 ? s.slice(5, 19) : s;
}
/** 待办加载：首页 + 状态筛选（切筛选时重置到第 1 页） */
async function loadTodos(): Promise<void> {
  todoLoading.value = true;
  try {
    const res = await portalApi.todosPageByAgent({
      agentId: agentId.value,
      status: todoStatusFilter.value ?? undefined,
      page: 1,
      size: TODO_PAGE_SIZE,
    });
    todos.value = res?.list || [];
    todoTotal.value = res?.count ?? todos.value.length;
    todoPage.value = 1;
    todoHasMore.value = todos.value.length < todoTotal.value;
  } catch {
    todos.value = [];
    todoTotal.value = 0;
    todoPage.value = 0;
    todoHasMore.value = false;
  } finally {
    todoLoading.value = false;
  }
}
/** 滚动到底加载下一页待办（哨兵触发；加载中/无更多时幂等跳过） */
async function loadMoreTodos(): Promise<void> {
  if (todoLoading.value || todoLoadingMore.value || !todoHasMore.value) return;
  todoLoadingMore.value = true;
  try {
    const res = await portalApi.todosPageByAgent({
      agentId: agentId.value,
      status: todoStatusFilter.value ?? undefined,
      page: todoPage.value + 1,
      size: TODO_PAGE_SIZE,
    });
    const items = res?.list || [];
    todos.value = [...todos.value, ...items];
    todoTotal.value = res?.count ?? todoTotal.value;
    todoPage.value += 1;
    todoHasMore.value = todos.value.length < todoTotal.value;
  } catch {
    todoHasMore.value = false;
  } finally {
    todoLoadingMore.value = false;
    rearmSentinel();
  }
}
/** 切状态筛选：重置列表并重新加载第 1 页 */
function setTodoStatus(status: number | null): void {
  if (todoStatusFilter.value === status) return;
  todoStatusFilter.value = status;
  void loadTodos();
}

/* ===== M6.1 活动 Tab（M6.2 改两层视图） =====
 * 第一层（默认）：话题列表（sessions 聚合接口，回答「哪个会话在做什么」）；
 * 第二层：单会话事件时间线（列表接口 + sessionKey 过滤，limit 200）。
 * 10s 轮询仅第二层可见且页面可见时进行（任务书 §3.2）；第一层靠「刷新」按钮。 */
const activityLoading = ref(true);
const activities = ref<PortalActivity[]>([]);
const sessionsLoading = ref(true);
const sessions = ref<PortalActivitySession[]>([]);
/** 当前视图：sessions=话题列表（默认）/ timeline=单会话时间线 */
const activityView = ref<"sessions" | "timeline">("sessions");
/** 第二层选中的会话 key（null = 第一层） */
const selectedSession = ref<string | null>(null);
/** 默认拉取条数（契约 limit 默认 50，服务端钳制 ≤200） */
const ACTIVITY_LIMIT = 50;
/** 话题列表拉取条数（契约 sessions limit 默认 20，服务端钳制 ≤50） */
const SESSIONS_LIMIT = 20;
/** 10s 轮询定时器句柄（onBeforeUnmount 清理） */
let activityTimer: number | null = null;

/** 事件类型 → 图标+文案：turn_started ▶ / tool_call 🔧+工具名 / status_changed ⏱ / turn_completed ✅+outcome */
function activityIcon(e: PortalActivity): string {
  if (e.eventType === "tool_call") return e.toolName ? `🔧 ${e.toolName}` : "🔧 工具";
  if (e.eventType === "turn_started") return "▶ 开始";
  if (e.eventType === "status_changed") return "⏱ 状态";
  return e.outcome ? `✅ ${e.outcome}` : "✅ 完成";
}

/** durationMs → 人话换算：<1s → Nms；<60s → N秒；否则 N分M秒 */
function durationText(ms?: number | null): string {
  if (ms == null) return "";
  if (ms < 1000) return `${ms}ms`;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}秒`;
  return `${Math.floor(s / 60)}分${s % 60}秒`;
}

/** summary 超长截断（时间线单行展示，完整内容看 title 悬浮） */
function summaryText(s?: string | null): string {
  const t = (s || "").trim();
  if (!t) return "";
  return t.length > 80 ? `${t.slice(0, 80)}…` : t;
}

/** 空摘要事件的兜底文案（可读性优化：v3 插件成功 end 事件 summary 为空，不再显示无信息的 "—"） */
function eventFallbackText(e: PortalActivity): string {
  if (e.eventType === "tool_call" && e.outcome === "completed") return "✓ 完成";
  if (e.eventType === "tool_call" && e.outcome === "failed") return "✗ 失败";
  if (e.eventType === "turn_completed") return e.outcome === "failed" ? "✗ 回合失败" : "✓ 回合完成";
  return "—";
}

/** 会话 key 短哈希：取协议前缀后前 12 字符（websocket:7257d081-... → 7257d081-...） */
function shortSessionKey(key?: string | null): string {
  const t = (key || "").trim();
  if (!t) return "—";
  const idx = t.indexOf(":");
  const body = idx >= 0 ? t.slice(idx + 1) : t;
  return body.length > 12 ? `${body.slice(0, 12)}…` : body;
}

/** 定时任务会话识别：通道会话 key 带 "channel:id" 前缀（websocket:/qq:），cron 会话无冒号 */
function isCronSession(key?: string | null): boolean {
  const t = (key || "").trim();
  return !!t && !t.includes(":");
}

/** 诊断 flag → 徽标文案 */
function flagLabel(f: string): string {
  if (f === "loop_suspect") return "疑似循环";
  if (f === "long_turn") return "回合超长";
  if (f === "long_tool") return "工具超长";
  if (f === "error_prone") return "高频失败";
  if (f === "stalled") return "疑似停滞";
  return f;
}

/** 诊断 flag → 徽标配色：loop 红 / long_* 橙 / error_prone 与 stalled 黄 */
function flagClass(f: string): string {
  if (f === "loop_suspect") return "flag-red";
  if (f === "long_turn" || f === "long_tool") return "flag-orange";
  return "flag-yellow";
}

/** 异常事件行高亮：outcome=failed 或耗时超限（tool >2min / turn >10min，与服务端阈值一致） */
function isAbnormalEvent(e: PortalActivity): boolean {
  if (e.outcome === "failed") return true;
  if (e.durationMs == null) return false;
  if (e.eventType === "tool_call") return e.durationMs > 120_000;
  if (e.eventType === "turn_completed") return e.durationMs > 600_000;
  return false;
}

/** 话题列表加载（第一层；silent 轮询静默刷新） */
async function loadSessions(silent = false): Promise<void> {
  if (!silent) sessionsLoading.value = true;
  try {
    const res = await portalApi.agentActivitySessions(agentId.value, SESSIONS_LIMIT);
    sessions.value = res?.list || [];
  } catch {
    if (!silent) sessions.value = [];
  } finally {
    if (!silent) sessionsLoading.value = false;
  }
}

/** 活动流加载（第二层：当前选中会话的事件时间线；silent=true 时不触发骨架屏） */
async function loadActivity(silent = false): Promise<void> {
  if (!silent) activityLoading.value = true;
  try {
    const res = await portalApi.agentActivity(agentId.value, ACTIVITY_LIMIT, selectedSession.value || undefined);
    activities.value = res?.list || [];
  } catch {
    if (!silent) activities.value = [];
  } finally {
    if (!silent) activityLoading.value = false;
  }
}

/** 进入第二层：选中会话并拉取事件时间线 */
function openSession(s: PortalActivitySession): void {
  selectedSession.value = s.sessionKey;
  activityView.value = "timeline";
  void loadActivity();
}

/** 返回第一层：清空选中态并刷新话题列表 */
function backToSessions(): void {
  selectedSession.value = null;
  activityView.value = "sessions";
  void loadSessions();
}

/* ===== 记忆区（M7.1-M3：agent 私域记忆库门户只读，仅活跃 status=1） ===== */
const memLoading = ref(true);
const memories = ref<PortalMemory[]>([]);
/* ===== 记忆分页（流式加载：首屏 20 条，滚动到底自动加载下一页） ===== */
const MEM_PAGE_SIZE = 20;
/** 记忆总条数（Tab 角标，来自 BPage.count，仅活跃口径） */
const memTotal = ref(0);
/** 已加载到的页码（1 起，0=尚未加载） */
const memPage = ref(0);
/** 是否还有下一页 */
const memHasMore = ref(false);
/** 翻页加载中（与首屏骨架 memLoading 区分） */
const memLoadingMore = ref(false);
/** 记忆类型色表（MemoryService.MEM_TYPES 五值：fact/decision/preference/lesson/event） */
const memTypeMeta: Record<string, { label: string; color: string; soft: string }> = {
  fact: { label: "事实", color: "#409eff", soft: "rgba(64, 158, 255, 0.14)" },
  decision: { label: "决策", color: "#9254de", soft: "rgba(146, 84, 222, 0.14)" },
  preference: { label: "偏好", color: "#2eb872", soft: "rgba(46, 184, 114, 0.14)" },
  lesson: { label: "教训", color: "#e6a23c", soft: "rgba(230, 162, 60, 0.14)" },
  event: { label: "事件", color: "#909399", soft: "rgba(144, 147, 153, 0.14)" },
};
function memTypeInfo(type: string): { label: string; color: string; soft: string } {
  return memTypeMeta[type] || memTypeMeta.event;
}
/** id → 正文 HTML（contentMd markdown 安全渲染；computed 随列表变化重算） */
const memBodyHtmlMap = computed<Map<number, string>>(() => {
  const map = new Map<number, string>();
  for (const m of memories.value) {
    map.set(m.id, m.contentMd ? renderMarkdown(m.contentMd) : "");
  }
  return map;
});
/** 记忆加载：首页（GET /memories/page，BPage 包装；agent 无记忆 → 空态不报错） */
async function loadMemories(): Promise<void> {
  memLoading.value = true;
  try {
    const res = await portalApi.memoriesPageByAgent({
      agentId: agentId.value,
      page: 1,
      size: MEM_PAGE_SIZE,
    });
    memories.value = res?.list || [];
    memTotal.value = res?.count ?? memories.value.length;
    memPage.value = 1;
    memHasMore.value = memories.value.length < memTotal.value;
  } catch {
    memories.value = [];
    memTotal.value = 0;
    memPage.value = 0;
    memHasMore.value = false;
  } finally {
    memLoading.value = false;
  }
}
/** 滚动到底加载下一页记忆（哨兵触发；加载中/无更多时幂等跳过） */
async function loadMoreMemories(): Promise<void> {
  if (memLoading.value || memLoadingMore.value || !memHasMore.value) return;
  memLoadingMore.value = true;
  try {
    const res = await portalApi.memoriesPageByAgent({
      agentId: agentId.value,
      page: memPage.value + 1,
      size: MEM_PAGE_SIZE,
    });
    const items = res?.list || [];
    memories.value = [...memories.value, ...items];
    memTotal.value = res?.count ?? memTotal.value;
    memPage.value += 1;
    memHasMore.value = memories.value.length < memTotal.value;
  } catch {
    memHasMore.value = false;
  } finally {
    memLoadingMore.value = false;
    rearmSentinel();
  }
}

/* ===== v4.18 概览 Tab（方案A 仪表盘；既有六 tab 逻辑零改动） =====
 * 数据纪律：优先复用本视图已加载 ref（agent/objStat/todoStat/sessions）；
 * 概览专属每 agent 拉一次（不轮询）：activity/stats（新端点）、activity/current、
 * activity 最近事件（模型徽章用）、objectives 全量、ledger 全量；失败各自空态兜底。 */
const OV_STATS_DAYS = 14;
/** 概览职责一句话：前端静态映射（agentId 对应文案），无配置整行隐藏（任务书 §3.2） */
const OV_ROLE: Record<string, { short: string; desc: string }> = {
  "1": {
    short: "指挥官 · 编排",
    desc: "多 Agent 体系指挥协调者：拆任务 / 审 MR / 部署验收 / 运维巡检 / 调研归档",
  },
  "2": {
    short: "开发 · 实现",
    desc: "开发 Worker：按任务书实现 / 编译自测 / MR 交付 / 技术沉淀与台账",
  },
};
const ovLoading = ref(true);
const ovStats = ref<PortalActivityDailyStat[]>([]);
const ovCurrent = ref<PortalActivityCurrent | null>(null);
const ovLedgerFull = ref<PortalLedger[]>([]);
const ovObjectivesFull = ref<PortalObjective[]>([]);
const ovModel = ref("");
/** 接口失败回退兜底（Promise.all 保形） */
async function ovSafe<T>(p: Promise<T>, fb: T): Promise<T> {
  try {
    return await p;
  } catch {
    return fb;
  }
}
/** 概览数据加载（进页/切 agent 各一次；已切走则丢弃过期结果） */
async function loadOverview(): Promise<void> {
  ovLoading.value = true;
  const id = agentId.value;
  const [statsRes, currentRes, recentRes, objRes, ledRes] = await Promise.all([
    ovSafe(portalApi.agentActivityStats(id, OV_STATS_DAYS), null),
    ovSafe(portalApi.agentActivityCurrent(id), null),
    ovSafe(portalApi.agentActivity(id, ACTIVITY_LIMIT), null),
    ovSafe(portalApi.objectivesByAgent(id), null),
    ovSafe(portalApi.ledgerByAgent(id), null),
  ]);
  if (id !== agentId.value) return;
  ovStats.value = statsRes?.list || [];
  ovCurrent.value = currentRes;
  /* 模型徽章：最近一条 turn_completed 的 summary 尾段（「… · 模型名」split 解析）；取不到隐藏 */
  const doneEvt = (recentRes?.list || []).find(
    (e) => e.eventType === "turn_completed" && !!e.summary,
  );
  const parts = (doneEvt?.summary || "").split("·");
  const tail = parts.length > 1 ? (parts[parts.length - 1] || "").trim() : "";
  ovModel.value = tail && tail.length <= 40 ? tail : "";
  ovObjectivesFull.value = objRes || [];
  ovLedgerFull.value = ledRes || [];
  ovLoading.value = false;
}
/* ---- 近 14 天柱状序列：以北京今天为终点回补窗口，零事件日 = gap 灰柱 ---- */
interface OvBar {
  key: string;
  date: string;
  events: number;
  sessions: number;
  turns: number;
  tools: number;
  gap: boolean;
  height: number;
}
const ovBars = computed<OvBar[]>(() => {
  if (!ovStats.value.length) return [];
  const byDate = new Map<string, PortalActivityDailyStat>();
  for (const r of ovStats.value) {
    byDate.set(String(r.statDate || "").slice(0, 10), r);
  }
  /* 北京墙钟今天（UTC+8 无夏令时），与 statDate 墙钟口径一致 */
  const bjToday = new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10);
  const endMs = new Date(`${bjToday}T00:00:00Z`).getTime();
  const max = Math.max(...ovStats.value.map((r) => r.events || 0), 1);
  const bars: OvBar[] = [];
  for (let i = OV_STATS_DAYS - 1; i >= 0; i--) {
    const key = new Date(endMs - i * 86400_000).toISOString().slice(0, 10);
    const row = byDate.get(key);
    const events = row?.events || 0;
    bars.push({
      key,
      date: key.slice(5),
      events,
      sessions: row?.sessions || 0,
      turns: row?.turns || 0,
      tools: row?.tools || 0,
      gap: !row,
      height: Math.max(6, Math.round((events / max) * 170)),
    });
  }
  return bars;
});
const ovToday = computed<OvBar | null>(
  () => ovBars.value[ovBars.value.length - 1] || null,
);
const ovPeak = computed<OvBar | null>(() => {
  let p: OvBar | null = null;
  for (const b of ovBars.value) {
    if (!b.gap && (!p || b.events > p.events)) p = b;
  }
  return p;
});
/** 柱状 title：零流水日提示「无流水记录」 */
function ovBarTitle(b: OvBar): string {
  return b.gap ? `${b.date} 无流水记录` : `${b.date}：${b.events} 事件`;
}
/** 今日活动 KPI 副行 */
const ovTodaySub = computed<string>(() => {
  const t = ovToday.value;
  if (!t || t.gap) return "暂无流水记录";
  return `${t.sessions} 会话 · ${t.turns} 回合 · ${t.tools} 工具调用`;
});
/* ---- 英雄卡 ---- */
const ovRunning = computed<boolean>(() => ovCurrent.value?.state === "running");
const ovRoleShort = computed<string>(
  () => OV_ROLE[String(agentId.value)]?.short || "",
);
const ovRoleDesc = computed<string>(
  () => OV_ROLE[String(agentId.value)]?.desc || "",
);
/** 未完成待办 = 待办 + 进行中（todoStat 全量口径） */
const ovTodoOpen = computed<number>(
  () => todoStat.value.pending + todoStat.value.doing,
);
/** 最近笔记 MM-DD HH:mm */
const ovLastNote = computed<string>(() => {
  const t = String(agent.value?.lastNoteAt || "");
  return t.length >= 16 ? t.slice(5, 16) : "";
});
/* ---- 台账聚合（全量一次，本地按 type 计数） ---- */
const ovLedgerCounts = computed<Record<PortalLedgerType, number>>(() => {
  const c: Record<PortalLedgerType, number> = {
    progress: 0,
    observation: 0,
    decision: 0,
    memo: 0,
    session: 0,
  };
  for (const it of ovLedgerFull.value) c[it.type] += 1;
  return c;
});
const ovLedgerTotalFull = computed<number>(() => ovLedgerFull.value.length);
/** 台账类型短标签（概览徽章口径，样张同款） */
const OV_TYPE_SHORT: Record<PortalLedgerType, string> = {
  progress: "进展",
  observation: "观察",
  decision: "决策",
  memo: "备忘",
  session: "会话",
};
/** 台账类型徽标配色（概览口径） */
function ovTypeClass(type: PortalLedgerType): string {
  if (type === "progress") return "t-blue";
  if (type === "observation") return "t-amber";
  if (type === "decision") return "t-purple";
  return "t-gray";
}
/** 台账类型分布条（样张配色；宽度 = 占最大类比例） */
const ovLedgerDist = computed(() => {
  const defs: { key: PortalLedgerType; name: string; color: string }[] = [
    { key: "progress", name: "progress 进展", color: "#2f6bff" },
    { key: "decision", name: "decision 决策", color: "#8b5cf6" },
    { key: "observation", name: "observation 观察", color: "#f59e0b" },
    { key: "memo", name: "memo 备忘", color: "#94a3b8" },
  ];
  const max = Math.max(...defs.map((d) => ovLedgerCounts.value[d.key]), 1);
  return defs.map((d) => ({
    ...d,
    count: ovLedgerCounts.value[d.key],
    pct: Math.max(2, Math.round((ovLedgerCounts.value[d.key] / max) * 100)),
  }));
});
const ovRecentLedger = computed<PortalLedger[]>(() =>
  ovLedgerFull.value.slice(0, 6),
);
/** 台账时间 MM-DD HH:mm */
function ovLedgerTime(t: string): string {
  const s = String(t || "");
  return s.length >= 16 ? s.slice(5, 16) : s;
}
/* ---- 目标状态环图（CSS conic-gradient，禁图表库） ---- */
const ovDonutStyle = computed<string>(() => {
  const s = objStat.value;
  const total = Math.max(s.total, 0);
  if (!total) {
    return "conic-gradient(var(--border) 0deg, var(--border) 360deg)";
  }
  const seg = (n: number) => (Math.max(n, 0) / total) * 360;
  const a1 = seg(s.doing);
  const a2 = a1 + seg(s.pending);
  const a3 = a2 + seg(s.done);
  return `conic-gradient(#2f6bff 0deg ${a1}deg, #f59e0b ${a1}deg ${a2}deg, #10b981 ${a2}deg ${a3}deg, #94a3b8 ${a3}deg 360deg)`;
});
/* ---- 进行中目标 Top7（status=1，progress 降序） ---- */
const ovDoingObjectives = computed<PortalObjective[]>(() =>
  ovObjectivesFull.value
    .filter((o) => o.status === 1)
    .sort((a, b) => (b.progress || 0) - (a.progress || 0))
    .slice(0, 7),
);
/* ---- 最近会话 Top5（复用活动 tab 已加载 sessions，不另发请求） ---- */
const ovRecentSessions = computed<PortalActivitySession[]>(() =>
  sessions.value.slice(0, 5),
);
/** 概览会话 flag 中文（任务书 §3.2 概览口径） */
function ovFlagText(f: string): string {
  if (f === "loop_suspect") return "疑似循环";
  if (f === "long_turn") return "长回合";
  if (f === "long_tool") return "长工具";
  if (f === "error_prone") return "有报错";
  if (f === "stalled") return "停滞";
  return f;
}
/** 会话最后事件 HH:mm */
function ovSessionTime(t?: string | null): string {
  const s = String(t || "");
  return s.length >= 16 ? s.slice(11, 16) : s;
}
/** 「当前」徽章：current 无 sessionKey 字段，以最后事件时间与 current 对齐判定 */
function isOvCurrentSession(s: PortalActivitySession): boolean {
  const cur = ovCurrent.value?.lastEventTime;
  return !!cur && !!s.lastEventTime && cur === s.lastEventTime;
}

onMounted(() => {
  void loadNotebooks();
  void loadAgent();
  void loadObjectives();
  void loadObjectiveStatusCounts();
  void loadNotebooksForAgent();
  void loadLedger();
  void loadTodos();
  void loadTodoStatusCounts();
  void loadSessions();
  void loadMemories();
  void loadOverview();
  /* M6.1 活动 10s 轮询；M6.2 起仅第二层（单会话时间线）可见且页面可见时静默刷新（第一层话题列表靠「刷新」按钮） */
  activityTimer = window.setInterval(() => {
    if (
      activeTab.value === "activity" &&
      activityView.value === "timeline" &&
      document.visibilityState === "visible"
    ) {
      void loadActivity(true);
    }
  }, 10000);
  /* 流式加载哨兵：目标/台账/待办三个 Tab 各一个 sentinel，进入视口即触发对应翻页 */
  scrollObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        const action = (entry.target as HTMLElement).dataset.action;
        if (action === "objectives") void loadMoreObjectives();
        else if (action === "ledger") void loadMoreLedger();
        else if (action === "todos") void loadMoreTodos();
        else if (action === "memory") void loadMoreMemories();
      }
    },
    { rootMargin: "240px" },
  );
});
onBeforeUnmount(() => {
  scrollObserver?.disconnect();
  scrollObserver = null;
  if (activityTimer != null) window.clearInterval(activityTimer);
  activityTimer = null;
});
/** 哨兵函数 ref：v-if 切 Tab 重建元素后自动重新 observe（卸载回调传 null 时跳过） */
let sentinelEl: Element | null = null;
function setSentinel(el: Element | { $el?: Element } | null): void {
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
/* 切换 agent 时重置选中态并重新加载（目标/台账/待办/笔记本四个 Tab 数据全部刷新） */
watch(
  () => route.params.agentId,
  () => {
    activeOid.value = "";
    detail.value = null;
    todoStatusFilter.value = 0;
    objStatusFilter.value = 1;
    void loadAgent();
    void loadObjectives();
    void loadObjectiveStatusCounts();
    void loadLedger();
    void loadTodos();
    void loadTodoStatusCounts();
    /* M6.2：切 agent 重置两层视图选中态，回第一层话题列表 */
    selectedSession.value = null;
    activityView.value = "sessions";
    void loadSessions();
    void loadMemories();
    void loadNotebooksForAgent();
    void loadOverview();
  },
);
</script>
<template>
  <div class="agent-page">
    <!-- Tab 切换：概览（默认）/ 目标 / 台账 / 待办 / 活动 / 记忆 / 笔记本 -->
    <div class="tab-bar" role="tablist">
      <button
        type="button"
        role="tab"
        class="tab-btn"
        :class="{ 'is-active': activeTab === 'overview' }"
        :aria-selected="activeTab === 'overview'"
        @click="switchTab('overview')"
      >
        概览
        <span v-if="ovRunning" class="live-dot" title="会话进行中" />
      </button>
      <button
        type="button"
        role="tab"
        class="tab-btn"
        :class="{ 'is-active': activeTab === 'objectives' }"
        :aria-selected="activeTab === 'objectives'"
        @click="switchTab('objectives')"
      >
        目标
        <span class="tab-count">{{ objStat.total }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-btn"
        :class="{ 'is-active': activeTab === 'ledger' }"
        :aria-selected="activeTab === 'ledger'"
        @click="switchTab('ledger')"
      >
        台账
        <span class="tab-count">{{ ledgerTotal }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-btn"
        :class="{ 'is-active': activeTab === 'todos' }"
        :aria-selected="activeTab === 'todos'"
        @click="switchTab('todos')"
      >
        待办
        <span class="tab-count">{{ todoStat.total }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-btn"
        :class="{ 'is-active': activeTab === 'activity' }"
        :aria-selected="activeTab === 'activity'"
        @click="switchTab('activity')"
      >
        活动
      </button>
      <button
        type="button"
        role="tab"
        class="tab-btn"
        :class="{ 'is-active': activeTab === 'memory' }"
        :aria-selected="activeTab === 'memory'"
        @click="switchTab('memory')"
      >
        记忆
        <span class="tab-count">{{ memTotal }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-btn"
        :class="{ 'is-active': activeTab === 'notebooks' }"
        :aria-selected="activeTab === 'notebooks'"
        @click="switchTab('notebooks')"
      >
        笔记本
        <span class="tab-count">{{ agentNotebooks.length }}</span>
      </button>
    </div>
    <!-- ===== 概览 Tab（v4.18 方案A：仪表盘聚合；既有六 tab 逻辑零改动） ===== -->
    <div v-if="activeTab === 'overview'" class="ov-root">
      <!-- 英雄卡：头像/名字/徽章（Live·模型·职责）/职责一句话/统计 meta/快捷入口 -->
      <div class="ov-hero">
        <span
          class="ov-hero-avatar"
          :style="agent?.avatar ? undefined : { background: avatarBg(agentId) }"
        >
          <img
            v-if="agent?.avatar"
            :src="agent.avatar"
            :alt="agentDisplayName"
          />
          <template v-else>{{ avatarText() }}</template>
        </span>
        <div class="ov-hero-main">
          <div class="ov-hero-name">
            {{ agentDisplayName }}
            <span v-if="agent" class="ov-hero-raw"
              >{{ agent.name }} · agent #{{ agent.id }}</span
            >
          </div>
          <div class="ov-badges">
            <span v-if="ovRunning" class="ov-badge b-live"
              ><span class="live-dot" />在线 · 会话进行中</span
            >
            <span v-if="ovModel" class="ov-badge b-model">{{ ovModel }}</span>
            <span v-if="ovRoleShort" class="ov-badge b-role">{{ ovRoleShort }}</span>
            <button
              v-if="agent"
              type="button"
              class="ov-badge b-notes"
              @click="switchTab('notebooks')"
            >
              <b>{{ agent.noteCount }}</b>
              <span class="u-dot">篇笔记</span>
            </button>
          </div>
          <div v-if="ovRoleDesc" class="ov-hero-desc">{{ ovRoleDesc }}</div>
          <div v-if="agent" class="ov-hero-meta">
            笔记本「{{ agent.notebookName }}」{{ agent.noteCount }} 篇<template
              v-if="ovLastNote"
              > · 最近笔记 {{ ovLastNote }}</template
            >
            · 未完成待办 {{ ovTodoOpen }} 项
          </div>
        </div>
        <div class="ov-acts">
          <button type="button" class="ov-btn pri" @click="switchTab('activity')">
            查看活动流
          </button>
          <button type="button" class="ov-btn" @click="switchTab('ledger')">
            查看台账
          </button>
        </div>
      </div>
      <!-- KPI 四卡 -->
      <div class="ov-kpis">
        <div class="ov-kpi">
          <div class="k-label">进行中目标</div>
          <div class="k-val">{{ objStat.doing }}</div>
          <div class="k-sub">
            共 {{ objStat.total }} 项目标 · 已完成 {{ objStat.done }} · 待启动
            {{ objStat.pending }}
          </div>
        </div>
        <div class="ov-kpi">
          <div class="k-label">累计台账</div>
          <div class="k-val">{{ ovLedgerTotalFull }}</div>
          <div class="k-sub">
            进展 {{ ovLedgerCounts.progress }} · 决策 {{ ovLedgerCounts.decision }} ·
            观察 {{ ovLedgerCounts.observation }} · 备忘 {{ ovLedgerCounts.memo }}
          </div>
        </div>
        <div class="ov-kpi">
          <div class="k-label">今日活动</div>
          <div class="k-val">{{ ovToday && !ovToday.gap ? ovToday.events : 0 }}</div>
          <div class="k-sub">{{ ovTodaySub }}</div>
        </div>
        <div class="ov-kpi">
          <div class="k-label">笔记总数</div>
          <div class="k-val">{{ agent?.noteCount ?? 0 }}</div>
          <div class="k-sub">最近更新 {{ ovLastNote || "—" }}</div>
        </div>
      </div>
      <!-- 图表行：14 天柱状图 + 目标环图/台账分布 -->
      <div class="ov-grid-2">
        <div class="ov-card">
          <h3 class="ov-h3">
            近 {{ OV_STATS_DAYS }} 天活动事件 <small>t_activity 按天聚合</small>
          </h3>
          <div v-if="ovLoading" class="ov-empty">加载中…</div>
          <template v-else-if="ovBars.length">
            <div class="ov-chart">
              <div v-for="b in ovBars" :key="b.key" class="bar-wrap">
                <div v-if="b.gap" class="ov-bar gap" :title="ovBarTitle(b)" />
                <div
                  v-else
                  class="ov-bar"
                  :style="{ height: b.height + 'px' }"
                  :title="ovBarTitle(b)"
                >
                  <span v-if="ovPeak && b.events === ovPeak.events" class="bar-val">{{
                    b.events
                  }}</span>
                </div>
                <div class="bar-label" :class="{ 'gap-label': b.gap }">{{ b.date }}</div>
              </div>
            </div>
            <div class="ov-chart-note">
              峰值 {{ ovPeak ? ovPeak.date + "（" + ovPeak.events + " 事件）" : "—" }} ·
              今日 {{ ovToday && !ovToday.gap ? ovToday.events : 0 }} · 灰色斜纹柱 =
              当日无流水记录
            </div>
          </template>
          <div v-else class="ov-empty">近 {{ OV_STATS_DAYS }} 天暂无活动流水</div>
        </div>
        <div class="ov-card">
          <h3 class="ov-h3">目标状态 <small>{{ objStat.total }} 项目标</small></h3>
          <div class="ov-donut-wrap">
            <div class="ov-donut" :style="{ background: ovDonutStyle }">
              <div class="ov-donut-in"><b>{{ objStat.total }}</b><span>目标</span></div>
            </div>
            <div class="ov-legend">
              <div>
                <span class="dot" style="background: #2f6bff" />进行中 {{ objStat.doing }}
              </div>
              <div>
                <span class="dot" style="background: #f59e0b" />待启动 {{ objStat.pending }}
              </div>
              <div>
                <span class="dot" style="background: #10b981" />已完成 {{ objStat.done }}
              </div>
              <div v-if="objStat.dropped">
                <span class="dot" style="background: #94a3b8" />已放弃 {{ objStat.dropped }}
              </div>
            </div>
          </div>
          <h3 class="ov-h3 ov-mt">
            台账类型分布 <small>累计 {{ ovLedgerTotalFull }} 条</small>
          </h3>
          <template v-if="ovLedgerTotalFull">
            <div v-for="d in ovLedgerDist" :key="d.key" class="lt-row">
              <span class="lt-name">{{ d.name }}</span>
              <div class="lt-track">
                <div
                  class="lt-fill"
                  :style="{ width: d.pct + '%', background: d.color }"
                />
              </div>
              <span class="lt-val">{{ d.count }}</span>
            </div>
          </template>
          <div v-else class="ov-empty">暂无台账流水</div>
        </div>
      </div>
      <!-- 三列区：进行中目标 / 最近台账 / 最近会话 -->
      <div class="ov-grid-3">
        <div class="ov-card">
          <h3 class="ov-h3">进行中目标 <small>按进度排序 Top 7</small></h3>
          <template v-if="ovDoingObjectives.length">
            <div v-for="o in ovDoingObjectives" :key="o.oid" class="obj-row">
              <div class="obj-top">
                <span class="obj-title" :title="o.title">{{ o.title }}</span>
                <span class="obj-meta"
                  >{{ o.period || "长期" }} · {{ o.progress ?? 0 }}%</span
                >
              </div>
              <div class="obj-track">
                <div
                  class="obj-fill"
                  :style="{ width: Math.max(o.progress ?? 0, 2) + '%' }"
                />
              </div>
            </div>
            <div class="obj-done">
              已完成 {{ objStat.done }} 项<template v-if="objStat.dropped"
                > · 已放弃 {{ objStat.dropped }} 项</template
              >
            </div>
          </template>
          <div v-else class="ov-empty">暂无进行中目标</div>
        </div>
        <div class="ov-card">
          <h3 class="ov-h3">最近台账 <small>最新 6 条</small></h3>
          <template v-if="ovRecentLedger.length">
            <div v-for="it in ovRecentLedger" :key="it.lid" class="lg-row">
              <span class="tag" :class="ovTypeClass(it.type)">{{
                OV_TYPE_SHORT[it.type] || it.type
              }}</span>
              <span class="lg-time">{{ ovLedgerTime(it.createTime) }}</span>
              <span class="lg-title" :title="it.title">{{ it.title }}</span>
            </div>
          </template>
          <div v-else class="ov-empty">暂无台账流水</div>
        </div>
        <div class="ov-card">
          <h3 class="ov-h3">最近会话 <small>最新 5 条</small></h3>
          <template v-if="ovRecentSessions.length">
            <div v-for="s in ovRecentSessions" :key="s.sessionKey" class="ss-row">
              <div class="ss-top">
                <span class="ss-time">{{ ovSessionTime(s.lastEventTime) }}</span>
                <span class="ss-topic">{{
                  s.sessionKey
                    ? s.sessionKey.slice(0, 24) +
                      (s.sessionKey.length > 24 ? "…" : "")
                    : "（无会话标识）"
                }}</span>
                <span v-if="isOvCurrentSession(s)" class="tag t-live">当前</span>
              </div>
              <div class="ss-sub">
                <span>{{ s.turnCount }} 回合 · {{ s.toolCallCount }} 工具</span>
                <span class="ss-flags">
                  <em
                    v-for="f in s.flags"
                    :key="f"
                    :title="
                      s.flagDetails && s.flagDetails[f] ? s.flagDetails[f] : ovFlagText(f)
                    "
                    >{{ ovFlagText(f) }}</em
                  >
                </span>
              </div>
            </div>
          </template>
          <div v-else class="ov-empty">暂无会话活动</div>
        </div>
      </div>
    </div>
    <!-- ===== 目标 Tab：左列表 + 右详情 ===== -->
    <div v-if="activeTab === 'objectives'" class="obj-layout">
      <!-- 左：目标卡片列表 -->
      <div class="obj-list">
        <!-- 状态筛选条：胶囊按钮组（全部/未开始/进行中/已完成/已放弃，chip 内带计数；样式交互对齐待办筛选条） -->
        <div v-if="!objLoading" class="obj-filter">
          <button
            v-for="f in [
              { v: null, label: '全部', count: objStat.total },
              { v: 0, label: '未开始', count: objStat.pending },
              { v: 1, label: '进行中', count: objStat.doing },
              { v: 2, label: '已完成', count: objStat.done },
              { v: 3, label: '已放弃', count: objStat.dropped },
            ]"
            :key="String(f.v)"
            type="button"
            class="filter-btn"
            :class="{ 'is-active': objStatusFilter === f.v }"
            @click="setObjectStatus(f.v)"
          >
            {{ f.label }} {{ f.count }}
          </button>
        </div>
        <div v-if="objLoading" class="skeleton-stack">
          <div v-for="i in 3" :key="i" class="skeleton-card" />
        </div>
        <template v-else-if="objectives.length">
          <button
            v-for="o in objectives"
            :key="o.oid"
            type="button"
            class="obj-card"
            :class="{ 'is-active': activeOid === o.oid }"
            @click="openDetail(o.oid)"
          >
            <div class="card-top">
              <span class="card-title">{{ o.title }}</span>
              <span class="status-chip" :class="statusClass(o.status)">{{
                statusText(o.status)
              }}</span>
            </div>
            <div class="card-meta">
              <span v-if="o.period" class="meta-item">周期 {{ o.period }}</span>
              <span v-if="o.targetDate" class="meta-item"
                >截止 {{ o.targetDate }}</span
              >
              <span v-if="!o.period && !o.targetDate" class="meta-item muted"
                >无周期约束</span
              >
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
        </template>
        <div v-else class="empty">
          {{ objStatusFilter === null ? "暂无目标" : "该状态下暂无目标" }}
        </div>
        <!-- 流式加载：哨兵进入视口自动翻页；兜底状态提示 -->
        <div
          v-if="!objLoading && objectives.length"
          :ref="setSentinel"
          data-action="objectives"
          class="load-more"
        >
          <span v-if="objLoadingMore" class="lm-loading"
            ><span class="lm-spinner" />加载中…</span
          >
          <span v-else-if="objHasMore" class="lm-hint">滚动加载更多</span>
          <span v-else class="lm-done">已加载全部 {{ objTotal }} 条</span>
        </div>
      </div>
      <!-- 右：目标详情（md 渲染 + 子项） -->
      <div ref="detailEl" class="obj-detail">
        <div v-if="detailLoading" class="detail-loading">加载中…</div>
        <template v-else-if="detail">
          <div class="detail-head">
            <h2 class="detail-title">{{ detail.title }}</h2>
            <div class="detail-meta">
              <span class="status-chip" :class="statusClass(detail.status)">{{
                statusText(detail.status)
              }}</span>
              <span v-if="detail.period" class="meta-item"
                >周期 {{ detail.period }}</span
              >
              <span v-if="detail.targetDate" class="meta-item"
                >截止 {{ detail.targetDate }}</span
              >
              <span class="meta-item">进度 {{ detail.progress ?? 0 }}%</span>
            </div>
            <div class="detail-progress">
              <div class="progress-track">
                <div
                  class="progress-fill"
                  :class="{ 'is-done': detail.status === 2 }"
                  :style="{ width: `${detail.progress ?? 0}%` }"
                />
              </div>
            </div>
          </div>
          <!-- 子项 checkbox（只读展示态） -->
          <div v-if="detail.subItems.length" class="subitems">
            <div
              v-for="(it, i) in detail.subItems"
              :key="i"
              class="subitem"
              :class="{ 'is-done': it.done }"
            >
              <span class="subitem-box" aria-hidden="true">{{
                it.done ? "✓" : ""
              }}</span>
              <span class="subitem-text">{{ it.text }}</span>
            </div>
          </div>
          <!-- 详情 md 全文（安全渲染） -->
          <div class="detail-md" v-html="renderedHtml" />
        </template>
        <div v-else-if="!objLoading && !detailLoading" class="empty">
          选择左侧目标查看详情
        </div>
      </div>
    </div>
    <!-- ===== 台账 Tab：日期分组时间线（独立 v-if，避免跨注释的 v-else 链被编译器判为断裂） ===== -->
    <div v-if="activeTab === 'ledger'" class="led-stream">
      <div v-if="ledgerLoading" class="skeleton-stack">
        <div v-for="i in 3" :key="i" class="skeleton-card" />
      </div>
      <template v-else-if="ledger.length">
        <div v-for="group in groupedLedger" :key="group.date" class="led-day">
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
                    <span class="meta-time">{{
                      formatTime(it.createTime)
                    }}</span>
                    <span v-if="it.refTaskId" class="ref-tag"
                      >任务 {{ it.refTaskId }}</span
                    >
                    <span v-if="it.refRequirementId" class="ref-tag"
                      >需求 {{ it.refRequirementId }}</span
                    >
                    <span v-if="it.refObjectiveId" class="ref-tag"
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
              <!-- 展开区：正文（markdown 渲染）；加载中显示骨架提示 -->
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
      <!-- 流式加载：哨兵进入视口自动翻页；兜底状态提示 -->
      <div
        v-if="!ledgerLoading && ledger.length"
        :ref="setSentinel"
        data-action="ledger"
        class="load-more"
      >
        <span v-if="ledgerLoadingMore" class="lm-loading"
          ><span class="lm-spinner" />加载中…</span
        >
        <span v-else-if="ledgerHasMore" class="lm-hint">滚动加载更多</span>
        <span v-else class="lm-done">已加载全部 {{ ledgerTotal }} 条</span>
      </div>
    </div>

    <!-- ===== 待办 Tab（M5.2）：状态筛选 + 列表（title/状态徽标/优先级/tags/updateTime） ===== -->
    <div v-if="activeTab === 'todos'" class="todo-list">
      <div v-if="todoLoading" class="skeleton-stack">
        <div v-for="i in 3" :key="i" class="skeleton-card" />
      </div>
      <template v-else>
        <!-- 状态筛选：全部/待办/进行中/已完成/已放弃 -->
        <div class="todo-filter">
          <button
            v-for="f in [
              { v: null, label: '全部' },
              { v: 0, label: '待办' },
              { v: 1, label: '进行中' },
              { v: 2, label: '已完成' },
              { v: 3, label: '已放弃' },
            ]"
            :key="String(f.v)"
            type="button"
            class="filter-btn"
            :class="{ 'is-active': todoStatusFilter === f.v }"
            @click="setTodoStatus(f.v)"
          >
            {{ f.label }}
          </button>
        </div>
        <template v-if="todos.length">
          <div
            v-for="t in todos"
            :key="t.todoId"
            class="todo-item"
          >
            <div class="todo-item-main">
              <div class="todo-item-title-row">
                <span
                  class="todo-priority"
                  :class="priorityClass(t.priority)"
                  >{{ priorityLabel(t.priority) }}</span
                >
                <span class="todo-item-title">{{ t.title }}</span>
                <span
                  class="status-chip"
                  :class="todoStatusClass(t.status)"
                  >{{ todoStatusText(t.status) }}</span
                >
              </div>
              <div class="todo-item-meta">
                <span v-if="t.tags && tagList(t.tags).length" class="todo-tags">
                  <span v-for="tag in tagList(t.tags)" :key="tag" class="tag-pill">{{ tag }}</span>
                </span>
                <span
                  v-if="t.dueDate"
                  class="todo-due"
                  :class="{ 'is-overdue': isTodoOverdue(t.dueDate) }"
                  >截止 {{ t.dueDate.slice(0, 10) }}</span
                >
                <span class="meta-time">
                  更新 {{ t.updateTime ? formatTodoTime(t.updateTime) : "—" }}
                </span>
              </div>
            </div>
          </div>
        </template>
        <div v-else class="empty">暂无待办</div>
        <!-- 流式加载：哨兵进入视口自动翻页；兜底状态提示 -->
        <div
          v-if="todos.length"
          :ref="setSentinel"
          data-action="todos"
          class="load-more"
        >
          <span v-if="todoLoadingMore" class="lm-loading"
            ><span class="lm-spinner" />加载中…</span
          >
          <span v-else-if="todoHasMore" class="lm-hint">滚动加载更多</span>
          <span v-else class="lm-done">已加载全部 {{ todoTotal }} 条</span>
        </div>
      </template>
    </div>

    <!-- ===== 活动 Tab（M6.1 建立；M6.2 两层视图） =====
         第一层（默认）：话题列表（sessions 聚合接口，回答「哪个会话在做什么」）；
         第二层：单会话事件时间线（列表接口 + sessionKey 过滤，limit 200，10s 轮询）。 -->
    <div v-if="activeTab === 'activity'" class="activity-list">
      <!-- 第一层：话题列表 -->
      <template v-if="activityView === 'sessions'">
        <div class="activity-toolbar">
          <span class="activity-hint">最近 {{ sessions.length }} 个会话话题 · 点击行查看事件轨迹</span>
          <button type="button" class="filter-btn" @click="loadSessions()">刷新</button>
        </div>
        <div v-if="sessionsLoading" class="skeleton-stack">
          <div v-for="i in 3" :key="i" class="skeleton-card" />
        </div>
        <template v-else-if="sessions.length">
          <div
            v-for="s in sessions"
            :key="s.sessionKey"
            class="session-item"
            role="button"
            tabindex="0"
            @click="openSession(s)"
            @keydown.enter="openSession(s)"
          >
            <div class="session-head">
              <span class="session-key" :title="s.sessionKey">{{ shortSessionKey(s.sessionKey) }}</span>
              <span v-if="isCronSession(s.sessionKey)" class="flag-badge flag-cron" title="定时任务触发的会话（cron）">⏰ 定时</span>
              <span v-if="s.lastEventTime" class="session-time">{{ formatActivityTime(s.lastEventTime) }}</span>
            </div>
            <div class="session-topic" :title="s.topicSummary || ''">{{
              s.topicSummary || "（无文本输入）"
            }}</div>
            <div class="session-meta">
              <span>回合 {{ s.turnCount }}</span>
              <span>工具 {{ s.toolCallCount }}</span>
              <span :class="{ 'session-err': s.errorCount > 0 }">错误 {{ s.errorCount }}</span>
              <span v-if="s.totalActiveMs != null">累计 {{ durationText(s.totalActiveMs) }}</span>
              <span
                v-for="f in s.flags"
                :key="f"
                class="flag-badge"
                :class="flagClass(f)"
                :title="s.flagDetails?.[f] || flagLabel(f)"
              >{{ flagLabel(f) }}</span>
            </div>
          </div>
        </template>
        <div v-else class="empty">暂无会话活动</div>
      </template>
      <!-- 第二层：单会话事件时间线（异常事件行高亮） -->
      <template v-else>
        <div class="activity-toolbar">
          <button type="button" class="filter-btn" @click="backToSessions">← 返回话题列表</button>
          <span class="activity-hint">{{ shortSessionKey(selectedSession) }} · {{ activities.length }} 条事件 · 10 秒自动刷新</span>
          <button type="button" class="filter-btn" @click="loadActivity()">刷新</button>
        </div>
        <div v-if="activityLoading" class="skeleton-stack">
          <div v-for="i in 3" :key="i" class="skeleton-card" />
        </div>
        <template v-else-if="activities.length">
          <div
            v-for="e in activities"
            :key="e.id"
            class="activity-item"
            :class="{ 'is-abnormal': isAbnormalEvent(e) }"
          >
            <span class="activity-time">{{ e.createTime ? formatActivityTime(e.createTime) : "—" }}</span>
            <span class="activity-type">{{ activityIcon(e) }}</span>
            <span class="activity-summary" :title="e.summary || ''">{{
              summaryText(e.summary) || eventFallbackText(e)
            }}</span>
            <span v-if="e.durationMs != null" class="activity-duration">{{
              durationText(e.durationMs)
            }}</span>
          </div>
        </template>
        <div v-else class="empty">该会话暂无事件</div>
      </template>
    </div>

    <!-- ===== 记忆 Tab（M7.1-M3）：活跃记忆流式列表（memType 徽标 + title + md 正文 + weight/更新时间） ===== -->
    <div v-if="activeTab === 'memory'" class="mem-list">
      <div v-if="memLoading" class="skeleton-stack">
        <div v-for="i in 3" :key="i" class="skeleton-card" />
      </div>
      <template v-else-if="memories.length">
        <div v-for="it in memories" :key="it.id" class="mem-item">
          <div class="mem-item-head">
            <span
              class="type-badge"
              :style="{
                color: memTypeInfo(it.memType).color,
                background: memTypeInfo(it.memType).soft,
              }"
              >{{ memTypeInfo(it.memType).label }}</span
            >
            <span class="mem-item-title">{{ it.title }}</span>
          </div>
          <div
            v-if="memBodyHtmlMap.get(it.id)"
            class="mem-item-md"
            v-html="memBodyHtmlMap.get(it.id)"
          />
          <div class="mem-item-meta">
            <span class="meta-weight">权重 {{ it.weight ?? 0 }}</span>
            <span v-if="it.updateTime" class="meta-time">{{ it.updateTime }}</span>
            <span v-for="t in tagList(it.tags)" :key="t" class="ref-tag">{{ t }}</span>
          </div>
        </div>
      </template>
      <div v-else class="empty">暂无记忆</div>
      <!-- 流式加载：哨兵进入视口自动翻页；兜底状态提示 -->
      <div
        v-if="!memLoading && memories.length"
        :ref="setSentinel"
        data-action="memory"
        class="load-more"
      >
        <span v-if="memLoadingMore" class="lm-loading"
          ><span class="lm-spinner" />加载中…</span
        >
        <span v-else-if="memHasMore" class="lm-hint">滚动加载更多</span>
        <span v-else class="lm-done">已加载全部 {{ memTotal }} 条</span>
      </div>
    </div>

    <!-- ===== 笔记本 Tab：该 agent 名下已关联笔记本卡片网格（与 /notebooks 承接页同风格） ===== -->
    <div v-if="activeTab === 'notebooks'" class="nb-grid-wrap">
      <!-- 加载骨架：3 张占位卡 -->
      <div v-if="nbLoading" class="nb-grid">
        <div v-for="i in 3" :key="i" class="nb-card nb-skel" />
      </div>

      <div v-else-if="agentNotebooks.length" class="nb-grid">
        <div
          v-for="nb in agentNotebooks"
          :key="nb.id"
          class="nb-card"
          role="button"
          tabindex="0"
          :title="nb.name"
          @click="openNotebook(nb)"
          @keydown.enter="openNotebook(nb)"
        >
          <!-- 卡片头：封面（cover 图或首字符色块兜底） -->
          <div class="card-cover">
            <img
              v-if="nb.cover"
              :src="nb.cover"
              class="cover-img"
              :alt="nb.name"
            />
            <div
              v-else
              class="cover-block"
              :style="{ background: coverTone(nb.id) }"
            >
              {{ nb.name.slice(0, 1) }}
            </div>
          </div>

          <!-- 卡片主体：名称 + 简介 -->
          <div class="card-body">
            <div class="card-name">{{ nb.name }}</div>
            <div v-if="nb.description" class="card-desc">
              {{ nb.description }}
            </div>
            <div v-else class="card-desc card-desc--empty">暂无简介</div>
          </div>

          <!-- 卡片脚：统计 + 最近更新 + 进入提示 -->
          <div class="card-foot">
            <span class="foot-stat"
              ><b>{{ nb.noteCount }}</b> 篇 ·
              <b>{{ nb.agentCount }}</b> 位协作者</span
            >
            <span class="foot-time">{{
              nb.lastNoteAt ? relativeTime(nb.lastNoteAt) : "暂无动态"
            }}</span>
            <span class="go-hint">查看笔记 →</span>
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <div v-else class="empty">该智能体暂未关联任何笔记本</div>
    </div>
  </div>
</template>
<style scoped>
/* ============ 页面容器 ============ */
.agent-page {
  max-width: var(--container-max);
  margin: 0 auto;
  padding: 0 var(--container-pad) 40px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
/* ============ Tab 切换条（与内容区左缘严格对齐） ============ */
.tab-bar {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border);
}
.tab-btn {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  padding: 10px 16px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition:
    color 0.2s ease,
    border-color 0.2s ease;
}
.tab-btn:hover {
  color: var(--text-main);
}
.tab-btn.is-active {
  color: var(--accent);
  border-bottom-color: var(--accent);
  font-weight: 600;
}
.tab-count {
  font-size: 11px;
  padding: 0 7px;
  line-height: 18px;
  border-radius: 999px;
  background: var(--bg-panel-2);
  color: var(--text-muted);
  font-family: var(--font-mono);
}
.tab-btn.is-active .tab-count {
  background: var(--accent-soft);
  color: var(--accent);
}
/* 窄屏：tab 数多时横向滑动，不撑爆视口（隐藏滚动条，tab 不可收缩） */
@media (max-width: 768px) {
  .tab-bar {
    overflow-x: auto;
    scrollbar-width: none;
  }
  .tab-bar::-webkit-scrollbar {
    display: none;
  }
  .tab-btn {
    flex-shrink: 0;
  }
}
/* ============ 目标 Tab：左右布局（无边框双栏 + 竖向 hairline 分隔） ============ */
.obj-layout {
  position: relative;
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  column-gap: 40px;
  align-items: start;
}
/* 左↔右 竖向 hairline：贯穿内容区高度，两栏视觉由此对齐
   （与笔记本阅读页的竖向分隔同款；位置 = 左栏宽 + gap 一半） */
.obj-layout::before {
  content: "";
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: calc(340px + 20px);
  width: 1px;
  background: var(--border);
  pointer-events: none;
}
/* 中屏以下折叠为单列（列表上、详情下），竖向分隔线一并隐藏 */
@media (max-width: 1024px) {
  .obj-layout {
    /* minmax(0,1fr)：单列轨道强制可收缩，防左栏 min-content 撑爆窄视口 */
    grid-template-columns: minmax(0, 1fr);
  }
  .obj-layout::before {
    display: none;
  }
}
/* 左列：统计条 + 目标卡片列表 */
.obj-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
/* 目标状态筛选条：胶囊按钮组（复用待办 .filter-btn 样式，交互一致） */
.obj-filter {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
/* 目标卡片（无边框列表项：透明底融入背景，hover 淡底，active 左竖条高亮） */
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
/* 选中态：淡蓝底 + 左侧 2px 钴蓝竖条（与最近更新列表的当前项呼应） */
.obj-card.is-active {
  background: var(--accent-soft);
  box-shadow: inset 2px 0 0 var(--accent);
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
/* 已完成目标：进度条转绿色，与"已完成"状态 chip 呼应 */
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
/* 右列：详情面板（无边框文档区，与左栏以竖向 hairline 分隔） */
.obj-detail {
  padding: 4px 0 0;
  min-height: 320px;
  min-width: 0;
}
.detail-loading {
  padding: 40px;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
}
.detail-head {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 18px;
}
.detail-title {
  margin: 0 0 10px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--text-main);
}
.detail-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.detail-progress .progress-track {
  height: 6px;
}
/* 子项 checkbox（只读） */
.subitems {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}
.subitem {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-panel-2);
}
.subitem.is-done .subitem-text {
  color: var(--text-muted);
  text-decoration: line-through;
}
.subitem-box {
  width: 18px;
  height: 18px;
  border-radius: 5px;
  border: 1px solid var(--border-strong);
  color: #2eb872;
  font-size: 12px;
  line-height: 16px;
  text-align: center;
  flex-shrink: 0;
  margin-top: 1px;
}
.subitem.is-done .subitem-box {
  border-color: #2eb872;
  background: rgba(46, 184, 114, 0.14);
}
.subitem-text {
  font-size: 13px;
  color: var(--text-main);
}
/* md 渲染区：继承全局 prose 样式，补阅读行高 */
.detail-md {
  font-size: 14px;
  color: var(--text-main);
  line-height: 1.75;
}
/* ============ 台账 Tab：时间线 ============ */
.led-stream {
  min-height: 200px;
}
/* 条目外壳：条目行 + 展开区整体圆角包裹（展开时视觉成组） */
.led-item-wrap {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  overflow: hidden;
}
.led-item-wrap + .led-item-wrap {
  margin-top: 8px;
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
/* ============ 记忆 Tab（M7.1-M3）：活跃记忆流式列表 ============ */
.mem-list {
  min-height: 200px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.mem-item {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  padding: 14px 16px;
}
.mem-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mem-item-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
}
.mem-item-md {
  font-size: 13px;
  color: var(--text-main);
  line-height: 1.7;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
  word-break: break-word;
}
.mem-item-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.meta-weight {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}
/* ============ 待办 Tab（M5.2）：状态筛选 + 列表 ============ */
.todo-list {
  min-height: 200px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 状态筛选条：胶囊按钮组（全部/待办/进行中/已完成/已放弃） */
.todo-filter {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.filter-btn {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  color: var(--text-secondary);
  font-size: 12px;
  padding: 4px 14px;
  border-radius: 999px;
  cursor: pointer;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    background 0.15s ease;
}
.filter-btn:hover {
  color: var(--text-main);
  border-color: var(--border-strong);
}
.filter-btn.is-active {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-soft);
  font-weight: 600;
}
/* 待办条目：卡片式列表（与台账条目同风格，无展开区） */
.todo-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
}
.todo-item + .todo-item {
  margin-top: 0;
}
.todo-item-main {
  flex: 1;
  min-width: 0;
}
.todo-item-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.todo-item-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  min-width: 0;
}
/* 优先级徽标：P1/P2 红、P3 琥珀、P4/P5 灰 */
.todo-priority {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  font-family: var(--font-mono);
  padding: 1px 8px;
  border-radius: 6px;
  line-height: 18px;
  white-space: nowrap;
}
.todo-priority.is-high {
  color: #f56c6c;
  background: rgba(245, 108, 108, 0.14);
}
.todo-priority.is-mid {
  color: #e6a23c;
  background: rgba(230, 162, 60, 0.14);
}
.todo-priority.is-low {
  color: #909399;
  background: rgba(144, 147, 153, 0.14);
}
/* 待办元信息：tags + 更新时间 */
.todo-item-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}
/* 待办截止日期（M5.3）：过期标红 */
.todo-due {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}
.todo-due.is-overdue {
  color: #f56c6c;
  font-weight: 600;
}
.todo-tags {
  display: inline-flex;
  gap: 6px;
  flex-wrap: wrap;
}
.tag-pill {
  display: inline-block;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 20px;
  background: var(--accent-soft);
  color: var(--accent);
  border: 1px solid var(--border);
}
/* ============ 骨架 / 空态 ============ */
.skeleton-stack {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.skeleton-card {
  height: 88px;
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
/* ============ 流式加载哨兵/状态提示（目标列表 & 台账流底部共用） ============ */
.load-more {
  padding: 14px 0 4px;
  text-align: center;
  color: var(--text-muted);
  font-size: 12px;
}
.load-more .lm-hint {
  opacity: 0.75;
}
.load-more .lm-done {
  opacity: 0.6;
}
.load-more .lm-loading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--accent);
}
.lm-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: lm-spin 0.8s linear infinite;
}
@keyframes lm-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ============ 笔记本 Tab：卡片网格（v4.23 对齐 /notebooks v4.22 缩小版 6 列密度） ============ */
.nb-grid-wrap {
  min-height: 200px;
}
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
/* 笔记本卡片骨架（16:9 封面后实卡更高，高度对齐 /notebooks v4.22 骨架基准；复用上方 shimmer 动画） */
.nb-skel {
  min-height: 205px;
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

/* 卡片主体：名称 + 简介（两行截断 + 定高防跳动） */
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
  min-height: 35px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-desc--empty {
  color: var(--divider);
}

/* 卡片脚：单行（统计 + 时间；hover 时时间让位、跳转提示浮现，提示绝对定位不占位防跳动） */
.card-foot {
  position: relative;
  display: flex;
  align-items: center;
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
.foot-time {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-muted);
  margin-left: auto;
  white-space: nowrap;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: opacity 0.14s ease;
}
.go-hint {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 11px;
  color: var(--accent);
  white-space: nowrap;
  opacity: 0;
  transition: opacity 0.14s ease;
}
/* hover 时浮现跳转提示，时间顺势让位（与 /notebooks 同款交互） */
.nb-card:hover .go-hint {
  opacity: 1;
}
.nb-card:hover .foot-time {
  margin-left: 0;
}
/* 触屏无 hover：窄屏两列下隐藏悬浮提示，防脚部溢出 */
@media (max-width: 640px) {
  .go-hint {
    display: none;
  }
}

/* ===== 活动 Tab（M6.1）：工具栏 + 时间线行 ===== */
.activity-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.activity-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.activity-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.activity-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
}
.activity-time {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  flex-shrink: 0;
}
.activity-type {
  font-size: 12px;
  color: var(--text-main);
  font-family: var(--font-mono);
  flex-shrink: 0;
  white-space: nowrap;
}
.activity-summary {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.activity-duration {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  flex-shrink: 0;
}

/* ===== M6.2 话题列表（第一层）与诊断徽标 ===== */
.session-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.session-item:hover {
  border-color: var(--accent);
  box-shadow: 0 2px 10px rgba(var(--accent-rgb), 0.12);
}
.session-item:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
.session-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.session-key {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-time {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  flex-shrink: 0;
}
.session-topic {
  font-size: 13px;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--text-muted);
}
.session-err {
  color: #e5484d;
  font-weight: 600;
}
/* 诊断徽标：loop_suspect 红 / long_* 橙 / error_prone 与 stalled 黄（契约 §1.13） */
.flag-badge {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}
.flag-red {
  color: #fff;
  background: #e5484d;
}
.flag-orange {
  color: #4a2b0a;
  background: #ffb86b;
}
.flag-yellow {
  color: #4a3b00;
  background: #ffd43b;
}
/* 定时任务会话徽标（中性灰蓝，非诊断 flag） */
.flag-cron {
  color: #3b4a5a;
  background: #dbe4ee;
}
/* 第二层异常事件行高亮（outcome=failed 或耗时超限） */
.activity-item.is-abnormal {
  border-color: rgba(229, 72, 77, 0.55);
  background: rgba(229, 72, 77, 0.08);
}

/* ============ 概览 Tab（v4.18 方案A：样张配色，纯 CSS 图表，禁图表库） ============ */
.ov-root {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ov-hero {
  display: flex;
  gap: 18px;
  align-items: center;
  padding: 20px 24px;
  background: linear-gradient(135deg, var(--bg-panel), var(--bg-panel-2));
  border: 1px solid var(--border);
  border-radius: 14px;
}
.ov-hero-avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  border: 2px solid var(--border-strong);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 28px;
  font-weight: 800;
  flex-shrink: 0;
  overflow: hidden;
}
.ov-hero-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.ov-hero-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ov-hero-name {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-main);
}
.ov-hero-raw {
  font-size: 13px;
  font-weight: 400;
  color: var(--text-muted);
  margin-left: 8px;
  font-family: var(--font-mono);
}
.ov-badges {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.ov-badge {
  font-size: 12px;
  padding: 2px 9px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.ov-badge.b-live {
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
  border: 1px solid rgba(16, 185, 129, 0.35);
}
.ov-badge.b-model {
  background: rgba(47, 107, 255, 0.1);
  color: #2f6bff;
  border: 1px solid rgba(47, 107, 255, 0.28);
}
.ov-badge.b-role {
  background: rgba(124, 58, 237, 0.1);
  color: #7c3aed;
  border: 1px solid rgba(124, 58, 237, 0.28);
}
/* 概览「N 篇笔记」可点徽章：原顶部身份条入口收编，点击直达笔记本 Tab */
.ov-badge.b-notes {
  background: #eef4ff;
  color: #2f6bff;
  border: 1px solid #c7dbff;
  cursor: pointer;
  font-family: inherit;
}
.ov-badge.b-notes b {
  font-weight: 700;
}
.ov-badge.b-notes .u-dot {
  text-decoration: underline dotted;
  text-underline-offset: 2px;
}
.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
  display: inline-block;
  flex-shrink: 0;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.18);
}
.ov-hero-desc {
  color: var(--text-secondary);
  font-size: 13px;
}
.ov-hero-meta {
  color: var(--text-muted);
  font-size: 12.5px;
}
.ov-acts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}
.ov-btn {
  display: block;
  text-align: center;
  padding: 7px 16px;
  border-radius: 8px;
  border: 1px solid var(--border-strong);
  color: var(--accent);
  font-size: 13px;
  background: var(--bg-panel);
  cursor: pointer;
  white-space: nowrap;
}
.ov-btn.pri {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
}
.ov-kpis {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.ov-card,
.ov-kpi {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.ov-kpi {
  padding: 16px 18px;
  min-width: 0;
}
.ov-kpi .k-label {
  color: var(--text-secondary);
  font-size: 13px;
}
.ov-kpi .k-val {
  font-size: 30px;
  font-weight: 700;
  margin: 6px 0 4px;
  color: var(--text-main);
  font-family: var(--font-mono);
}
.ov-kpi .k-sub {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.6;
}
.ov-grid-2 {
  display: grid;
  grid-template-columns: 1.5fr 1fr;
  gap: 14px;
}
.ov-grid-3 {
  display: grid;
  grid-template-columns: 1.15fr 1fr 1fr;
  gap: 14px;
}
.ov-card {
  padding: 18px 20px;
  min-width: 0;
}
.ov-h3 {
  font-size: 14.5px;
  color: var(--text-main);
  margin: 0 0 14px;
}
.ov-h3 small {
  color: var(--text-muted);
  font-weight: 400;
  margin-left: 8px;
  font-size: 12px;
}
.ov-h3.ov-mt {
  margin-top: 20px;
}
.ov-chart {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 204px;
  padding-top: 6px;
}
.bar-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
  min-width: 0;
}
.ov-bar {
  width: 100%;
  max-width: 34px;
  background: linear-gradient(180deg, #5b8cff, #2f6bff);
  border-radius: 5px 5px 2px 2px;
  position: relative;
}
.ov-bar.gap {
  height: 10px;
  background: repeating-linear-gradient(45deg, #e2e8f0 0 4px, #f1f5f9 4px 8px);
}
.bar-val {
  position: absolute;
  top: -16px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 10.5px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.bar-label {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 6px;
}
.bar-label.gap-label {
  opacity: 0.6;
}
.ov-chart-note {
  color: var(--text-muted);
  font-size: 12px;
  margin-top: 10px;
  line-height: 1.7;
}
.ov-donut-wrap {
  display: flex;
  align-items: center;
  gap: 18px;
}
.ov-donut {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ov-donut-in {
  width: 76px;
  height: 76px;
  border-radius: 50%;
  background: var(--bg-panel);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.ov-donut-in b {
  font-size: 22px;
  color: var(--text-main);
}
.ov-donut-in span {
  font-size: 11px;
  color: var(--text-muted);
}
.ov-legend {
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 2.1;
}
.ov-legend .dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 3px;
  margin-right: 7px;
}
.lt-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 11px 0;
}
.lt-name {
  width: 132px;
  color: var(--text-secondary);
  font-size: 12.5px;
  flex-shrink: 0;
}
.lt-track {
  flex: 1;
  height: 9px;
  background: var(--bg-panel-2);
  border-radius: 99px;
  overflow: hidden;
}
.lt-fill {
  height: 100%;
  border-radius: 99px;
}
.lt-val {
  width: 40px;
  text-align: right;
  color: var(--text-secondary);
  font-size: 12.5px;
  font-family: var(--font-mono);
}
.obj-row {
  margin: 12px 0;
}
.obj-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 5px;
}
.obj-title {
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.obj-meta {
  color: var(--text-muted);
  font-size: 12px;
  flex-shrink: 0;
}
.obj-track {
  height: 8px;
  background: var(--bg-panel-2);
  border-radius: 99px;
  overflow: hidden;
}
.obj-fill {
  height: 100%;
  background: linear-gradient(90deg, #5b8cff, #2f6bff);
  border-radius: 99px;
}
.obj-done {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.8;
}
.lg-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 0;
  border-bottom: 1px solid var(--border);
}
.lg-row:last-child {
  border-bottom: none;
}
.tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 6px;
  white-space: nowrap;
  flex-shrink: 0;
}
.tag.t-blue {
  background: rgba(47, 107, 255, 0.1);
  color: #2f6bff;
}
.tag.t-purple {
  background: rgba(139, 92, 246, 0.12);
  color: #7c3aed;
}
.tag.t-amber {
  background: rgba(245, 158, 11, 0.14);
  color: #b45309;
}
.tag.t-gray {
  background: var(--bg-panel-2);
  color: var(--text-secondary);
}
.tag.t-live {
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
}
.lg-time {
  color: var(--text-muted);
  font-size: 12px;
  white-space: nowrap;
  flex-shrink: 0;
  font-family: var(--font-mono);
}
.lg-title {
  color: var(--text-main);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.ss-row {
  padding: 10px 0;
  border-bottom: 1px solid var(--border);
}
.ss-row:last-child {
  border-bottom: none;
}
.ss-top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.ss-time {
  color: var(--text-muted);
  font-size: 12px;
  flex-shrink: 0;
  font-family: var(--font-mono);
}
.ss-topic {
  color: #64748b;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  font-weight: 400;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.ss-sub {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}
.ss-flags em {
  font-style: normal;
  background: rgba(229, 72, 77, 0.1);
  color: #b91c1c;
  border-radius: 5px;
  padding: 1px 6px;
  margin-left: 5px;
  font-size: 11px;
  white-space: nowrap;
}
.ov-empty {
  padding: 28px 12px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
  border: 1px dashed var(--border);
  border-radius: 8px;
}
@media (max-width: 900px) {
  .ov-kpis,
  .ov-grid-2,
  .ov-grid-3 {
    grid-template-columns: 1fr;
  }
  .ov-hero {
    flex-wrap: wrap;
  }
  .ov-acts {
    flex-direction: row;
  }
}
</style>


