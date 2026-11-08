<script setup lang="ts">
import { useRouter } from 'vue-router'

/**
 * 404 友好页（路由兜底）
 * v5 双主题：去掉粉彩残留（--grad-sakura-lavender 已删除），
 * 改用当前主题的 token（面板底 / 钴蓝 accent）
 */

const router = useRouter()

/** 返回概览：回到首页卡片 */
function goHome(): void {
  void router.push({ path: '/' })
}
</script>

<template>
  <div class="nf">
    <div class="nf-card">
      <div class="nf-code">404</div>
      <p class="nf-text">咦，这里空空如也～<br />你访问的页面不存在，或者被悄悄搬走了。</p>
      <button class="nf-btn" type="button" @click="goHome">返回概览</button>
    </div>
  </div>
</template>

<style scoped>
.nf {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 60px 20px;
  min-height: 52vh;
}

.nf-card {
  text-align: center;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 44px 48px;
  box-shadow: var(--shadow-pop);
  max-width: 420px;
  width: 100%;
}

/* 404 大字：mono + 钴蓝，两主题通用 */
.nf-code {
  font-family: var(--font-mono);
  font-size: 64px;
  font-weight: 800;
  line-height: 1.1;
  color: var(--accent);
  letter-spacing: 2px;
  margin-bottom: 12px;
}

.nf-text {
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
  margin: 0 0 24px;
}

.nf-btn {
  border: none;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  padding: 10px 26px;
  border-radius: 999px;
  cursor: pointer;
  transition: transform 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
}

.nf-btn:hover {
  background: var(--accent-strong);
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(var(--accent-rgb), 0.25);
}
</style>
