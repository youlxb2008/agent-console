<!--
  AgentKeysDialog（v3：agent 列表操作列「Key」弹窗）
  -------------------
  - 锁定模式复用 KeyManagerPanel（只管目标 agent，隐藏选择器）
  - destroy-on-close：关闭即销毁内容，明文/表单状态不残留
  - changed 透传：父级刷新 agent 列表的 keyCount/lastUsedAt 列
  - ✨ M5.4：autoCreate —— 打开时自动弹出生成表单（列表行「生成 Key」直达入口）
-->
<template>
  <el-dialog
    :model-value="visible"
    :title="`${agentName} 的 API Key`"
    width="860px"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <KeyManagerPanel
      :agent-id="agentId"
      :agent-name="agentName"
      :auto-create="autoCreate"
      @changed="emit('changed')"
    />
  </el-dialog>
</template>

<script setup lang="ts">
import KeyManagerPanel from './KeyManagerPanel.vue'

defineProps<{
  visible: boolean
  /** 目标 agent id（锁定模式） */
  agentId: number
  /** 标题回显（displayName 优先） */
  agentName: string
  /** 打开时自动弹出生成表单（M5.4「生成 Key」直达入口） */
  autoCreate?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  changed: []
}>()
</script>
