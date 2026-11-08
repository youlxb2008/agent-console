<script setup lang="ts">
import {
  ref,
  computed,
  watch,
  onMounted,
  onBeforeUnmount,
  nextTick,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { renderMarkdown, runMermaid } from "@/composables/useMarkdown";
import { exportNoteToDocx } from "@/utils/docxExporter";
import {
  portalApi,
  type NoteTreeNode,
  type NoteContent,
  type NoteSummary,
  type TagCount,
  type KbHit,
  type KbAsk,
} from "@/api/portal";
import {
  currentNotebook,
  selectNotebookId,
  loadNotebooks,
} from "@/store/notebook";
import TreeNode from "@/components/TreeNode.vue";
import ImageViewer from "@/components/ImageViewer.vue";

/**
 * 笔记本视图（图二复刻：无边框三栏文档布局；v3 笔记本化）
 * - 左栏（sticky）：笔记本信息卡 + 标签 chips + 目录区（双视图胶囊切换：
 *   "目录"=树形目录；"最近更新"=调 /notebooks/{id}/notes/recent 的扁平 list，最多 20 条）
 *   （笔记本切换下拉与返回概览已上移至顶栏，见 App.vue）
 * - 中栏：搜索框 + 复制链接（顶部行）→ 面包屑 → 文档标题/元信息 → 正文 → 贡献者 chips → 上一篇/下一篇
 * - 右栏（sticky）：大纲 scroll-spy 锚点导航
 * - 三栏均无面板边框与底色，直接融入页面背景；顶部保留 2px 钴蓝阅读进度条
 * - v3.5：🔥 删 path 兜底读（数据驱动树 nid 恒有值）；✨ 贡献者 chips 纯展示（不承担切换导航）
 * - M3.8：路由 RESTful 化——/notebooks/{notebookId}[/notes/{nid}]，notebookId/nid 从 route.params 取
 */

const route = useRoute();
const router = useRouter();

/* ============ 状态（笔记本列表/选择来自全局 store，见 store/notebook.ts） ============ */
const tree = ref<NoteTreeNode[]>([]);
const treeLoading = ref(false);

const currentNote = ref<NoteContent | null>(null);
const contentLoading = ref(false);
const contentError = ref("");

const currentKey = ref<string | null>(null);

let lastTreeNotebookId: string | null = null;

const notebookId = computed<string | null>(() => {
  const v = route.params.notebookId;
  return typeof v === "string" && v ? v : null;
});

const renderedHtml = computed<string>(() => {
  let md = currentNote.value?.contentMd || "";
  if (!md) return "";
  // 剥离正文开头的首个 h1：标题改由独立的 doc-title 展示，避免页面出现两个大标题
  md = md.replace(/^\uFEFF?\s*#\s+[^\n]*\n+/, "");
  // marked + DOMPurify + mermaid 占位（composables/useMarkdown.ts）
  return renderMarkdown(md);
});

const crumbs = computed<string[]>(() => {
  if (!currentNote.value) return [];
  const dirs = currentNote.value.dirPath
    ? currentNote.value.dirPath.split("/").filter(Boolean)
    : [];
  return [...dirs, currentNote.value.title];
});

/* ============ 阅读进度条 ============ */
const readProgress = ref(0);

/* 中栏滚动容器（app-shell：页面整体不滚，阅读进度 / 大纲高亮都以它为基准） */
const contentScrollEl = ref<HTMLElement | null>(null);

function updateReadProgress(): void {
  const el = contentScrollEl.value;
  if (!el) return;
  const max = el.scrollHeight - el.clientHeight;
  readProgress.value = max > 0 ? Math.min(100, (el.scrollTop / max) * 100) : 0;
}

/* ============ 数据加载 ============ */
async function loadFromRoute(): Promise<void> {
  const nbid = notebookId.value;
  if (!nbid) return;
  if (lastTreeNotebookId !== nbid) {
    lastTreeNotebookId = nbid;
    tree.value = [];
    currentNote.value = null;
    currentKey.value = null;
    contentError.value = "";
    treeLoading.value = true;
    try {
      tree.value = (await portalApi.tree(nbid)) || [];
    } catch {
      tree.value = [];
    } finally {
      treeLoading.value = false;
    }
  }
  await loadContent();
  // 切笔记后回到顶部（中栏滚动容器），刷新阅读进度
  contentScrollEl.value?.scrollTo({
    top: 0,
    behavior: "instant" as ScrollBehavior,
  });
  updateReadProgress();
}

async function loadContent(): Promise<void> {
  const nbid = notebookId.value;
  const nid = typeof route.params.nid === "string" ? route.params.nid : "";
  if (!nbid) {
    currentNote.value = null;
    return;
  }
  if (nid) {
    currentKey.value = nid;
    await fetchContent(() => portalApi.contentByNid(nid));
  } else {
    currentKey.value = null;
    currentNote.value = null;
    contentError.value = "";
  }
}

async function fetchContent(fn: () => Promise<NoteContent>): Promise<void> {
  contentLoading.value = true;
  contentError.value = "";
  try {
    currentNote.value = await fn();
  } catch (e: unknown) {
    currentNote.value = null;
    contentError.value = (e as { message?: string })?.message || "笔记加载失败";
  } finally {
    contentLoading.value = false;
  }
}

/* ============ 交互 ============ */
function onNodeOpen(node: NoteTreeNode): void {
  goNote(node);
}

function onCrumbClick(): void {
  const nbid = notebookId.value;
  if (!nbid) return;
  void router.replace(`/notebooks/${nbid}`);
}

/** 目录摘要：初次进入时展示给用户，帮助快速理解笔记本规模 */
const notebookOverview = computed(() => {
  let folderCount = 0;
  let noteCount = 0;
  const walk = (nodes: NoteTreeNode[]): void => {
    nodes.forEach((node) => {
      if (node.type === "folder") {
        folderCount += 1;
        walk(node.children || []);
      } else {
        noteCount += 1;
      }
    });
  };
  walk(tree.value);
  return { folderCount, noteCount };
});

/** 左栏笔记本首字符 */
const notebookInitial = computed<string>(() => {
  const n = currentNotebook.value;
  return (n ? n.name || "?" : "?").slice(0, 1);
});

/** 左栏笔记本名 */
const currentNotebookLabel = computed<string>(() => {
  const n = currentNotebook.value;
  return n ? n.name : "…";
});

/** 封面色底循环（与 HomeView 一致） */
function avatarTone(id: number | undefined): string {
  if (!id) return "var(--tone-blue)";
  const tones = [
    "var(--tone-blue)",
    "var(--tone-teal)",
    "var(--tone-green)",
    "var(--tone-amber)",
    "var(--tone-violet)",
  ];
  return tones[id % tones.length];
}

/**
 * 正文字数（口径：中文按字、英文/数字按词）
 * 计数前先剔除 ``` 围栏代码块与 ![alt](url) 图片语法；
 * markdown 标记（# ** - > 等）与标点不匹配任何分支，自然不计，
 * 避免旧实现「去空白字符数」把语法符号全算进去导致虚高
 */
const wordCount = computed<number>(() => {
  const md = currentNote.value?.contentMd || "";
  // 剔除围栏代码块（代码内容不代表正文写作量）
  const text = md
    .replace(/```[\s\S]*?```/g, "")
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "");
  // 中文字符逐个计数
  const chinese = (text.match(/[\u4e00-\u9fa5]/g) || []).length;
  // 连续英文字母串算 1 个词（如 docker、API）
  const english = (text.match(/[a-zA-Z]+/g) || []).length;
  // 连续数字串算 1 个词（如 2026、8000）
  const numbers = (text.match(/[0-9]+/g) || []).length;
  return chinese + english + numbers;
});

/* ============ 搜索（v4.1：升级 KB 混合检索——标题+BM25+语义，带命中片段） ============ */
const searchBoxEl = ref<HTMLElement | null>(null);
const searchKeyword = ref("");
const searchOpen = ref(false);
const searchLoading = ref(false);
const searchResults = ref<KbHit[]>([]);
let searchTimer: ReturnType<typeof setTimeout> | null = null;

function onSearchInput(): void {
  if (searchTimer) clearTimeout(searchTimer);
  if (!searchKeyword.value.trim()) {
    clearSearch();
    return;
  }
  searchTimer = setTimeout(() => void runSearch(), 300);
}

function onSearchEnter(): void {
  if (searchTimer) clearTimeout(searchTimer);
  void runSearch();
}

function onSearchFocus(): void {
  if (
    searchKeyword.value.trim() &&
    searchResults.value.length &&
    !searchOpen.value
  ) {
    searchOpen.value = true;
  }
}

async function runSearch(): Promise<void> {
  const kw = searchKeyword.value.trim();
  const nbid = notebookId.value;
  if (!kw || !nbid) {
    clearSearch();
    return;
  }
  searchLoading.value = true;
  try {
    // KB 混合检索：范围限当前笔记本；后端三路召回（标题/BM25/语义）RRF 融合
    searchResults.value =
      (await portalApi.kbSearch({ q: kw, notebookId: nbid, limit: 20 })) || [];
    searchOpen.value = true;
  } catch {
    searchResults.value = [];
    searchOpen.value = false;
  } finally {
    searchLoading.value = false;
  }
}

function clearSearch(): void {
  if (searchTimer) clearTimeout(searchTimer);
  searchOpen.value = false;
  searchResults.value = [];
}

/** 命中来源标签：语义 / 全文 / 标题（对应后端 sources: vector/bm25/title） */
function sourceLabel(s: string): string {
  return s === "vector" ? "语义" : s === "bm25" ? "全文" : "标题";
}

/** 点击 KB 命中项 → 跳转该笔记（notebookId 用命中项自身的，支持跨笔记本命中兜底） */
function goHit(hit: KbHit): void {
  if (!hit.nid) return;
  clearSearch();
  clearTag();
  void router.push(`/notebooks/${hit.notebookId}/notes/${hit.nid}`);
}

/* ============ AI 问答（RAG）：搜索词作为问题，答案面板替换正文区 ============ */
const askActive = ref(false);
const askLoading = ref(false);
const askAnswer = ref("");
const askRefs = ref<KbAsk["references"]>([]);
const askQuestionText = ref("");

/** 答案 md → 安全 HTML（renderMarkdown 内部经 DOMPurify 消毒） */
const askAnswerHtml = computed<string>(() =>
  askAnswer.value ? renderMarkdown(askAnswer.value) : "",
);

/** 触发 AI 问答（入口：搜索下拉底部「问 AI」按钮；问题=当前搜索词） */
async function runAsk(): Promise<void> {
  const q = searchKeyword.value.trim();
  const nbid = notebookId.value;
  if (!q || !nbid || askLoading.value) return;
  askActive.value = true;
  askQuestionText.value = q;
  askAnswer.value = "";
  askRefs.value = [];
  clearSearch();
  askLoading.value = true;
  try {
    const res = await portalApi.kbAsk({ question: q, notebookId: nbid });
    askAnswer.value = res?.answer || "（空回答）";
    askRefs.value = res?.references || [];
  } catch (e: unknown) {
    askAnswer.value = `问答失败：${
      (e as { message?: string })?.message || "请稍后重试"
    }`;
  } finally {
    askLoading.value = false;
  }
}

/** 关闭问答面板，回到正文/空态 */
function closeAsk(): void {
  askActive.value = false;
  askAnswer.value = "";
  askRefs.value = [];
}

/** 点击引用 chip → 跳转对应笔记原文 */
function goRef(r: KbAsk["references"][number]): void {
  if (!r.nid) return;
  closeAsk();
  void router.push(`/notebooks/${r.notebookId}/notes/${r.nid}`);
}

/* ============ tags ============ */
const tags = ref<TagCount[]>([]);
const tagsLoading = ref(true);
const activeTag = ref("");
const tagLoading = ref(false);
const tagResults = ref<NoteSummary[]>([]);
/* 标签折叠：默认最多两行，超出可展开/收起 */
const tagsExpanded = ref(false);
const tagsOverflowing = ref(false);
const tagsListRef = ref<HTMLElement | null>(null);

function checkTagsOverflow(): void {
  const el = tagsListRef.value;
  tagsOverflowing.value = !!el && el.scrollHeight > el.clientHeight + 1;
}

async function loadTags(nbid: string): Promise<void> {
  tagsLoading.value = true;
  try {
    tags.value = (await portalApi.noteTags(nbid)) || [];
  } catch {
    tags.value = [];
  } finally {
    tagsLoading.value = false;
  }
  await nextTick();
  checkTagsOverflow();
}

function onTagClick(t: TagCount): void {
  if (activeTag.value === t.tag) {
    clearTag();
    return;
  }
  activeTag.value = t.tag;
  void runByTag();
}

async function runByTag(): Promise<void> {
  const nbid = notebookId.value;
  const tag = activeTag.value;
  if (!nbid || !tag) return;
  tagLoading.value = true;
  try {
    tagResults.value =
      (await portalApi.notesByTag({ notebookId: nbid, tag })) || [];
  } catch {
    tagResults.value = [];
  } finally {
    tagLoading.value = false;
  }
}

function clearTag(): void {
  activeTag.value = "";
  tagResults.value = [];
}

function goSummary(item: NoteSummary): void {
  // v3.5：结果本就限定在当前笔记本内，深链带当前 notebookId + nid 即可（无歧义）；M3.8：路径式
  const nbid = notebookId.value;
  if (!item.nid || !nbid) return;
  clearSearch();
  clearTag();
  void router.push(`/notebooks/${nbid}/notes/${item.nid}`);
}

function onDocClick(e: MouseEvent): void {
  const el = searchBoxEl.value;
  if (el && !el.contains(e.target as Node)) {
    searchOpen.value = false;
  }
  // 点击分享菜单之外也收起：利用 .share-menu 容器 contains 判断（容器本身已 @click.stop）
  const sm = (e.target as HTMLElement | null)?.closest?.(".share-menu");
  if (!sm) {
    shareMenuOpen.value = false;
  }
}

/* ============ 目录排序 + 最近更新列表 + 大纲 + scroll-spy ============
   sortMode 双语义（胶囊按钮文案："目录" / "最近更新"）：
   - 'name'：目录视图——树形目录（按名称排序的树形结构）
   - 'time'：最近更新——不再是"树按时间排序"，而是切换为扁平 list，
     调后端 /notebooks/{id}/notes/recent 接口展示最多 20 条（按 updateTime 倒序） */
type SortMode = "name" | "time";
const sortMode = ref<SortMode>(
  (() => {
    try {
      return localStorage.getItem("portal-tree-sort") === "time"
        ? "time"
        : "name";
    } catch {
      return "name";
    }
  })(),
);

/* ---- 最近更新列表状态 ---- */
const recentNotes = ref<NoteSummary[]>([]);
const recentLoading = ref(false);
/** 记录 recent 数据对应的 notebookId，避免同一笔记本重复请求 */
let recentLoadedForNotebook: string | null = null;

/** 拉取最近更新（最多 20 条，后端按 updateTime 倒序返回） */
async function loadRecent(nbid: string): Promise<void> {
  if (recentLoadedForNotebook === nbid) return;
  recentLoading.value = true;
  try {
    recentNotes.value =
      (await portalApi.recentNotes({ notebookId: nbid, limit: 20 })) || [];
    recentLoadedForNotebook = nbid;
  } catch {
    recentNotes.value = [];
  } finally {
    recentLoading.value = false;
  }
}

function setSortMode(m: SortMode): void {
  sortMode.value = m;
  // 切到"最近更新"时按需拉取列表
  if (m === "time") {
    const nbid = notebookId.value;
    if (nbid) void loadRecent(nbid);
  }
  try {
    localStorage.setItem("portal-tree-sort", m);
  } catch {
    // localStorage 不可用时静默降级
  }
}

/** name 模式的树排序：文件夹在前，同类型按中文名字典序 */
function compareNodes(a: NoteTreeNode, b: NoteTreeNode): number {
  if (a.type !== b.type) return a.type === "folder" ? -1 : 1;
  if (a.type === "folder") return a.name.localeCompare(b.name, "zh");
  return a.name.localeCompare(b.name, "zh");
}

function sortTree(nodes: NoteTreeNode[]): NoteTreeNode[] {
  return [...nodes]
    .sort(compareNodes)
    .map((n) =>
      n.type === "folder" && n.children?.length
        ? { ...n, children: sortTree(n.children) }
        : n,
    );
}

const sortedTree = computed<NoteTreeNode[]>(() => sortTree(tree.value));

/**
 * notes-mobile-v1：当前笔记的祖先 folder 名序列（DFS；无命中/未选中返回空数组）。
 * 根级 TreeNode 传入后逐层下传 slice(1)，手机端命中层自动展开
 */
const activeTrail = computed<string[]>(() => {
  if (!currentKey.value) return [];
  const path: string[] = [];
  const dfs = (nodes: NoteTreeNode[]): boolean => {
    for (const n of nodes) {
      if (n.type === "note") {
        if (n.nid === currentKey.value) return true;
      } else {
        path.push(n.name);
        if (n.children?.length && dfs(n.children)) return true;
        path.pop();
      }
    }
    return false;
  };
  dfs(sortedTree.value);
  return path;
});

/** 最近更新项是否为当前打开的笔记（nid 比对） */
function isRecentActive(nid: string | null): boolean {
  return !!nid && nid === currentKey.value;
}

const markdownBodyEl = ref<HTMLElement | null>(null);
const outline = ref<{ id: string; text: string; level: number }[]>([]);
const activeHeadingId = ref("");

function slug(s: string): string {
  return (
    s
      .trim()
      .replace(/\s+/g, "-")
      .replace(/[^\w\u4e00-\u9fa5-]/g, "")
      .slice(0, 40) || "h"
  );
}

function scanMarkdownDom(): void {
  outline.value = [];
  const container = markdownBodyEl.value;
  if (!container) return;
  const headings = Array.from(
    container.querySelectorAll("h1, h2, h3"),
  ) as HTMLElement[];
  const items: { id: string; text: string; level: number }[] = [];
  headings.forEach((h, i) => {
    if (!h.id) h.id = `portal-head-${i}-${slug(h.textContent || "")}`;
    items.push({
      id: h.id,
      text: (h.textContent || "").trim(),
      level: Number(h.tagName.slice(1)),
    });
  });
  outline.value = items;

  container.querySelectorAll("pre").forEach((pre) => {
    /* 已包过 wrapper（重复扫描）直接跳过，避免嵌套 */
    if (pre.parentElement?.classList.contains("code-block-wrap")) return;
    /*
     * 用 .code-block-wrap 包住 pre，复制按钮挂在 wrapper 上（而非 pre 内）：
     * pre 是 overflow-x:auto 的滚动容器，按钮若 absolute 在 pre 内，
     * 长代码横向滚动时按钮会随内容滚出视野；挂 wrapper 上则始终固定在可视区右上。
     */
    const wrap = document.createElement("div");
    wrap.className = "code-block-wrap";
    pre.parentNode?.insertBefore(wrap, pre);
    wrap.appendChild(pre);

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "code-copy-btn";
    btn.setAttribute("aria-label", "复制代码");
    btn.title = "复制代码";
    /* 结构：复制图标（feather 风格 stroke SVG，跟随 currentColor）+ 文字。
       复制成功只切换 .code-copy-tx 文字并加 .is-copied 类，图标保留不重建 */
    btn.innerHTML =
      '<svg class="code-copy-ic" viewBox="0 0 24 24" width="13" height="13"' +
      ' fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"' +
      ' stroke-linejoin="round" aria-hidden="true">' +
      '<rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>' +
      '<path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>' +
      "</svg>" +
      '<span class="code-copy-tx">复制</span>';
    wrap.appendChild(btn);
  });
}

/* 大纲锚线：标题视为"已到达"的容器内偏移。点击跳转与滚动高亮必须共用
   同一基准，否则点击后标题停在锚线上方之外，高亮会停留在上一个标题
   （对不准） */
const OUTLINE_ANCHOR_OFFSET = 140;

function onOutlineClick(id: string): void {
  const el = document.getElementById(id);
  const sc = contentScrollEl.value;
  if (!el || !sc) return;
  /* 不用 scrollIntoView(block:"start")：它把标题顶到容器可视区 0px（会被
     锚线判定为"未到达"），改用容器 scrollTo 让标题正好停在锚线上，
     与高亮判定一致 */
  const top =
    el.getBoundingClientRect().top -
    sc.getBoundingClientRect().top +
    sc.scrollTop;
  sc.scrollTo({ top: top - OUTLINE_ANCHOR_OFFSET, behavior: "smooth" });
}

function onContentScroll(): void {
  updateReadProgress();
  const sc = contentScrollEl.value;
  if (!sc || !outline.value.length || !markdownBodyEl.value) return;
  /* 锚线（视口坐标）：容器可视区顶部 + 偏移。标题的视口 top 与之直接比较 */
  const anchorY = sc.getBoundingClientRect().top + OUTLINE_ANCHOR_OFFSET;
  let active = "";
  for (const item of outline.value) {
    const el = document.getElementById(item.id);
    if (el && el.getBoundingClientRect().top <= anchorY) active = item.id;
  }
  /* 容器滚到底兜底：最后一个标题可能永远够不到锚线（尾部内容不足一屏），
     此时直接高亮最后一项，避免停在倒数第二个 */
  if (
    sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 2 &&
    outline.value.length
  ) {
    active = outline.value[outline.value.length - 1].id;
  }
  activeHeadingId.value = active;
}

/* ============ 复制链接 ============ */
const copied = ref(false);

function currentDeepLink(): string | null {
  const nbid = notebookId.value;
  const nid = currentNote.value?.nid;
  if (!nbid || !nid) return null;
  return `${location.origin}/notebooks/${encodeURIComponent(nbid)}/notes/${encodeURIComponent(nid)}`;
}

async function writeClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // 降级
  }
  try {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}

