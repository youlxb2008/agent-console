<!--
  消息通知管理
  - 列表展示通知（标题、类型、状态、发布人、创建时间）
  - 支持按标题/类型/状态搜索
  - 新增/编辑弹窗：标题、类型、状态、接收范围（全员广播/指定人员）、内容（textarea）、备注
  - 查看详情弹窗：展示完整通知内容
-->
<template>
  <div class="na-page">
    <PageHeader
      title="消息通知"
      subtitle="管理系统通知、版本更新、维护公告"
    />

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :action-width="240"
        :api="{ list: getNoticeList, delete: deleteNotice }"
      >
        <template #toolbar>
          <el-button
            v-permiss="'notice:create'"
            type="primary"
            :icon="Plus"
            @click="openAdd"
          >
            新增通知
          </el-button>
        </template>

        <!-- 类型列 -->
        <template #column-type="{ row }">
          <el-tag
            :type="typeTagMap[row.type as number]?.type || 'info'"
            size="small"
          >
            {{ typeTagMap[row.type as number]?.label || '未知' }}
          </el-tag>
        </template>

        <!-- 状态列 -->
        <template #column-status="{ row }">
          <el-tag
            :type="row.status === 1 ? 'success' : 'warning'"
            size="small"
          >
            {{ row.status === 1 ? '已发布' : '草稿' }}
          </el-tag>
        </template>

        <!-- 操作列 -->
        <template #actions="{ row }">
          <el-button
            v-permiss="'notice:view'"
            link
            type="info"
            :icon="View"
            size="small"
            style="margin-right: 4px"
            @click="openView(row)"
          >
            查看
          </el-button>
          <el-button
            v-permiss="'notice:update'"
            link
            type="primary"
            :icon="Edit"
            size="small"
            style="margin-right: 4px"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            v-permiss="'notice:delete'"
            link
            type="danger"
            :icon="Delete"
            size="small"
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
      :title="isEdit ? '编辑通知' : '新增通知'"
      width="640px"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item
          label="标题"
          prop="title"
        >
          <el-input
            v-model="form.title"
            placeholder="请输入通知标题"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>
        <el-form-item
          label="类型"
          prop="type"
        >
          <el-radio-group v-model="form.type">
            <el-radio :value="1">
              系统通知
            </el-radio>
            <el-radio :value="2">
              版本更新
            </el-radio>
            <el-radio :value="3">
              维护公告
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item
          label="状态"
          prop="status"
        >
          <el-radio-group v-model="form.status">
            <el-radio :value="1">
              已发布
            </el-radio>
            <el-radio :value="0">
              草稿
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item
          label="接收范围"
          prop="recipientType"
        >
          <el-radio-group v-model="recipientType">
            <el-radio value="all">
              全员广播
            </el-radio>
            <el-radio value="specific">
              指定人员
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <!-- 选中"指定人员"时，显示多选下拉选择接收人 -->
        <el-form-item
          v-if="recipientType === 'specific'"
          label="接收人"
          prop="recipientUserIds"
        >
          <el-select
            v-model="form.recipientUserIds"
            multiple
            filterable
            placeholder="请选择接收人"
            style="width: 100%"
          >
            <el-option
              v-for="user in userOptions"
              :key="user.id"
              :label="user.nickname || user.username"
              :value="user.id ?? ''"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          label="内容"
          prop="content"
        >
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            placeholder="支持 HTML 格式的通知内容"
          />
        </el-form-item>
        <el-form-item
          label="备注"
          prop="remark"
        >
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="备注（可选）"
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

    <!-- 查看详情弹窗 -->
    <el-dialog
      v-model="viewVisible"
      title="通知详情"
      width="640px"
    >
      <div
        v-if="viewData"
        class="notice-detail"
      >
        <h3 class="notice-title">
          {{ viewData.title }}
        </h3>
        <div class="notice-meta">
          <el-tag
            :type="typeTagMap[viewData.type as number]?.type || 'info'"
            size="small"
          >
            {{ typeTagMap[viewData.type as number]?.label || '未知' }}
          </el-tag>
          <el-tag
            :type="viewData.status === 1 ? 'success' : 'warning'"
            size="small"
          >
            {{ viewData.status === 1 ? '已发布' : '草稿' }}
          </el-tag>
          <span class="notice-publisher">发布人：{{ viewData.publisher || '-' }}</span>
          <span class="notice-time">{{ viewData.createTime }}</span>
        </div>
        <el-divider />
        <div
          class="notice-content"
          v-html="viewData.content"
        />
        <div
          v-if="viewData.remark"
          class="notice-remark"
        >
          备注：{{ viewData.remark }}
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Edit, Delete, View } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable, { type CrudColumn } from '@/components/CrudTable.vue'
import { getNoticeList, addNotice, updateNotice, deleteNotice, getNoticeRecipients, type SysNotice } from '@/api/system/notice'
import { getAllUsers, type SysUser } from '@/api/system/user'

// CrudTable 是泛型组件，InstanceType 无法正确推断，使用 any 代替
const crudRef = ref<any>(null)

/** 类型 → 标签样式映射 */
const typeTagMap: Record<number, { label: string; type: 'primary' | 'success' | 'warning' | 'info' | 'danger' }> = {
  1: { label: '系统通知', type: 'primary' },
  2: { label: '版本更新', type: 'success' },
  3: { label: '维护公告', type: 'warning' }
}

