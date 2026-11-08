<!--
  系统配置管理
  -----------
  - t_option 表的完整 CRUD 页面
  - 支持按配置键模糊搜索
  - 新增/编辑弹窗，optionKey 唯一校验
-->
<template>
  <div class="na-page">
    <PageHeader
      title="系统配置"
      subtitle="管理系统全局配置项（key-value）"
    />

    <div class="na-card">
      <CrudTable
        ref="crudRef"
        :columns="columns"
        :search-items="searchItems"
        :action-width="180"
        :show-index="false"
        :api="{ list: getOptionList, delete: deleteOption }"
      >
        <template #toolbar>
          <el-button
            v-permiss="'system:option'"
            type="primary"
            :icon="Plus"
            @click="openAdd"
          >
            新增配置
          </el-button>
        </template>

        <!-- 配置键列：代码风格展示 -->
        <template #column-optionKey="{ row }">
          <code class="option-key">{{ row.optionKey }}</code>
        </template>

        <!-- 配置值列：超长文本截断显示 -->
        <template #column-optionValue="{ row }">
          <el-tooltip
            v-if="row.optionValue && row.optionValue.length > 60"
            :content="row.optionValue"
            placement="top"
          >
            <span class="option-value">{{ row.optionValue.slice(0, 60) }}...</span>
          </el-tooltip>
          <span v-else class="option-value">{{ row.optionValue || '-' }}</span>
        </template>

        <template #actions="{ row }">
          <el-button
            v-permiss="'system:option'"
            link
            type="primary"
            :icon="Edit"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            v-permiss="'system:option'"
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
      :title="isEdit ? '编辑配置' : '新增配置'"
      width="520px"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item
          label="配置键"
          prop="optionKey"
        >
          <!-- 编辑模式下禁止修改配置键 -->
          <el-input
            v-model="form.optionKey"
            :disabled="isEdit"
            placeholder="如 site_title"
          />
        </el-form-item>
        <el-form-item
          label="配置值"
          prop="optionValue"
        >
          <el-input
            v-model="form.optionValue"
            type="textarea"
            :rows="4"
            placeholder="请输入配置值"
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import CrudTable from '@/components/CrudTable.vue'
import {
  getOptionList,
  addOption,
  updateOption,
  deleteOption,
  type SysOption
} from '@/api/system/option'

// CrudTable 是泛型组件，InstanceType 无法正确推断，使用 any 代替
const crudRef = ref<any>(null)

// 列定义
const columns = [
  { prop: 'id', label: 'ID', width: 70 },
  { prop: 'optionKey', label: '配置键', minWidth: 180, slot: true },
  { prop: 'optionValue', label: '配置值', minWidth: 260, slot: true }
]

// 搜索项
const searchItems = [
  { prop: 'optionKey', label: '配置键' }
]

/* ===== 新增/编辑弹窗 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

// 表单默认值工厂函数
const defaultForm = (): SysOption => ({
  optionKey: '',
  optionValue: ''
})

const form = reactive<SysOption>(defaultForm())

// 表单校验规则
const rules: FormRules = {
  optionKey: [
    { required: true, message: '请输入配置键', trigger: 'blur' },
    { pattern: /^[a-zA-Z][a-zA-Z0-9_]*$/, message: '仅支持字母开头，含字母/数字/下划线', trigger: 'blur' }
  ],
  optionValue: [
    { required: true, message: '请输入配置值', trigger: 'blur' }
  ]
}

/** 打开新增弹窗 */
function openAdd() {
  isEdit.value = false
  Object.assign(form, defaultForm())
  dialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEdit(row: SysOption) {
  isEdit.value = true
  Object.assign(form, defaultForm(), row)
  dialogVisible.value = true
}

/** 弹窗关闭时重置表单 */
function resetForm() {
  formRef.value?.resetFields()
  Object.assign(form, defaultForm())
}

/** 提交表单（新增或编辑） */
async function submit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateOption(form)
      ElMessage.success('修改成功')
    } else {
      await addOption(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    crudRef.value?.reload()
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.option-key {
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 12px;
  font-family: 'Courier New', Courier, monospace;
}

.option-value {
  font-size: 13px;
  word-break: break-all;
}
</style>
