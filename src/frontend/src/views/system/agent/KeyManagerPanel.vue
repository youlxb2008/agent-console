<!--
  KeyManagerPanel（v3 公共组件）
  -------------------
  - key 的列表/生成/明文查看/吊销，两种模式复用同一实现：
    · 锁定模式：传 agentId —— 只管该 agent，隐藏 agent 选择器（AgentKeysDialog 用）
    · 全局模式：不传 agentId —— 显示 agent 下拉筛选（独立页 /agent/keys 用）
  - 生成成功 → 明文弹窗（即时展示 + 复制；v4.1 起明文落库，列表页历史 key 可随时点击复制）
  - 删除：二次确认后物理删除（M5.4-R1 删除语义修正：吊销概念下线，key 失效一律硬删除）
   - 生成/删除/编辑成功后 emit('changed')，父级刷新 agent 列表的 keyCount/lastUsedAt
   - 权限码：agent:key:list（入口由父级控制）/ create（生成+编辑）/ revoke（删除复用）
   - ✨ M5.4：密钥脱敏（眼睛切换）+ 权限范围只留中文标签 + 砍创建时间列 + 编辑弹窗
     + autoCreate 直达生成；M5.4-R1：去「显示已吊销」开关与状态列（硬删除后无吊销态）
 -->
