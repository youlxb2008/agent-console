<!--
  API Key 管理（v3 壳化）
  -------------------
  - 独立页保留：菜单 /agent/keys（ID 3020）不变
  - 业务逻辑全部迁入 KeyManagerPanel（全局模式），本页仅保留页头与容器
  - 功能：全局筛选 → 生成（即时展示明文）→ 列表（历史 key 点击复制）→ 吊销
-->
<template>
  <div class="na-page">
    <PageHeader
      title="API Key 管理"
      subtitle="全局视角管理全部 agent 的密钥与权限范围（历史密钥可点击复制）"
    />

    <div class="na-card">
      <KeyManagerPanel />
    </div>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import KeyManagerPanel from './KeyManagerPanel.vue'
</script>
