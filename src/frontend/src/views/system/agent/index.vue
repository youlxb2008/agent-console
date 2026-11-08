<!--
  Agent 管理（v3.5 紧凑表）
  ---------------
  - 表格归一为 4 列：Agent（name+displayName+状态+描述）/ Key 数 / 创建时间 / 操作
  - Agent 复合列内含状态开关（吊销/启用即刻生效，切换前提示）
  - 新建/编辑对话框（name 唯一，编辑时不可改）
  - 操作列：编辑 / Key（弹窗维护，agent:key:list）/ 生成 Key
    （笔记本关联维护已收敛到「笔记本管理」页，此处不再提供入口）
  - M5.4-R1 删除语义修正：agent 不支持删除（接口已下线），只有 吊销/启用
    （status 0/1 切换，可随时恢复）
  - 权限码：agent:agent:list / save + agent:key:*（弹窗入口）
-->
<template>
  <div class="na-page">
    <PageHeader title="Agent 管理" subtitle="维护 agent 档案、启用状态与关联 API Key" />

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :api="{ list: getAgentList }"
        :action-width="300"
        :default-size="10"
      >
        <!-- 工具栏：新建按钮 -->
        <template #toolbar>
          <el-button v-permiss="'agent:agent:save'" type="primary" :icon="Plus" @click="openAdd">
            新建 Agent
          </el-button>
        </template>

        <!-- Agent 复合列：name + displayName + 状态开关 + 能力描述 -->
        <template #column-agent="{ row }">
          <div class="agent-cell">
            <div class="agent-head">
              <span class="agent-name">{{ row.name }}</span>
              <span v-if="row.displayName" class="agent-display">（{{ row.displayName }}）</span>
              <span class="agent-spacer" />
              <el-switch
                :model-value="row.status === 1"
                :loading="statusLoadingMap[row.id]"
                size="small"
                @change="(val: string | number | boolean) => toggleStatus(row as AgentItem, !!val)"
              />
              <span class="agent-status" :class="row.status === 1 ? 'is-on' : 'is-off'">{{
                row.status === 1 ? '启用' : '已吊销'
              }}</span>
            </div>
            <div v-if="row.description" class="agent-desc">
              {{ row.description }}
            </div>
          </div>
        </template>

        <!-- 操作列 -->
        <template #actions="{ row }">
          <el-button
            v-permiss="'agent:agent:save'"
            link
            type="primary"
            :icon="Edit"
            @click="openEdit(row as AgentItem)"
          >
            编辑
          </el-button>
          <!-- v3：Key 弹窗维护（锁定模式 KeyManagerPanel） -->
          <el-button
            v-permiss="'agent:key:list'"
            link
            type="primary"
            :icon="Key"
            @click="openKeysDialog(row as AgentItem)"
          >
            Key
          </el-button>
          <!-- ✨ M5.4：生成 Key 直达入口（打开弹窗并自动弹出生成表单） -->
          <el-button
            v-permiss="'agent:key:create'"
            link
            type="primary"
            :icon="Plus"
            @click="openCreateKeyDialog(row as AgentItem)"
          >
            生成 Key
          </el-button>
          <!-- M5.4-R1：删除按钮已移除（agent 不支持删除，吊销走状态开关） -->
        </template>
      </CrudTable>
    </div>

    <!-- ✨ Key 维护弹窗（destroy-on-close，明文不残留） -->
    <AgentKeysDialog
      v-model:visible="keysDialogVisible"
      :agent-id="dialogAgent?.id ?? 0"
      :agent-name="dialogAgentLabel"
      :auto-create="autoCreateKey"
      @changed="crudRef?.reload()"
    />

    <!-- 新建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑 Agent' : '新建 Agent'" width="560px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="Agent 名" prop="name">
          <el-input v-model="form.name" :disabled="isEdit" placeholder="唯一标识，如 alpha / bravo" />
          <div v-if="!isEdit" class="field-tip">name 唯一，创建后不可修改</div>
        </el-form-item>
        <el-form-item label="展示名" prop="displayName">
          <el-input v-model="form.displayName" placeholder="门户端显示名称（可选）" />
        </el-form-item>
        <!-- ✨ v4.3：头像上传（复用 /system/upload，存 /uploads/avatar/... 相对路径） -->
        <el-form-item label="头像" prop="avatar">
          <div class="avatar-field">
            <el-upload
              :show-file-list="false"
              :auto-upload="true"
              :http-request="handleAvatarUpload"
              :before-upload="beforeAvatarUpload"
              accept="image/*"
              class="avatar-upload"
            >
              <div class="avatar-edit">
                <el-avatar :size="64" :src="resolveMediaUrl(form.avatar)" shape="square" class="avatar-preview">
                  {{ (form.displayName || form.name || '?').charAt(0).toUpperCase() }}
                </el-avatar>
                <div class="avatar-mask">
                  <span class="avatar-mask-text">更换</span>
                </div>
              </div>
            </el-upload>
            <div class="avatar-meta">
              <el-link v-if="form.avatar" type="danger" :underline="false" @click="form.avatar = ''">
                移除头像
              </el-link>
              <span v-else class="field-tip">支持 JPG / PNG / WebP · ≤5MB</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="能力描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="该 agent 的职责/能力说明（可选）"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1"> 启用（active） </el-radio>
            <el-radio :value="0"> 吊销（revoked） </el-radio>
          </el-radio-group>
          <div class="field-tip">吊销后 agent 调用接口立即返回 403（缓存 30s 内过期），可随时重新启用</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false"> 取消 </el-button>
        <el-button type="primary" :loading="submitting" @click="submit"> 确定 </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { Plus, Edit, Key } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable from '@/components/CrudTable.vue'