<template>
  <div class="key-panel">
    <!-- 筛选与操作栏（agent 选择器仅全局模式显示） -->
    <div class="key-toolbar">
      <div class="key-toolbar__filters">
        <el-form v-if="!isLocked" inline>
          <el-form-item label="Agent">
            <el-select v-model="filterAgentId" clearable placeholder="全部 Agent" style="width: 220px" @change="loadKeys">
              <el-option v-for="a in agentOptions" :key="a.id" :label="agentOptionLabel(a)" :value="a.id" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
      <div class="key-toolbar__actions">
        <!-- v4.2：下载 Skill 文档（模块介绍 + API 索引概览，下载后提供给 AI 助手快速接入） -->
        <el-button
          v-permiss="'agent:key:list'"
          :icon="Download"
          :loading="skillDownloading"
          @click="handleDownloadSkillDoc"
        >
          下载 Skill 文档
        </el-button>
        <el-button v-permiss="'agent:key:create'" type="primary" :icon="Plus" @click="openCreate"> 生成 Key </el-button>
      </div>
    </div>

    <!-- Key 列表（永不显示 hash；密钥列脱敏 + 眼睛切换；M5.4-R1 去状态列） -->
    <el-table
      v-loading="loading"
      :data="keyList"
      stripe
      border
      style="width: 100%"
    >
      <!-- 密钥列：默认脱敏（prefix+****+末4位），眼睛切换明文，点击复制完整明文；旧 key 回退展示前缀 -->
      <el-table-column label="密钥" min-width="230" align="center">
        <template #default="{ row }">
          <!-- 有明文：默认脱敏展示，眼睛切换明文，点击复制 -->
          <div v-if="row.keyPlain" class="key-cell" title="点击复制完整密钥" @click="handleCopyKey(row as ApiKeyItem)">
            <code class="key-text">{{ isRevealed(row.id) ? row.keyPlain : maskKey(row as ApiKeyItem) }}</code>
            <el-icon
              class="key-eye-icon"
              :title="isRevealed(row.id) ? '隐藏明文' : '显示明文'"
              @click.stop="toggleReveal(row.id)"
            >
              <Hide v-if="isRevealed(row.id)" />
              <View v-else />
            </el-icon>
            <el-icon class="key-copy-icon"><CopyDocument /></el-icon>
          </div>
          <!-- 兜底：v4.1 之前的旧 key 只存了 hash，明文无法恢复 -->
          <div v-else class="key-cell key-cell--legacy">
            <code class="key-text">{{ row.keyPrefix }}</code>
            <el-tooltip content="旧 key 未存明文，无法复制（删除后重新生成即可）" placement="top">
              <span class="key-legacy-tag">未存明文</span>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>
      <!-- ✨ M5.4：权限范围只留中文标签（英文原文移除，列表瘦身） -->
      <el-table-column label="权限范围" min-width="150" align="center">
        <template #default="{ row }">
          <el-tag v-for="s in row.scopes || []" :key="s" size="small" style="margin-right: 4px">
            {{ formatScopeLabel(s) }}
          </el-tag>
          <span v-if="!row.scopes?.length" style="color: var(--el-text-color-secondary)">无</span>
        </template>
      </el-table-column>
      <el-table-column label="过期时间" min-width="150" align="center">
        <template #default="{ row }">
          <span v-if="row.expiresAt">{{ formatTime(row.expiresAt) }}</span>
          <span v-else style="color: var(--el-text-color-secondary)">永久</span>
        </template>
      </el-table-column>
      <el-table-column label="最近使用" min-width="150" align="center">
        <template #default="{ row }">
          <span v-if="row.lastUsedAt">{{ formatTime(row.lastUsedAt) }}</span>
          <span v-else style="color: var(--el-text-color-secondary)">从未使用</span>
        </template>
      </el-table-column>
      <!-- ✨ M5.4：砍「创建时间」列（信息价值低，列表瘦身） -->
      <!-- M5.4-R1：操作列——「编辑」+「删除」（物理删除不可恢复，二次确认） -->
      <el-table-column label="操作" width="130" fixed="right" align="center">
        <template #default="{ row }">
          <el-button v-permiss="'agent:key:create'" link type="primary" @click="openEdit(row as ApiKeyItem)">
            编辑
          </el-button>
          <el-button v-permiss="'agent:key:revoke'" link type="danger" @click="handleDelete(row as ApiKeyItem)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 生成/编辑 Key 对话框（✨ M5.4 两用：编辑模式预填当前值，提交走 PUT /keys/{id}） -->
    <el-dialog
      v-model="createVisible"
      :title="dialogTitle"
      width="760px"
      append-to-body
      class="key-create-dialog"
      @closed="resetCreateForm"
    >
      <el-form ref="createFormRef" :model="genForm" :rules="createRules" label-width="84px" label-position="top">
        <el-form-item v-if="!isLocked && !isEditMode" label="Agent" prop="agentId">
          <el-select v-model="genForm.agentId" placeholder="选择要签发 key 的 agent" style="width: 100%">
            <el-option v-for="a in agentOptions" :key="a.id" :label="agentOptionLabel(a)" :value="a.id" />
          </el-select>
        </el-form-item>

        <!-- 权限范围：按维度分组卡片，多选 -->
        <el-form-item label="权限范围" prop="scopes" class="scope-form-item">
          <div class="scope-grid">
            <div v-for="grp in scopeGroups" :key="grp.dim" class="scope-group">
              <div class="scope-group__head">
                <div class="scope-group__title-row">
                  <el-icon class="scope-group__icon"><component :is="grp.icon" /></el-icon>
                  <span class="scope-group__title">{{ grp.title }}</span>
                </div>
                <span class="scope-group__hint">{{ grp.hint }}</span>
              </div>
              <div class="scope-group__items">
                <button
                  v-for="opt in grp.options"
                  :key="opt.value"
                  type="button"
                  class="scope-chip"
                  :class="{ 'is-checked': genForm.scopes.includes(opt.value) }"
                  @click="toggleScope(opt.value)"
                >
                  <span class="scope-chip__cn">{{ opt.cn }}</span>
                  <span class="scope-chip__en">{{ opt.value }}</span>
                </button>
              </div>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="过期时间" prop="expiresAt">
          <el-date-picker
            v-model="genForm.expiresAt"
            type="datetime"
            :placeholder="isEditMode ? '留空 = 不修改' : '留空 = 永久有效'"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :disabled-date="(d: Date) => d.getTime() < Date.now() - 86400000"
            style="width: 100%"
          />
          <div v-if="isEditMode" class="field-tip">不支持清空过期时间改为永久；如需永久有效请删除后重新生成</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="footer-hint">
          已选 <b>{{ genForm.scopes.length }}</b> / {{ totalScopes }} 项权限
        </span>
        <el-button @click="createVisible = false"> 取消 </el-button>
        <el-button type="primary" :loading="creating" @click="submitKeyForm">
          {{ isEditMode ? '保存' : '生成' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 明文弹窗（生成成功即时展示；明文已落库，之后列表页也可随时复制） -->
    <el-dialog
      v-model="plainKeyVisible"
      title="生成成功"
      width="560px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      append-to-body
      @closed="plainKey = ''"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="请复制并妥善保管该密钥"
        description="密钥已保存，之后也可在本页面随时查看与复制。请勿泄露给无关人员。"
        style="margin-bottom: 16px"
      />
      <div class="plain-key-box">
        <code class="plain-key-text">{{ plainKey }}</code>
      </div>
      <template #footer>
        <el-button type="primary" :icon="CopyDocument" @click="copyKey"> 复制 Key </el-button>
        <el-button @click="plainKeyVisible = false"> 完成，关闭 </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Plus,
  CopyDocument,
  EditPen,
  Folder,
  Document,
  View,
  Hide,
  Trophy,
  Notebook,
  Download,
  AlarmClock,
  Monitor,
  MagicStick
} from '@element-plus/icons-vue'
import { copyToClipboard } from '@/utils/clipboard'
import {
  getAgentList,
  getKeyList,
  createKey,
  updateKey,
  deleteAgentKey,
  fetchSkillDoc,
  type AgentItem,
  type ApiKeyItem,
  type KeyCreateResult
} from '@/api/system/agent'

/**
 * 锁定模式：传入 agentId 则面板只管该 agent（隐藏选择器）；
 * 全局模式：不传显示 agent 下拉筛选。
 */
const props = defineProps<{
  agentId?: number
  /** 锁定模式下标题/生成弹窗回显用 */
  agentName?: string
  /** ✨ M5.4：打开面板时自动弹出生成表单（Agent 列表「生成 Key」直达入口） */
  autoCreate?: boolean
}>()

/** 生成/删除/编辑成功后触发（父级刷新 agent 列表的 keyCount/lastUsedAt 列） */
const emit = defineEmits<{ changed: [] }>()

/** 是否锁定模式（传入 agentId 即锁定） */
const isLocked = computed(() => props.agentId !== undefined)

const loading = ref(false)
const keyList = ref<ApiKeyItem[]>([])

/** agent 下拉数据（仅全局模式加载；分页接口取全量） */
const agentOptions = ref<AgentItem[]>([])
async function loadAgentOptions(): Promise<void> {
  if (isLocked.value) return
  try {
    const res = await getAgentList({ page: 1, size: 1000 })
    agentOptions.value = res.data?.records || res.data?.list || []
  } catch {
    agentOptions.value = []
  }
}

/** 当前筛选的 agent id（锁定模式恒为 props.agentId；undefined=全部） */
const filterAgentId = ref<number | undefined>(props.agentId)

// 锁定模式下 props.agentId 变化（父级切换弹窗目标 agent）时同步刷新
watch(
  () => props.agentId,
  (id) => {
    filterAgentId.value = id
    loadKeys()
  }
)

async function loadKeys(): Promise<void> {
  loading.value = true
  try {
    const res = await getKeyList(filterAgentId.value || undefined)
    keyList.value = res.data || []
  } catch {
    keyList.value = []
  } finally {
    loading.value = false
  }
}

/* ==================== 密钥脱敏（✨ M5.4：prefix+****+末4位，眼睛切换） ==================== */

/** 明文可见的 key id 集合（默认全部脱敏） */
const revealedKeys = ref<Set<number>>(new Set())

function isRevealed(id: number): boolean {
  return revealedKeys.value.has(id)
}

function toggleReveal(id: number): void {
  const next = new Set(revealedKeys.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  revealedKeys.value = next
}

/** 脱敏展示：keyPrefix + **** + 明文末4位（如 ac_alpha_gZqx****LoE5） */
function maskKey(row: ApiKeyItem): string {
  if (!row.keyPlain) return row.keyPrefix
  return `${row.keyPrefix}****${row.keyPlain.slice(-4)}`
}

/* ==================== Skill 文档下载（v4.2） ==================== */

/** 下载中状态（按钮 loading，防重复点击） */
const skillDownloading = ref(false)

/**
 * 下载 Skill 文档：拉取 markdown 概览（模块介绍 + API 索引），
 * 包装为 Blob 触发浏览器保存，命名为 SKILL.md（与 口径一致，
 * AI 助手约定识别该文件名；文档内指引 agent 运行时动态拉取完整 api-docs）
 */
async function handleDownloadSkillDoc(): Promise<void> {
  if (skillDownloading.value) return
  skillDownloading.value = true
  try {
    const md = await fetchSkillDoc()
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'SKILL.md'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('SKILL.md 已下载，上传/粘贴给 AI 助手即可接入')
  } catch (e) {
    ElMessage.error('下载失败：' + (e instanceof Error ? e.message : String(e)))
  } finally {
    skillDownloading.value = false
  }
}

/** agent 下拉 label */
function agentOptionLabel(a: AgentItem): string {
  return a.displayName ? `${a.name}（${a.displayName}）` : a.name
}

/** 格式化时间 */
function formatTime(timeStr?: string | null): string {
  if (!timeStr) return ''
  try {
    return new Date(timeStr).toLocaleString('zh-CN')
  } catch {
    return timeStr
  }
}

/** scope → 中文标签（不含英文，英文由模板单独渲染） */
function formatScopeLabel(scope: string): string {
  const map: Record<string, string> = {
    'notes:write': '写笔记',
    'notes:read': '读笔记',
    'notebook:link': '授权笔记本',
    'notebook:manage': '笔记本管理',
    'audit:read': '读审计',
    'objective:write': '写目标',
    'todo:write': '写待办',
    'ledger:write': '写台账',
    'memory:read': '读记忆',
    'memory:write': '写记忆'
  }
  return map[scope] || scope
}

/* ===== 删除（M5.4-R1：物理删除不可恢复，二次确认） ===== */
async function handleDelete(row: ApiKeyItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除后不可恢复，该 key 将立即失效。确认删除？\n密钥：${row.keyPrefix}`,
      '删除 API Key',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    await deleteAgentKey(row.id)
    ElMessage.success('删除成功')
    loadKeys()
    emit('changed')
  } catch {
    // 用户取消或操作失败（错误已由拦截器提示）
  }
}

/* ===== 生成 Key ===== */
const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()

const scopeOptions = [
  { label: '写笔记（notes:write）', value: 'notes:write' },
  { label: '读审计（audit:read）', value: 'audit:read' },
  { label: '授权笔记本（notebook:link）', value: 'notebook:link' },
  { label: '笔记本管理（notebook:manage）', value: 'notebook:manage' },
  { label: '写目标（objective:write）', value: 'objective:write' },
  { label: '活动上报（activity:write）', value: 'activity:write' }
]

/* 权限范围分组（按维度聚合卡片渲染，匹配后端 @RequireScope scopeKey） */
interface ScopeChipOpt {
  value: string
  cn: string
}
interface ScopeGroup {
  dim: string
  title: string
  hint: string
  icon: typeof EditPen
  options: ScopeChipOpt[]
}
const scopeGroups: ScopeGroup[] = [
  {
    dim: 'note',
    title: '笔记维度',
    hint: 'CRUD 全包，勾选即可增删改',
    icon: EditPen,
    options: [{ value: 'notes:write', cn: '写笔记' }]
  },
  {
    dim: 'notebook',
    title: '笔记本维度',
    hint: '授权 = 关联/解绑；管理 = 创建/编辑/软删',
    icon: Folder,
    options: [
      { value: 'notebook:link', cn: '授权笔记本' },
      { value: 'notebook:manage', cn: '笔记本管理' }
    ]
  },
  {
    dim: 'audit',
    title: '审计维度',
    hint: '读自己的审计记录',
    icon: Document,
    options: [{ value: 'audit:read', cn: '读审计' }]
  },
  {
    dim: 'objective',
    title: '目标维度',
    hint: '目标模块：CRUD 全包，勾选即可增删改',
    icon: Trophy,
    options: [{ value: 'objective:write', cn: '写目标' }]
  },
  {
    dim: 'ledger',
    title: '台账维度',
    hint: '台账模块：自己增删改查私域流水',
    icon: Notebook,
    options: [{ value: 'ledger:write', cn: '写台账' }]
  },
  {
    dim: 'todo',
    title: '待办维度',
    hint: '待办模块：自己增删改查近期任务清单',
    icon: AlarmClock,
    options: [{ value: 'todo:write', cn: '写待办' }]
  },
  {
    dim: 'activity',
    title: '运行时维度',
    hint: '活动流：批量上报实时执行事件（M6.1）',
    icon: Monitor,
    options: [{ value: 'activity:write', cn: '活动上报' }]
  },
  {
    dim: 'memory',
    title: '记忆维度',
    hint: '长期记忆：读 = 列表/详情/检索；写 = 增删改/确认/取代/恢复（M7.1）',
    icon: MagicStick,
    options: [
      { value: 'memory:read', cn: '读记忆' },
      { value: 'memory:write', cn: '写记忆' }
    ]
  }
]

const totalScopes = computed(() => scopeGroups.reduce((acc, g) => acc + g.options.length, 0))

/** 切换单个 scope 选中状态 */
function toggleScope(value: string): void {
  const i = genForm.scopes.indexOf(value)
  if (i >= 0) {
    genForm.scopes.splice(i, 1)
  } else {
    genForm.scopes.push(value)
  }
}

const genForm = reactive<{ agentId?: number; scopes: string[]; expiresAt?: string }>({
  agentId: undefined,
  scopes: [],
  expiresAt: undefined
})

const createRules: FormRules = {
  agentId: [{ required: true, message: '请选择 Agent', trigger: 'change' }],
  scopes: [
    {
      validator: (_rule, value: string[], callback) => {
        if (!value || !value.length) {
          callback(new Error('至少勾选一个权限范围'))
        } else {
          callback()
        }
      },
      trigger: 'change'
    }
  ]
}

function openCreate(): void {
  editingKey.value = null
  Object.assign(genForm, {
    agentId: isLocked.value ? props.agentId : filterAgentId.value || undefined,
    scopes: [],
    expiresAt: undefined
  })
  createVisible.value = true
}

/* ===== 编辑 Key（✨ M5.4：复用生成弹窗，预填当前值，提交走 PUT /keys/{id}） ===== */
const editingKey = ref<ApiKeyItem | null>(null)
const isEditMode = computed(() => editingKey.value !== null)

const dialogTitle = computed(() => {
  if (isEditMode.value) return '编辑 API Key'
  return isLocked.value ? `为 ${props.agentName ?? ''} 生成 API Key` : '生成 API Key'
})

function openEdit(row: ApiKeyItem): void {
  editingKey.value = row
  Object.assign(genForm, {
    agentId: row.agentId,
    scopes: [...(row.scopes || [])],
    expiresAt: row.expiresAt || undefined
  })
  createVisible.value = true
}

function resetCreateForm(): void {
  createFormRef.value?.resetFields()
  editingKey.value = null
}

async function submitKeyForm(): Promise<void> {
  await createFormRef.value?.validate()
  creating.value = true
  try {
    if (isEditMode.value && editingKey.value) {
      // 编辑：null=不修改（expiresAt 留空即不修改；不支持清空改永久）
      await updateKey(editingKey.value.id, {
        scopes: genForm.scopes,
        expiresAt: genForm.expiresAt || null
      })
      ElMessage.success('修改成功')
      createVisible.value = false
    } else {
      const res = await createKey({
        agentId: genForm.agentId as number,
        scopes: genForm.scopes,
        expiresAt: genForm.expiresAt || null
      })
      createVisible.value = false
      showPlainKey(res.data)
    }
    loadKeys()
    emit('changed')
  } finally {
    creating.value = false
  }
}

/* ===== 明文弹窗（生成成功即时展示） ===== */
const plainKeyVisible = ref(false)
const plainKey = ref('')

function showPlainKey(result: KeyCreateResult): void {
  plainKey.value = result.key || ''
  plainKeyVisible.value = true
}

/**
 * 复制生成弹窗中的明文 key
 * 统一走 copyToClipboard（HTTP 非安全上下文自动降级 execCommand）
 */
async function copyKey(): Promise<void> {
  if (!plainKey.value) return
  const ok = await copyToClipboard(plainKey.value)
  if (ok) {
    ElMessage.success('已复制到剪贴板')
  } else {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
}

/**
 * 复制列表页历史 key 明文（密钥列点击触发）
 * 统一走 copyToClipboard；旧 key（未存明文）给出明确提示
 */
async function handleCopyKey(row: ApiKeyItem): Promise<void> {
  if (!row.keyPlain) {
    ElMessage.warning('旧 key 未存储明文，无法复制（删除后重新生成即可）')
    return
  }
  const ok = await copyToClipboard(row.keyPlain)
  if (ok) {
    ElMessage.success('已复制到剪贴板')
  } else {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
}

// 初始化：全局模式加载下拉；两种模式均加载列表
loadAgentOptions()
loadKeys()

// ✨ M5.4：autoCreate 直达生成（Agent 列表「生成 Key」入口）。
// AgentKeysDialog 为 destroy-on-close，每次打开都会重建本面板，
// immediate 触发即可在挂载时自动弹出生成表单；关闭生成弹窗后不会重复弹出。
watch(
  () => props.autoCreate,
  (v) => {
    if (v) openCreate()
  },
  { immediate: true }
)
</script>

<style scoped>
.key-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

/* ====== 工具栏（✨ M5.4：左筛选区 + 右操作区） ====== */
.key-toolbar__filters {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.key-toolbar__actions {
  display: flex;
  align-items: center;
}

/* ====== 密钥列眼睛切换（✨ M5.4） ====== */
.key-eye-icon {
  flex-shrink: 0;
  font-size: 14px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition: color 0.15s;
}
.key-eye-icon:hover {
  color: var(--el-color-primary);
}

/* ====== 编辑模式过期时间提示（✨ M5.4） ====== */
.field-tip {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.plain-key-box {
  padding: 16px;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  word-break: break-all;
}

.plain-key-text {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 14px;
  line-height: 1.6;
  user-select: all;
}

/* ====== 密钥列（点击复制） ====== */
.key-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 3px 8px;
  border-radius: 4px;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s;
}
/* hover 高亮提示「可点击」 */
.key-cell:hover {
  background: var(--el-fill-color-light);
}
.key-text {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-all; /* 完整 key 较长，允许换行展示 */
  user-select: all; /* 便于不点复制按钮时也能手动选中 */
}
.key-copy-icon {
  flex-shrink: 0;
  font-size: 14px;
  color: var(--el-color-primary);
}
/* 旧 key（未存明文）兜底样式：不可点击，弱化展示 */
.key-cell--legacy {
  cursor: default;
}
.key-legacy-tag {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 1;
  padding: 3px 6px;
  border-radius: 3px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
  white-space: nowrap;
}

/* ====== 权限范围分组卡片（生成 Key 弹窗） ====== */
.key-create-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
}

.scope-form-item :deep(.el-form-item__label) {
  font-weight: 600;
  color: var(--el-text-color-primary);
  padding-bottom: 6px;
}

.scope-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  width: 100%;
}

.scope-group {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 14px;
  background: var(--el-fill-color-extra-light);
  transition:
    border-color 0.18s,
    background 0.18s;
}
.scope-group:hover {
  border-color: var(--el-border-color-light);
}

.scope-group__head {
  margin-bottom: 10px;
  color: var(--el-text-color-regular);
}
.scope-group__title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.scope-group__icon {
  color: var(--el-color-primary);
  font-size: 15px;
}
.scope-group__title {
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.scope-group__hint {
  display: block;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 4px;
}

.scope-group__items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.scope-chip {
  appearance: none;
  border: 1px solid var(--el-border-color);
  background: #fff;
  border-radius: 6px;
  padding: 6px 10px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  text-align: left;
  white-space: nowrap;
  transition: all 0.15s ease;
}
.scope-chip:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}
.scope-chip.is-checked {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}
.scope-chip__cn {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  flex-shrink: 0;
}
.scope-chip__en {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.scope-chip.is-checked .scope-chip__en {
  color: var(--el-color-primary);
}

.footer-hint {
  margin-right: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.footer-hint b {
  color: var(--el-color-primary);
  margin: 0 2px;
}
</style>
