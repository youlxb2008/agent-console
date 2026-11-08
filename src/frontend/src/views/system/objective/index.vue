<!--
  目标管理（M4.1，菜单一级目录 /objective/list，组件 views/system/objective/index）
  --------------------------------------------------------
  - 分页表格：标题 / 所属 agent / 状态 / 周期 / 截止日期 / 进度（进度条）/ 更新时间
  - keyword（title 模糊）搜索 + agentId 筛选下拉
  - 新建（管理员代建，指定 agentId）/ 编辑 / 软删（二次确认，md 保留）
  - 详情抽屉：marked + dompurify 渲染 contentMd 全文 + 子项进度
  - 权限码：objective:list / create / edit / delete
-->
<template>
  <div class="na-page">
    <PageHeader
      title="目标列表"
      subtitle="管理 agent 私域 todolist（目标/进度/周期）"
    />

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :api="{ list: getObjectiveList }"
        :default-size="10"
      >
        <!-- 工具栏：新建 -->
        <template #toolbar>
          <el-button
            v-permiss="'objective:create'"
            type="primary"
            :icon="Plus"
            @click="openAdd"
          >
            新建目标
          </el-button>
        </template>

        <!-- 标题：主文字（首列） -->
        <template #column-title="{ row }">
          <span class="title-cell">{{ (row as ObjectiveItem).title }}</span>
        </template>

        <!-- 所属 agent：回填 agentName，无则 — -->
        <template #column-agentName="{ row }">
          <span class="agent-cell">{{ (row as ObjectiveItem).agentName || '—' }}</span>
        </template>

        <!-- 状态：克制文字标签 -->
        <template #column-status="{ row }">
          <span class="status-tag" :class="`is-${(row as ObjectiveItem).status}`">
            {{ statusText((row as ObjectiveItem).status) }}
          </span>
        </template>

        <!-- 周期 / 截止日期 -->
        <template #column-period="{ row }">
          <span class="muted-cell">{{ (row as ObjectiveItem).period || '—' }}</span>
        </template>
        <template #column-targetDate="{ row }">
          <span class="muted-cell">{{ (row as ObjectiveItem).targetDate || '—' }}</span>
        </template>

        <!-- 进度：数字 + 细进度条 -->
        <template #column-progress="{ row }">
          <div class="progress-cell">
            <el-progress
              :percentage="Number((row as ObjectiveItem).progress ?? 0)"
              :stroke-width="6"
              :show-text="false"
            />
            <span class="progress-num">{{ (row as ObjectiveItem).progress ?? 0 }}%</span>
          </div>
        </template>

        <!-- 操作列 -->
        <template #actions="{ row }">
          <el-button
            link
            type="primary"
            :icon="View"
            @click="openPreview(row as ObjectiveItem)"
          >
            详情
          </el-button>
          <el-button
            v-permiss="'objective:edit'"
            link
            type="primary"
            :icon="Edit"
            @click="openEdit(row as ObjectiveItem)"
          >
            编辑
          </el-button>
          <el-button
            v-permiss="'objective:delete'"
            link
            type="danger"
            :icon="Delete"
            @click="handleDelete(row as ObjectiveItem)"
          >
            删除
          </el-button>
        </template>
      </CrudTable>
    </div>

    <!-- 详情抽屉：marked + dompurify 渲染全文 -->
    <el-drawer
      v-model="drawerVisible"
      title="目标详情"
      size="640px"
    >
      <div v-loading="contentLoading" class="obj-detail">
        <template v-if="detail">
          <div class="detail-head">
            <h3 class="detail-title">{{ detail.title }}</h3>
            <div class="detail-meta">
              <span class="status-tag" :class="`is-${detail.status}`">{{ statusText(detail.status) }}</span>
              <span class="muted-cell">周期 {{ detail.period || '—' }}</span>
              <span class="muted-cell">截止 {{ detail.targetDate || '—' }}</span>
              <span class="progress-num">进度 {{ detail.progress ?? 0 }}%</span>
            </div>
          </div>
          <div class="detail-md" v-html="renderedHtml" />
        </template>
        <el-empty v-else-if="!contentLoading" description="目标不存在或已删除" />
      </div>
    </el-drawer>

    <!-- 新建 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑目标' : '新建目标'"
      width="600px"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item
          v-if="!isEdit"
          label="归属 Agent"
          prop="agentId"
        >
          <el-select
            v-model="form.agentId"
            filterable
            placeholder="选择目标归属的 agent"
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
        <el-form-item
          label="标题"
          prop="title"
        >
          <el-input
            v-model="form.title"
            placeholder="目标标题（todolist 条目）"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="周期">
          <el-input
            v-model="form.period"
            placeholder="如 2026-Q4 / 2026 / 自由文本"
            maxlength="20"
          />
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker
            v-model="form.targetDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择截止日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item
          v-if="isEdit"
          label="状态"
        >
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="未开始" :value="0" />
            <el-option label="进行中" :value="1" />
            <el-option label="已完成" :value="2" />
            <el-option label="已放弃" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.descMd"
            type="textarea"
            :rows="5"
            placeholder="描述段 markdown（可选）；子项由 agent 在详情 md「子项」段直接维护 checkbox"
          />
          <div class="field-tip">
            详情 md 结构：## 描述 / ## 子项（- [x] 已完成 · - [ ] 待做）/ ## 反思；进度按子项 checkbox 自动计算
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="submit"
        >
          确定
        </el-button>
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
import CrudTable from '@/components/CrudTable.vue'
import {
  getObjectiveList,
  getObjectiveDetail,
  createObjective,
  updateObjective,
  deleteObjective,
  type ObjectiveItem,
  type ObjectiveDetail
} from '@/api/system/objective'
import { loadAgentOptions, agentOptionLabel } from '@/api/system/note'
import type { AgentItem } from '@/api/system/agent'