import AgentKeysDialog from './AgentKeysDialog.vue'
import { uploadImage } from '@/api/system/upload'
import {
  getAgentList,
  createAgent,
  updateAgent,
  type AgentItem,
  type AgentCreatePayload
} from '@/api/system/agent'

const crudRef = ref<any>(null)

/** 列定义：把 name/displayName/status/description 合并为一列「Agent」 */
const columns = [
  { prop: 'agent', label: 'Agent', minWidth: 360, slot: true },
  { prop: 'keyCount', label: 'Key 数', width: 80, align: 'center' as const },
  { prop: 'createTime', label: '创建时间', minWidth: 160, align: 'center' as const }
]

/** 搜索项 */
const searchItems = [{ prop: 'keyword', label: '关键字' }]

/** 格式化时间（沿用既有前端惯例） */
function formatTime(timeStr?: string | null): string {
  if (!timeStr) return ''
  try {
    return new Date(timeStr).toLocaleString('zh-CN')
  } catch {
    return timeStr
  }
}

/* ===== 状态切换（吊销/启用即刻生效，M5.4-R1 吊销语义） ===== */
const statusLoadingMap = reactive<Record<string | number, boolean>>({})

async function toggleStatus(row: AgentItem, val: boolean): Promise<void> {
  const target = val ? 1 : 0
  const tip =
    target === 0
      ? '确认吊销该 agent？吊销后其所有 key 调用立即返回 403，可随时重新启用。'
      : '确认启用该 agent？'
  try {
    await ElMessageBox.confirm(tip, '状态变更', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  statusLoadingMap[row.id] = true
  try {
    await updateAgent(row.id, { status: target })
    ElMessage.success(target === 1 ? '已启用' : '已吊销')
    crudRef.value?.reload()
  } finally {
    statusLoadingMap[row.id] = false
  }
}

/* ===== v3：操作列弹窗（Key） ===== */
const keysDialogVisible = ref(false)
/** 当前弹窗目标 agent */
const dialogAgent = ref<AgentItem | null>(null)
/** ✨ M5.4：打开弹窗时是否自动弹出生成表单（「生成 Key」直达入口） */
const autoCreateKey = ref(false)

/** 弹窗标题回显名：displayName 优先回退 name */
const dialogAgentLabel = computed(() =>
  dialogAgent.value ? dialogAgent.value.displayName || dialogAgent.value.name : ''
)

function openKeysDialog(row: AgentItem): void {
  autoCreateKey.value = false
  dialogAgent.value = row
  keysDialogVisible.value = true
}

/** ✨ M5.4：生成 Key 直达入口——打开弹窗并自动弹出生成表单 */
function openCreateKeyDialog(row: AgentItem): void {
  autoCreateKey.value = true
  dialogAgent.value = row
  keysDialogVisible.value = true
}

/* ===== v4.3：头像上传（复用统一上传接口，type=avatar 分目录存储） ===== */

/** 头像上传体积上限：5MB（门户展示足够，避免无谓大图） */
const MAX_AVATAR_SIZE = 5 * 1024 * 1024

/**
 * 解析媒体 URL：/uploads/... 相对路径拼 API base URL（与 profile 页同规则）
 * @param url 相对路径或完整 URL
 * @returns 可用于 img src 的完整 URL，空值返回 undefined
 */
function resolveMediaUrl(url: string | undefined): string | undefined {
  if (!url) return undefined
  if (url.startsWith('http')) return url
  const baseURL = import.meta.env.VITE_API_BASE_URL || ''
  return `${baseURL}${url}`
}

/** 上传前校验：仅允许图片且不超过 5MB */
function beforeAvatarUpload(file: File): boolean {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('只能上传图片文件')
    return false
  }
  if (file.size > MAX_AVATAR_SIZE) {
    ElMessage.error('图片大小不能超过 5MB')
    return false
  }
  return true
}

/** 自定义上传：调 /system/upload（type=avatar），成功后回填表单 avatar */
async function handleAvatarUpload(options: UploadRequestOptions): Promise<void> {
  const file = options.file as File
  try {
    const res = await uploadImage(file, 'avatar')
    const url = res.data?.url
    if (url) {
      form.avatar = url
      ElMessage.success('头像上传成功')
    } else {
      ElMessage.error('上传成功但未返回图片地址')
    }
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '头像上传失败')
  }
}