async function copyLink(): Promise<void> {
  const url = currentDeepLink();
  if (!url) return;
  const ok = await writeClipboard(url);
  if (ok) {
    copied.value = true;
    setTimeout(() => {
      copied.value = false;
    }, 1500);
  }
}

/* ============ 分享下拉菜单（顶部三个动作收进一个入口） ============ */
const shareMenuOpen = ref(false);

/** 三种复制中只要任意一个刚成功都显示 ✓，提示用户 */
const copiedAny = computed(() => copied.value || copiedMd.value);

/**
 * 统一处理下拉菜单项点击：
 *   - 复制类成功后默认 1.2s 关闭菜单、保留 ✓ 一瞬；
 *   - 下载类动作直接关闭。
 * 入口 ref 是 onDocClick 关闭下拉的关窍（见下方函数）。
 */
async function onShareItem(
  action: "copyLink" | "copyMdLink" | "downloadMd" | "downloadDocx",
): Promise<void> {
  if (action === "copyLink") {
    await copyLink();
  } else if (action === "copyMdLink") {
    await copyMdLink();
  } else if (action === "downloadMd") {
    downloadMd();
  } else {
    // docx 生成较慢（mermaid 图/内嵌图片要现渲染现抓取）：先收菜单再异步生成，
    // 期间通过 docxExporting 防重复触发，完成后浏览器自动弹出下载
    shareMenuOpen.value = false;
    await downloadDocx();
    return;
  }
  // 复制类动作完成后再短暂保留一会儿让用户看见反馈，再关
  shareMenuOpen.value = false;
}

