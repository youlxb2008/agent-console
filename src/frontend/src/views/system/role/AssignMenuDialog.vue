<!--
  AssignMenuDialog - 分配菜单弹窗
  -------------------------------
  el-dialog + el-tree（show-checkbox），加载全量菜单树，
  默认勾选角色已有菜单，树节点独立勾选（check-strictly），
  确认时只提交当前勾选节点。
  参照 NodeAdmin 风格，适配 管理端 JWT 认证与 ResultVO 响应格式。
-->
<template>
  <el-dialog
    :model-value="visible"
    title="分配菜单权限"
    width="500px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:visible', $event)"
    @open="onOpen"
  >
    <!-- 加载中 -->
    <div
      v-if="loading"
      style="text-align: center; padding: 40px 0"
    >
      <el-icon
        class="is-loading"
        :size="24"
      >
        <Loading />
      </el-icon>
      <p style="color: var(--el-text-color-secondary); margin-top: 8px">
        加载菜单树...
      </p>
    </div>

    <!-- 菜单树 -->
    <el-tree
      v-show="!loading"
      ref="treeRef"
      :data="menuTree"
      node-key="id"
      show-checkbox
      check-strictly
      default-expand-all
      :props="{ label: 'name', children: 'children' }"
      class="menu-tree"
    />

    <template #footer>
      <el-button @click="$emit('update:visible', false)">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        @click="handleSubmit"
      >
        确认
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { getMenuTree } from '@/api/system/menu'
import type { MenuNode } from '@/store/menu'
import { getRoleDetail, assignRoleMenus } from '@/api/system/role'

/**
 * Props 定义
 */
interface Props {
  /** 弹窗显隐 */
  visible: boolean
  /** 目标角色 ID */
  roleId: number | string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'done'): void
}>()

/** el-tree 组件引用 */
const treeRef = ref()
/** 菜单树数据 */
const menuTree = ref<MenuNode[]>([])
/** 角色已有菜单 ID 列表 */
const checkedMenuIds = ref<(number | string)[]>([])
/** 加载状态 */
const loading = ref(false)
/** 提交状态 */
const submitting = ref(false)

/**
 * 弹窗打开时：
 * 1. 拉取全量菜单树
 * 2. 拉取角色详情（含已分配菜单 IDs）
 */
async function onOpen(): Promise<void> {
  if (!props.roleId) return
  loading.value = true
  try {
    // 并行加载菜单树与角色详情
    const [treeRes, roleRes] = await Promise.all([getMenuTree(), getRoleDetail(props.roleId)])
    menuTree.value = treeRes.data || []
    // 角色详情中的 menuIds 字段包含已分配菜单 ID
    const role = roleRes.data as any
    checkedMenuIds.value = role?.menuIds || []
  } catch {
    ElMessage.error('加载菜单数据失败')
  } finally {
    loading.value = false
  }
  // el-tree 的 default-checked-keys 仅初始化时生效，弹窗复用时需要主动同步勾选状态
  await nextTick()
  treeRef.value?.setCheckedKeys(checkedMenuIds.value, false)
}

/**
 * 提交菜单分配
 * 树节点启用 check-strictly 后，父子节点互不联动，直接提交当前勾选节点即可。
 */
async function handleSubmit(): Promise<void> {
  if (!treeRef.value) return
  submitting.value = true
  try {
    // 获取当前勾选的节点 key 列表（不含半选节点）
    const menuIds = treeRef.value.getCheckedKeys(false) as (number | string)[]
    await assignRoleMenus(props.roleId, menuIds)
    ElMessage.success('菜单分配成功')
    emit('update:visible', false)
    emit('done')
  } catch (err: unknown) {
    const msg = (err as Error)?.message || '分配失败'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.menu-tree {
  max-height: 420px;
  overflow-y: auto;
  padding: 8px 0;
}
</style>
