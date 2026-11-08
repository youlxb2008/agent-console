<!--
  菜单管理
  --------
  参照 NodeAdmin 风格改造：
  - 新增/编辑弹窗增加 hidden（是否隐藏）和 status（启用/禁用）开关
  - IconPicker 选择后显示图标预览行
  - MenuTreeSelect 支持 excludeId 排除自身
  - 默认只展开到第 2 级（不用 default-expand-all）
  - CSS 使用普通 CSS（不用 SCSS）
-->
<template>
  <div class="na-page">
    <PageHeader title="菜单管理" subtitle="维护系统菜单、目录与按钮权限" />

    <div class="na-card">
      <div class="menu-toolbar">
        <el-button v-permiss="'system:menu'" type="primary" :icon="Plus" @click="openAdd()"> 新增菜单 </el-button>
        <el-button :icon="Refresh" @click="loadData"> 刷新 </el-button>
      </div>

      <!-- 树形表格 -->
      <el-table
        v-loading="loading"
        :data="tableData"
        row-key="id"
        border
        :tree-props="{ children: 'children' }"
        :default-expand-all="false"
        :expand-row-keys="expandedIds"
      >
        <el-table-column label="菜单名称" prop="name" min-width="200">
          <template #default="{ row }">
            <Icon v-if="row.icon" :icon="row.icon" class="menu-icon" />
            <span>{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.type)" size="small">
              {{ typeText(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权限标识" prop="perms" min-width="140" />
        <el-table-column label="路由路径" prop="path" min-width="140" />
        <el-table-column label="排序" prop="sort" width="70" align="center" />
        <el-table-column label="隐藏" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.hidden" size="small" type="warning"> 隐藏 </el-tag>
            <span v-else style="color: var(--el-text-color-secondary)">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 || row.status === undefined ? 'success' : 'danger'" size="small">
              {{ row.status === 1 || row.status === undefined ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-permiss="'system:menu'" link type="primary" :icon="Plus" @click="openAdd(row.id)">
              新增
            </el-button>
            <el-button v-permiss="'system:menu'" link type="primary" :icon="Edit" @click="openEdit(row as any)">
              编辑
            </el-button>
            <el-button v-permiss="'system:menu'" link type="danger" :icon="Delete" @click="handleDelete(row as any)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑菜单' : '新增菜单'" width="620px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="上级菜单">
          <!-- 编辑时排除自身节点，防止循环父级 -->
          <MenuTreeSelect v-model="form.parentId" :exclude-id="isEdit ? editingId : undefined" />
        </el-form-item>
        <el-form-item label="菜单类型" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio :value="1"> 目录 </el-radio>
            <el-radio :value="2"> 菜单 </el-radio>
            <el-radio :value="3"> 按钮 </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="菜单名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入菜单名称" />
        </el-form-item>
        <!-- 按钮类型不需要图标/路径/组件 -->
        <el-form-item v-if="form.type !== 3" label="菜单图标">
          <IconPicker v-model="form.icon" />
          <!-- 图标预览行 -->
          <div v-if="form.icon" class="form-tip">
            预览：<Icon :icon="form.icon" style="font-size: 18px; vertical-align: middle" />
          </div>
        </el-form-item>
        <el-form-item v-if="form.type !== 3" label="路由路径" prop="path">
          <el-input v-model="form.path" placeholder="如 /system/user 或 user" />
        </el-form-item>
        <el-form-item v-if="form.type === 2" label="组件路径" prop="component">
          <el-input v-model="form.component" placeholder="如 system/user/index" />
        </el-form-item>
        <el-form-item v-if="form.type !== 1" label="权限标识" prop="perms">
          <el-input v-model="form.perms" placeholder="如 system:user:add" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" controls-position="right" />
        </el-form-item>
        <!-- 隐藏开关 -->
        <el-form-item label="是否隐藏">
          <el-switch v-model="form.hidden" />
        </el-form-item>
        <!-- 启用/禁用开关 -->
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
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
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Edit, Delete, Refresh } from '@element-plus/icons-vue'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import IconPicker from '@/components/IconPicker.vue'
import MenuTreeSelect from '@/components/MenuTreeSelect.vue'
import { getMenuTree, addMenu, updateMenu, deleteMenu } from '@/api/system/menu'
import type { MenuNode } from '@/store/menu'

const loading = ref(false)
const tableData = ref<MenuNode[]>([])

/** 默认展开到第 2 级的节点 ID 列表（expand-row-keys 要求 string[]） */
const expandedIds = ref<string[]>([])

/** 菜单类型文本 */
function typeText(type?: number) {
  return { 1: '目录', 2: '菜单', 3: '按钮' }[type as number] || '菜单'
}
/** 菜单类型标签颜色 */
function typeTag(type?: number): 'primary' | 'success' | 'warning' {
  return ({ 1: 'primary', 2: 'success', 3: 'warning' } as const)[type as number] || 'success'
}

/**
 * 从树数据中收集前 maxLevel 级节点的 ID
 * @param tree 菜单树数据
 * @param maxLevel 最大展开层级
 */
function collectExpandIds(tree: MenuNode[], maxLevel: number): string[] {
  const ids: string[] = []
  function walk(nodes: MenuNode[], level: number): void {
    for (const node of nodes) {
      if (level < maxLevel) {
        ids.push(String(node.id))
        if (node.children?.length) {
          walk(node.children, level + 1)
        }
      }
    }
  }
  walk(tree, 1)
  return ids
}

/** 加载菜单树 */
async function loadData() {
  loading.value = true
  try {
    const res = await getMenuTree()
    tableData.value = res.data || []
    // 默认展开到第 2 级
    expandedIds.value = collectExpandIds(tableData.value, 2)
  } catch (e) {
    tableData.value = []
  } finally {
    loading.value = false
  }
}

/* ===== 新增/编辑弹窗 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const editingId = ref<number | string | undefined>(undefined)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const defaultForm = () => ({
  parentId: null as number | string | null,
  type: 1,
  name: '',
  icon: '',
  path: '',
  component: '',
  perms: '',
  sort: 0,
  hidden: false,
  status: 1
})

const form = reactive(defaultForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择菜单类型', trigger: 'change' }]
}

/** 打开新增弹窗（可指定父级 ID） */
function openAdd(parentId?: number | string) {
  isEdit.value = false
  editingId.value = undefined
  Object.assign(form, defaultForm(), { parentId: parentId ?? null })
  dialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEdit(row: MenuNode) {
  isEdit.value = true
  editingId.value = row.id
  Object.assign(form, defaultForm(), {
    ...row,
    // hidden/status 确保有默认值
    hidden: row.hidden ?? false,
    status: row.status ?? 1
  })
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
      await updateMenu(form)
      ElMessage.success('修改成功')
    } else {
      await addMenu(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

/** 删除菜单（后端级联删除子节点） */
async function handleDelete(row: MenuNode) {
  // 如果有子节点，提示将一并删除
  const hasChildren = row.children && row.children.length > 0
  const tip = hasChildren
    ? `菜单「${row.name}」包含子菜单/按钮，删除后将一并级联删除所有子节点及关联数据，确定删除吗？`
    : `确定删除菜单「${row.name}」吗？`
  try {
    await ElMessageBox.confirm(tip, '警告', { type: 'warning' })
  } catch (e) {
    return
  }
  await deleteMenu(row.id as number)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>

<style scoped>
.menu-icon {
  margin-right: 6px;
  font-size: 16px;
  color: var(--na-primary);
  vertical-align: middle;
}

.menu-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
  gap: 8px;
}

.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