/** 点击页面其它位置关闭分享菜单 */
function onShareMenuOutsideClick(): void {
  shareMenuOpen.value = false;
}

/* ============ 下载 markdown（功能1：把笔记原文喂给其他智能体） ============ */

/** 下载文件名安全化：替换 Windows 非法字符（\ / : * ? " < > |），空标题用 nid 兜底 */
function noteFileBase(): string {
  const n = currentNote.value;
  const raw = (n?.title || n?.nid || "note").trim();
  return raw.replace(/[\\/:*?"<>|]/g, "_");
}

/** .md 下载文件名 */
function mdFileName(): string {
  return noteFileBase() + ".md";
}

/** .docx 下载文件名（与 .md 同名不同扩展，方便两份文件配对） */
function docxFileName(): string {
  return noteFileBase() + ".docx";
}

/**
 * 下载当前笔记的 markdown 原文。
 * contentMd 已随详情接口（/api/portal/notes/{nid}）返回，纯前端 Blob 即可，无需再请求后端。
 */
function downloadMd(): void {
  const md = currentNote.value?.contentMd ?? "";
  if (!md) return;
  const blob = new Blob([md], { type: "text/markdown;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = mdFileName();
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // 释放 Blob URL，避免内存泄漏
  URL.revokeObjectURL(url);
}

/* ============ 下载 word（功能4：md → docx，mermaid/内嵌图片转图片嵌入） ============ */

/** docx 生成中标记：防重复点击；菜单重开时按钮文案切「生成中…」并禁用 */
const docxExporting = ref(false);

/**
 * 生成并下载当前笔记的 Word 文档。
 * 纯前端转换（utils/docxExporter.ts）：
 * - 标题/元信息行照搬页面 doc-title/doc-meta
 * - mermaid 代码块 → 浏览器渲染 SVG → 2x 光栅化 PNG 嵌入（与页面同款 mermaid 实例）
 * - 内嵌图片（/uploads/... 或外链）→ 同源 fetch → 原样/转 PNG 嵌入，限宽等比缩放
 * - 标题/段落/代码块/引用/列表/表格/超链接逐 token 映射为 docx 元素
 */
async function downloadDocx(): Promise<void> {
  const n = currentNote.value;
  const md = n?.contentMd ?? "";
  // 无正文或正在生成时忽略；避免 mermaid 渲染期间重复触发
  if (!md || docxExporting.value) return;
  docxExporting.value = true;
  try {
    // 元信息行与页面 doc-meta 同源：更新时间 · 目录路径 · 字数
    const metaParts: string[] = [];
    if (n?.updateTime) metaParts.push(n.updateTime);
    if (n?.dirPath) metaParts.push(n.dirPath);
    metaParts.push(`${wordCount.value} 字`);
    // 核心转换：marked 解析 + mermaid/图片异步嵌入，产出 docx Blob
    const blob = await exportNoteToDocx({
      title: n?.title || "",
      contentMd: md,
      metaLine: metaParts.join(" · "),
    });
    // Blob → 隐形 <a download> 触发浏览器另存（与 downloadMd 同款手法）
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = docxFileName();
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    // 释放 Blob URL，避免内存泄漏
    URL.revokeObjectURL(url);
  } catch (e) {
    // 转换失败（如个别图片解码异常/mermaid 全局报错）弹窗兜底，不打断页面
    alert("导出 Word 失败：" + ((e as Error)?.message || String(e)));
  } finally {
    docxExporting.value = false;
  }
}

/* ============ 复制给智能体的 markdown 链接（功能2） ============ */
const copiedMd = ref(false);

/**
 * 当前笔记的 markdown 原文 API：
 * 后端 GET /api/portal/notes/{nid}/raw 直接返回 text/markdown 纯文本（无 ResultVO JSON 包装），
 * 智能体/脚本 fetch 该 URL 拿到的即是正文本身，无需解析 JSON 包裹；匿名只读。
 */
function currentRawMdUrl(): string | null {
  const nid = currentNote.value?.nid;
  if (!nid) return null;
  return `${location.origin}/api/portal/notes/${encodeURIComponent(nid)}/raw`;
}

async function copyMdLink(): Promise<void> {
  const url = currentRawMdUrl();
  if (!url) return;
  const ok = await writeClipboard(url);
  if (ok) {
    copiedMd.value = true;
    setTimeout(() => {
      copiedMd.value = false;
    }, 1500);
  }
}

/* ============ 左栏宽度拖拽（功能3：分隔线可左右拉动） ============ */
const SIDE_W_KEY = "portal-side-left-w"; // localStorage 持久化 key
const SIDE_W_MIN = 160; // 下限：保证目录文字不挤成一团
const SIDE_W_MAX = 420; // 上限：正文区至少留出舒适行宽
const SIDE_W_DEFAULT = 220; // 默认宽（与原设计稿一致），双击手柄可恢复

/** 左栏宽度（px）：绑定到 .notebook-body 的 --side-left-w，grid 列与左分隔线位置随之更新 */
const sideW = ref<number>(
  (() => {
    // 初始化时恢复上次的宽度（IIFE 与上方 sortMode 同款写法）
    try {
      const v = Number(localStorage.getItem(SIDE_W_KEY));
      if (Number.isFinite(v) && v >= SIDE_W_MIN && v <= SIDE_W_MAX) return v;
    } catch {
      // localStorage 不可用时用默认值
    }
    return SIDE_W_DEFAULT;
  })(),
);

const dragging = ref(false);

/** 拖拽起点状态：起始鼠标 X + 起始宽度（增量计算基准） */
let dragStartX = 0;
let dragStartW = 0;

/** 绑定到 .notebook-body 的 CSS 变量样式 */
const notebookBodyStyle = computed(() => ({
  "--side-left-w": `${sideW.value}px`,
}));

/** 拖拽开始：记录起点，监听 window 级 move/up（鼠标移出手柄也能继续拖） */
function onDragStart(e: MouseEvent): void {
  dragStartX = e.clientX;
  dragStartW = sideW.value;
  dragging.value = true;
  // 拖拽期间禁用文本选中，避免鼠标划过正文时选一片
  document.body.style.userSelect = "none";
  window.addEventListener("mousemove", onDragMove);
  window.addEventListener("mouseup", onDragEnd);
}

/** 拖拽中：宽度 = 起始宽 + 鼠标 X 增量，clamp 到 [MIN, MAX] */
function onDragMove(e: MouseEvent): void {
  if (!dragging.value) return;
  const w = dragStartW + (e.clientX - dragStartX);
  sideW.value = Math.min(SIDE_W_MAX, Math.max(SIDE_W_MIN, w));
}

/** 拖拽结束：解绑监听 + 持久化宽度 */
function onDragEnd(): void {
  if (!dragging.value) return;
  dragging.value = false;
  document.body.style.userSelect = "";
  window.removeEventListener("mousemove", onDragMove);
  window.removeEventListener("mouseup", onDragEnd);
  try {
    localStorage.setItem(SIDE_W_KEY, String(sideW.value));
  } catch {
    // localStorage 不可用时静默降级（仅影响下次刷新不记忆）
  }
}

/** 双击手柄：恢复默认宽度并持久化 */
function onDragReset(): void {
  sideW.value = SIDE_W_DEFAULT;
  try {
    localStorage.setItem(SIDE_W_KEY, String(SIDE_W_DEFAULT));
  } catch {
    // 忽略
  }
}

/* ============ 代码块复制 ============ */
let copiedBtn: HTMLElement | null = null;

/* ============ 图形查看器（图片 / mermaid 放大 + 下载） ============ */
const viewerRef = ref<InstanceType<typeof ImageViewer> | null>(null);

function onMarkdownBodyClick(e: MouseEvent): void {
  const target = e.target as HTMLElement;

  /* ① 代码复制按钮（既有功能；按钮挂在 .code-block-wrap 上，从 wrapper 里找 pre 取码） */
  const btn = target.closest(".code-copy-btn") as HTMLElement | null;
  if (btn) {
    const pre = btn.closest(".code-block-wrap")?.querySelector("pre");
    if (!pre) return;
    const code = pre.querySelector("code");
    const text = code ? code.textContent || "" : pre.textContent || "";
    void doCopyCode(btn, text);
    return;
  }

  /* ② 普通图片：直接用 src 打开查看器 */
  if (target.tagName === "IMG") {
    e.preventDefault();
    const img = target as HTMLImageElement;
    const name = img.getAttribute("alt") || img.src.split("/").pop() || "image";
    viewerRef.value?.open({ type: "img", src: img.src, name });
    return;
  }

  /* ③ mermaid 图表：向上查找最近的 .mermaid 容器，取其 SVG 源码 */
  const mermaidWrap = target.closest(".mermaid") as HTMLElement | null;
  if (mermaidWrap) {
    const svg = mermaidWrap.querySelector("svg");
    if (svg) {
      e.preventDefault();
      /* 克隆 SVG 并补充命名空间属性，确保独立使用时能正确渲染 */
      const clone = svg.cloneNode(true) as SVGElement;
      clone.setAttribute("xmlns", "http://www.w3.org/2000/svg");
      clone.setAttribute("xmlns:xlink", "http://www.w3.org/1999/xlink");
      /*
       * 清理「页面呈现用」的尺寸痕迹：正文中 svg 被 constrainMermaidSize
       * 注入了 width:80% / height:70vh 等内联样式，且 mermaid 自带
       * max-width / width="100%"——这些进入 data URL 后没有容器上下文，
       * 会导致查看器/下载图尺寸异常；清掉后交给 viewBox + 查看器
       * 的 max-width:90vw / max-height:80vh 约束，按原始比例自适应。
       */
      clone.removeAttribute("style");
      clone.removeAttribute("width");
      clone.removeAttribute("height");
      /*
       * 必须用 XMLSerializer 而非 outerHTML 序列化：
       * SVG 是 XML 格式，内部 foreignObject 可能含 <br> 等 HTML 空标签，
       * outerHTML（HTML 序列化器）输出未闭合 <br>，导致 .svg 文件 XML 解析报错；
       * XMLSerializer 会正确输出自闭合 <br/>，保证文件合法。
       */
      const svgStr = new XMLSerializer().serializeToString(clone);
      viewerRef.value?.open({
        type: "svg",
        rawSvg: svgStr,
        dataUrl:
          "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svgStr),
        name: "mermaid-diagram",
      });
    }
  }
}

async function doCopyCode(btn: HTMLElement, text: string): Promise<void> {
  const ok = await writeClipboard(text);
  if (!ok) return;
  /* 只切换文字 span（图标保留），并加 .is-copied 成功态样式（绿色底） */
  const tx = btn.querySelector(".code-copy-tx");
  const setTx = (s: string): void => {
    if (tx) tx.textContent = s;
    else btn.textContent = s;
  };
  /* 上一颗还在"已复制"态的按钮先复位，避免多个按钮同时绿 */
  if (copiedBtn && copiedBtn !== btn) {
    const prevTx = copiedBtn.querySelector(".code-copy-tx");
    if (prevTx) prevTx.textContent = "复制";
    else copiedBtn.textContent = "复制";
    copiedBtn.classList.remove("is-copied");
  }
  setTx("已复制 ✓");
  btn.classList.add("is-copied");
  copiedBtn = btn;
  setTimeout(() => {
    if (copiedBtn === btn) {
      setTx("复制");
      btn.classList.remove("is-copied");
      copiedBtn = null;
    }
  }, 1500);
}

/* ============ 上一篇/下一篇 ============ */
const prevNote = ref<NoteTreeNode | null>(null);
const nextNote = ref<NoteTreeNode | null>(null);

function updatePrevNext(): void {
  prevNote.value = null;
  nextNote.value = null;
  const nid = currentNote.value?.nid;
  if (!nid) return;
  const walk = (nodes: NoteTreeNode[]): NoteTreeNode[] | null => {
    for (const n of nodes) {
      if (n.type === "note" && n.nid === nid)
        return nodes.filter((x) => x.type === "note");
      if (n.type === "folder" && n.children?.length) {
        const r = walk(n.children);
        if (r) return r;
      }
    }
    return null;
  };
  const siblings = walk(sortedTree.value);
  if (!siblings) return;
  const idx = siblings.findIndex((s) => s.nid === nid);
  if (idx > 0) prevNote.value = siblings[idx - 1];
  if (idx >= 0 && idx < siblings.length - 1) nextNote.value = siblings[idx + 1];
}

function goNote(node: NoteTreeNode): void {
  const nbid = notebookId.value;
  if (!nbid || !node.nid) return;
  clearTag();
  void router.replace(`/notebooks/${nbid}/notes/${node.nid}`);
}

watch(renderedHtml, async () => {
  await nextTick();
  activeHeadingId.value = "";
  scanMarkdownDom();
  // 同款：mermaid 占位 → 懒加载 mermaid 异步替换为 SVG（composables/useMarkdown.ts runMermaid）
  if (markdownBodyEl.value) void runMermaid(markdownBodyEl.value);
  updatePrevNext();
  updateReadProgress();
});

watch(
  notebookId,
  (v) => {
    selectNotebookId.value = v ?? "";
    clearSearch();
    clearTag();
    // 笔记本切换后 recent 缓存失效，重置后按当前模式决定是否重拉
    recentNotes.value = [];
    recentLoadedForNotebook = null;
    if (v) {
      void loadTags(v);
      // 无条件预拉最近更新（loadRecent 内部按 notebookId 缓存，同一笔记本不会重复请求）：
      // ① 目录"最近更新"模式直接可用 ② 空态页的快捷入口有数据
      void loadRecent(v);
    } else {
      tags.value = [];
      tagsLoading.value = false;
    }
  },
  { immediate: true },
);

watch(
  () => [route.params.notebookId, route.params.nid],
  () => {
    void loadFromRoute();
  },
);

onMounted(() => {
  document.addEventListener("click", onDocClick);
  // 中栏滚动容器挂载后绑定 scroll：阅读进度 / 大纲高亮都以容器滚动为基准
  contentScrollEl.value?.addEventListener("scroll", onContentScroll, {
    passive: true,
  });
  void loadNotebooks();
  void loadFromRoute();
  updateReadProgress();
});

onBeforeUnmount(() => {
  document.removeEventListener("click", onDocClick);
  contentScrollEl.value?.removeEventListener("scroll", onContentScroll);
  // 拖拽中途卸载组件时兜底解绑 window 监听，避免泄漏
  onDragEnd();
});
</script>

<template>
  <div class="notebook">
    <!-- :style 注入 --side-left-w（功能3）：grid 左列宽与左分隔线位置随拖拽实时更新 -->
    <div class="notebook-body" :style="notebookBodyStyle">
      <!-- 左栏宽度拖拽手柄（功能3）：贴在左↔中分隔线上，可左右拖动 / 双击恢复默认宽 -->
      <div
        class="drag-handle"
        :class="{ dragging }"
        title="拖动调整左栏宽度（双击恢复默认）"
        @mousedown.prevent="onDragStart"
        @dblclick="onDragReset"
      ></div>
      <!-- ============ 左栏：笔记本信息 + 标签 + 目录树（sticky，无边框） ============ -->
      <aside class="side-left">
        <!-- v3.5：笔记本信息卡（去 agent 术语：封面/首字符色块 + 笔记本名 + 篇数 + 简介）
             v4.3：cover 真实图优先，无图回退首字符色块 -->
        <div class="agent-card">
          <div
            class="agent-avatar"
            :style="
              (currentNotebook?.logo || currentNotebook?.cover)
                ? undefined
                : { background: avatarTone(currentNotebook?.id) }
            "
          >
            <img
              v-if="currentNotebook?.logo || currentNotebook?.cover"
              :src="(currentNotebook?.logo ?? currentNotebook?.cover) as string"
              :alt="currentNotebookLabel"
              class="agent-avatar-img"
            />
            <template v-else>{{ notebookInitial }}</template>
          </div>
          <div class="agent-info">
            <!-- title 提示全名（功能4）：笔记本名过长被省略时 hover 可看全称 -->
            <div class="agent-name" :title="currentNotebookLabel">
              {{ currentNotebookLabel }}
            </div>
            <div v-if="currentNotebook" class="agent-meta">
              {{ currentNotebook.noteCount ?? 0 }} 篇
            </div>
            <div v-if="currentNotebook?.description" class="agent-desc">
              {{ currentNotebook.description }}
            </div>
          </div>
        </div>

        <!-- hairline 分隔：身份区 ↔ 标签区（agent 切换/返回概览已上移顶栏） -->
        <div class="side-divider" aria-hidden="true"></div>

        <!-- 标签 chips（默认最多两行，超出折叠可展开） -->
        <div v-if="tags.length || tagsLoading" class="side-section">
          <div class="side-label-row">
            <div class="side-label">标签</div>
            <button
              v-if="tagsOverflowing"
              class="tags-toggle"
              type="button"
              @click="tagsExpanded = !tagsExpanded"
            >
              {{ tagsExpanded ? "收起 ▴" : "展开 ▾" }}
            </button>
          </div>
          <div v-if="tagsLoading && !tags.length" class="side-hint">
            加载中…
          </div>
          <div
            v-else
            ref="tagsListRef"
            class="tags-list"
            :class="{ collapsed: !tagsExpanded }"
          >
            <button
              v-for="t in tags"
              :key="t.tag"
              class="tag-chip"
              :class="{ active: activeTag === t.tag }"
              type="button"
              @click="onTagClick(t)"
            >
              #{{ t.tag }}<span class="tag-count">{{ t.count }}</span>
            </button>
          </div>
        </div>

        <!-- hairline 分隔：标签区 ↔ 目录区 -->
        <div v-if="tags.length" class="side-divider" aria-hidden="true"></div>

        <!-- 目录区：双视图切换（目录=树形目录 / 最近更新=扁平 list 最多20条）
             胶囊按钮本身即分区标题（"目录"/"最近更新"），故不再重复渲染左侧 side-label -->
        <div class="side-section tree-section">
          <div class="tree-toolbar">
            <div class="sort-toggle">
              <button
                type="button"
                class="sort-btn"
                :class="{ active: sortMode === 'name' }"
                @click="setSortMode('name')"
              >
                目录
              </button>
              <button
                type="button"
                class="sort-btn"
                :class="{ active: sortMode === 'time' }"
                @click="setSortMode('time')"
              >
                最近更新
              </button>
            </div>
          </div>

          <!-- 工具条以下的内容才是滚动区：身份卡/标签/目录胶囊固定，仅列表滚动 -->
          <div class="tree-scroll">
            <!-- 名称模式：树形目录 -->
            <template v-if="sortMode === 'name'">
              <div v-if="treeLoading" class="side-hint">加载目录…</div>
              <div v-else-if="tree.length" class="tree">
                <TreeNode
                  v-for="n in sortedTree"
                  :key="n.type === 'note' ? n.nid : n.name"
                  :node="n"
                  :current-key="currentKey"
                  :notebook-id="notebookId"
                  :active-trail="activeTrail"
                  @open="onNodeOpen"
                />
              </div>
              <div v-else class="side-hint">该笔记本不存在或暂无内容</div>
            </template>

            <!-- 最近更新模式：后端 /notebooks/{id}/notes/recent 拉取，最多 20 条，updateTime 倒序 -->
            <template v-else>
              <div v-if="recentLoading" class="side-hint">加载中…</div>
              <div v-else-if="recentNotes.length" class="recent-list">
                <button
                  v-for="item in recentNotes"
                  :key="item.nid"
                  type="button"
                  class="recent-item"
                  :class="{ current: isRecentActive(item.nid) }"
                  :title="item.title"
                  @click="goSummary(item)"
                >
                  <span class="recent-title">{{ item.title }}</span>
                  <span v-if="item.updateTime" class="recent-date">{{
                    item.updateTime
                  }}</span>
                </button>
              </div>
              <div v-else class="side-hint">暂无更新记录</div>
            </template>
          </div>
        </div>
      </aside>

      <!-- ============ 中栏：搜索/复制链接 + 文章正文（无边框，内部滚动） ============ -->
      <section ref="contentScrollEl" class="content-pane">
        <!-- 阅读进度条：中栏顶部（跟随正文阅读进度）。
             仅在已加载笔记时渲染：min-width 2% 的兜底宽度在空态下会变成
             一个孤零零的小蓝条，看起来像渲染残留 -->
        <div
          v-if="currentNote"
          class="read-progress"
          :style="{ width: readProgress + '%' }"
          aria-hidden="true"
        ></div>
        <!-- 中栏顶部行：搜索框 + 复制链接 -->
        <div class="content-top">
          <div ref="searchBoxEl" class="search-box">
            <input
              v-model="searchKeyword"
              class="search-input"
              type="search"
              placeholder="搜索知识库（标题/正文/语义）…"
              @input="onSearchInput"
              @keydown.enter="onSearchEnter"
              @focus="onSearchFocus"
            />
            <div v-if="searchOpen" class="search-dropdown">
              <div v-if="searchLoading" class="drop-loading">搜索中…</div>
              <template v-else>
                <div v-if="searchResults.length" class="drop-list">
                  <!-- v4.1：KB 混合检索命中项（标题 + 命中片段 + 来源标签） -->
                  <div
                    v-for="item in searchResults"
                    :key="item.nid"
                    class="result-item"
                    role="button"
                    tabindex="0"
                    @click="goHit(item)"
                    @keydown.enter="goHit(item)"
                  >
                    <div class="result-title">
                      {{ item.title }}
                      <!-- 来源标签：语义/全文/标题（多路命中多枚） -->
                      <span
                        v-for="s in item.sources"
                        :key="s"
                        class="hit-source"
                        :class="{ 'hit-source--vec': s === 'vector' }"
                        >{{ sourceLabel(s) }}</span
                      >
                    </div>
                    <!-- 命中片段：最相关分块前 ~160 字 -->
                    <div v-if="item.snippet" class="result-snippet">
                      {{ item.snippet }}
                    </div>
                    <div class="result-meta">
                      <span v-if="item.dirPath" class="result-path">{{
                        item.dirPath
                      }}</span>
                    </div>
                  </div>
                </div>
                <div v-else class="drop-empty">没有匹配的笔记</div>
                <!-- 问 AI 入口：搜索词作为问题走 RAG 问答（有结果=「基于命中继续问」，无结果=直接问） -->
                <button
                  v-if="searchKeyword.trim()"
                  class="drop-ask"
                  type="button"
                  @click="runAsk"
                >
                  ✨ 问 AI：{{ searchKeyword.trim() }}
                </button>
              </template>
            </div>
          </div>
          <!-- 四个分享动作收进一个下拉（设计精简）：默认只显示「分享 ▾」入口，
               打开后按用途分「页面链接 / 智能体 MD 链接 / 下载 .md / 下载 .docx」四条 -->
          <div
            v-if="currentNote?.nid"
            class="share-menu"
            :class="{ open: shareMenuOpen }"
            @click.stop
          >
            <button
              class="share-trigger"
              type="button"
              :title="copiedAny ? '已复制 ✓' : '分享 / 下载'"
              @click="shareMenuOpen = !shareMenuOpen"
            >
              {{ copiedAny ? "已复制 ✓" : "分享 ▾" }}
            </button>
            <ul v-if="shareMenuOpen" class="share-dropdown" role="menu">
              <li>
                <button
                  class="share-item"
                  type="button"
                  @click="onShareItem('copyLink')"
                >
                  <span class="share-item-label">复制页面链接</span>
                  <span class="share-item-hint">给人类看</span>
                </button>
              </li>
              <li>
                <button
                  class="share-item"
                  type="button"
                  title="返回纯 markdown，智能体可直接 fetch"
                  @click="onShareItem('copyMdLink')"
                >
                  <span class="share-item-label">复制 MD 链接</span>
                  <span class="share-item-hint">给智能体</span>
                </button>
              </li>
              <li>
                <button
                  class="share-item"
                  type="button"
                  @click="onShareItem('downloadMd')"
                >
                  <span class="share-item-label">下载 .md</span>
                  <span class="share-item-hint">本地文件</span>
                </button>
              </li>
              <li>
                <button
                  class="share-item"
                  type="button"
                  :disabled="docxExporting"
                  title="生成 Word 文档：流程图渲染为图片嵌入，内嵌图片一并 Word 化"
                  @click="onShareItem('downloadDocx')"
                >
                  <span class="share-item-label">{{
                    docxExporting ? "生成中…" : "下载 .docx"
                  }}</span>
                  <span class="share-item-hint">{{
                    docxExporting ? "正在嵌入图片" : "Word 文档"
                  }}</span>
                </button>
              </li>
            </ul>
          </div>
        </div>

        <!-- AI 问答面板（RAG）：优先级最高，激活时替换正文区（问题 + 答案 md + 引用 chips） -->
        <div v-if="askActive" class="ask-pane">
          <div class="ask-head">
            <span class="ask-question">✨ {{ askQuestionText }}</span>
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              @click="closeAsk"
            >
              关闭
            </button>
          </div>
          <!-- 生成中：呼吸提示（qwen 生成通常数秒） -->
          <div v-if="askLoading" class="ask-loading">
            AI 正在检索知识库并作答…
          </div>
          <template v-else>
            <!-- 答案（md 安全渲染；答案内含 [n] 引用标注与下方 chips 编号对应） -->
            <div class="ask-answer markdown-body" v-html="askAnswerHtml" />
            <!-- 引用来源：点击跳转笔记原文 -->
            <div v-if="askRefs.length" class="ask-refs">
              <span class="ask-refs-label">引用（{{ askRefs.length }}）：</span>
              <button
                v-for="(r, i) in askRefs"
                :key="r.nid"
                class="ask-ref-chip"
                type="button"
                :title="r.dirPath ? r.dirPath + ' / ' + r.title : r.title"
                @click="goRef(r)"
              >
                [{{ i + 1 }}] {{ r.title }}
              </button>
            </div>
          </template>
        </div>

        <!-- 激活标签时：显示标签筛选结果列表（替换正文区域） -->
        <div v-else-if="activeTag" class="tag-result-pane">
          <div class="tag-result-head">
            <span class="tag-result-title"
              >标签 <b>#{{ activeTag }}</b> 下的笔记</span
            >
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              @click="clearTag"
            >
              清除筛选
            </button>
          </div>
          <div v-if="tagLoading" class="tag-result-loading">加载中…</div>
          <div v-else-if="tagResults.length" class="tag-result-list">
            <div
              v-for="item in tagResults"
              :key="item.nid"
              class="result-item"
              role="button"
              tabindex="0"
              @click="goSummary(item)"
              @keydown.enter="goSummary(item)"
            >
              <div class="result-title">{{ item.title }}</div>
              <div class="result-meta">
                <span v-if="item.dirPath" class="result-path">{{
                  item.dirPath
                }}</span>
                <span v-if="item.updateTime" class="result-date">{{
                  item.updateTime
                }}</span>
              </div>
            </div>
          </div>
          <div v-else class="tag-result-empty">该标签下暂无笔记</div>
        </div>

        <template v-else>
          <!-- 初始欢迎态：把笔记本的身份、规模和下一步动作集中展示，避免中栏只剩一块空白。 -->
          <div
            v-if="!contentLoading && !contentError && !currentNote"
            class="content-empty"
          >
            <div class="welcome-kicker">
              <span class="welcome-kicker-dot" aria-hidden="true"></span>
              {{ currentNotebookLabel }} · 知识空间
            </div>
            <!-- v4.3：cover 真实图优先，无图回退首字符色块 -->
            <div
              class="welcome-mark"
              :style="
                (currentNotebook?.hero || currentNotebook?.cover)
                  ? undefined
                  : { background: avatarTone(currentNotebook?.id) }
              "
            >
              <img
                v-if="currentNotebook?.hero || currentNotebook?.cover"
                :src="(currentNotebook?.hero ?? currentNotebook?.cover) as string"
                :alt="currentNotebookLabel"
                class="welcome-mark-img"
              />
              <template v-else>{{ notebookInitial }}</template>
            </div>
            <h1 class="welcome-title">从这里开始探索</h1>
            <p class="welcome-intro">
              选择一篇笔记开始阅读，或使用搜索快速找到你需要的内容。
            </p>

            <div class="welcome-stats" aria-label="笔记本概览">
              <div class="welcome-stat">
                <strong>{{
                  currentNotebook?.noteCount ?? notebookOverview.noteCount
                }}</strong>
                <span>篇笔记</span>
              </div>
              <div class="welcome-stat">
                <strong>{{ notebookOverview.folderCount }}</strong>
                <span>个目录</span>
              </div>
              <div class="welcome-stat">
                <strong>{{ tags.length }}</strong>
                <span>个标签</span>
              </div>
            </div>

            <div class="welcome-actions">
              <button
                v-if="recentNotes.length"
                type="button"
                class="welcome-action welcome-action--primary"
                @click="goSummary(recentNotes[0])"
              >
                <span class="welcome-action-icon">↗</span>
                <span>
                  <b>继续阅读</b>
                  <small>{{ recentNotes[0].title }}</small>
                </span>
              </button>
              <button
                type="button"
                class="welcome-action"
                @click="setSortMode('name')"
              >
                <span class="welcome-action-icon">☷</span>
                <span>
                  <b>浏览目录</b>
                  <small>按结构查看全部笔记</small>
                </span>
              </button>
            </div>

            <div v-if="recentNotes.length" class="empty-recent">
              <div class="welcome-section-title">最近更新</div>
              <button
                v-for="item in recentNotes.slice(0, 3)"
                :key="item.nid"
                type="button"
                class="empty-recent-item"
                :title="item.title"
                @click="goSummary(item)"
              >
                <span class="recent-title">{{ item.title }}</span>
                <span v-if="item.updateTime" class="recent-date">{{
                  item.updateTime
                }}</span>
              </button>
            </div>
          </div>

          <div v-else-if="contentError" class="content-error">
            {{ contentError }}
          </div>

          <template v-else>
            <div v-if="contentLoading" class="content-loading">加载中…</div>
            <template v-else>
              <!-- 面包屑（无边框，小字弱化） -->
              <nav v-if="crumbs.length" class="breadcrumb">
                <template v-for="(c, i) in crumbs" :key="`${c}-${i}`">
                  <span
                    class="crumb"
                    :class="{ current: i === crumbs.length - 1 }"
                    @click="onCrumbClick"
                    >{{ c }}</span
                  >
                  <span v-if="i < crumbs.length - 1" class="crumb-sep">/</span>
                </template>
              </nav>

              <!-- 文档标题 + 元信息行（完整更新时间 yyyy-MM-dd HH:mm:ss · 路径 · 字数） -->
              <h1 class="doc-title">{{ currentNote?.title }}</h1>
              <div class="doc-meta">
                <span v-if="currentNote?.updateTime">{{
                  currentNote.updateTime
                }}</span>
                <span v-if="currentNote?.dirPath">{{
                  currentNote.dirPath
                }}</span>
                <span>{{ wordCount }} 字</span>
              </div>

              <!-- 正文 -->
              <div
                ref="markdownBodyEl"
                class="markdown-body"
                @click="onMarkdownBodyClick"
                v-html="renderedHtml"
              />

              <!-- 图形放大查看器：点击正文图片 / mermaid 流程图弹出（放大 + 下载，照搬） -->
              <ImageViewer ref="viewerRef" />

              <!-- v3.5：贡献者 chips（纯展示，不承担切换导航） -->
              <div
                v-if="currentNote?.contributors?.length"
                class="contributors"
              >
                <span class="contributors-label">贡献者：</span>
                <span
                  v-for="c in currentNote.contributors"
                  :key="c.id"
                  class="contributor-chip"
                >
                  {{ c.displayName }}
                </span>
              </div>

              <!-- 上一篇 / 下一篇 -->
              <nav v-if="prevNote || nextNote" class="prev-next">
                <button
                  v-if="prevNote"
                  type="button"
                  class="btn btn-ghost btn-sm prev-next-btn"
                  @click="goNote(prevNote)"
                >
                  ← 上一篇：{{ prevNote.name }}
                </button>
                <span v-else class="prev-next-gap" />
                <button
                  v-if="nextNote"
                  type="button"
                  class="btn btn-ghost btn-sm prev-next-btn"
                  @click="goNote(nextNote)"
                >
                  下一篇：{{ nextNote.name }} →
                </button>
              </nav>
            </template>
          </template>
        </template>
      </section>

      <!-- ============ 右栏：大纲 scroll-spy（sticky，无边框） ============ -->
      <aside
        v-if="outline.length && !activeTag && currentNote && !askActive"
        class="outline-pane"
      >
        <div class="side-label">大纲</div>
        <ul class="outline-list">
          <li
            v-for="item in outline"
            :key="item.id"
            class="outline-item"
            :class="[
              { active: activeHeadingId === item.id },
              `lv-${item.level}`,
            ]"
          >
            <button
              type="button"
              class="outline-link"
              @click="onOutlineClick(item.id)"
            >
              {{ item.text }}
            </button>
          </li>
        </ul>
      </aside>
    </div>
  </div>
</template>

<style scoped>
/* ============ 页面骨架 ============ */
/* app-shell：.notebook 撑满 App 的 .page-scroll 容器（无溢出、无外层滚动），
   高度 = 视口 - 顶栏 - 页脚，滚动发生在三栏内部 */
.notebook {
  position: relative;
  height: 100%;
}

/* ============ 三栏布局 ============
   左 var(--side-left-w)（默认220px，功能3可拖拽调宽） / 中 1fr（文章） / 右 180px（大纲）
   容器统一 --container-max（大屏放宽，减少两侧空白）
   左栏宽可变 → 左竖线位置用同一 CSS 变量计算，始终与左列右缘对齐；
   右线从右对称计算。伪元素 absolute 贯穿整个 grid 高度（恒满内容区） */
.notebook-body {
  display: grid;
  /* --side-left-w 由拖拽手柄经 :style 注入（px 值），未注入时回退 220px */
  grid-template-columns: var(--side-left-w, 220px) minmax(0, 1fr) 180px;
  column-gap: 40px;
  /* 三栏拉伸满高：滚动在各栏内部进行（左右栏固定，中栏文章滚动） */
  align-items: stretch;
  height: 100%;
  max-width: var(--container-max);
  margin: 0 auto;
  padding: 12px var(--container-pad) 16px;
  position: relative;
}

/* 左↔中 竖向分隔线：padding + 左栏宽(--side-left-w) + gap一半 处，贯穿全高 */
.notebook-body::before {
  content: "";
  position: absolute;
  top: 16px;
  bottom: 16px;
  left: calc(var(--container-pad) + var(--side-left-w, 220px) + 20px);
  width: 1px;
  background: var(--border);
  pointer-events: none;
}

/* 中↔右 竖向分隔线：从右侧 padding + 180 + 20 处，贯穿全高 */
.notebook-body::after {
  content: "";
  position: absolute;
  top: 16px;
  bottom: 16px;
  right: calc(var(--container-pad) + 180px + 20px);
  width: 1px;
  background: var(--border);
  pointer-events: none;
}

/* ============ 左栏宽度拖拽手柄（功能3） ============
   中心对齐左分隔线（比 1px 线宽出 9px 热区，更好抓），
   hover/拖拽时在分隔线位置显示 2px 钴蓝竖条提示可拖 */
.drag-handle {
  position: absolute;
  top: 16px;
  bottom: 16px;
  /* 分隔线在 padding + 左栏 + gap一半 处，手柄左偏 (9-1)/2≈4px 使中心对齐线 */
  left: calc(var(--container-pad) + var(--side-left-w, 220px) + 20px - 4px);
  width: 9px;
  cursor: col-resize;
  z-index: 5;
}

.drag-handle::after {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  /* 竖条画在手柄中心，恰好覆盖分隔线 */
  left: 4px;
  width: 2px;
  border-radius: 1px;
  background: var(--accent);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.drag-handle:hover::after,
.drag-handle.dragging::after {
  opacity: 0.55;
}

/* 阅读内容限宽：1560 容器下中栏约 1016px，超过中文舒适行宽
   （16px 正文 50~55 字/行）→ 仅对"阅读型"内容限到 880px。
   注意 .content-pane 本身不限宽：否则顶部搜索行距右侧竖线空出 ~136px，
   与左竖线不对称，破坏三栏"凵"形框架感 */
.breadcrumb,
.doc-title,
.doc-meta,
.markdown-body,
.contributors,
.prev-next,
.tag-result-pane {
  max-width: 880px;
}

/* 阅读进度条：中栏顶部，宽度跟随阅读进度 */
.read-progress {
  display: block;
  height: 3px;
  width: 0;
  border-radius: 2px;
  background: var(--accent);
  margin: 4px 0 20px;
  transition: width 0.1s linear;
  min-width: 2%;
  /* 中栏内部滚动时进度条常驻可视区顶部（随阅读进度前进） */
  position: sticky;
  top: 0;
  z-index: 5;
}

/* ============ 侧栏通用小标题（标签 / 目录 / 大纲） ============ */
.side-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.side-hint {
  font-size: 12px;
  color: var(--text-muted);
  padding: 8px 0;
}

/* 左栏分组之间的细线（与图二标签/目录/身份卡之间的小分割一致） */
.side-divider {
  height: 1px;
  background: var(--border);
  opacity: 0.85;
}

/* ============ 左栏（固定满高，内部仅目录列表滚动） ============ */
.side-left {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 0;
  padding: 12px 8px 24px 4px;
}

/* ---- agent 身份卡：与下拉/返回之间留细分隔 ---- */
.agent-card {
  display: flex;
  align-items: center;
  gap: 10px;
}

.agent-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  font-size: 15px;
  flex-shrink: 0;
  /* 封面图裁成圆形不外溢 */
  overflow: hidden;
}

/* v4.3：左栏信息卡封面图（铺满圆形容器） */
.agent-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.agent-info {
  min-width: 0;
}

.agent-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.agent-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 笔记本信息卡简介：两行省略 */
.agent-desc {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ---- 侧栏分区（标签 / 目录） ---- */
.side-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* ---- 标签 chips（与图二一致的胶囊底色） ---- */
.side-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.tags-toggle {
  border: none;
  background: none;
  padding: 0;
  font-size: 11px;
  color: var(--accent);
  cursor: pointer;
  line-height: 1;
}

.tags-toggle:hover {
  opacity: 0.8;
}

.tags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* 折叠态：仅显示两行（chip 高 26px = 18px 行高 + 上下 4px 内边距） */
.tags-list.collapsed {
  max-height: 58px;
  overflow: hidden;
}

.tag-chip {
  border: none;
  background: var(--bg-panel-2);
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 4px 11px;
  font-size: 12px;
  line-height: 18px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tag-chip:hover {
  background: var(--accent-soft);
  color: var(--accent);
}

.tag-chip.active {
  background: var(--accent);
  color: #fff;
}

.tag-count {
  margin-left: 3px;
  font-size: 11px;
  opacity: 0.75;
}

/* ---- 目录树（无面板边框，融入背景） ---- */
.tree-section {
  flex: 1;
  min-height: 0;
}

.tree-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-shrink: 0;
}

/* 目录胶囊以下的滚动区：仅列表滚动，滚动条默认隐藏、hover 悬停时浮现 */
.tree-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* Firefox：默认全透明，hover 时显现滑块 */
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
  transition: scrollbar-color 0.2s ease;
}

.tree-scroll:hover {
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

/* WebKit：细轨道 + 透明滑块，hover 容器时滑块淡入 */
.tree-scroll::-webkit-scrollbar {
  width: 6px;
}

.tree-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.tree-scroll::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 999px;
  transition: background 0.2s ease;
}

.tree-scroll:hover::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
}

.sort-toggle {
  display: flex;
  align-items: center;
  gap: 2px;
  background: var(--bg-panel-2);
  border-radius: 999px;
  padding: 2px;
}

.sort-btn {
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 11px;
  padding: 4px 10px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.sort-btn:hover {
  color: var(--accent);
}

.sort-btn.active {
  background: var(--accent);
  color: #fff;
  font-weight: 600;
}

.tree {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* ---- 最近更新 list（time 模式）：扁平条目 = 标题 + 日期 ---- */
.recent-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.recent-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  border: none;
  background: transparent;
  text-align: left;
  padding: 7px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.recent-item:hover {
  background: var(--bg-panel-2);
}

.recent-title {
  max-width: 100%;
  font-size: 13px;
  line-height: 1.45;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 当前正在阅读的笔记：钴蓝高亮 + 左侧 2px 竖条（与目录树当前项呼应） */
.recent-item.current {
  background: var(--accent-soft);
  box-shadow: inset 2px 0 0 var(--accent);
}

.recent-item.current .recent-title {
  color: var(--accent);
  font-weight: 600;
}

.recent-date {
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}

/* ============ 中栏（无边框文章区，app-shell 下是本页主滚动容器） ============ */
.content-pane {
  min-width: 0;
  min-height: 0;
  /* 滚动条在内容区内部形成（中栏右缘），页面整体不产生外部滚动条 */
  overflow-y: auto;
  /* 滚动内容底部呼吸空间（容器 padding 已改小，由这里补足） */
  padding-bottom: 64px;
  /* 滚动条与左栏目录一致：默认隐藏，hover 时滑块浮现 */
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
  transition: scrollbar-color 0.2s ease;
}

/* Firefox：hover 容器时显现滑块 */
.content-pane:hover {
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

/* WebKit：细轨道 + 透明滑块，hover 容器时滑块淡入 */
.content-pane::-webkit-scrollbar {
  width: 6px;
}

.content-pane::-webkit-scrollbar-track {
  background: transparent;
}

.content-pane::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 999px;
  transition: background 0.2s ease;
}

.content-pane:hover::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
}

/* ---- 中栏顶部行：搜索 + 复制链接 ---- */
.content-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
}

.search-box {
  position: relative;
  flex: 1;
  min-width: 0;
}

.search-input {
  width: 100%;
  height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg-panel);
  color: var(--text-main);
  font-size: 14px;
  outline: none;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.search-input::placeholder {
  color: var(--text-muted);
}

.search-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(var(--accent-rgb), 0.18);
}

.search-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  z-index: 30;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  box-shadow: var(--shadow-pop);
  overflow: hidden;
}

.drop-list {
  max-height: 320px;
  overflow-y: auto;
  padding: 6px;
}

.drop-loading,
.drop-empty {
  padding: 18px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.result-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.result-item:hover,
.result-item:focus-visible {
  background: var(--bg-panel-2);
  outline: none;
}

.result-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-muted);
  flex-wrap: wrap;
}