/** 表格列定义 */
const columns: CrudColumn[] = [
  { prop: 'title', label: '标题', minWidth: 200, showOverflowTooltip: true },
  { prop: 'type', label: '类型', width: 110, slot: true },
  { prop: 'status', label: '状态', width: 90, slot: true },
  { prop: 'publisher', label: '发布人', width: 100 },
  { prop: 'createTime', label: '创建时间', minWidth: 160 },
  { prop: 'action', label: '操作', width: 240, fixed: 'right', slot: true }
]

/** 搜索项 */
const searchItems = [
  { prop: 'title', label: '标题' },
  {
    prop: 'type',
    label: '类型',
    component: 'select',
    options: [
      { label: '系统通知', value: 1 },
      { label: '版本更新', value: 2 },
      { label: '维护公告', value: 3 }
    ]
  },
  {
    prop: 'status',
    label: '状态',
    component: 'select',
    options: [
      { label: '已发布', value: 1 },
      { label: '草稿', value: 0 }
    ]
  }
]

/* ===== 用户列表（接收人选择器用） ===== */
/** 用户选项列表，弹窗打开时从后端获取 */
const userOptions = ref<SysUser[]>([])

/** 加载全部用户列表，用于接收人选择器 */
async function loadUserOptions() {
  try {
    const res = await getAllUsers()
    if (res.success && res.data) {
      userOptions.value = res.data
    }
  } catch (e) {
    // 获取用户列表失败不影响弹窗打开，仅打印警告
    console.warn('获取用户列表失败', e)
  }
}

// 组件挂载时预加载用户列表
onMounted(() => {
  loadUserOptions()
})

/* ===== 新增/编辑弹窗 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

/** 接收范围类型：all=全员广播，specific=指定人员（仅前端使用，不传后端） */
const recipientType = ref<'all' | 'specific'>('all')

const defaultForm = (): SysNotice => ({
  title: '',
  content: '',
  type: 1,
  status: 1,
  remark: '',
  // 默认空数组，表示全员广播
  recipientUserIds: []
})

const form = reactive<SysNotice>(defaultForm())

/** 接收人校验：选择"指定人员"时必须至少选一个接收人 */
const validateRecipientUserIds = (_rule: any, value: number[], callback: any) => {
  if (recipientType.value === 'specific' && (!value || value.length === 0)) {
    callback(new Error('请至少选择一个接收人'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  title: [{ required: true, message: '请输入通知标题', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  content: [{ required: true, message: '请输入通知内容', trigger: 'blur' }],
  // 接收人校验：指定人员模式下必须选择至少一人
  recipientUserIds: [{ validator: validateRecipientUserIds, trigger: 'change' }]
}

/** 打开新增弹窗 */
function openAdd() {
  isEdit.value = false
  Object.assign(form, defaultForm())
  // 新增时默认全员广播
  recipientType.value = 'all'
  dialogVisible.value = true
}

/** 打开编辑弹窗，并回显已有接收人 */
async function openEdit(row: SysNotice) {
  isEdit.value = true
  Object.assign(form, defaultForm(), row)
  // 默认先设为全员广播，等获取接收人列表后再判断
  recipientType.value = 'all'
  form.recipientUserIds = []
  dialogVisible.value = true

  // 编辑时：调用 getRecipients API 获取已有接收人列表
  try {
    const res = await getNoticeRecipients(row.id!)
    if (res.success && res.data && res.data.length > 0) {
      // 有接收人记录 → 定向通知
      recipientType.value = 'specific'
      form.recipientUserIds = res.data
    }
    // 无接收人记录 → 全员广播（保持默认值）
  } catch (e) {
    // 获取接收人失败不影响编辑，默认全员广播
    console.warn('获取通知接收人失败', e)
  }
}

/** 重置表单 */
function resetForm() {
  formRef.value?.resetFields()
  Object.assign(form, defaultForm())
  // 重置接收范围为全员广播
  recipientType.value = 'all'
}

/** 提交表单 */
async function submit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    // 根据接收范围类型处理 recipientUserIds
    if (recipientType.value === 'all') {
      // 全员广播：传空数组（后端逻辑：空数组=全员广播）
      form.recipientUserIds = []
    }
    // specific 模式下 form.recipientUserIds 已由 el-select 绑定，无需额外处理

    if (isEdit.value) {
      await updateNotice(form)
      ElMessage.success('修改成功')
    } else {
      await addNotice(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    crudRef.value?.reload()
  } finally {
    submitting.value = false
  }
}

/* ===== 查看详情弹窗 ===== */
const viewVisible = ref(false)
const viewData = ref<SysNotice | null>(null)

function openView(row: SysNotice) {
  viewData.value = row
  viewVisible.value = true
}
</script>

<style scoped>
.notice-detail {
  padding: 0 4px;
}

.notice-title {
  margin: 0 0 12px;
  font-size: 18px;
  font-weight: 600;
  color: var(--na-text-primary);
}

.notice-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--na-text-secondary);
}

.notice-publisher {
  margin-left: 8px;
}

.notice-content {
  line-height: 1.7;
  color: var(--na-text-primary);
  word-break: break-word;
}

.notice-content :deep(p) {
  margin: 0 0 8px;
}

.notice-remark {
  margin-top: 16px;
  padding: 8px 12px;
  background: var(--na-bg-hover);
  border-radius: 4px;
  font-size: 13px;
  color: var(--na-text-secondary);
}
</style>
