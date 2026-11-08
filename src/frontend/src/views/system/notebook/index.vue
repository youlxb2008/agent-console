<!--
  笔记本管理（v3.5 独立笔记本实体，菜单顶级 /notebook/list）
  --------------------------------------------------------
  - 分页表格：封面（cover 图/首字符色块）/ 名称 / 简介 / 笔记数 / Agent 数 / 状态 / 创建时间
  - keyword（name 模糊）搜索
  - 新建/编辑对话框（name 必填唯一，后端校验；cover 仅存 URL）
  - 状态 switch 行内切换（1=启用 0=隐藏，切换即 updateNotebook 带当前 name）
  - 操作列：编辑 / 关联 agent（NotebookAgentsDialog）/ 删除（软删，二次确认三要素）
  - 权限码：notebook:list / create / edit / delete / agent:list / agent:link
-->
<template>
  <div class="na-page">
    <PageHeader
      title="笔记本列表"
      subtitle="管理独立笔记本与 agent 的写作授权"
    />

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :api="{ list: getNotebookList }"
        :default-size="10"
      >
        <!-- 工具栏：新建 -->
        <template #toolbar>
          <el-button
            v-permiss="'notebook:create'"
            type="primary"
            :icon="Plus"
            @click="openAdd"
          >
            新建笔记本
          </el-button>
        </template>

        <!-- 封面：cover 图，无则 name 首字符 + 浅边框色块（克制风） -->
        <template #column-cover="{ row }">
          <el-avatar
            v-if="(row as NotebookItem).cover"
            :src="(row as NotebookItem).cover"
            shape="square"
            :size="40"
            class="cover-img"
          />
          <span
            v-else
            class="cover-fallback"
            :style="coverStyle((row as NotebookItem).name)"
          >
            {{ (row as NotebookItem).name?.charAt(0) || '诺' }}
          </span>
        </template>

        <!-- 名称：浅色文字 + 下划线虚化；尾部挂「门户打开」小按钮（仅启用态） -->
        <template #column-name="{ row }">
          <span class="name-wrap">
            <span
              class="name-cell"
              :class="{ 'is-hidden': (row as NotebookItem).status === 0 }"
            >
              {{ (row as NotebookItem).name }}
            </span>
            <el-tooltip
              v-if="(row as NotebookItem).status === 1"
              content="在门户端打开"
              placement="top"
              :show-after="300"
            >
              <a
                class="portal-link"
                :href="portalUrl(row as NotebookItem)"
                target="_blank"
                rel="noopener noreferrer"
                @click.stop
              >
                <el-icon :size="14"><Position /></el-icon>
              </a>
            </el-tooltip>
          </span>
        </template>

        <!-- 简介：超长省略 + hover 全文；空显示 '—' -->
        <template #column-description="{ row }">
          <el-tooltip
            v-if="(row as NotebookItem).description"
            :content="(row as NotebookItem).description!"
            placement="top"
            :show-after="300"
          >
            <span class="desc-cell">{{ (row as NotebookItem).description }}</span>
          </el-tooltip>
          <span
            v-else
            class="muted-cell"
          >—</span>
        </template>

        <!-- 笔记数 / Agent 数：mono 数字（无背景） -->
        <template #column-noteCount="{ row }">
          <span class="metric-cell">{{ (row as NotebookItem).noteCount }}</span>
        </template>
        <template #column-agentCount="{ row }">
          <span class="metric-cell">{{ (row as NotebookItem).agentCount }}</span>
        </template>

        <!-- 状态：行内 switch + 克制文字标签（不再使用色块胶囊） -->
        <template #column-status="{ row }">
          <el-switch
            :model-value="(row as NotebookItem).status === 1"
            :loading="statusLoadingMap[row.id as number]"
            @change="(val: string | number | boolean) => toggleStatus(row as NotebookItem, !!val)"
          />
          <span
            class="status-text"
            :class="(row as NotebookItem).status === 1 ? 'is-active' : 'is-hidden'"
            style="margin-left: 8px"
          >
            {{ (row as NotebookItem).status === 1 ? '启用' : '隐藏' }}
          </span>
        </template>

        <!-- 操作列 -->
        <template #actions="{ row }">
          <el-button
            v-permiss="'notebook:edit'"
            link
            type="primary"
            :icon="Edit"
            @click="openEdit(row as NotebookItem)"
          >
            编辑
          </el-button>
          <!-- 关联 agent：NotebookAgentsDialog 全量覆盖 -->
          <el-button
            v-permiss="'notebook:agent:list'"
            link
            type="primary"
            :icon="Connection"
            @click="openAgentsDialog(row as NotebookItem)"
          >
            关联 agent
          </el-button>
          <el-button
            v-permiss="'notebook:delete'"
            link
            type="danger"
            :icon="Delete"
            @click="handleDelete(row as NotebookItem)"
          >
            删除
          </el-button>
        </template>
      </CrudTable>
    </div>

    <!-- ✨ agent 关联维护弹窗（穿梭框全量覆盖） -->
    <NotebookAgentsDialog
      v-model:visible="agentsDialogVisible"
      :notebook-id="dialogNotebook?.id ?? 0"
      :notebook-name="dialogNotebook?.name || ''"
      @changed="crudRef?.reload()"
    />

    <!-- 新建/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑笔记本' : '新建笔记本'"
      width="560px"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item
          label="名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            placeholder="笔记本唯一名称，如 研发知识库"
            maxlength="128"
          />
          <div class="field-tip">
            name 唯一（未软删范围内），由后端校验
          </div>
        </el-form-item>
        <el-form-item
          label="简介"
          prop="description"
        >
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            placeholder="门户卡片副文案（可选）"
          />
        </el-form-item>
        <!-- ✨ v4.3：封面上传（复用 /system/upload，type=cover 分目录存储；替代纯 URL 输入） -->
        <el-form-item
          label="封面"
          prop="cover"
        >
          <div class="cover-field">
            <el-upload
              :show-file-list="false"
              :auto-upload="true"
              :http-request="handleCoverUpload"
              :before-upload="beforeImageUpload"
              accept="image/*"
              class="cover-upload"
            >
              <div class="cover-edit">
                <img
                  v-if="form.cover"
                  :src="resolveMediaUrl(form.cover)"
                  class="cover-preview"
                  alt="封面预览"
                />
                <div
                  v-else
                  class="cover-empty"
                >
                  <el-icon :size="20"><Plus /></el-icon>
                  <span>上传封面</span>
                </div>
                <div
                  v-if="form.cover"
                  class="cover-mask"
                >
                  <span class="cover-mask-text">更换</span>
                </div>
              </div>
            </el-upload>
            <div class="cover-meta">
              <el-link
                v-if="form.cover"
                type="danger"
                :underline="false"
                @click="form.cover = ''"
              >
                移除封面
              </el-link>
              <span
                v-else
                class="field-tip"
              >建议宽幅图 ≥720×240（3:1）· JPG / PNG / WebP · ≤5MB</span>
            </div>
          </div>
        </el-form-item>

        <!-- v4.17：logo 上传（笔记页左上角小标识，复用 /system/upload，type=logo 分目录） -->
        <el-form-item
          label="logo"
          prop="logo"
        >
          <div class="cover-field">
            <el-upload
              :show-file-list="false"
              :auto-upload="true"
              :http-request="handleLogoUpload"
              :before-upload="beforeImageUpload"
              accept="image/*"
              class="cover-upload"
            >
              <div class="logo-edit">
                <img
                  v-if="form.logo"
                  :src="resolveMediaUrl(form.logo)"
                  class="cover-preview"
                  alt="logo 预览"
                />
                <div
                  v-else
                  class="cover-empty"
                >
                  <el-icon :size="20"><Plus /></el-icon>
                  <span>上传 logo</span>
                </div>
                <div
                  v-if="form.logo"
                  class="cover-mask"
                >
                  <span class="cover-mask-text">更换</span>
                </div>
              </div>
            </el-upload>
            <div class="cover-meta">
              <el-link
                v-if="form.logo"
                type="danger"
                :underline="false"
                @click="form.logo = ''"
              >
                移除 logo
              </el-link>
              <span
                v-else
                class="field-tip"
              >建议方形 ≥128×128（1:1）· JPG / PNG / WebP · ≤5MB</span>
            </div>
          </div>
        </el-form-item>

        <!-- v4.17：hero 上传（笔记页空态中央展示图，复用 /system/upload，type=hero 分目录） -->
        <el-form-item
          label="空态图"
          prop="hero"
        >
          <div class="cover-field">
            <el-upload
              :show-file-list="false"
              :auto-upload="true"
              :http-request="handleHeroUpload"
              :before-upload="beforeImageUpload"
              accept="image/*"
              class="cover-upload"
            >
              <div class="hero-edit">
                <img
                  v-if="form.hero"
                  :src="resolveMediaUrl(form.hero)"
                  class="cover-preview"
                  alt="hero 预览"
                />
                <div
                  v-else
                  class="cover-empty"
                >
                  <el-icon :size="20"><Plus /></el-icon>
                  <span>上传空态图</span>
                </div>
                <div
                  v-if="form.hero"
                  class="cover-mask"
                >
                  <span class="cover-mask-text">更换</span>
                </div>
              </div>
            </el-upload>
            <div class="cover-meta">
              <el-link
                v-if="form.hero"
                type="danger"
                :underline="false"
                @click="form.hero = ''"
              >
                移除空态图
              </el-link>
              <span
                v-else
                class="field-tip"
              >建议宽幅图 ≥640×360· JPG / PNG / WebP · ≤5MB</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
          />
          <div class="field-tip">
            {{ form.status === 1 ? '启用：笔记本将出现在门户' : '隐藏：笔记本不展示在门户' }}
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
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { Plus, Edit, Delete, Connection, Position } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable from '@/components/CrudTable.vue'
import NotebookAgentsDialog from './NotebookAgentsDialog.vue'
import { uploadImage } from '@/api/system/upload'
import {
  getNotebookList,
  createNotebook,
  updateNotebook,
  deleteNotebook,
  type NotebookItem,
  type NotebookPayload
} from '@/api/system/notebook'

