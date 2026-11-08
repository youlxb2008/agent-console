<!--
  角色管理
  --------
  参照 NodeAdmin 风格改造：
  - 引入 AssignMenuDialog 组件，添加"分配菜单"按钮
  - 超管保护：禁止删除 code === 'super_admin' 的角色
  - 排序列显示
  - 权限码保持 system:role 不变
  - CSS 使用普通 CSS（不用 SCSS）
-->
<template>
  <div class="na-page">
    <PageHeader
      title="角色管理"
      subtitle="维护系统角色及其菜单权限"
    />

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :action-width="260"
        :api="{ list: getRoleList, delete: deleteRole }"
      >
        <template #toolbar>
          <el-button
            v-permiss="'system:role'"
            type="primary"
            :icon="Plus"
            @click="openAdd"
          >
            新增角色
          </el-button>
        </template>

        <!-- 状态列 -->
        <template #column-status="{ row }">
          <el-tag
            :type="row.status === 0 ? 'success' : 'danger'"
            size="small"
          >
            {{ row.status === 0 ? '正常' : '禁用' }}
          </el-tag>
        </template>

        <template #actions="{ row }">
          <el-button
            v-permiss="'system:role'"
            link
            type="primary"
            :icon="Edit"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            v-permiss="'system:role'"
            link
            type="warning"
            @click="openAssignMenu(row)"
          >
            分配菜单
          </el-button>
          <el-button
            v-permiss="'system:role'"
            link
            type="danger"
            :icon="Delete"
            :disabled="row.code === 'super_admin'"
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
      :title="isEdit ? '编辑角色' : '新增角色'"
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
          label="角色名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            placeholder="请输入角色名称"
          />
        </el-form-item>
        <el-form-item
          label="角色编码"
          prop="code"
        >
          <el-input
            v-model="form.code"
            :disabled="isEdit"
            placeholder="如 admin / system:user"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="form.sort"
            :min="0"
            :max="999"
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
            :rows="2"
            placeholder="请输入备注"
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

    <!-- 分配菜单弹窗 -->
    <AssignMenuDialog
      v-model:visible="assignMenuVisible"
      :role-id="selectedRoleId"
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
import { getRoleList, addRole, updateRole, deleteRole, type SysRole } from '@/api/system/role'
import AssignMenuDialog from './AssignMenuDialog.vue'

// CrudTable 是泛型组件，InstanceType 无法正确推断，使用 any 代替
const crudRef = ref<any>(null)

// 列定义（新增排序列）
const columns = [
  { prop: 'name', label: '角色名称', minWidth: 140 },
  { prop: 'code', label: '角色编码', minWidth: 140 },
  { prop: 'sort', label: '排序', width: 70, align: 'center' as const },
  { prop: 'status', label: '状态', width: 90, slot: true },
  { prop: 'createTime', label: '创建时间', minWidth: 160 }
]

const searchItems = [
  { prop: 'name', label: '角色名称' },
  { prop: 'code', label: '角色编码' },
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

/* ===== 弹窗 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const defaultForm = (): SysRole => ({
  name: '',
  code: '',
  sort: 0,
  status: 0,
  remark: ''
})

const form = reactive<SysRole>(defaultForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  code: [{ required: true, message: '请输入角色编码', trigger: 'blur' }]
}

function openAdd() {
  isEdit.value = false
  Object.assign(form, defaultForm())
  dialogVisible.value = true
}

function openEdit(row: SysRole) {
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
      await updateRole(form)
      ElMessage.success('修改成功')
    } else {
      await addRole(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    crudRef.value?.reload()
  } finally {
    submitting.value = false
  }
}

/* ===== 分配菜单 ===== */
const assignMenuVisible = ref(false)
const selectedRoleId = ref<number | string>(0)

/** 打开分配菜单弹窗 */
function openAssignMenu(row: SysRole) {
  selectedRoleId.value = row.id!
  assignMenuVisible.value = true
}
</script>

<style scoped></style>