.result-agent {
  color: var(--accent);
  font-weight: 600;
}

/* ---- v4.1 KB 搜索：来源标签（语义/全文/标题，语义路钴蓝高亮） ---- */
.hit-source {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 10px;
  line-height: 16px;
  font-weight: 500;
  vertical-align: 2px;
  background: var(--bg-panel-2);
  color: var(--text-muted);
  border: 1px solid var(--border);
}

.hit-source--vec {
  background: var(--accent-soft);
  color: var(--accent);
  border-color: transparent;
}

/* 命中片段：两行省略的灰字（最相关分块） */
.result-snippet {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 问 AI 入口（下拉底部固定行：渐变分隔 + 悬停提亮） */
.drop-ask {
  display: block;
  width: 100%;
  border: none;
  border-top: 1px dashed var(--border);
  background: transparent;
  color: var(--accent);
  font-size: 13px;
  text-align: center;
  padding: 10px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.drop-ask:hover {
  background: var(--accent-soft);
}

/* ---- AI 问答面板（RAG）：替换正文区的问答展示 ---- */
.ask-pane {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 880px;
}

.ask-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}

.ask-question {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-main);
}

.ask-loading {
  padding: 40px 0;
  text-align: center;
  color: var(--accent);
  font-size: 14px;
  animation: ask-breath 1.2s ease-in-out infinite;
}