const crudRef = ref<any>(null)

/** 列定义 */
const columns = [
  { prop: 'cover', label: '封面', width: 64, slot: true },
  { prop: 'name', label: '名称', minWidth: 160, slot: true },
  { prop: 'description', label: '简介', minWidth: 220, slot: true },
  { prop: 'noteCount', label: '笔记数', width: 90, align: 'right' as const, slot: true },
  { prop: 'agentCount', label: 'Agent 数', width: 100, align: 'right' as const, slot: true },
  { prop: 'status', label: '状态', width: 130, slot: true },
  { prop: 'createTime', label: '创建时间', minWidth: 160 }
]

/** 搜索项：name 模糊（CrudTable 内建 300ms 防抖） */
const searchItems = [
  { prop: 'keyword', label: '关键词', type: 'input' as const, placeholder: '笔记本名称' }
]

/* ============ 克制字色（仅取名字色，背景统一极浅灰） ============ */
const NAME_COLORS = [
  '#1d4ed8', // 蓝
  '#047857', // 绿
  '#b45309', // 橙
  '#9d174d', // 粉
  '#6d28d9', // 紫
  '#0e7490', // 青
  '#be185d', // 玫红
  '#92400e'  // 黄棕
]

/** 名称 hash（djb2，稳定映射） */
function nameHash(name: string): number {
  let h = 5381
  for (let i = 0; i < name.length; i++) {
    h = ((h << 5) + h + name.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

function pickFg(name: string): string {
  return NAME_COLORS[(nameHash(name) * 7) % NAME_COLORS.length]
}

/** 封面 fallback 样式：纯中性灰底（参照审计日志克制风，不做差异化着色） */
function coverStyle(_name?: string): Record<string, string> {
  return {
    color: 'var(--el-text-color-regular)',
    background: 'var(--el-fill-color-lighter)',
    borderColor: 'var(--el-border-color-light)'
  }
}

/* ===== v4.3：封面上传（复用统一上传接口，type=cover 分目录存储） ===== */

/** 封面上传体积上限：5MB */
const MAX_IMAGE_SIZE = 5 * 1024 * 1024

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

/** v4.17：图片上传前公共校验（封面/logo/hero 共用） */
function beforeImageUpload(file: File): boolean {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('只能上传图片文件')
    return false
  }
  if (file.size > MAX_IMAGE_SIZE) {
    ElMessage.error('图片大小不能超过 5MB')
    return false
  }
  return true
}

/** 封面上传（type=cover） */
async function handleCoverUpload(options: UploadRequestOptions): Promise<void> {
  const file = options.file as File
  try {
    const res = await uploadImage(file, 'cover')
    const url = res.data?.url
    if (url) {
      form.cover = url
      ElMessage.success('封面上传成功')
    } else {
      ElMessage.error('上传成功但未返回图片地址')
    }
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '封面上传失败')
  }
}

/** v4.17 logo 上传（type=logo） */
async function handleLogoUpload(options: UploadRequestOptions): Promise<void> {
  const file = options.file as File
  try {
    const res = await uploadImage(file, 'logo')
    const url = res.data?.url
    if (url) {
      form.logo = url
      ElMessage.success('logo 上传成功')
    } else {
      ElMessage.error('上传成功但未返回图片地址')
    }
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : 'logo 上传失败')
  }
}

