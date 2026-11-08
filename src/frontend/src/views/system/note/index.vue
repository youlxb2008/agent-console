<!--
  笔记管理（v3：由 views/system/agent/review.vue 迁移 + 无主化改造）
  -------------------------------------------------
  - 菜单升顶级：路由 /note/list（菜单 3040 父级 3050「笔记管理」）
  - agent 筛选（按关联 agent 过滤，后端 JOIN t_agent_resource）+ 关键词（title LIKE）
  - 索引分页表格：title / 贡献者（多 tag，超 3 个 +N）/ dirPath / updateTime
  - 预览抽屉：el-drawer + marked/dompurify 渲染全文 + 顶部贡献者面包屑
  - 操作列：预览 / ✨关联（agent:note:link，NoteLinkDialog 穿梭框全量覆盖）/ 删除
  - 权限码：agent:note:list / agent:note:link / agent:note:delete
-->
<template>
  <div class="na-page">
    <PageHeader
      title="笔记列表"
      subtitle="管理无主笔记与 agent 的关联（文件系统真相源，删除后不可恢复）"
    />

    <!-- ✨ KB 知识索引状态条：key/Lucene 健康度 + 重建入口（重建=分块+向量化+全文索引全量重跑） -->
    <div class="kb-bar">
      <div class="kb-bar-left">
        <span class="kb-bar-title">知识索引</span>
        <el-tag :type="kbStatus?.keyConfigured ? 'success' : 'warning'" size="small">
          {{ kbStatus?.keyConfigured ? '语义检索已启用' : '未配置 Key（仅全文检索）' }}
        </el-tag>
        <el-tag :type="kbStatus?.luceneAvailable ? 'success' : 'danger'" size="small">
          {{ kbStatus?.luceneAvailable ? '全文索引正常' : '全文索引异常' }}
        </el-tag>
        <!-- 上次重建结果 / 进行中进度 -->
        <span v-if="kbStatus?.rebuilding" class="kb-bar-progress">
          重建中 {{ kbStatus.done }}/{{ kbStatus.total }}…
        </span>
        <span v-else-if="kbStatus?.message" class="kb-bar-progress">
          上次重建：{{ kbStatus.message }}
        </span>
      </div>
      <el-button
        v-permiss="'agent:note:link'"
        size="small"
        :loading="kbRebuilding"
        @click="handleRebuildKb"
      >
        重建知识索引
      </el-button>
    </div>

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :api="{ list: getNoteList }"
        row-key="nid"
        :action-width="170"
        :default-size="10"
      >
        <!-- agent 筛选（空=全部）：动态 option，走自定义 slot -->
        <template #search-agentId="{ query }">
          <el-select
            v-model="(query as Record<string, any>).agentId"
            clearable
            placeholder="全部 Agent"
            style="width: 180px"
          >
            <el-option
              v-for="a in agentOptions"
              :key="a.id"
              :label="agentOptionLabel(a)"
              :value="a.id"
            />
          </el-select>
        </template>

        <!-- v3.5：笔记本筛选（空=全部 notebook，数据源 getAllNotebooks） -->
        <template #search-notebookId="{ query }">
          <el-select
            v-model="(query as Record<string, any>).notebookId"
            clearable
            placeholder="全部笔记本"
            style="width: 180px"
          >
            <el-option
              v-for="nb in notebookOptions"
              :key="nb.id"
              :label="nb.name"
              :value="nb.id"
            />
          </el-select>
        </template>

        <!-- v3.5：所属笔记本（参照审计日志：中性 outline 边框 + 等宽样式） -->
        <template #column-notebookName="{ row }">
          <span
            v-if="(row as AdminNoteItem).notebookName"
            class="outline-tag"
          >
            {{ (row as AdminNoteItem).notebookName }}
          </span>
          <span
            v-else
            class="muted-cell"
          >无</span>
        </template>

        <!-- 贡献者：多 tag 渲染，超 3 个显示 +N（中性 outline 边框） -->
        <template #column-contributors="{ row }">
          <template v-if="((row as AdminNoteItem).contributors || []).length">
            <span
              v-for="c in (row as AdminNoteItem).contributors!.slice(0, 3)"
              :key="c.id"
              class="outline-tag"
            >
              {{ c.displayName }}
            </span>
            <el-tooltip
              v-if="(row as AdminNoteItem).contributors!.length > 3"
              :content="(row as AdminNoteItem).contributors!.map((c) => c.displayName).join('、')"
              placement="top"
            >
              <span class="outline-tag outline-tag-more">
                +{{ (row as AdminNoteItem).contributors!.length - 3 }}
              </span>
            </el-tooltip>
          </template>
          <span
            v-else
            class="muted-cell"
          >无</span>
        </template>

        <!-- 所在目录：dirPath 直读（v3 不再从 filePath 剥离），用等宽 / 弱化样式 -->
        <template #column-dirPath="{ row }">
          <span class="dir-cell">{{ (row as AdminNoteItem).dirPath || '根目录' }}</span>
        </template>

        <!-- 标题：近 24h 新增加左上高亮条；右侧挂「门户打开」小按钮 -->
        <template #column-title="{ row }">
          <div class="title-cell" :class="{ 'is-fresh': isFresh((row as AdminNoteItem).updateTime) }">
            <span class="title-text">{{ (row as AdminNoteItem).title }}</span>
            <el-tooltip
              v-if="(row as AdminNoteItem).notebookId"
              content="在门户端打开"
              placement="top"
              :show-after="300"
            >
              <a
                class="portal-link"
                :href="portalUrl(row as AdminNoteItem)"
                target="_blank"
                rel="noopener noreferrer"
                @click.stop
              >
                <el-icon :size="14"><Position /></el-icon>
              </a>
            </el-tooltip>
          </div>
        </template>

        <!-- 操作列：预览 + 关联 + 删除 -->
        <template #actions="{ row }">
          <el-button
            v-permiss="'agent:note:list'"
            link
            type="primary"
            @click="openPreview(row as AdminNoteItem)"
          >
            预览
          </el-button>
          <el-button
            v-if="(row as AdminNoteItem).notebookId"
            v-permiss="'agent:note:list'"
            link
            type="primary"
            @click="openInPortal(row as AdminNoteItem)"
          >
            门户
          </el-button>
          <el-button
            v-permiss="'agent:note:link'"
            link
            type="primary"
            @click="openLinkDialog(row as AdminNoteItem)"
          >
            关联
          </el-button>
          <el-button
            v-permiss="'agent:note:delete'"
            link
            type="danger"
            @click="handleDelete(row as AdminNoteItem)"
          >
            删除
          </el-button>
        </template>
      </CrudTable>
    </div>

    <!-- 预览抽屉：markdown 渲染全文（marked + dompurify） -->
    <el-drawer
      v-model="drawerVisible"
      :title="content?.title || '笔记预览'"
      size="620px"
    >
      <div v-if="contentLoading" class="drawer-loading">
        加载中…
      </div>
      <template v-else-if="content">
        <!-- 顶部面包屑：贡献者（多 tag）· 所在目录 -->
        <div class="drawer-crumb">
          <template v-if="content.contributors?.length">
            <el-tag
              v-for="c in content.contributors"
              :key="c.id"
              size="small"
              type="info"
              style="margin-right: 4px"
            >
              {{ c.displayName }}
            </el-tag>
          </template>
          <span class="crumb-sep">·</span>
          <span class="crumb-dir">{{ content.dirPath || '根目录' }}</span>
        </div>

        <!-- markdown 正文（净化后注入） -->
        <div class="drawer-body markdown-body" v-html="renderedHtml" />

        <!-- 底部元信息 -->
        <div class="drawer-foot">
          <span v-if="content.updateTime">更新时间：{{ content.updateTime }}</span>
          <span v-if="content.sizeBytes"> · {{ formatBytes(content.sizeBytes) }}</span>
        </div>
      </template>
      <el-empty v-else description="未找到笔记内容" :image-size="60" />
    </el-drawer>

    <!-- ✨ 关联维护弹窗（穿梭框全量覆盖） -->
    <NoteLinkDialog
      v-model:visible="linkDialogVisible"
      :nid="linkTarget?.nid || ''"
      :note-title="linkTarget?.title || ''"
      @success="crudRef?.reload()"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Position } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable from '@/components/CrudTable.vue'
