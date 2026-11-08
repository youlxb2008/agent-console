<template>
  <!--
    Markdown 渲染公共组件
    - 统一封装：marked 渲染 + DOMPurify 消毒 + mermaid 异步渲染
    - 用法：<MarkdownView class="xxx" :source="text" />
    - 外部 class 会合并到根 div，便于复用既有 v-html 容器的样式选择器
    - 本组件从 门户（packages/-portal/src/components/MarkdownView.vue）原样照搬
  -->
  <div ref="root" class="markdown-view" v-html="html"></div>
</template>

<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted } from "vue";
import { renderMarkdown, runMermaid } from "../composables/useMarkdown";

const props = defineProps<{
  /** Markdown 源文本 */
  source: string;
}>();

/* 渲染后的安全 HTML（mermaid 代码块此时为占位 div） */
const root = ref<HTMLElement | null>(null);
const html = computed(() => renderMarkdown(props.source));

/**
 * 触发 mermaid 渲染
 * 必须在 v-html 写入 DOM 之后调用，故先等 nextTick
 */
async function triggerMermaid() {
  await nextTick();
  if (root.value) await runMermaid(root.value);
}

/* 挂载首屏 + 源文本变化时重新渲染 mermaid 占位 */
onMounted(triggerMermaid);
watch(() => props.source, triggerMermaid);
</script>

<style scoped>
/* mermaid 图表居中展示，并限制 SVG 不溢出容器 */
.markdown-view :deep(.mermaid) {
  display: flex;
  justify-content: center;
  margin: 12px 0;
  overflow-x: auto;
}
.markdown-view :deep(.mermaid svg) {
  max-width: 100%;
  height: auto;
}
</style>
