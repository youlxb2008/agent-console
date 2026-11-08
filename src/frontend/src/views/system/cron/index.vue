<!--
  定时任务管理
  -----------
  - Cron 表达式解析（parseCronDesc 函数将 cron 转为中文描述 + tooltip）
  - 状态列改为 el-switch 行内切换
  - 执行日志改为 el-drawer（700px），增加搜索和清空功能
  - 新增/编辑弹窗 handler 从后端获取列表并下拉选择
  - 手动触发后解析返回的执行结果
-->
<template>
  <div class="na-page">
    <PageHeader
      title="定时任务"
      subtitle="管理系统定时任务及执行日志"
    />

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :action-width="280"
        :api="{ list: getCronList, delete: deleteCron }"
      >
        <template #toolbar>
          <el-button
            v-permiss="'system:cron'"
            type="primary"
            :icon="Plus"
            @click="openAdd"
          >
            新增任务
          </el-button>
        </template>

        <!-- Cron 表达式列 -->
        <template #column-cronExpr="{ row }">
          <el-tooltip
            :content="parseCronDesc(row.cronExpr)"
            placement="top"
          >
            <code class="cron-code">{{ row.cronExpr }}</code>
          </el-tooltip>
        </template>

        <!-- 处理器列：显示 handler 名称 + 描述 -->
        <template #column-handlerClass="{ row }">
          <span>{{ getHandlerDesc(row.handlerClass) }}</span>
        </template>

        <!-- 状态列：el-switch 行内切换 -->
        <template #column-status="{ row }">
          <el-switch
            :model-value="row.status === 1"
            active-text="启用"
            inactive-text="禁用"
            inline-prompt
            @change="handleToggleStatus(row)"
          />
        </template>

        <template #actions="{ row }">
          <el-button
            v-permiss="'system:cron'"
            link
            type="success"
            :icon="CaretRight"
            @click="handleTrigger(row)"
          >
            执行一次
          </el-button>
          <el-button
            v-permiss="'system:cron'"
            link
            type="primary"
            :icon="Edit"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            v-permiss="'system:cron'"
            link
            type="info"
            :icon="Document"
            @click="openLogDrawer(row)"
          >
            日志
          </el-button>
          <el-button
            v-permiss="'system:cron'"
            link
            type="danger"
            :icon="Delete"
            @click="crudRef?.handleDelete(row)"
          >
            删除
          </el-button>
        </template>
      </CrudTable>
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑任务' : '新增任务'"
      width="560px"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <el-form-item
          label="任务名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            placeholder="请输入任务名称"
          />
        </el-form-item>
        <el-form-item
          label="Cron 表达式"
          prop="cronExpr"
        >
          <el-input
            v-model="form.cronExpr"
            placeholder="如 0 0/5 * * * ?"
          />
          <div
            v-if="form.cronExpr"
            class="cron-hint"
          >
            {{ parseCronDesc(form.cronExpr) }}
          </div>
        </el-form-item>
        <el-form-item
          label="处理器"
          prop="handlerClass"
        >
          <el-select
            v-model="form.handlerClass"
            placeholder="请选择处理器"
            style="width: 100%"
          >
            <el-option
              v-for="h in handlerList"
              :key="h.name"
              :label="`${h.name}（${h.description}）`"
              :value="h.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          label="任务参数"
          prop="params"
        >
          <el-input
            v-model="form.params"
            placeholder="如 {&quot;name&quot;:&quot;Admin&quot;}（可选）"
          />
        </el-form-item>
        <el-form-item
          label="状态"
          prop="status"
        >
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
          />
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

    <!-- 执行日志抽屉 -->
    <el-drawer
      v-model="logDrawerVisible"
      :title="`执行日志 - ${logJobName}`"
      size="700px"
      direction="rtl"
    >
      <div class="log-toolbar">
        <el-select
          v-model="logQuery.logStatus"
          placeholder="执行状态"
          clearable
          style="width: 140px"
        >
          <el-option
            label="成功"
            :value="1"
          />
          <el-option
            label="失败"
            :value="0"
          />
        </el-select>
        <el-button
          type="primary"
          @click="loadLogs"
        >
          查询
        </el-button>
        <el-button
          type="danger"
          plain
          @click="handleClearLogs"
        >
          清空日志
        </el-button>
      </div>

      <el-table
        v-loading="logLoading"
        :data="logData"
        border
        stripe
        size="small"
      >
        <el-table-column
          prop="id"
          label="ID"
          width="60"
        />
        <el-table-column
          prop="createTime"
          label="执行时间"
          width="170"
        />
        <el-table-column
          prop="status"
          label="状态"
          width="80"
          align="center"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.status === 1 ? 'success' : 'danger'"
              size="small"
            >
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="duration"
          label="耗时(ms)"
          width="100"
          align="center"
        />
        <el-table-column
          label="结果/错误"
          min-width="200"
        >
          <template #default="{ row }">
            <div
              v-if="row.status === 0"
              class="log-error"
            >
              {{ row.result }}
            </div>
            <div
              v-else
              class="log-result"
            >
              {{ row.result || '-' }}
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="log-pagination">
        <el-pagination
          v-model:current-page="logQuery.page"
          v-model:page-size="logQuery.size"
          :total="logTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadLogs"
          @size-change="loadLogs"
        />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Edit, Delete, CaretRight, Document } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable from '@/components/CrudTable.vue'
