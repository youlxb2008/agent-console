<!--
  MenuTreeSelect - 父菜单选择器
  ----------------------------
  参照 NodeAdmin 风格改造：
  - 支持 excludeId prop（排除自身节点，编辑时防止循环父级）
  - 对 type=3 的按钮节点设 disabled
  - 构造伪根节点（id=0, name="根节点"）放在树顶部
  - CSS 使用普通 CSS（不用 SCSS）
-->
<template>
  <el-tree-select
    :model-value="modelValue"
    :data="treeData"
    :props="treeProps as any"
    node-key="id"
    check-strictly
    :render-after-expand="false"
    :placeholder="placeholder"
    :disabled="disabled"
    clearable
    filterable
    style="width: 100%"
    @update:model-value="onChange"
  />
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { getMenuTree } from '@/api/system/menu'
import { useMenuStore, type MenuNode } from '@/store/menu'

/**
 * Props 定义
 */
const props = withDefaults(
  defineProps<{
    /** v-model 值（菜单父 ID；0 表示根节点） */
    modelValue?: number | string | null
    /** 需要排除的菜单 ID（编辑时排除自身，防止把自己设为父级） */
    excludeId?: number | string
    /** 占位文本 */
    placeholder?: string
    /** 是否禁用 */
    disabled?: boolean
  }>(),
  {
    modelValue: null,
    excludeId: 0,
    placeholder: '请选择父菜单（不选则为根节点）',
    disabled: false
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: any): void
}>()

const menuStore = useMenuStore()

// el-tree-select 的 :props 配置（label / value / children / disabled）
const treeProps = {
  label: 'name',
  value: 'id',
  children: 'children',
  disabled: isDisabled
}

/**
 * 拼接「根节点」+ 后端菜单树作为下拉数据
 * 若 store 中已有菜单则复用，否则懒加载一次
 */
const treeData = computed<MenuNode[]>(() => {
  // 构造伪根节点便于「选父菜单 = 根」
  const root: MenuNode = {
    id: 0,
    parentId: null,
    name: '根节点',
    path: '',
    component: '',
    icon: '',
    type: 1,
    perms: '',
    sort: 0,
    hidden: false,
    status: 1,
    children: pruneExclude(menuStore.menus, props.excludeId)
  }
  return [root]
})

/**
 * 在树中剔除排除的节点（含其子树）
 * @param nodes 原始节点列表
 * @param excludeId 要剔除的节点 ID（0 表示不剔除）
 */
function pruneExclude(nodes: MenuNode[], excludeId: number | string): MenuNode[] {
  if (!excludeId) return nodes
  return nodes
    .filter((n) => n.id !== excludeId)
    .map((n) => ({ ...n, children: pruneExclude(n.children || [], excludeId) }))
}

/**
 * 树节点是否禁用
 * 按钮类型节点（type=3）不能作父菜单
 */
function isDisabled(data: any): boolean {
  return data?.type === 3
}

/** 转发选择（清空时转为 0 表示根节点） */
function onChange(value: unknown): void {
  emit('update:modelValue', value != null ? value : 0)
}

/**
 * 首次挂载：若菜单 store 为空，则主动拉取菜单树
 * 兜底逻辑，正常登录后已自动写入
 */
onMounted(async () => {
  if (menuStore.menus.length === 0) {
    try {
      const res = await getMenuTree()
      menuStore.setMenus(res.data || [])
    } catch {
      // 静默失败：组件展示为空树，不影响其他功能
    }
  }
})
</script>