@keyframes ask-breath {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

/* 答案区复用 markdown-body 排版（与正文同款） */
.ask-answer {
  min-height: 80px;
}

/* 引用 chips */
.ask-refs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.ask-refs-label {
  font-size: 12px;
  color: var(--text-muted);
}

.ask-ref-chip {
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
  max-width: 260px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ask-ref-chip:hover {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-soft);
}

/* ---- 复制链接（ghost 小按钮） ---- */
.copy-link-btn {
  flex-shrink: 0;
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  padding: 7px 14px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.copy-link-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}

/* ---- 分享下拉菜单（顶部：分享 ▾ + 三项） ---- */
.share-menu {
  position: relative;
  flex-shrink: 0;
}

.share-trigger {
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  padding: 7px 14px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.share-trigger:hover,
.share-menu.open .share-trigger {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-soft);
}

/* 下拉面板：右上对齐 trigger，柔和阴影 + hairline 边框 */
.share-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 20;
  list-style: none;
  margin: 0;
  padding: 6px;
  min-width: 200px;
  /* 底色 = --bg-panel，与页面卡片同色实色填充，不透明、不泛白；
  border-strong + 双层阴影塑出"浮在卡片之上"的层次（之前白底在浅色页面下反而像半透明） */
  background: var(--bg-panel);
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  /* 加深阴影并拉开层次：原 8px/0.08 在浅灰底上几乎看不见 */
  box-shadow:
    0 12px 32px rgba(0, 0, 0, 0.18),
    0 2px 6px rgba(0, 0, 0, 0.08);
  /* 入场：轻微浮起 */
  animation: share-in 0.14s ease-out;
}

