<!--
  通用 CRUD 表格组件
  ------------------
  - 内嵌搜索栏（inline 表单）+ Element Plus 表格 + 分页
  - 通过 props 配置列、搜索项与 api，使业务页只关心数据形状
  - 暴露 slots：
      actions          —— 操作列内容，会得到 row / $index 等作用域
      column-{prop}    —— 自定义某列的渲染（不用 col-{prop}）
      search-{prop}    —— 自定义某个搜索控件
  - 暴露事件：add / selection-change
  - 支持 selectable prop 支持多选列
  - 保留 管理端 的内置删除确认功能
-->
<template>
  <div class="crud-table">
    <!-- 搜索栏：仅当存在 searchItems 或 showAddButton 时渲染 -->
    <div
      v-if="searchItems?.length || showAddButton"
      class="crud-toolbar"
    >
      <el-form
        v-if="searchItems?.length"
        :inline="true"
        :model="query"
        class="crud-search"
      >
        <el-form-item
          v-for="item in searchItems"
          :key="item.prop"
          :label="item.label"
        >
          <!-- 优先使用具名 slot 自定义搜索控件 -->
          <slot
            :name="`search-${item.prop}`"
            :query="query"
            :item="item"
          >
            <!-- 输入框 -->
            <el-input
              v-if="!item.type || item.type === 'input'"
              v-model="(query as Record<string, any>)[item.prop]"
              :placeholder="item.placeholder || `请输入${item.label}`"
              clearable
              style="width: 200px"
              @keyup.enter="handleSearch"
            />
            <!-- 下拉框 -->
            <el-select
              v-else-if="item.type === 'select'"
              v-model="(query as Record<string, any>)[item.prop]"
              :placeholder="item.placeholder || `请选择${item.label}`"
              clearable
              style="width: 180px"
            >
              <el-option
                v-for="opt in item.options || []"
                :key="String(opt.value)"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <!-- 日期选择 -->
            <el-date-picker
              v-else-if="item.type === 'date'"
              v-model="(query as Record<string, any>)[item.prop]"
              type="date"
              :placeholder="item.placeholder || `请选择${item.label}`"
              value-format="YYYY-MM-DD"
              style="width: 180px"
            />
          </slot>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            @click="handleSearch"
          >
            查询
          </el-button>
          <el-button @click="handleResetAll">
            重置
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 右侧操作区：新增按钮 + 自定义工具栏 slot -->
      <div class="crud-toolbar-right">
        <slot name="toolbar" />
        <!-- 导出按钮：业务页通过 @export 事件自行调用 exportToExcel -->
        <el-button
          v-if="showExportButton"
          :icon="Download"
          @click="emit('export')"
        >
          导出
        </el-button>
        <el-button
          v-if="showAddButton"
          type="primary"
          @click="emit('add')"
        >
          {{ addButtonText }}
        </el-button>
      </div>
    </div>

    <!-- 数据表格 -->
    <el-table
      v-loading="loading"
      :data="tableData"
      :row-key="rowKey"
      :row-class-name="rowClassName"
      stripe
      border
      style="width: 100%"
      @selection-change="onSelectionChange"
    >
      <!-- 多选列（仅当 selectable=true 时出现） -->
      <el-table-column
        v-if="selectable"
        type="selection"
        width="46"
      />

      <!-- 序号列 -->
      <el-table-column
        v-if="showIndex"
        type="index"
        label="序号"
        width="60"
        align="center"
      />

      <!-- 业务列 -->
      <template
        v-for="col in columns"
        :key="col.prop"
      >
        <el-table-column
          :prop="col.prop"
          :label="col.label"
          :width="col.width"
          :min-width="col.minWidth"
          :align="col.align || 'center'"
          :show-overflow-tooltip="col.showOverflowTooltip !== false"
          :fixed="col.fixed"
        >
          <template #default="scope">
            <!-- 自定义列 slot 优先，命名改为 column-{prop} -->
            <slot
              v-if="$slots[`column-${col.prop}`]"
              :name="`column-${col.prop}`"
              :row="scope.row"
              :index="scope.$index"
            />
            <!-- 默认渲染原始字段 -->
            <template v-else>
              {{ scope.row[col.prop as string] }}
            </template>
          </template>
        </el-table-column>
      </template>

      <!-- 操作列：仅当存在 actions slot 时渲染 -->
      <el-table-column
        v-if="$slots.actions"
        label="操作"
        :width="actionWidth"
        :fixed="actionFixed"
        align="center"
      >
        <template #default="scope">
          <slot
            name="actions"
            :row="scope.row"
            :index="scope.$index"
          />
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="crud-pagination">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="pageSizes"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts" generic="T extends Record<string, any>">
import { onMounted, watch } from 'vue'
import { Download } from '@element-plus/icons-vue'
import { useCrud } from '@/composables/useCrud'
import type { ApiResponse, PageResponse } from '@/types/api'

/** 列定义 */
export interface CrudColumn {
  prop: string
  label: string
  width?: string | number
  minWidth?: string | number
  fixed?: 'left' | 'right' | boolean
  align?: 'left' | 'center' | 'right'
  /** 是否使用具名插槽自定义列内容 */
  slot?: boolean
  /** 是否显示溢出提示，默认 true */
  showOverflowTooltip?: boolean
}

