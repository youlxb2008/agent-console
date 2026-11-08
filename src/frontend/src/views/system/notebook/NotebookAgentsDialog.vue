<!--
  NotebookAgentsDialog（v3.5：笔记本管理页操作列「关联 agent」弹窗）
  ------------------------------------------------------------
  - 上半 el-table：当前已关联 agent 明细（Agent / 关联时间 / 移除=重新 PUT 去掉该项）
  - 下半 el-transfer：左=全量 agent（getAgentList 1000），右=已关联集合（全量覆盖）
  - 保存：无变化禁用；确认框「当前 N 位 → 目标 M 位」→ updateNotebookAgents
  - 数据源：GET /system/notebook/{id}/agents + PUT /system/notebook/{id}/agents
  - 权限码：notebook:agent:list（查看）/ notebook:agent:link（保存/移除，入口按钮控制）
  - destroy-on-close 状态不残留
-->
<template>
  <el-dialog
    :model-value="visible"
    :title="`${notebookName} · 关联 Agent`"
    width="820px"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @open="onOpen"
  >
    <div v-loading="loading">
      <!-- 上半：当前已关联 agent -->
      <div class="section-title">当前关联（{{ linkedTable.length }}）</div>
      <el-table
        :data="linkedTable"
        stripe
        border
        size="small"
        style="width: 100%"
      >
        <el-table-column label="Agent" min-width="180">
          <template #default="{ row }">
            <span class="agent-name">{{ row.displayName || row.name }}</span>
            <span class="agent-sub">（{{ row.name }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="关联时间" min-width="160" align="center">
          <template #default="{ row }">
            {{ formatTime(row.linkTime) || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              @click="handleRemove(row as NotebookAgentItem)"
            >
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 下半：穿梭框全量覆盖 -->
      <div class="section-title" style="margin-top: 16px">
        调整关联
      </div>
      <div class="transfer-wrap">
        <el-transfer
          v-model="selectedAgentIds"
          :data="transferData"
          :titles="['未关联', '已关联']"
          filterable
          :filter-placeholder="'搜索 agent'"
        />
      </div>
      <div class="link-tip">
        勾选结果即最终关联集合（全量覆盖）：保存后未勾选的 agent 将被解除；空集=清空全部关联。
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('update:visible', false)">
        取消
      </el-button>
      <el-button
        type="primary"
        :disabled="!changed"
        :loading="saving"
        @click="handleSave"
      >
        保存关联
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TransferKey } from 'element-plus'
import { getAgentList, type AgentItem } from '@/api/system/agent'
import {
  getNotebookAgents,
  updateNotebookAgents,
  type NotebookAgentItem
} from '@/api/system/notebook'

const props = defineProps<{
  visible: boolean
  /** 目标笔记本 id */
  notebookId: number
  /** 标题回显 */
  notebookName: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** 覆盖保存/移除成功后，父级刷新列表（agentCount 列联动） */
  changed: []
}>()

const loading = ref(false)
const saving = ref(false)

/** 上半表格：当前已关联 agent 明细（含关联时间） */
const linkedTable = ref<NotebookAgentItem[]>([])
/** 全量 agent（穿梭框左侧数据源） */
const agentOptions = ref<AgentItem[]>([])
/** 右侧已勾选（已关联）的 agent id 集合 */
const selectedAgentIds = ref<TransferKey[]>([])
/** 打开时加载到的初始关联（变更检测基准） */
let initialAgentIds: number[] = []

/** el-transfer 数据源：key/label */
const transferData = computed(() =>
  agentOptions.value.map((a) => ({
    key: a.id,
    label: a.displayName ? `${a.displayName}（${a.name}）` : a.name
  }))
)

/** 穿梭框是否有变化（无变化禁用保存按钮） */
const changed = computed(() => {
  const cur = [...selectedAgentIds.value].sort() as number[]
  const init = [...initialAgentIds].sort()
  return JSON.stringify(cur) !== JSON.stringify(init)
})

/** 打开弹窗：并行拉当前关联明细 + 全量 agent */
async function onOpen(): Promise<void> {
  loading.value = true
  selectedAgentIds.value = []
  initialAgentIds = []
  linkedTable.value = []
  try {
    const [agentsRes, linkedRes] = await Promise.all([
      getAgentList({ page: 1, size: 1000 }),
      getNotebookAgents(props.notebookId)
    ])
    agentOptions.value = agentsRes.data?.records || agentsRes.data?.list || []
    linkedTable.value = linkedRes.data || []
    const linked = linkedTable.value.map((a) => a.agentId)
    initialAgentIds = linked
    selectedAgentIds.value = linked
  } catch {
    agentOptions.value = []
    linkedTable.value = []
  } finally {
    loading.value = false
  }
}

/** 重拉明细（保存/移除成功后） */
async function reloadDetail(): Promise<void> {
  try {
    const res = await getNotebookAgents(props.notebookId)
    linkedTable.value = res.data || []
  } catch {
    linkedTable.value = []
  }
}

/** 移除单条：重新 PUT 当前集合去掉该项（全量覆盖语义，未勾选即被解除） */
async function handleRemove(row: NotebookAgentItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '解除后该 agent 即刻失去此笔记本的写作权限（笔记与贡献者关系不受影响）。\n确定移除？',
      '移除关联 Agent',
      {
        type: 'warning',
        confirmButtonText: '确认移除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }
  try {
    await updateNotebookAgents(
      props.notebookId,
      initialAgentIds.filter((id) => id !== row.agentId)
    )
    ElMessage.success('已移除')
    initialAgentIds = initialAgentIds.filter((id) => id !== row.agentId)
    selectedAgentIds.value = [...initialAgentIds]
    await reloadDetail()
    emit('changed')
  } catch {
    // 失败：错误已由请求拦截器统一提示
  }
}

/** 保存：全量覆盖确认（当前 N 位 → 目标 M 位） */
async function handleSave(): Promise<void> {
  const from = initialAgentIds.length
  const to = selectedAgentIds.value.length
  try {
    await ElMessageBox.confirm(
      `将全量覆盖现有关联（当前 ${from} 位 → 目标 ${to} 位）。\n被移除的 agent 即刻失去该笔记本写作权限，是否继续？`,
      '保存关联',
      {
        type: 'warning',
        confirmButtonText: '确认保存',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return // 用户取消
  }
  saving.value = true
  try {
    await updateNotebookAgents(props.notebookId, selectedAgentIds.value as number[])
    ElMessage.success('关联保存成功')
    initialAgentIds = [...(selectedAgentIds.value as number[])]
    await reloadDetail()
    emit('changed')
  } catch {
    // 失败：错误已由请求拦截器统一提示
  } finally {
    saving.value = false
  }
}

/** 格式化时间（沿用前端惯例） */
function formatTime(timeStr?: string | null): string {
  if (!timeStr) return ''
  try {
    return new Date(timeStr).toLocaleString('zh-CN')
  } catch {
    return timeStr
  }
}
</script>

<style scoped>
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 8px;
}

.agent-name {
  font-weight: 600;
}

.agent-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.transfer-wrap {
  display: flex;
  justify-content: center;
  min-height: 260px;
}

.link-tip {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>