/* ===== 新建/编辑对话框 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
/** 编辑中的 agent id（提交时使用） */
const editingId = ref<number>(0)

const defaultForm = (): AgentCreatePayload => ({
  name: '',
  displayName: '',
  avatar: '',
  description: '',
  status: 0
})

const form = reactive<AgentCreatePayload>(defaultForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入 Agent 名', trigger: 'blur' }]
}

function openAdd(): void {
  isEdit.value = false
  Object.assign(form, defaultForm())
  dialogVisible.value = true
}

function openEdit(row: AgentItem): void {
  isEdit.value = true
  editingId.value = row.id
  Object.assign(form, defaultForm(), {
    name: row.name,
    displayName: row.displayName || '',
    avatar: row.avatar || '',
    description: row.description || '',
    status: row.status
  })
  dialogVisible.value = true
}

function resetForm(): void {
  formRef.value?.resetFields()
  Object.assign(form, defaultForm())
}

async function submit(): Promise<void> {
  await formRef.value?.validate()
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateAgent(editingId.value, {
        displayName: form.displayName,
        // avatar 传空串表示清除头像（后端归一为 null），undefined 则不修改
        avatar: form.avatar || '',
        description: form.description,
        status: form.status
      })
      ElMessage.success('修改成功')
    } else {
      await createAgent({
        name: form.name,
        displayName: form.displayName,
        avatar: form.avatar || '',
        description: form.description,
        status: form.status
      })
      ElMessage.success('新建成功')
    }
    dialogVisible.value = false
    crudRef.value?.reload()
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.field-tip {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

/* ===== v4.3：头像上传 ===== */
.avatar-field {
  display: flex;
  align-items: center;
  gap: 14px;
}

.avatar-upload {
  line-height: 1;
}

.avatar-edit {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
}

.avatar-preview {
  width: 100%;
  height: 100%;
  font-size: 24px;
  font-weight: 600;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
}

/* hover 蒙层：提示可点击更换 */
.avatar-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.avatar-edit:hover .avatar-mask {
  opacity: 1;
}

.avatar-mask-text {
  font-size: 12px;
  color: #fff;
}

.avatar-meta {
  display: flex;
  align-items: center;
}

/* ===== Agent 复合列 ===== */
.agent-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 4px 0;
}

.agent-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.agent-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
  letter-spacing: 0.2px;
}

.agent-display {
  color: var(--el-text-color-secondary);
  font-weight: 500;
}

.agent-spacer {
  flex: 1;
  min-width: 0;
}

.agent-status {
  font-size: 12px;
  line-height: 1;
  padding: 0 4px;
}

.agent-status.is-on {
  color: var(--el-color-success);
}

.agent-status.is-off {
  color: var(--el-text-color-placeholder);
}

.agent-desc {
  font-size: 12px;
  line-height: 1.5;
  max-height: 36px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  word-break: break-word;
  display: -webkit-box !important;
  -webkit-line-clamp: 2 !important;
  -webkit-box-orient: vertical !important;
}
</style>