/** 搜索项定义 */
export interface CrudSearchItem {
  prop: string
  label: string
  type?: 'input' | 'select' | 'date'
  placeholder?: string
  clearable?: boolean
  options?: { label: string; value: any }[]
}

/** API 配置 */
export interface CrudApi<T> {
  list: (query: any) => Promise<ApiResponse<PageResponse<T>>>
  delete?: (id: number | string) => Promise<ApiResponse>
}

/** Props 定义 */
const props = withDefaults(
  defineProps<{
    /** 列配置 */
    columns: CrudColumn[]
    /** 搜索项配置 */
    searchItems?: CrudSearchItem[]
    /** 列表/删除接口 */
    api: CrudApi<T>
    /** 行主键字段名 */
    rowKey?: string
    /** 是否显示序号列 */
    showIndex?: boolean
    /**
     * 是否显示 CrudTable 内置的"新增"按钮
     * 默认 false — 业务页通常通过 toolbar slot 自定义按钮（含图标、文案、权限等）
     * 若业务页只想要一个简单的"新增"按钮，可设为 true 使用默认行为
     */
    showAddButton?: boolean
    /** 新增按钮文案 */
    addButtonText?: string
    /** 是否显示导出按钮（业务页通过 @export 监听并自行调用 exportToExcel） */
    showExportButton?: boolean
    /** 操作列宽度 */
    actionWidth?: number | string
    /** 操作列固定 */
    actionFixed?: boolean | 'left' | 'right'
    /** 是否多选 */
    selectable?: boolean
    /** 分页可选每页条数 */
    pageSizes?: number[]
    /** 默认每页条数 */
    defaultSize?: number
    /** 默认查询条件 */
    defaultQuery?: Record<string, any>
    /**
     * 行类名（透传 el-table row-class-name；用于按行数据高亮，如 alert 消息浅红底）。
     * undefined 时行为与原先完全一致（不带 row-class-name）。
     */
    rowClassName?: string | ((data: { row: any; rowIndex: number }) => string)
  }>(),
  {
    rowKey: 'id',
    showIndex: true,
    showAddButton: false,
    addButtonText: '新增',
    showExportButton: false,
    actionWidth: 180,
    actionFixed: 'right',
    selectable: false,
    pageSizes: () => [10, 20, 50, 100],
    defaultSize: 10,
    // 可选 prop 的默认值，避免 vue/require-default-prop 告警
    searchItems: () => [],
    defaultQuery: () => ({})
  }
)
/** 事件定义 */
const emit = defineEmits<{
  /** 点击新增按钮 */
  (e: 'add'): void
  /** 点击导出按钮 */
  (e: 'export'): void
  /** 选择行变化 */
  (e: 'selection-change', rows: T[]): void
}>()

// 使用 useCrud 组合式函数承担列表加载、分页、搜索
const {
  loading,
  tableData,
  total,
  query,
  loadData,
  handlePageChange,
  handleSizeChange,
  handleSearch,
  handleReset,
  handleDelete
} = useCrud<T>(props.api.list, {
  delete: props.api.delete,
  defaultSize: props.defaultSize,
  defaultQuery: props.defaultQuery
})

// 将业务的搜索项字段动态挂到 query 上，避免「未定义字段」响应性问题
for (const item of props.searchItems || []) {
  if (!(item.prop in query)) {
    // eslint-disable-next-line no-extra-semi
    ;(query as Record<string, any>)[item.prop] = ''
  }
}

/**
 * 重置：清空所有搜索项并重新加载
 * 在 useCrud.handleReset 基础上额外清空业务字段
 */
function handleResetAll(): void {
  for (const item of props.searchItems || []) {
    // eslint-disable-next-line no-extra-semi
    ;(query as Record<string, any>)[item.prop] = ''
  }
  handleReset()
}

/** 多选变化转发（使用 any[] 兼容 Element Plus 内部 UnwrapRef 类型） */
function onSelectionChange(rows: any[]): void {
  emit('selection-change', rows as T[])
}

// 监听 api.list 引用变化（如外部切换 api），重置后重新加载
watch(
  () => props.api.list,
  () => {
    query.page = 1
    loadData()
  }
)

// 暴露给父组件
defineExpose({
  /** 重新加载当前页（增删改后刷新用） */
  reload: loadData,
  /** 跳到第一页重新加载 */
  reset: handleResetAll,
  /** 搜索 */
  handleSearch,
  /** 删除确认 */
  handleDelete,
  /** 暴露 query 用于业务页定制 */
  query,
  /** 表格数据 */
  tableData
})

// 首次挂载触发一次查询
onMounted(() => {
  loadData()
})
</script>

<style scoped>
.crud-table {
  background: var(--na-bg-container);
  padding: 16px;
  border-radius: 6px;
  border: 1px solid var(--na-border-lighter);
}

.crud-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  margin-bottom: 12px;
  gap: 8px;
}

.crud-search :deep(.el-form-item) {
  margin-bottom: 8px;
  margin-right: 12px;
}

.crud-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.crud-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
