<!--
  AssignRoleDialog - 分配角色弹窗
  -------------------------------
  基于 el-checkbox-group 展示全部角色，默认勾选用户已有角色，
  确认后全量覆盖 POST /system/user/assignRoles。
  参照 NodeAdmin 风格，适配 管理端 JWT 认证与 ResultVO 响应格式。
-->
<template>
  <el-dialog
    :model-value="visible"
    title="分配角色"
    width="480px"
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
        加载中...
      </p>
    </div>

    <!-- 角色列表 -->
    <el-checkbox-group
      v-else
      v-model="checkedRoleIds"
      class="role-list"
    >
      <el-checkbox
        v-for="role in allRoles"
        :key="String(role.id)"
        :label="role.id"
        :value="role.id"
        class="role-item"
      >
        <!-- 角色名称 -->
        <span class="role-name">{{ role.name }}</span>
        <!-- 角色编码 -->
        <span class="role-code">({{ role.code }})</span>
      </el-checkbox>
    </el-checkbox-group>

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
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { getRoleList } from '@/api/system/role'
import type { SysRole } from '@/api/system/role'
import { getUserById, assignUserRoles } from '@/api/system/user'

/**
 * Props 定义
 */
interface Props {
  /** 弹窗显隐 */
  visible: boolean
  /** 目标用户 ID */
  userId: number | string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'done'): void
}>()

/** 全部角色列表 */
const allRoles = ref<SysRole[]>([])
/** 已勾选的角色 ID 列表 */
const checkedRoleIds = ref<(number | string)[]>([])
/** 加载状态 */
const loading = ref(false)
/** 提交状态 */
const submitting = ref(false)

/**
 * 弹窗打开时加载数据：
 * 1. 全部角色列表
 * 2. 用户当前已有的角色 ID
 */
async function onOpen(): Promise<void> {
  if (!props.userId) return
  loading.value = true
  try {
    // 并行加载角色列表与用户详情
    const [rolesRes, userRes] = await Promise.all([getRoleList({ page: 1, size: 1000 }), getUserById(props.userId)])
    // 角色列表来自分页响应
    allRoles.value = rolesRes.data?.list || []
    // 用户详情中的 roles 字段包含当前角色
    const user = userRes.data as any
    checkedRoleIds.value = (user?.roles || []).map((r: any) => r.id)
  } catch {
    ElMessage.error('加载角色数据失败')
  } finally {
    loading.value = false
  }
}

/**
 * 提交角色分配（全量覆盖）
 */
async function handleSubmit(): Promise<void> {
  submitting.value = true
  try {
    await assignUserRoles(props.userId, checkedRoleIds.value)
    ElMessage.success('角色分配成功')
    emit('update:visible', false)
    emit('done')
  } catch (err: unknown) {
    // 提取错误信息（request.ts 拦截器已弹过提示，此处兜底）
    const msg = (err as Error)?.message || '分配失败'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.role-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 360px;
  overflow-y: auto;
  padding: 8px 0;
}

.role-item {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  transition: background 0.15s;
}

.role-item:hover {
  background: var(--el-fill-color-light);
}

.role-name {
  font-weight: 500;
  color: var(--el-text-color-primary);
  margin-right: 6px;
}

.role-code {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
