<template>
  <div class="na-page-header">
    <div class="na-page-header__title">
      <h2 class="na-page-header__name">
        {{ title }}
      </h2>
      <p
        v-if="subtitle"
        class="na-page-header__desc"
      >
        {{ subtitle }}
      </p>
    </div>
    <!-- 操作按钮插槽 -->
    <div
      v-if="$slots.default"
      class="na-page-header__actions"
    >
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 页头组件
 * 用于业务页面顶部，展示标题与操作按钮
 */
defineProps<{
  /** 页面标题 */
  title?: string
  /** 副标题/描述 */
  subtitle?: string
}>()
</script>

<style lang="scss" scoped>
.na-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;

  &__name {
    font-size: 18px;
    font-weight: 600;
    color: var(--na-text-primary);
    margin: 0;
  }

  &__desc {
    font-size: 13px;
    color: var(--na-text-secondary);
    margin-top: 4px;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
}
</style>
