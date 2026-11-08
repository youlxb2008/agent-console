<!--
  AgentNotesDialog（v3：agent 列表操作列「笔记」弹窗）
  -------------------
  - 该 agent 关联笔记分页（GET /note/admin/agents/{agentId}/notes，后端 JOIN 关联表）
  - keyword 搜索（300ms 防抖）；标题 / 所在目录 / 更新时间 / 操作
  - 「解除关联」（agent:note:link）：仅解除该 agent 与此笔记的关联，
    笔记本身与其他贡献者不受影响；解除后该页无数据且 page>1 时回退一页重查
  - destroy-on-close：关闭即销毁状态
-->
<template>
  <el-dialog
    :model-value="visible"
    :title="`${agentName} 的笔记`"
    width="760px"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @open="onOpen"
  >
    <!-- 搜索行 -->
    <div class="notes-toolbar">
      <el-input
        v-model="keyword"
        placeholder="标题关键词"
        clearable
        style="width: 240px"
        :prefix-icon="Search"
        @input="onKeywordInput"
      />
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      stripe
      border
      style="width: 100%"
    >
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="所在目录" min-width="160" align="center">
        <template #default="{ row }">
          <span class="dir-cell">{{ row.dirPath || '/' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="150" align="center">
        <template #default="{ row }">
          {{ formatTime(row.updateTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" align="center">
        <template #default="{ row }">
          <el-button
            v-permiss="'agent:note:link'"
            link
            type="warning"
            @click="handleUnlink(row as AdminNoteItem)"
          >
            解除关联
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 弹窗内简单分页 -->
    <div class="notes-pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="loadData"
      />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  getAgentNotes,
  unlinkNoteAgent,
  type AdminNoteItem
} from '@/api/system/note'

const props = defineProps<{
  visible: boolean
  /** 目标 agent id */
  agentId: number
  /** 标题回显（displayName 优先） */
  agentName: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const loading = ref(false)
const rows = ref<AdminNoteItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const keyword = ref('')

/** keyword 输入 300ms 防抖（回第 1 页重查） */
let debounceTimer: ReturnType<typeof setTimeout> | null = null
function onKeywordInput(): void {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    page.value = 1
    loadData()
  }, 300)
}

/** 打开/切换目标 agent 时重置查询 */
function onOpen(): void {
  keyword.value = ''
  page.value = 1
  loadData()
}

watch(
  () => props.agentId,
  () => {
    if (props.visible) onOpen()
  }
)

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const res = await getAgentNotes(props.agentId, {
      page: page.value,
      size,
      keyword: keyword.value || undefined
    })
    const data = res.data
    rows.value = (data as any)?.records || data?.list || []
    total.value = Number(data?.total ?? 0)
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 解除单条关联：笔记与索引不动，仅删该 agent 的关联行 */
async function handleUnlink(row: AdminNoteItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `仅解除该 agent 与「${row.title}」的关联。\n笔记本身与其他贡献者不受影响。`,
      '解除关联',
      {
        type: 'warning',
        confirmButtonText: '确认解除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return // 用户取消
  }
  try {
    await unlinkNoteAgent(row.nid, props.agentId)
    ElMessage.success('已解除关联')
    // 解除后该页无数据且非首页 → 回退一页重查
    if (rows.value.length === 1 && page.value > 1) {
      page.value--
    }
    loadData()
  } catch {
    // 失败：错误已由请求拦截器统一提示
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
.notes-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.dir-cell {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.notes-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