import NoteLinkDialog from './NoteLinkDialog.vue'
import {
  getNoteList,
  getNoteContent,
  deleteNote,
  agentOptionLabel,
  getKbStatus,
  rebuildKb,
  type AdminNoteItem,
  type AdminNoteContent,
  type KbStatus
} from '@/api/system/note'
import { getAgentList, type AgentItem } from '@/api/system/agent'
import { getAllNotebooks, type NotebookItem } from '@/api/system/notebook'

const crudRef = ref<any>(null)

/** 列定义 */
const columns = [
  { prop: 'title', label: '标题', minWidth: 220, slot: true },
  { prop: 'notebookName', label: '所属笔记本', minWidth: 140, slot: true },
  { prop: 'contributors', label: '贡献者', minWidth: 180, slot: true },
  { prop: 'dirPath', label: '所在目录', minWidth: 180, slot: true },
  { prop: 'updateTime', label: '更新时间', minWidth: 150 }
]

/** 24h 内更新判定（高亮新鲜笔记） */
function isFresh(updateTime?: string): boolean {
  if (!updateTime) return false
  const t = new Date(updateTime.replace(' ', 'T')).getTime()
  if (Number.isNaN(t)) return false
  return Date.now() - t < 24 * 60 * 60 * 1000
}

/* ===== 门户端跳转（v3.5：管理端列表 → 门户深链接，新窗口打开） ===== */
/** 门户基础地址（来自 .env.development 的 VITE_PORTAL_BASE_URL；生产同源部署时留空 → 回退当前站点源） */
const PORTAL_BASE = import.meta.env.VITE_PORTAL_BASE_URL
  || (typeof window !== 'undefined' ? window.location.origin : '')

