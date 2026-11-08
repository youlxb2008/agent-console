<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { NoteTreeNode } from "@/api/portal";

/**
 * 目录树节点（递归组件，自引用文件名 TreeNode）
 * - folder：默认展开（expanded=true），点击标题行折叠/展开；note 是叶子
 * - note：点击 emit('open')，由父组件按 nid 定位 + 高亮
 * - C6（M2.2 包C）：folder 折叠状态记忆 localStorage（key 含 notebookId + 节点路径），
 *   刷新/重进恢复；默认全展开逻辑不变；切换笔记本隔离记忆（key 带 notebookId）
 * - v3：删 path 兜底（数据驱动树 nid 恒有值，无孤儿文件）
 * - notes-mobile-v1（≤640px 手机端）：folder 默认折叠且忽略 localStorage 记忆
 *   （toggle 只改内存不落盘，桌面记忆逻辑零变化）；folder 标题行右侧加子树篇数徽章
 *   （仅手机端显示）；activeTrail 命中时自动展开祖先链
 */

interface Props {
  node: NoteTreeNode;
  /** 当前笔记高亮键（nid） */
  currentKey: string | null;
  /** 当前 notebookId（C6：折叠记忆按笔记本隔离） */
  notebookId?: string | null;
  /** 父级拼接路径（C6：folder 唯一折叠 key，根级为 node.name） */
  basePath?: string;
  /** 当前笔记的祖先 folder 名序列（notes-mobile-v1：根级为完整序列，逐层下传 slice(1)） */
  activeTrail?: string[];
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: "open", node: NoteTreeNode): void;
}>();

/** 手机端判定（SPA 无 SSR，初始化一次即可；不处理运行中跨断点切换） */
const isMobile = window.matchMedia("(max-width: 640px)").matches;

/** C6：folder 折叠记忆 key（notebookId + 节点全路径，切换笔记本隔离） */
function folderKey(node: NoteTreeNode): string {
  const path = props.basePath ? `${props.basePath}/${node.name}` : node.name;
  const nbid = props.notebookId ? String(props.notebookId) : "global";
  return `portal-tree-expanded:${nbid}:${path}`;
}

/**
 * folder 展开状态：桌面=默认展开 + localStorage 记忆恢复（行为零变化）；
 * 手机端=默认折叠且忽略记忆（初值 false，展开交给 activeTrail watch）
 */
const expanded = ref<boolean>(
  (() => {
    if (props.node.type !== "folder") return true;
    if (isMobile) return false;
    try {
      return localStorage.getItem(folderKey(props.node)) !== "0";
    } catch {
      return true;
    }
  })(),
);

/** notes-mobile-v1：当前笔记祖先链变化时命中层自动展开（手机端；immediate 覆盖首次挂载） */
watch(
  () => props.activeTrail,
  (trail) => {
    if (!isMobile || !trail || !trail.length) return;
    if (props.node.type === "folder" && trail[0] === props.node.name) {
      expanded.value = true;
    }
  },
  { immediate: true },
);

function toggle(): void {
  expanded.value = !expanded.value;
  if (isMobile) {
    // 手机端默认折叠语义优先，toggle 只改内存，避免旧 localStorage 记忆污染下次进入
    return;
  }
  try {
    localStorage.setItem(folderKey(props.node), expanded.value ? "1" : "0");
  } catch {
    // localStorage 不可用（隐私模式等）时静默降级，不影响折叠交互
  }
}

/** 递归统计子树笔记数（notes-mobile-v1 篇数徽章） */
function countNotes(n: NoteTreeNode): number {
  if (n.type === "note") return 1;
  return (n.children ?? []).reduce((sum, c) => sum + countNotes(c), 0);
}

const noteCount = computed(() =>
  props.node.type === "folder" ? countNotes(props.node) : 0,
);

/** 节点是否高亮：note 且 nid 匹配当前笔记 */
function isActive(n: NoteTreeNode): boolean {
  return !!props.currentKey && n.type === "note" && n.nid === props.currentKey;
}
</script>

<template>
  <div class="tree-node">
    <!-- folder：可折叠，点击切换展开 -->
    <template v-if="node.type === 'folder'">
      <!-- title 提示全名：左栏宽度有限，长名称被 ellipsis 截断时 hover 可看全称 -->
      <div class="tree-row tree-folder" :title="node.name" @click="toggle">
        <span class="tree-arrow">{{ expanded ? "▾" : "▸" }}</span>
        <span class="tree-label tree-folder-label">{{ node.name }}</span>
        <!-- notes-mobile-v1：子树篇数徽章（仅手机端显示，桌面 CSS 隐藏） -->
        <span class="tree-count" aria-hidden="true">{{ noteCount }}</span>
      </div>
      <div
        v-if="expanded && node.children && node.children.length"
        class="tree-children"
      >
        <TreeNode
          v-for="child in node.children"
          :key="child.type === 'note' ? child.nid : child.name"
          :node="child"
          :current-key="currentKey"
          :notebook-id="notebookId"
          :base-path="basePath ? `${basePath}/${node.name}` : node.name"
          :active-trail="
            activeTrail && activeTrail[0] === node.name
              ? activeTrail.slice(1)
              : []
          "
          @open="emit('open', $event)"
        />
      </div>
    </template>

    <!-- note：叶子，点击打开 -->
    <template v-else>
      <!-- title 提示全名：同 folder，长标题截断时 hover 可看全称 -->
      <div
        class="tree-row tree-note"
        :class="{ active: isActive(node) }"
        :title="node.name"
        @click="emit('open', node)"
      >
        <span class="tree-arrow" />
        <span class="tree-label">{{ node.name }}</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tree-node {
  user-select: none;
}

.tree-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: 10px;
  cursor: pointer;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.tree-row:hover {
  background: var(--bg-panel-2);
  color: var(--accent);
}

.tree-arrow {
  width: 12px;
  flex-shrink: 0;
  font-size: 10px;
  color: var(--text-muted);
  text-align: center;
  line-height: 1;
}

/* note 前的占位小圆点（与图二目录风格一致） */
.tree-note .tree-arrow::before {
  content: "•";
  display: inline-block;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1;
}

.tree-folder-label {
  font-weight: 600;
  color: var(--text-main);
}

.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 当前笔记高亮：钴蓝淡底 + 左侧 2px 钴蓝竖条作为强视觉分隔 */
.tree-note.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
  position: relative;
}

.tree-note.active::before {
  content: "";
  position: absolute;
  left: -2px;
  top: 6px;
  bottom: 6px;
  width: 2px;
  border-radius: 2px;
  background: var(--accent);
}

/* 子层缩进 + 左侧虚线 hairline（钴蓝半透明） */
.tree-children {
  margin-left: 14px;
  border-left: 1px dashed rgba(138, 146, 163, 0.25);
  padding-left: 4px;
}

/* notes-mobile-v1：子树篇数徽章，仅手机端显示（默认隐藏，桌面零变化） */
.tree-count {
  display: none;
  margin-left: auto;
  flex-shrink: 0;
  min-width: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--bg-panel-2);
  color: var(--text-muted);
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 640px) {
  .tree-count {
    display: inline-block;
  }
}
</style>
