<!--
  台账管理（M4.2，菜单一级目录 /ledger/list，组件 views/system/ledger/index）
  --------------------------------------------------------
  - 台账流样式（非普通表格）：按日期倒序分组 + 类型色块（契约 §1.4 色表）
  - agent 筛选 + type 筛选（后端过滤）
  - 单条详情抽屉：marked + dompurify 渲染正文 + 关联对象展示（ref 四列有值显示对应 ID）
  - 管理员代记 / 编辑 / 软删（二次确认，md 保留）；以只读查看为主，agent 自写
  - 权限码：ledger:list / create / edit / delete
-->
<template>
  <div class="na-page">
    <PageHeader
      title="台账列表"
      subtitle="agent 私域流水（progress🟢 / observation🟡 / decision🟣 / memo🔵 / session⚪）"
    />

    <!-- 筛选栏 -->
    <div class="led-filter">
      <el-select
        v-model="filterAgentId"
        clearable
        filterable
        placeholder="全部 Agent"
        style="width: 220px"
        @change="loadList"
      >
        <el-option
          v-for="a in agentOptions"
          :key="a.id"
          :label="agentOptionLabel(a)"
          :value="a.id"
        />
      </el-select>
      <el-select
        v-model="filterType"
        clearable
        placeholder="全部类型"
        style="width: 180px"
        @change="loadList"
      >
        <el-option
          v-for="t in typeOptions"
          :key="t.value"
          :label="t.label"
          :value="t.value"
        >
          <span class="type-dot" :class="`is-${t.value}`" />{{ t.label }}
        </el-option>
      </el-select>
      <el-button
        v-permiss="'ledger:create'"
        type="primary"
        :icon="Plus"
        @click="openAdd"
      >
        代记一笔
      </el-button>
      <span class="filter-count">共 {{ total }} 条流水</span>
    </div>

    <!-- 时间线流（按日期分组倒序，组内时间倒序） -->
    <div v-loading="loading" class="led-stream">
      <div
        v-for="group in groupedList"
        :key="group.date"
        class="led-day"
      >
        <div class="led-day-head">
          <span class="led-day-date">{{ group.date }}</span>
          <span class="led-day-count">{{ group.items.length }} 条</span>
        </div>
        <div class="led-day-body">
          <div
            v-for="it in group.items"
            :key="it.lid"
            class="led-item"
          >
            <span class="type-dot lg" :class="`is-${it.type}`" :title="typeText(it.type)" />
            <div class="led-item-main">
              <div class="led-item-title-row">
                <span class="led-item-title">{{ it.title }}</span>
                <span class="type-badge" :class="`is-${it.type}`">{{ typeText(it.type) }}</span>
              </div>
              <div class="led-item-meta">
                <span class="meta-agent">{{ it.agentName || '—' }}</span>
                <span class="meta-time">{{ formatTime(it.createTime) }}</span>
                <span v-if="it.refTaskId" class="ref-tag">任务 {{ it.refTaskId }}</span>
                <span v-if="it.refRequirementId" class="ref-tag">需求 {{ it.refRequirementId }}</span>
                <span v-if="it.refObjectiveId" class="ref-tag">目标 {{ it.refObjectiveId }}</span>
                <span v-if="it.refNoteNid" class="ref-tag">笔记 {{ it.refNoteNid }}</span>
              </div>
            </div>
            <div class="led-item-ops">
              <el-button link type="primary" :icon="View" @click="openPreview(it)">详情</el-button>
              <el-button
                v-permiss="'ledger:edit'"
                link
                type="primary"
                :icon="Edit"
                @click="openEdit(it)"
              >
                编辑
              </el-button>
              <el-button
                v-permiss="'ledger:delete'"
                link
                type="danger"
                :icon="Delete"
                @click="handleDelete(it)"
              >
                删除
              </el-button>
            </div>
          </div>
        </div>
      </div>
      <el-empty v-if="!loading && !groupedList.length" description="暂无台账流水" />
    </div>

    <!-- 分页 -->
    <div class="led-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        background
        layout="total, prev, pager, next, sizes"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="loadList"
        @size-change="onSizeChange"
      />
    </div>

    <!-- 详情抽屉：marked + dompurify 渲染正文 + 关联对象 -->
    <el-drawer v-model="drawerVisible" title="台账详情" size="640px">
      <div v-loading="contentLoading" class="led-detail">
        <template v-if="detail">
          <div class="detail-head">
            <span class="type-badge lg" :class="`is-${detail.type}`">{{ typeText(detail.type) }}</span>
            <h3 class="detail-title">{{ detail.title }}</h3>
            <div class="detail-meta">
              <span class="meta-agent">{{ detail.agentName || '—' }}</span>
              <span class="meta-time">{{ formatTime(detail.createTime) }}</span>
            </div>
            <div class="detail-refs">
              <span v-if="detail.refTaskId" class="ref-tag lg">关联任务 {{ detail.refTaskId }}</span>
              <span v-if="detail.refRequirementId" class="ref-tag lg">
                关联需求 {{ detail.refRequirementId }}
              </span>
              <span v-if="detail.refObjectiveId" class="ref-tag lg">
                关联目标 {{ detail.refObjectiveId }}
              </span>
              <span v-if="detail.refNoteNid" class="ref-tag lg">关联笔记 {{ detail.refNoteNid }}</span>
              <span v-if="!detail.refTaskId && !detail.refRequirementId && !detail.refObjectiveId && !detail.refNoteNid"
                class="ref-empty"
              >无关联对象</span>
            </div>
          </div>
          <div v-if="detail.body" class="detail-md" v-html="renderedHtml" />
          <div v-else class="detail-empty">（本条无正文）</div>
        </template>
        <el-empty v-else-if="!contentLoading" description="台账不存在或已删除" />
      </div>
    </el-drawer>

    <!-- 代记 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑台账' : '代记一笔'"
      width="620px"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item v-if="!isEdit" label="归属 Agent" prop="agentId">
          <el-select
            v-model="form.agentId"
            filterable
            placeholder="选择台账归属的 agent"
            style="width: 100%"
          >
            <el-option
              v-for="a in agentOptions"
              :key="a.id"
              :label="agentOptionLabel(a)"
              :value="a.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" style="width: 100%">
            <el-option
              v-for="t in typeOptions"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="条目标题" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="form.body"
            type="textarea"
            :rows="6"
            placeholder="可选；≤500 字直接入库，>500 字写入 LEDGER_ROOT 文件系统"
          />
          <div class="field-tip">
            body 双轨：短正文存库；长正文（>500 字）写
            LEDGER_ROOT/agent-{id}/ledger-{lid}.md，列表不读文件、详情读正文
          </div>
        </el-form-item>
        <el-form-item label="关联任务">
          <el-input v-model="form.refTaskId" placeholder="tid（可选，事件归因）" maxlength="16" />
        </el-form-item>
        <el-form-item label="关联需求">
          <el-input v-model="form.refRequirementId" placeholder="rid（可选）" maxlength="16" />
        </el-form-item>
        <el-form-item label="关联目标">
          <el-input v-model="form.refObjectiveId" placeholder="oid（可选）" maxlength="16" />
        </el-form-item>
        <el-form-item label="关联笔记">
          <el-input v-model="form.refNoteNid" placeholder="nid（可选）" maxlength="16" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Edit, Delete, View } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import PageHeader from '@/components/PageHeader.vue'