/** v4.17 hero 上传（type=hero） */
async function handleHeroUpload(options: UploadRequestOptions): Promise<void> {
  const file = options.file as File
  try {
    const res = await uploadImage(file, 'hero')
    const url = res.data?.url
    if (url) {
      form.hero = url
      ElMessage.success('空态图上传成功')
    } else {
      ElMessage.error('上传成功但未返回图片地址')
    }
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '空态图上传失败')
  }
}

/* ===== 门户端跳转（v3.5：管理端笔记本 → 门户笔记本视图） ===== */
const PORTAL_BASE = import.meta.env.VITE_PORTAL_BASE_URL
  || (typeof window !== 'undefined' ? window.location.origin : '')

function portalUrl(row: NotebookItem): string {
  return `${PORTAL_BASE}/notebooks/${row.id}`
}

/* ===== 操作列弹窗（关联 agent） ===== */
const agentsDialogVisible = ref(false)
const dialogNotebook = ref<NotebookItem | null>(null)

function openAgentsDialog(row: NotebookItem): void {
  dialogNotebook.value = row
  agentsDialogVisible.value = true
}

/* ===== 状态切换（隐藏后不再出现在门户；切换需带当前 name 供后端校验） ===== */
const statusLoadingMap = reactive<Record<string | number, boolean>>({})

