<!--
  404 页面不存在
  - 大号 404 数字 + 描述
  - 使用普通 CSS（不用 SCSS）
  - 保留返回首页按钮
-->
<template>
  <div class="error-page">
    <div class="error-code">
      404
    </div>
    <div class="error-title">
      页面不存在
    </div>
    <div class="error-desc">
      您访问的页面可能已被删除或链接错误
    </div>
    <el-button
      type="primary"
      @click="goHome"
    >
      返回首页
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

/** 返回首页 */
function goHome(): void {
  router.push('/')
}
</script>

<style scoped>
.error-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: var(--na-text-primary);
}

.error-code {
  font-size: 96px;
  font-weight: 700;
  color: var(--na-info);
  line-height: 1;
  margin-bottom: 12px;
}

.error-title {
  font-size: 22px;
  margin-bottom: 8px;
}

.error-desc {
  font-size: 14px;
  color: var(--na-text-secondary);
  margin-bottom: 24px;
}
</style>