import {
  getLedgerList,
  getLedgerDetail,
  createLedger,
  updateLedger,
  deleteLedger,
  type LedgerItem,
  type LedgerDetail,
  type LedgerType
} from '@/api/system/ledger'
import { loadAgentOptions, agentOptionLabel } from '@/api/system/note'
import type { AgentItem } from '@/api/system/agent'

/** 类型选项（契约 §1.4：progress🟢/observation🟡/decision🟣/memo🔵/session⚪） */
const typeOptions: { value: LedgerType; label: string }[] = [
  { value: 'progress', label: '任务推进' },
  { value: 'observation', label: '巡检发现' },
  { value: 'decision', label: '拍板决策' },
  { value: 'memo', label: '备忘' },
  { value: 'session', label: '会话小结' }
]

function typeText(type: string): string {
  return typeOptions.find((t) => t.value === type)?.label || type
}

/* ===== 筛选 ===== */
const agentOptions = ref<AgentItem[]>([])
const filterAgentId = ref<number | undefined>(undefined)
const filterType = ref<string | undefined>(undefined)

/* ===== 列表数据 ===== */
const loading = ref(false)
const list = ref<LedgerItem[]>([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

/** 按 yyyy-MM-dd 分组（倒序），组内 create_time 倒序 */
interface LedgerGroup {
  date: string
  items: LedgerItem[]
}
const groupedList = computed<LedgerGroup[]>(() => {
  const groups = new Map<string, LedgerItem[]>()
  for (const it of list.value) {
    const date = String(it.createTime || '').slice(0, 10) || '未知日期'
    const arr = groups.get(date) || []
    arr.push(it)
    groups.set(date, arr)
  }
  return [...groups.entries()]
    .map(([date, items]) => ({
      date,
      items: items.sort((a, b) => String(b.createTime).localeCompare(String(a.createTime)))
    }))
    .sort((a, b) => b.date.localeCompare(a.date))
})

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const res = await getLedgerList({
      page: page.value,
      size: pageSize.value,
      agentId: filterAgentId.value,
      type: filterType.value || undefined
    })
    const data = res.data
    list.value = data?.list || []
    total.value = Number(data?.count ?? 0)
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onSizeChange(): void {
  page.value = 1
  void loadList()
}

function formatTime(timeStr?: string): string {
  if (!timeStr) return ''
  return String(timeStr).replace('T', ' ')
}

/* ===== 代记 / 编辑 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const editingLid = ref('')

const form = reactive<{
  agentId?: number
  type: LedgerType
  title: string
  body?: string
  refTaskId?: string
  refRequirementId?: string
  refObjectiveId?: string
  refNoteNid?: string
}>({
  agentId: undefined,
  type: 'progress',
  title: '',
  body: '',
  refTaskId: '',
  refRequirementId: '',
  refObjectiveId: '',
  refNoteNid: ''
})

const rules: FormRules = {
  agentId: [{ required: true, message: '请选择归属 agent', trigger: 'change' }],
  title: [{ required: true, message: '标题不能为空', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }]
}

function openAdd(): void {
  isEdit.value = false
  editingLid.value = ''
  Object.assign(form, {
    agentId: undefined,
    type: 'progress',
    title: '',
    body: '',
    refTaskId: '',
    refRequirementId: '',
    refObjectiveId: '',
    refNoteNid: ''
  })
  dialogVisible.value = true
}

function openEdit(row: LedgerItem): void {
  isEdit.value = true
  editingLid.value = row.lid
  Object.assign(form, {
    agentId: row.agentId,
    type: row.type,
    title: row.title,
    body: '',
    refTaskId: row.refTaskId || '',
    refRequirementId: row.refRequirementId || '',
    refObjectiveId: row.refObjectiveId || '',
    refNoteNid: row.refNoteNid || ''
  })
  dialogVisible.value = true
}

function resetForm(): void {
  formRef.value?.clearValidate()
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const refs = {
      refTaskId: form.refTaskId || undefined,
      refRequirementId: form.refRequirementId || undefined,
      refObjectiveId: form.refObjectiveId || undefined,
      refNoteNid: form.refNoteNid || undefined
    }
    if (isEdit.value) {
      const payload: Record<string, unknown> = { type: form.type, title: form.title }
      if (form.body !== undefined) payload.body = form.body
      Object.assign(payload, refs)
      await updateLedger(editingLid.value, payload as never)
      ElMessage.success('已更新')
    } else {
      await createLedger(form.agentId!, {
        type: form.type,
        title: form.title,
        body: form.body || undefined,
        ...refs
      })
      ElMessage.success('已记录')
    }
    dialogVisible.value = false
    void loadList()
  } catch {
    // 错误已由拦截器提示
  } finally {
    submitting.value = false
  }
}

/* ===== 详情抽屉 ===== */
const drawerVisible = ref(false)
const contentLoading = ref(false)
const detail = ref<LedgerDetail | null>(null)

const renderedHtml = computed<string>(() => {
  if (!detail.value?.body) return ''
  const raw = marked.parse(detail.value.body, { async: false }) as string
  return DOMPurify.sanitize(raw)
})

async function openPreview(row: LedgerItem): Promise<void> {
  drawerVisible.value = true
  contentLoading.value = true
  detail.value = null
  try {
    const res = await getLedgerDetail(row.lid)
    detail.value = res.data
  } catch {
    detail.value = null
  } finally {
    contentLoading.value = false
  }
}

/* ===== 软删（二次确认：md 保留，门户与管理端不再展示） ===== */
async function handleDelete(row: LedgerItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `软删台账「${row.title}」：is_deleted=1（长正文 md 文件保留，门户与管理端不再展示）。确认删除？`,
      '删除台账',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  try {
    await deleteLedger(row.lid)
    ElMessage.success('已删除')
    if (detail.value?.lid === row.lid) {
      drawerVisible.value = false
      detail.value = null
    }
    void loadList()
  } catch {
    // 错误已由拦截器提示
  }
}

/* ===== 初始化 ===== */
loadAgentOptions().then((opts) => {
  agentOptions.value = opts
})
void loadList()
</script>

<style scoped>
/* ============ 筛选栏 ============ */
.led-filter {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.filter-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

/* ============ 类型色块（契约 §1.4 色表） ============ */
.type-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}
.type-dot.lg {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  margin: 4px 0 0;
}
.type-dot.is-progress,
.type-badge.is-progress { background: #2eb872; color: #2eb872; }
.type-dot.is-observation,
.type-badge.is-observation { background: #e6a23c; color: #e6a23c; }
.type-dot.is-decision,
.type-badge.is-decision { background: #9254de; color: #9254de; }
.type-dot.is-memo,
.type-badge.is-memo { background: #409eff; color: #409eff; }
.type-dot.is-session,
.type-badge.is-session { background: #909399; color: #909399; }

.type-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 18px;
  border: 1px solid currentColor;
  background: transparent;
  white-space: nowrap;
}
.type-badge.lg {
  font-size: 12px;
  padding: 2px 10px;
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
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 8px;
}
.led-day-date {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.led-day-count {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
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
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
  transition: border-color 0.18s, background 0.18s;
}
.led-item:hover {
  border-color: var(--el-border-color-light);
  background: var(--el-fill-color-light);
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
  font-weight: 500;
  color: var(--el-text-color-primary);
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
  color: var(--el-text-color-secondary);
}
.meta-time {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.ref-tag {
  display: inline-block;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 18px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  border: 1px solid var(--el-color-primary-light-7);
}
.ref-tag.lg {
  font-size: 12px;
  padding: 1px 8px;
}
.led-item-ops {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

/* ============ 分页 ============ */
.led-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* ============ 详情抽屉 ============ */
.led-detail {
  min-height: 200px;
}
.detail-head {
  margin-bottom: 16px;
}
.detail-title {
  margin: 8px 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.detail-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  margin-bottom: 10px;
}
.detail-refs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ref-empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.detail-md {
  line-height: 1.7;
  color: var(--el-text-color-primary);
  overflow-wrap: break-word;
}
.detail-md :deep(h1),
.detail-md :deep(h2),
.detail-md :deep(h3) {
  margin: 20px 0 8px;
  font-weight: 600;
}
.detail-md :deep(ul) {
  padding-left: 20px;
}
.detail-md :deep(li) {
  margin: 4px 0;
}
.detail-md :deep(pre) {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
  overflow-x: auto;
  font-size: 13px;
}
.detail-empty {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.field-tip {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  line-height: 1.5;
  margin-top: 4px;
}
</style>