async function toggleStatus(row: NotebookItem, val: boolean): Promise<void> {
  const target = val ? 1 : 0
  const tip =
    target === 0
      ? '隐藏后该笔记本不再出现在门户（笔记数据与 agent 关联保留）。确认隐藏？'
      : '确认将该笔记本设为启用（展示在门户）？'
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
    // 后端 NotebookSaveReq.name 为 @NotBlank 必填，切换状态须回传当前 name
    await updateNotebook(row.id, { name: row.name, status: target })
    ElMessage.success(target === 1 ? '已启用' : '已隐藏')
    crudRef.value?.reload()
  } finally {
    statusLoadingMap[row.id] = false
  }
}

/* ===== 删除（软删：数据保留 / 关联解除 / 同名可重建） ===== */
async function handleDelete(row: NotebookItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '软删除后：笔记本从门户与管理端移除，笔记数据保留；该笔记本与全部 agent 的关联将解除，相关 agent 即刻失去写作权限。同名笔记本可重新创建。',
      '删除笔记本',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  await deleteNotebook(row.id)
  ElMessage.success('已删除')
  crudRef.value?.reload()
}

/* ===== 新建/编辑对话框 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
/** 编辑中的笔记本 id（提交时使用） */
const editingId = ref<number>(0)

const defaultForm = (): NotebookPayload => ({
  name: '',
  description: '',
  cover: '',
  logo: '',
  hero: '',
  status: 1
})