import {
  getCronList,
  addCron,
  updateCron,
  deleteCron,
  runCron,
  getCronLogs,
  toggleCronStatus,
  clearCronLogs,
  getCronHandlers,
  type SysCron,
  type CronLog,
  type CronHandler
} from '@/api/system/cron'

// CrudTable 是泛型组件，InstanceType 无法正确推断，使用 any 代替
const crudRef = ref<any>(null)

// 列定义
const columns = [
  { prop: 'name', label: '任务名称', minWidth: 140 },
  { prop: 'cronExpr', label: 'Cron 表达式', minWidth: 150, slot: true },
  { prop: 'handlerClass', label: '处理器', minWidth: 200, slot: true },
  { prop: 'status', label: '状态', width: 100, slot: true },
  { prop: 'createTime', label: '创建时间', minWidth: 160 }
]

const searchItems = [
  { prop: 'name', label: '任务名称' },
  {
    prop: 'status',
    label: '状态',
    type: 'select' as const,
    options: [
      { label: '启用', value: 1 },
      { label: '禁用', value: 0 }
    ]
  }
]

/* ===== 处理器列表 ===== */
const handlerList = ref<CronHandler[]>([])

onMounted(async () => {
  try {
    const res = await getCronHandlers()
    handlerList.value = res.data || []
  } catch {
    handlerList.value = []
  }
})

function getHandlerDesc(name: string): string {
  const h = handlerList.value.find((item) => item.name === name)
  return h ? `${h.name}（${h.description}）` : name || '-'
}

/* ===== 新增/编辑弹窗 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const defaultForm = (): SysCron => ({
  name: '',
  cronExpr: '',
  handlerClass: '',
  params: '',
  status: 1
})

const form = reactive<SysCron>(defaultForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  cronExpr: [{ required: true, message: '请输入 Cron 表达式', trigger: 'blur' }],
  handlerClass: [{ required: true, message: '请选择处理器', trigger: 'change' }]
}

function openAdd() {
  isEdit.value = false
  Object.assign(form, defaultForm())
  dialogVisible.value = true
}

function openEdit(row: SysCron) {
  isEdit.value = true
  Object.assign(form, defaultForm(), row)
  dialogVisible.value = true
}

function resetForm() {
  formRef.value?.resetFields()
  Object.assign(form, defaultForm())
}

async function submit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateCron(form)
      ElMessage.success('修改成功')
    } else {
      await addCron(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    crudRef.value?.reload()
  } finally {
    submitting.value = false
  }
}

/* ===== 状态切换 ===== */
async function handleToggleStatus(row: SysCron): Promise<void> {
  try {
    await toggleCronStatus(row.id!)
    ElMessage.success(row.status === 1 ? '已禁用' : '已启用')
    crudRef.value?.reload()
  } catch (err: unknown) {
    const msg = (err as Error)?.message || '操作失败'
    ElMessage.error(msg)
  }
}