/** 拼装门户深链接（M3.8 RESTful）：有 nid → /notebooks/{notebookId}/notes/{nid}；仅 notebookId → /notebooks/{notebookId}；无 → / */
function portalUrl(row: AdminNoteItem): string {
  if (row.notebookId && row.nid) {
    return `${PORTAL_BASE}/notebooks/${row.notebookId}/notes/${row.nid}`
  }
  if (row.notebookId) {
    return `${PORTAL_BASE}/notebooks/${row.notebookId}`
  }
  return `${PORTAL_BASE}/`
}

/** 操作列按钮：打开门户深链接（无 notebookId 兜底跳首页） */
function openInPortal(row: AdminNoteItem): void {
  const url = portalUrl(row)
  window.open(url, '_blank', 'noopener,noreferrer')
}

/** 搜索项：agent 筛选（slot 自定义，按关联过滤）+ 笔记本筛选（slot 自定义）+ 关键词 */
const searchItems = [
  { prop: 'agentId', label: 'Agent', type: 'select' as const, placeholder: '全部 Agent' },
  { prop: 'notebookId', label: '笔记本', type: 'select' as const, placeholder: '全部笔记本' },
  { prop: 'keyword', label: '关键词', type: 'input' as const, placeholder: '标题关键词' }
]

/** agent 下拉数据（分页接口取全量，供筛选） */
const agentOptions = ref<AgentItem[]>([])
async function loadAgentOptions(): Promise<void> {
  try {
    const res = await getAgentList({ page: 1, size: 1000 })
    agentOptions.value = res.data?.records || res.data?.list || []
  } catch {
    agentOptions.value = []
  }
}

/** v3.5：笔记本下拉数据（getAllNotebooks 未软删全量，供过滤） */
const notebookOptions = ref<NotebookItem[]>([])
async function loadNotebookOptions(): Promise<void> {
  try {
    const res = await getAllNotebooks()
    notebookOptions.value = res.data || []
  } catch {
    notebookOptions.value = []
  }
}

/** 字节数格式化 */
function formatBytes(bytes?: number): string {
  if (!bytes) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/* ===== 预览抽屉 ===== */
const drawerVisible = ref(false)
const contentLoading = ref(false)
const content = ref<AdminNoteContent | null>(null)

/** marked 渲染 + DOMPurify 净化后的安全 HTML（禁 raw HTML） */
const renderedHtml = computed<string>(() => {
  if (!content.value?.contentMd) return ''
  const raw = marked.parse(content.value.contentMd, { async: false }) as string
  return DOMPurify.sanitize(raw)
})

async function openPreview(row: AdminNoteItem): Promise<void> {
  drawerVisible.value = true
  contentLoading.value = true
  content.value = null
  try {
    const res = await getNoteContent(row.nid)
    content.value = res.data
  } catch {
    content.value = null
  } finally {
    contentLoading.value = false
  }
}

/* ===== 关联维护弹窗 ===== */
const linkDialogVisible = ref(false)
const linkTarget = ref<AdminNoteItem | null>(null)

function openLinkDialog(row: AdminNoteItem): void {
  linkTarget.value = row
  linkDialogVisible.value = true
}

/* ===== 删除（二次确认「删除后文件不可恢复」） ===== */
async function handleDelete(row: AdminNoteItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '删除后文件不可恢复（文件 + 索引 + 全部关联行），确认删除？',
      '危险操作',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return // 用户取消
  }
  try {
    await deleteNote(row.nid)
    ElMessage.success('已删除')
    // 若预览的是该笔记，关闭抽屉
    if (content.value?.nid === row.nid) {
      drawerVisible.value = false
      content.value = null
    }
    crudRef.value?.reload()
  } catch {
    // 删除失败：错误已由请求拦截器统一提示
  }
}

/* ===== KB 知识索引：状态查询 + 全量重建（2s 轮询直至完成） ===== */
const kbStatus = ref<KbStatus | null>(null)
/** 前端按钮 loading 态（与 rebuilding 分开：rebuild 请求返回即启动轮询，按钮仍转圈到完成） */
const kbRebuilding = ref(false)
let kbPollTimer: ReturnType<typeof setInterval> | null = null