@keyframes share-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.share-dropdown li {
  margin: 0;
  padding: 0;
}

.share-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  background: transparent;
  border-radius: 6px;
  text-align: left;
  cursor: pointer;
  color: var(--text-main);
  font-size: 13px;
  transition: background 0.12s ease;
}

.share-item:hover {
  background: var(--accent-soft);
}

/* docx 生成中：禁用态（防重复触发），弱化整项 */
.share-item:disabled {
  cursor: default;
  opacity: 0.6;
}

.share-item:disabled:hover {
  background: transparent;
}

.share-item-label {
  font-weight: 500;
}

.share-item-hint {
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  /* 不让 hint 喧宾夺主 */
  opacity: 0.85;
}

/* ---- 标签筛选结果 ---- */
.tag-result-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tag-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border);
}

.tag-result-title {
  font-size: 14px;
  color: var(--text-secondary);
}

.tag-result-title b {
  color: var(--accent);
}

.btn-sm {
  padding: 5px 10px;
  font-size: 12px;
}

.tag-result-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tag-result-loading,
.tag-result-empty {
  padding: 28px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.btn {
  border: none;
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-ghost {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-strong);
}

.btn-ghost:hover {
  color: var(--accent);
  border-color: var(--accent);
}

/* ---- 面包屑（小字，无分隔线） ---- */
.breadcrumb {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  margin-bottom: 4px;
}

.crumb {
  color: var(--text-muted);
  cursor: pointer;
  transition: color 0.15s ease;
}

.crumb:not(.current):hover {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.crumb.current {
  color: var(--text-secondary);
  cursor: default;
}

.crumb-sep {
  color: var(--text-muted);
  opacity: 0.6;
}

/* ---- 文档标题 + 元信息行（顶部双层分隔：下方 hairline + 与正文间距） ---- */
.doc-title {
  margin: 8px 0 10px;
  font-size: 28px;
  line-height: 1.3;
  font-weight: 800;
  color: var(--text-main);
  letter-spacing: -0.01em;
}

.doc-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  padding-bottom: 18px;
  margin-bottom: 22px;
  border-bottom: 1px solid var(--border);
}

/* 元信息项之间用中点分隔 */
.doc-meta span + span::before {
  content: "·";
  margin: 0 8px;
  opacity: 0.6;
}

/* ---- 空态 / 错误 / 加载 ---- */
.content-error,
.content-loading {
  padding: 48px 0;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
}

.content-error {
  color: #f87171;
}

/* ---- 初始欢迎态：以内容摘要和明确动作替代无信息空白 ---- */
.content-empty {
  max-width: 720px;
  min-height: min(620px, calc(100vh - 220px));
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 74px 24px 56px;
  text-align: center;
}

.welcome-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
  color: var(--text-muted);
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.welcome-kicker-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-soft);
}

