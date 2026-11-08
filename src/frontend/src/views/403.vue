<!--
  403 无权限页
  - 显示错误码、标题、描述
  - 如果路由 query 中携带 perm 参数，显示缺少的权限码
  - 提供返回上一页和回到首页两个操作
-->
<template>
  <div class="error-page">
    <div class="error-content">
      <h1 class="error-code">
        403
      </h1>
      <p class="error-title">
        访问受限
      </p>
      <p class="error-desc">
        抱歉，您没有权限访问该页面
      </p>
      <p
        v-if="requiredPerm"
        class="error-perm"
      >
        需要权限：<el-tag
          type="danger"
          size="small"
        >
          {{ requiredPerm }}
        </el-tag>
      </p>
      <div class="error-actions">
        <el-button
          type="primary"
          @click="goBack"
        >
          返回上一页
        </el-button>
        <el-button @click="goHome">
          回到首页
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()

/** 从路由 query 中获取缺少的权限码 */
const requiredPerm = ref((route.query.perm as string) || '')

/** 返回上一页 */
function goBack() {
  router.back()
}

/** 回到首页 */
function goHome() {
  router.push('/')
}
</script>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 400px;
}
.error-content {
  text-align: center;
}
.error-code {
  font-size: 120px;
  font-weight: 700;
  color: #e5e7eb;
  margin: 0;
  line-height: 1;
}
.error-title {
  font-size: 20px;
  font-weight: 600;
  color: #374151;
  margin: 16px 0 8px;
}
.error-desc {
  font-size: 14px;
  color: #6b7280;
  margin: 0 0 12px;
}
.error-perm {
  font-size: 13px;
  color: #9ca3af;
  margin: 0 0 24px;
}
.error-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}
</style>