/** 拉取 KB 状态（进页面一次 + 重建期间轮询） */
async function loadKbStatus(): Promise<void> {
  try {
    const res = await getKbStatus()
    kbStatus.value = res.data
    // 重建已结束 → 停轮询 + 提示结果
    if (kbRebuilding.value && res.data && !res.data.rebuilding) {
      stopKbPoll()
      kbRebuilding.value = false
      ElMessage.success(res.data.message || '知识索引重建完成')
    }
  } catch {
    // 状态拉取失败不打断页面（状态条显示空）
  }
}

function stopKbPoll(): void {
  if (kbPollTimer) {
    clearInterval(kbPollTimer)
    kbPollTimer = null
  }
}

/** 触发全量重建：启动任务 → 2s 轮询进度直至完成（向量化逐篇调百炼，篇数多时耗时数分钟） */
async function handleRebuildKb(): Promise<void> {
  try {
    await rebuildKb()
  } catch {
    return // 启动失败（含"重建中重复触发"）：错误已由拦截器提示
  }
  kbRebuilding.value = true
  stopKbPoll()
  kbPollTimer = setInterval(() => void loadKbStatus(), 2000)
}

/** 离开页面清理轮询 */
onUnmounted(() => stopKbPoll())

// 初始化：加载 agent 下拉 + 笔记本下拉 + KB 状态
loadAgentOptions()
loadNotebookOptions()
loadKbStatus()
</script>

<style scoped>
/* ✨ KB 知识索引状态条：标题 + 健康 tag + 进度/结果 + 重建按钮（与列表卡片分离的轻量行） */
.kb-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}

.kb-bar-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.kb-bar-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.kb-bar-progress {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* outline-tag（笔记本 / 贡献者）：参照审计日志的克制风
   - 1px 灰边框 + 透明底 + 主题色字
   - 无 hover 动画、无阴影 */
.outline-tag {
  display: inline-block;
  padding: 0 8px;
  margin-right: 6px;
  margin-bottom: 2px;
  border-radius: 3px;
  border: 1px solid var(--el-border-color-light);
  background: transparent;
  color: var(--el-color-primary);
  font-size: 12px;
  line-height: 20px;
  font-weight: 500;
  user-select: none;
  white-space: nowrap;
}

.outline-tag-more {
  color: var(--el-text-color-secondary);
  font-weight: 400;
}

.muted-cell {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.dir-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.title-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.title-cell.is-fresh .title-text {
  font-weight: 600;
  color: var(--el-color-primary);
}

.title-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 标题尾部的「门户打开」小图标（hover 显色，克制风） */
.portal-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-left: 4px;
  border-radius: 3px;
  color: var(--el-text-color-placeholder);
  text-decoration: none;
  transition: color 0.15s ease, background 0.15s ease;
  flex-shrink: 0;
}
.portal-link:hover {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}

/* ============ 预览抽屉 ============ */
.drawer-loading {
  padding: 48px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.drawer-crumb {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding-bottom: 12px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.crumb-sep {
  color: var(--el-text-color-secondary);
}

.crumb-dir {
  color: var(--el-text-color-regular);
}

.drawer-body {
  max-height: 62vh;
  overflow-y: auto;
}

.drawer-foot {
  margin-top: 20px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* markdown 排版（drawer 内） */
.drawer-body :deep(h1),
.drawer-body :deep(h2),
.drawer-body :deep(h3),
.drawer-body :deep(h4) {
  margin: 1.1em 0 0.5em;
  line-height: 1.35;
}

.drawer-body :deep(h1:first-child) {
  margin-top: 0;
}

.drawer-body :deep(h2) {
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.drawer-body :deep(p) {
  margin: 0.7em 0;
  line-height: 1.75;
}

.drawer-body :deep(a) {
  color: var(--el-color-primary);
}

.drawer-body :deep(code) {
  background: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 2px 5px;
  font-size: 13px;
}

.drawer-body :deep(pre) {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 14px;
  overflow-x: auto;
}

.drawer-body :deep(pre code) {
  background: transparent;
  padding: 0;
}

.drawer-body :deep(blockquote) {
  margin: 0.8em 0;
  padding: 4px 14px;
  border-left: 3px solid var(--el-color-primary);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
}

.drawer-body :deep(table) {
  border-collapse: collapse;
  margin: 0.8em 0;
  width: 100%;
}

.drawer-body :deep(th),
.drawer-body :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 8px 12px;
}

.drawer-body :deep(th) {
  background: var(--el-fill-color-light);
  font-weight: 600;
}

.drawer-body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
</style>