.welcome-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 58px;
  height: 58px;
  border-radius: 18px;
  color: #fff;
  font-size: 24px;
  font-weight: 800;
  box-shadow: 0 12px 24px rgba(37, 99, 235, 0.18);
  /* 封面图裁进圆角容器不外溢 */
  overflow: hidden;
}

/* v4.3：欢迎态封面图（铺满圆角方块容器） */
.welcome-mark-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.welcome-title {
  margin: 18px 0 8px;
  color: var(--text-main);
  font-size: clamp(25px, 3vw, 34px);
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.welcome-intro {
  max-width: 430px;
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.welcome-stats {
  display: flex;
  align-items: stretch;
  margin-top: 30px;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: color-mix(in srgb, var(--bg-panel) 78%, transparent);
  overflow: hidden;
}

.welcome-stat {
  min-width: 112px;
  padding: 13px 20px 12px;
  border-right: 1px solid var(--border);
}

.welcome-stat:last-child {
  border-right: 0;
}

.welcome-stat strong,
.welcome-stat span {
  display: block;
}

.welcome-stat strong {
  color: var(--text-main);
  font-size: 20px;
  line-height: 1.15;
}

.welcome-stat span {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 11px;
}

.welcome-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(190px, 1fr));
  gap: 10px;
  width: min(100%, 520px);
  margin-top: 26px;
}

