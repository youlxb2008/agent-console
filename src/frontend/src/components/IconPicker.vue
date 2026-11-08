<!--
  IconPicker - Iconify 图标选择器
  --------------------------------
  - 输入框展示当前已选图标，左侧实时预览
  - 点击展开 popover，里面是图标网格，可搜索过滤
  - 图标候选清单抽离到 IconPicker.preset-icons.ts，避免组件文件过长
  - 选完即关闭 popover
-->
<template>
  <el-popover
    v-model:visible="visible"
    placement="bottom-start"
    :width="380"
    trigger="click"
    popper-class="icon-picker-popover"
  >
    <template #reference>
      <div class="icon-picker-trigger">
        <el-input :model-value="modelValue" :placeholder="placeholder" readonly style="cursor: pointer">
          <template #prefix>
            <!-- 预览当前已选图标，未选时退化为占位图标 -->
            <Icon
              :icon="modelValue || 'ph:image'"
              :style="{
                fontSize: '18px',
                color: modelValue ? 'var(--na-text-primary)' : 'var(--na-text-secondary)'
              }"
            />
          </template>
          <template #suffix>
            <el-icon><ArrowDown /></el-icon>
          </template>
        </el-input>
      </div>
    </template>

    <!-- 弹窗内容：搜索 + 图标网格 -->
    <div class="icon-picker-panel">
      <el-input v-model="filter" placeholder="搜索图标名称（如 user）" clearable size="small" />

      <div class="icon-grid">
        <div
          v-for="icon in filteredIcons"
          :key="icon"
          class="icon-item"
          :class="{ active: icon === modelValue }"
          :title="icon"
          @click="onPick(icon)"
        >
          <Icon :icon="icon" style="font-size: 22px" />
        </div>
        <div v-if="filteredIcons.length === 0" class="icon-empty">未找到匹配图标</div>
      </div>

      <!-- 底部清空按钮 -->
      <div class="icon-picker-footer">
        <el-button size="small" text @click="onPick('')"> 清空 </el-button>
      </div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Icon } from '@iconify/vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { presetIcons } from './IconPicker.preset-icons'

/**
 * Props 定义
 */
interface Props {
  /** v-model 值（如 'ph:user'） */
  modelValue?: string
  /** 占位符 */
  placeholder?: string
  /** 是否允许清除 */
  allowClear?: boolean
}

// 模板中直接使用 modelValue/placeholder 等解构后的 prop，无需在脚本中保留引用
withDefaults(defineProps<Props>(), {
  modelValue: '',
  placeholder: '请选择图标',
  allowClear: true
})

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

/** popover 显隐 */
const visible = ref(false)
/** 搜索关键字 */
const filter = ref('')

/** 根据过滤词得到候选图标 */
const filteredIcons = computed(() => {
  const kw = filter.value.trim().toLowerCase()
  if (!kw) return presetIcons
  return presetIcons.filter((name) => name.toLowerCase().includes(kw))
})

/**
 * 选中图标
 * @param icon 图标标识，传空字符串表示清空
 */
function onPick(icon: string): void {
  emit('update:modelValue', icon)
  // 选完即关闭
  visible.value = false
}
</script>

<style scoped>
.icon-picker-trigger {
  display: inline-block;
  width: 100%;
}

.icon-picker-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.icon-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
  max-height: 240px;
  overflow-y: auto;
  padding: 4px 0;
}

.icon-item {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 4px;
  cursor: pointer;
  color: var(--na-text-primary);
  transition: background 0.15s;
}

.icon-item:hover {
  background: var(--na-bg-hover);
}

.icon-item.active {
  background: var(--el-color-primary-light-8);
  color: var(--na-primary);
}

.icon-empty {
  grid-column: span 8;
  text-align: center;
  font-size: 13px;
  color: var(--na-text-secondary);
  padding: 16px 0;
}

.icon-picker-footer {
  display: flex;
  justify-content: flex-end;
  border-top: 1px solid var(--na-border-lighter);
  padding-top: 4px;
}
</style>