const crudRef = ref<any>(null)

/** 状态文案（契约：0未开始 1进行中 2已完成 3已放弃） */
function statusText(status: number): string {
  return ['未开始', '进行中', '已完成', '已放弃'][status] ?? '未知'
}

/** 列定义 */
const columns = [
  { prop: 'title', label: '标题', minWidth: 200, slot: true },
  { prop: 'agentName', label: '归属 Agent', width: 140, slot: true },
  { prop: 'status', label: '状态', width: 90, slot: true },
  { prop: 'period', label: '周期', width: 110, slot: true },
  { prop: 'targetDate', label: '截止日期', width: 120, slot: true },
  { prop: 'progress', label: '进度', width: 150, slot: true },
  { prop: 'updateTime', label: '更新时间', minWidth: 160 }
]

/** 搜索项：keyword（标题）+ agentId 下拉 */
const searchItems = [
  { prop: 'keyword', label: '关键词', type: 'input' as const, placeholder: '目标标题' },
  { prop: 'agentId', label: 'Agent', type: 'select' as const, placeholder: '全部 Agent' }
]

const agentOptions = ref<AgentItem[]>([])

/* ===== 新建 / 编辑 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const editingOid = ref('')

const form = reactive<{
  agentId?: number
  title: string
  period?: string
  targetDate?: string
  status: number
  descMd?: string
}>({
  agentId: undefined,
  title: '',
  period: '',
  targetDate: '',
  status: 0,
  descMd: ''
})

const rules: FormRules = {
  title: [{ required: true, message: '标题不能为空', trigger: 'blur' }],
  agentId: [{ required: true, message: '请选择归属 agent', trigger: 'change' }]
}

function openAdd(): void {
  isEdit.value = false
  editingOid.value = ''
  Object.assign(form, { agentId: undefined, title: '', period: '', targetDate: '', status: 0, descMd: '' })
  dialogVisible.value = true
}

function openEdit(row: ObjectiveItem): void {
  isEdit.value = true
  editingOid.value = row.oid
  Object.assign(form, {
    agentId: row.agentId,
    title: row.title,
    period: row.period || '',
    targetDate: row.targetDate || '',
    status: row.status,
    descMd: ''
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
    if (isEdit.value) {
      const payload: Record<string, unknown> = { title: form.title }
      if (form.period !== undefined) payload.period = form.period
      if (form.targetDate) payload.targetDate = form.targetDate
      payload.status = form.status
      if (form.descMd) payload.descMd = form.descMd
      await updateObjective(editingOid.value, payload as never)
      ElMessage.success('已更新')
    } else {
      await createObjective(form.agentId!, {
        title: form.title,
        period: form.period || undefined,
        targetDate: form.targetDate || undefined,
        descMd: form.descMd || undefined
      })
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    crudRef.value?.reload()
  } catch {
    // 错误已由拦截器提示
  } finally {
    submitting.value = false
  }
}

/* ===== 详情抽屉 ===== */
const drawerVisible = ref(false)
const contentLoading = ref(false)
const detail = ref<ObjectiveDetail | null>(null)

const renderedHtml = computed<string>(() => {
  if (!detail.value?.contentMd) return ''
  const raw = marked.parse(detail.value.contentMd, { async: false }) as string
  return DOMPurify.sanitize(raw)
})

async function openPreview(row: ObjectiveItem): Promise<void> {
  drawerVisible.value = true
  contentLoading.value = true
  detail.value = null
  try {
    const res = await getObjectiveDetail(row.oid)
    detail.value = res.data
  } catch {
    detail.value = null
  } finally {
    contentLoading.value = false
  }
}

/* ===== 软删（二次确认：md 保留，门户不再展示） ===== */
async function handleDelete(row: ObjectiveItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `软删目标「${row.title}」：is_deleted=1（详情 md 文件保留，门户与管理端不再展示）。确认删除？`,
      '删除目标',
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
    await deleteObjective(row.oid)
    ElMessage.success('已删除')
    if (detail.value?.oid === row.oid) {
      drawerVisible.value = false
      detail.value = null
    }
    crudRef.value?.reload()
  } catch {
    // 错误已由拦截器提示
  }
}

// 初始化：agent 下拉
loadAgentOptions().then((opts) => {
  agentOptions.value = opts
})
</script>

<style scoped>
.title-cell {
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.agent-cell {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.muted-cell {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

/* 状态标签：克制灰底 + 语义色字 */
.status-tag {
  display: inline-block;
  padding: 0 8px;
  border-radius: 3px;
  font-size: 12px;
  line-height: 20px;
  border: 1px solid var(--el-border-color-light);
  background: transparent;
  color: var(--el-text-color-regular);
}
.status-tag.is-0 { color: #909399; }
.status-tag.is-1 { color: #409eff; }
.status-tag.is-2 { color: #67c23a; }
.status-tag.is-3 { color: #e6a23c; }

/* 进度：细进度条 + mono 数字 */
.progress-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.progress-cell .el-progress {
  flex: 1;
  min-width: 60px;
}
.progress-num {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.field-tip {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  line-height: 1.5;
  margin-top: 4px;
}

/* 详情抽屉 */
.obj-detail {
  min-height: 200px;
}
.detail-head {
  margin-bottom: 16px;
}
.detail-title {
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.detail-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 13px;
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
</style>
