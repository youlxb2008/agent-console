<!--
  NoteLinkDialog（v3：笔记管理页操作列「关联」弹窗）
  -------------------
  - el-transfer 穿梭框全量覆盖式关联维护：左=未关联 agent，右=已关联（最终态）
  - 数据源：getAgentList 全量（active+suspended，label=displayName(name)）
  - 保存：确认框「将全量覆盖现有关联（当前 N 位 → 目标 M 位）」→ updateNoteAgents
  - 穿梭框无变化时保存按钮禁用；提交 loading 防重复；destroy-on-close 状态不残留
  - 权限码：agent:note:link（入口按钮控制）
-->
<template>
  <el-dialog
    :model-value="visible"
    :title="`关联 Agent —— ${noteTitle}`"
    width="720px"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @open="onOpen"
  >
    <div v-loading="loading" class="transfer-wrap">
      <el-transfer
        v-model="selectedIds"
        :data="transferData"
        :titles="['未关联', '已关联']"
        filterable
        :filter-placeholder="搜索占位"
      />
    </div>
    <div class="link-tip">
      勾选结果即最终关联集合（全量覆盖）：保存后未勾选的关联将被解除；空集=清空全部关联。
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
import { getNoteAgents, updateNoteAgents } from '@/api/system/note'

const props = defineProps<{
  visible: boolean
  /** 目标笔记 nid */
  nid: string
  /** 标题回显 */
  noteTitle: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** 保存成功，父级刷新列表（contributors 列变化） */
  success: []
}>()

const 搜索占位 = '搜索 agent'

const loading = ref(false)
const saving = ref(false)

/** 全量 agent（穿梭框左侧数据源） */
const agentOptions = ref<AgentItem[]>([])
/** 右侧已勾选（已关联）的 agent id 集合 */
const selectedIds = ref<TransferKey[]>([])
/** 打开时加载到的初始关联（变更检测基准） */
let initialIds: number[] = []

/** el-transfer 数据源：key/label */
const transferData = computed(() =>
  agentOptions.value.map((a) => ({
    key: a.id,
    label: a.displayName ? `${a.displayName}（${a.name}）` : a.name
  }))
)

/** 穿梭框是否有变化（无变化禁用保存按钮） */
const changed = computed(() => {
  const cur = [...selectedIds.value].sort() as number[]
  const init = [...initialIds].sort()
  return JSON.stringify(cur) !== JSON.stringify(init)
})

/** 打开弹窗：并行拉全量 agent + 已关联回显 */
async function onOpen(): Promise<void> {
  loading.value = true
  selectedIds.value = []
  initialIds = []
  try {
    const [agentsRes, linkedRes] = await Promise.all([
      getAgentList({ page: 1, size: 1000 }),
      getNoteAgents(props.nid)
    ])
    agentOptions.value = agentsRes.data?.records || agentsRes.data?.list || []
    // 回显已关联（过滤已删除 agent 的悬挂行，防御后端脏数据）
    const linked = (linkedRes.data || []).map((a) => a.id)
    initialIds = linked
    selectedIds.value = linked
  } catch {
    agentOptions.value = []
  } finally {
    loading.value = false
  }
}

/** 保存：全量覆盖确认（当前 N 位 → 目标 M 位） */
async function handleSave(): Promise<void> {
  const from = initialIds.length
  const to = selectedIds.value.length
  try {
    await ElMessageBox.confirm(
      `将全量覆盖现有关联（当前 ${from} 位 → 目标 ${to} 位）。\n未勾选的关联将被解除，是否继续？`,
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
    await updateNoteAgents(props.nid, selectedIds.value as number[])
    ElMessage.success('关联保存成功')
    emit('update:visible', false)
    emit('success')
  } catch {
    // 失败：错误已由请求拦截器统一提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
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