/* ===== 手动触发 ===== */
async function handleTrigger(row: SysCron): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认手动执行一次「${row.name}」？`, '手动执行', {
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
      type: 'info'
    })
  } catch {
    return
  }
  try {
    const res = await runCron(row.id!)
    const log = res.data as any
    if (!log) {
      ElMessage.info('任务已触发')
    } else if (log.status === 1) {
      ElMessage.success(`执行成功，耗时 ${log.duration || 0}ms`)
    } else if (log.status === 0) {
      ElMessage.error(`执行失败：${log.result || '未知错误'}`)
    } else {
      ElMessage.info('任务已触发，正在执行中')
    }
    crudRef.value?.reload()
  } catch {
    // 错误由拦截器处理
  }
}

/* ===== 执行日志抽屉 ===== */
const logDrawerVisible = ref(false)
const logJobId = ref<number | string>('')
const logJobName = ref('')
const logLoading = ref(false)
const logData = ref<CronLog[]>([])
const logTotal = ref(0)
const logQuery = reactive({
  page: 1,
  size: 10,
  jobId: '' as number | string,
  logStatus: undefined as number | undefined
})

function openLogDrawer(row: SysCron) {
  logJobId.value = row.id!
  logJobName.value = row.name || ''
  logQuery.jobId = row.id!
  logQuery.page = 1
  logQuery.logStatus = undefined
  logDrawerVisible.value = true
  loadLogs()
}

async function loadLogs() {
  logLoading.value = true
  try {
    const res = await getCronLogs(logQuery)
    logData.value = res.data?.list || []
    logTotal.value = res.data?.count || res.data?.total || 0
  } catch {
    logData.value = []
    logTotal.value = 0
  } finally {
    logLoading.value = false
  }
}

async function handleClearLogs(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认清空所有执行日志？此操作不可恢复。', '警告', {
      confirmButtonText: '确认清空',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await clearCronLogs(logJobId.value)
    ElMessage.success('日志已清空')
    loadLogs()
  } catch {
    // 用户取消
  }
}

/* ===== 工具函数 ===== */
function parseCronDesc(expression: string): string {
  if (!expression) return ''
  const parts = expression.trim().split(/\s+/)
  if (parts.length === 5) {
    const [min, hour, day, month, week] = parts
    if (day === '*' && month === '*' && week === '*') {
      if (min.startsWith('*/')) return `每隔 ${min.slice(2)} 分钟执行`
      if (hour.startsWith('*/')) return `每隔 ${hour.slice(2)} 小时执行`
      if (min !== '*' && hour !== '*') return `每天 ${hour.padStart(2, '0')}:${min.padStart(2, '0')} 执行`
    }
    if (week !== '*' && day === '*' && month === '*') {
      const weekDays = ['日', '一', '二', '三', '四', '五', '六']
      const weekIdx = parseInt(week, 10)
      if (!isNaN(weekIdx) && weekIdx >= 0 && weekIdx <= 6) {
        return `每周${weekDays[weekIdx]} ${hour.padStart(2, '0')}:${min.padStart(2, '0')} 执行`
      }
    }
    return expression
  }
  if (parts.length === 6) {
    const [sec, min, hour, day, month, week] = parts
    if (day === '*' && month === '*' && week === '*') {
      if (min.startsWith('*/')) return `每隔 ${min.slice(2)} 分钟执行`
      if (hour.startsWith('*/')) return `每隔 ${hour.slice(2)} 小时执行`
      if (sec === '0' && min !== '*' && hour !== '*') {
        return `每天 ${hour.padStart(2, '0')}:${min.padStart(2, '0')}:${sec.padStart(2, '0')} 执行`
      }
    }
    if (week !== '*' && day === '*' && month === '*') {
      const weekDays = ['日', '一', '二', '三', '四', '五', '六']
      const weekIdx = parseInt(week, 10)
      if (!isNaN(weekIdx) && weekIdx >= 0 && weekIdx <= 6) {
        return `每周${weekDays[weekIdx]} ${hour.padStart(2, '0')}:${min.padStart(2, '0')} 执行`
      }
    }
    return expression
  }
  return expression
}
</script>

<style scoped>
.cron-code {
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 12px;
  font-family: 'Courier New', Courier, monospace;
}

.cron-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.log-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}

.log-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.log-error {
  color: var(--el-color-danger);
  font-size: 12px;
}

.log-result {
  color: var(--el-text-color-regular);
  font-size: 12px;
}
</style>