.welcome-action {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-panel);
  color: var(--text-main);
  text-align: left;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.welcome-action:hover,
.welcome-action:focus-visible {
  border-color: var(--accent);
  box-shadow: 0 8px 20px rgba(var(--accent-rgb), 0.12);
  outline: none;
  transform: translateY(-2px);
}

.welcome-action--primary {
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  background: var(--accent-soft);
}

.welcome-action-icon {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: 9px;
  background: var(--bg-panel);
  color: var(--accent);
  font-size: 18px;
  font-weight: 700;
}

.welcome-action b,
.welcome-action small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.welcome-action b {
  font-size: 13px;
}

.welcome-action small {
  max-width: 190px;
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
}

.welcome-section-title {
  margin-bottom: 8px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.empty-recent {
  width: min(100%, 520px);
  margin-top: 30px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

.empty-recent-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  border: none;
  border-radius: 8px;
  background: transparent;
  padding: 7px 10px;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease;
}

.empty-recent-item:hover {
  background: var(--bg-panel-2);
}

.empty-recent-item .recent-title {
  max-width: 340px;
}

.empty-recent-item:hover .recent-title {
  color: var(--accent);
}

/* ---- 正文排版 ---- */
.markdown-body {
  line-height: 1.7;
  font-size: 15px;
  color: var(--text-main);
  word-break: break-word;
  overflow-wrap: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 1.4em 0 0.5em;
  line-height: 1.35;
  font-weight: 700;
}

.markdown-body :deep(h1:first-child) {
  margin-top: 0;
}

.markdown-body :deep(h1) {
  font-size: 24px;
}

.markdown-body :deep(h2) {
  font-size: 20px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border);
}

.markdown-body :deep(h3) {
  font-size: 17px;
  color: var(--accent);
}

/* ---- 列表：用自定义 • 样式（与图二一致） ---- */
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0.8em 0;
  padding-left: 1.4em;
}

.markdown-body :deep(ul) {
  list-style: none;
}

.markdown-body :deep(ul > li) {
  position: relative;
  padding-left: 4px;
  margin: 0.3em 0;
}

.markdown-body :deep(ul > li::before) {
  content: "•";
  position: absolute;
  left: -1em;
  color: var(--accent);
  font-weight: 700;
}

.markdown-body :deep(ol) {
  list-style: decimal;
  list-style-position: outside;
}

.markdown-body :deep(ol > li) {
  padding-left: 4px;
  margin: 0.3em 0;
}

.markdown-body :deep(ol > li::marker) {
  color: var(--accent);
  font-weight: 600;
}

/* 段落间距更舒展，与图二一致 */
.markdown-body :deep(p) {
  margin: 0.9em 0;
}

/* 链接加细分隔下划线 */
.markdown-body :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-color: rgba(59, 130, 246, 0.4);
}

.markdown-body :deep(a:hover) {
  text-decoration-color: var(--accent);
}

/* 行内 code */
.markdown-body :deep(code) {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 2px 6px;
  font-size: 13px;
  font-family: var(--font-mono);
}

/*
 * 代码块：随主题底色 + hairline 边框；外层 .code-block-wrap 承载复制按钮
 * （按钮挂 wrapper 而非 pre 内，横向滚动长代码时按钮固定在可视区右上）
 */
.markdown-body :deep(.code-block-wrap) {
  position: relative;
}

.markdown-body :deep(.code-block-wrap pre) {
  position: static; /* 滚动容器不再做定位基准，按钮相对 wrapper 定位 */
  /* 顶部为常显复制按钮预留位置（8px 偏移 + ~24px 按钮高 + 间距），避免按钮遮住代码首行 */
  padding-top: 44px;
}

.markdown-body :deep(pre) {
  background: var(--code-pre-bg);
  color: var(--text-main);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px 14px;
  overflow-x: auto;
}

.markdown-body :deep(pre code) {
  background: transparent;
  border: none;
  padding: 0;
  font-size: 13px;
}

/*
 * 代码块复制按钮：常显（不再 hover 才出现）+ 主题面板底色高对比。
 * 旧版问题：opacity:0 默认全透明 + 深色胶囊配 var(--text-main) 文字，
 * 亮色主题下深底深字几乎看不见。现改为 --bg-panel 底（亮=白/暗=深灰）
 * + 边框 + 阴影，两种主题都与 pre 底色拉开层次；hover 钴蓝、成功绿。
 */
.markdown-body :deep(.code-copy-btn) {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--border-strong);
  background: var(--bg-panel);
  color: var(--text-main);
  font-size: 12px;
  line-height: 1;
  padding: 5px 10px;
  border-radius: 999px;
  cursor: pointer;
  opacity: 0.92;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.14);
  transition:
    opacity 0.15s ease,
    background 0.15s ease,
    color 0.15s ease,
    border-color 0.15s ease;
}

.markdown-body :deep(.code-block-wrap:hover .code-copy-btn),
.markdown-body :deep(.code-copy-btn:focus-visible) {
  opacity: 1;
}

.markdown-body :deep(.code-copy-btn:hover) {
  background: var(--accent);
  border-color: transparent;
  color: #fff;
}

/* 复制成功态：绿色底白字（--tone-green 两主题通用） */
.markdown-body :deep(.code-copy-btn.is-copied) {
  background: var(--tone-green);
  border-color: transparent;
  color: #fff;
  opacity: 1;
}

/* blockquote：钴蓝左竖条 + 淡蓝底（与图二一致） */
.markdown-body :deep(blockquote) {
  margin: 1em 0;
  padding: 10px 16px;
  border-left: 3px solid var(--accent);
  background: var(--accent-soft);
  border-radius: 0 12px 12px 0;
  color: var(--text-main);
}

/* 表格：hairline 边框 */
.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 0.8em 0;
  width: 100%;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--border);
  padding: 8px 12px;
  font-size: 14px;
}

.markdown-body :deep(th) {
  background: var(--bg-panel-2);
  font-weight: 600;
}

/* 图片占位：浅色框 + 右下角 AI 徽章 */
.markdown-body :deep(img) {
  max-width: 100%;
  display: block;
  margin: 1.2em auto;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: var(--bg-panel);
}

/* 无图片时，markdown 图片占位段（image placeholder）样式 */
.markdown-body :deep(.image-placeholder) {
  position: relative;
  margin: 1.2em auto;
  min-height: 200px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 13px;
}

.markdown-body :deep(.image-placeholder::after) {
  content: "AI 生成";
  position: absolute;
  right: 10px;
  bottom: 10px;
  padding: 2px 10px;
  font-size: 11px;
  color: var(--text-secondary);
  background: rgba(0, 0, 0, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 999px;
}

/* ---- v3 贡献者 chips（正文底部，小字弱化） ---- */
.contributors {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 28px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.contributors-label {
  font-size: 12px;
  color: var(--text-muted);
}

.contributor-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 999px;
  cursor: default;
}

/* ---- 上一篇 / 下一篇（双按钮 + 上下分隔线） ---- */
.prev-next {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 32px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.prev-next-btn {
  max-width: 48%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prev-next-gap {
  flex: 1;
}

/* ============ 右栏：大纲（固定满高，内部滚动，无边框） ============ */
.outline-pane {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 4px 24px;
}

.outline-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.outline-item {
  margin: 0;
  padding: 0;
}

.outline-link {
  display: block;
  width: 100%;
  border: none;
  background: transparent;
  text-align: left;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
  padding: 4px 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 按标题层级缩进 */
.outline-item.lv-2 .outline-link {
  padding-left: 18px;
}

.outline-item.lv-3 .outline-link {
  padding-left: 28px;
}

.outline-link:hover {
  color: var(--accent);
  background: var(--bg-panel-2);
}

.outline-item.active .outline-link {
  color: var(--accent);
  background: var(--accent-soft);
  font-weight: 600;
}

/* ============ 响应式（窄屏退化为单列） ============ */
@media (max-width: 960px) {
  /* 回退传统整页滚动：骨架恢复自然高度，滚动交还页面 */
  .notebook,
  .notebook-body {
    height: auto;
  }

  .notebook-body {
    /* minmax(0,1fr)：窄屏单列也强制轨道可收缩，防左栏 nowrap/长词 min-content 撑爆视口（手机横向溢出根治） */
    grid-template-columns: minmax(0, 1fr);
    gap: 28px;
    padding: 0 20px 64px;
  }

  /* 单列布局下隐藏两条竖向分隔线 */
  .notebook-body::before,
  .notebook-body::after {
    display: none;
  }

  /* notes-mobile-v2：单列布局无左栏可拖，拖拽手柄整体隐藏（含触摸 sticky hover 蓝条与热区） */
  .drag-handle {
    display: none;
  }

  .side-left,
  .outline-pane {
    position: static;
    max-height: none;
    min-height: 0;
    min-width: 0;
    overflow: visible;
    padding: 0;
  }

  .content-pane {
    overflow: visible;
    padding-bottom: 0;
  }
}

/* 640px 以下欢迎区动作按钮改单列，避免双列最小宽撑出容器 */
@media (max-width: 640px) {
  .welcome-actions {
    grid-template-columns: 1fr;
  }

  /* notes-mobile-v1：大纲手机端整体隐藏（渲染条件不动，纯展示隐藏） */
  .outline-pane {
    display: none;
  }

  /* notes-mobile-v1：目录区限高内部滚动 + 底部渐隐提示（身份卡/标签/胶囊工具条不参与限高） */
  .tree-section {
    position: relative;
  }

  .tree-scroll {
    max-height: 40vh;
  }

  .tree-section::after {
    content: "";
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 34px;
    background: linear-gradient(rgba(246, 247, 249, 0), var(--bg-body));
    pointer-events: none;
    z-index: 1;
  }

  .tree-section::before {
    content: "目录区限高，内部滑动";
    position: absolute;
    left: 50%;
    bottom: 0;
    transform: translateX(-50%);
    font-size: 10px;
    color: var(--text-muted);
    background: var(--bg-body);
    border-radius: 8px 8px 0 0;
    padding: 2px 10px 0;
    pointer-events: none;
    z-index: 2;
    white-space: nowrap;
  }

  /* notes-mobile-v1：笔记本头卡简介压 1 行 */
  .agent-desc {
    -webkit-line-clamp: 1;
  }
}
</style>
