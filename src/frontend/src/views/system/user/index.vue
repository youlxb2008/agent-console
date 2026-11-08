<!--
  用户管理
  --------
  参照 NodeAdmin 风格改造：
  - 引入 AssignRoleDialog 组件，添加"分配角色"按钮
  - 新增 roles 列，用多个 el-tag 展示角色
  - status 列改为 el-switch 行内切换（超管 id=1 禁用 switch）
  - 去掉手机号列
  - 新增备注字段到编辑弹窗
  - 权限码保持 system:user 不变
  - CSS 使用普通 CSS（不用 SCSS）
-->
<template>
  <div class="na-page">
    <PageHeader
      title="用户管理"
      subtitle="维护系统用户账号、角色与状态"
    />

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :action-width="260"
        :api="{ list: getUserList, delete: deleteUser }"
      >
        <!-- 工具栏：新增按钮 -->
        <template #toolbar>
          <el-button
            v-permiss="'system:user'"
            type="primary"
            :icon="Plus"
            @click="openAdd"
          >
            新增用户
          </el-button>
        </template>

        <!-- 角色列：多个 el-tag 展示 -->
        <template #column-roles="{ row }">
          <el-tag
            v-for="role in row.roles || []"
            :key="role.id"
            size="small"
            style="margin-right: 4px"
          >
            {{ role.name }}
          </el-tag>
          <span
            v-if="!row.roles?.length"
            style="color: var(--el-text-color-secondary)"
          >未分配</span>
        </template>

        <!-- 状态列：el-switch 行内切换（超管 id=1 禁用） -->
        <template #column-status="{ row }">
          <el-switch
            :model-value="row.status === 0"
            :loading="statusLoadingMap[row.id]"
            :disabled="row.id === 1"
            @change="(val: string | number | boolean) => toggleStatus(row, !!val)"
          />
        </template>

        <!-- 操作列 -->
        <template #actions="{ row }">
          <el-button
            v-permiss="'system:user'"
            link
            type="primary"
            :icon="Edit"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            v-permiss="'system:user'"
            link
            type="warning"
            @click="openAssignRole(row)"
          >
            分配角色
          </el-button>
          <el-button
            v-permiss="'system:user'"
            link
            type="danger"
            :icon="Delete"
            :disabled="row.id === 1"
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
      :title="isEdit ? '编辑用户' : '新增用户'"
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
          label="用户名"
          prop="username"
        >
          <!-- 编辑时用户名不可改 -->
          <el-input
            v-model="form.username"
            :disabled="isEdit"
            placeholder="请输入用户名"
          />
        </el-form-item>
        <el-form-item
          label="昵称"
          prop="nickname"
        >
          <el-input
            v-model="form.nickname"
            placeholder="请输入昵称"
          />
        </el-form-item>
        <!-- 仅新增时填写密码 -->
        <el-form-item
          v-if="!isEdit"
          label="密码"
          prop="password"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
          />
        </el-form-item>
        <el-form-item
          label="邮箱"
          prop="email"
        >
          <el-input
            v-model="form.email"
            placeholder="请输入邮箱"
          />
        </el-form-item>
        <el-form-item
          label="状态"
          prop="status"
        >
          <el-switch
            v-model="form.status"
            :active-value="0"
            :inactive-value="1"
          />
        </el-form-item>
        <el-form-item
          label="备注"
          prop="remark"
        >
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
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

    <!-- 分配角色弹窗 -->
    <AssignRoleDialog
      v-model:visible="assignRoleVisible"
      :user-id="selectedUserId"
      @done="crudRef?.reload()"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable from '@/components/CrudTable.vue'
import { getUserList, addUser, updateUser, deleteUser, type SysUser } from '@/api/system/user'
import AssignRoleDialog from './AssignRoleDialog.vue'

// CrudTable 是泛型组件，InstanceType 无法正确推断，使用 any 代替
const crudRef = ref<any>(null)

// 列定义（去掉手机号列，新增 roles 列）
const columns = [
  { prop: 'username', label: '用户名', minWidth: 120 },
  { prop: 'nickname', label: '昵称', minWidth: 120 },
  { prop: 'email', label: '邮箱', minWidth: 160 },
  { prop: 'roles', label: '角色', minWidth: 180, slot: true },
  { prop: 'status', label: '状态', width: 90, slot: true },
  { prop: 'createTime', label: '创建时间', minWidth: 160 }
]

// 搜索项
const searchItems = [
  { prop: 'username', label: '用户名' },
  { prop: 'nickname', label: '昵称' },
  {
    prop: 'status',
    label: '状态',
    type: 'select' as const,
    options: [
      { label: '正常', value: 0 },
      { label: '禁用', value: 1 }
    ]
  }
]

/* ===== 状态切换 ===== */
/** 每个用户独立缓存 loading 状态，避免多个开关互相影响 */
const statusLoadingMap = reactive<Record<string | number, boolean>>({})

/**
 * 启用/禁用用户
 * @param user 用户行数据
 * @param val 新的开关值
 */
async function toggleStatus(user: SysUser, val: boolean): Promise<void> {
  const newStatus = val ? 0 : 1
  statusLoadingMap[user.id!] = true
  try {
    await updateUser({ ...user, status: newStatus })
    ElMessage.success(val ? '已启用' : '已禁用')
    crudRef.value?.reload()
  } catch {
    ElMessage.error('操作失败')
  } finally {
    statusLoadingMap[user.id!] = false
  }
}

/* ===== 弹窗表单 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

// 表单初始值
const defaultForm = (): SysUser => ({
  username: '',
  nickname: '',
  password: '',
  email: '',
  status: 0,
  remark: ''
})

const form = reactive<SysUser>(defaultForm())

// 表单校验规则
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

/** 打开新增弹窗 */
function openAdd() {
  isEdit.value = false
  Object.assign(form, defaultForm())
  dialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEdit(row: SysUser) {
  isEdit.value = true
  Object.assign(form, defaultForm(), row)
  dialogVisible.value = true
}

/** 重置表单（弹窗关闭后） */
function resetForm() {
  formRef.value?.resetFields()
  Object.assign(form, defaultForm())
}

/** 提交新增/编辑 */
async function submit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateUser(form)
      ElMessage.success('修改成功')
    } else {
      await addUser(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    // 刷新列表
    crudRef.value?.reload()
  } finally {
    submitting.value = false
  }
}

/* ===== 分配角色 ===== */
const assignRoleVisible = ref(false)
const selectedUserId = ref<number | string>(0)

/** 打开分配角色弹窗 */
function openAssignRole(row: SysUser) {
  selectedUserId.value = row.id!
  assignRoleVisible.value = true
}
</script>

<style scoped></style>