const form = reactive<NotebookPayload>(defaultForm())

const rules: FormRules<NotebookPayload> = {
  name: [
    { required: true, message: '请输入笔记本名称', trigger: 'blur' },
    { max: 128, message: '名称不能超过 128 字符', trigger: 'blur' }
  ]
}

function openAdd(): void {
  isEdit.value = false
  Object.assign(form, defaultForm())
  dialogVisible.value = true
}

function openEdit(row: NotebookItem): void {
  isEdit.value = true
  editingId.value = row.id
  Object.assign(form, defaultForm(), {
    name: row.name,
    description: row.description || '',
    cover: row.cover || '',
    // v4.23.1 修复：编辑弹窗回填 logo/hero——缺失时提交会以空串覆盖已配置值（后端仅 null 防御）
    logo: row.logo || '',
    hero: row.hero || '',
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
      // 后端 PUT "" 需 body 带 id + name
      await updateNotebook(editingId.value, {
        name: form.name,
        description: form.description,
        cover: form.cover,
        logo: form.logo,
        hero: form.hero,
        status: form.status
      })
      ElMessage.success('修改成功')
    } else {
      await createNotebook({
        name: form.name,
        description: form.description,
        cover: form.cover,
        logo: form.logo,
        hero: form.hero,
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

/* ============ 克制视觉系统（参照审计日志：小尺寸边框元素 + 居中对齐 + 文字色） ============ */
.cover-img {
  border-radius: 6px;
}

/* ===== v4.3：封面上传 ===== */
.cover-field {
  display: flex;
  align-items: center;
  gap: 14px;
}

.cover-upload {
  line-height: 1;
}

/* 封面预览框：16:9 横图比例，与门户卡片封面观感一致 */
.cover-edit {
  position: relative;
  width: 128px;
  height: 72px;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--el-border-color-light);
}

/* v4.17 logo 预览框：方形 1:1 */
.logo-edit {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--el-border-color-light);
}

/* v4.17 hero 预览框：宽幅 16:9 */
.hero-edit {
  position: relative;
  width: 160px;
  height: 90px;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--el-border-color-light);
}

.cover-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 空态占位：虚线框 + 上传提示 */
.cover-empty {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter);
  border: 1px dashed var(--el-border-color);
  font-size: 12px;
  box-sizing: border-box;
}

/* hover 蒙层：提示可点击更换 */
.cover-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.cover-edit:hover .cover-mask {
  opacity: 1;
}

.cover-mask-text {
  font-size: 12px;
  color: #fff;
}

.cover-meta {
  display: flex;
  align-items: center;
}

.cover-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 14px;
  font-weight: 500;
  line-height: 1;
  user-select: none;
  flex-shrink: 0;
}

.name-wrap {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.name-cell {
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.name-cell.is-hidden {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

/* 「门户打开」小图标（hover 显色，克制风） */
.portal-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
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

.metric-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-regular);
}

.desc-cell {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.5;
  color: var(--el-text-color-regular);
}

.muted-cell {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.status-text {
  font-size: 12px;
  font-weight: 500;
}
.status-text.is-active {
  color: var(--el-color-success);
}
.status-text.is-hidden {
  color: var(--el-text-color-secondary);
}
</style>
