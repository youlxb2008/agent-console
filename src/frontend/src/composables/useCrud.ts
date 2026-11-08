import { ref, reactive } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import type { ApiResponse, PageResponse } from '@/types/api'

/** 列表查询参数 */
export interface CrudQuery {
  page: number
  size: number
  [key: string]: any
}

/**
 * useCrud 选项
 * 参数形式统一为 (api, options)
 */
export interface UseCrudOptions {
  /** 删除接口（可选） */
  delete?: (id: number | string) => Promise<ApiResponse>
  /** 默认每页条数，默认 10 */
  defaultSize?: number
  /** 默认查询条件 */
  defaultQuery?: Record<string, any>
}

/**
 * CRUD 列表 API 约定
 * 必须接收形如 { page, size, ... } 的查询参数并返回 ApiResponse<PageResponse>
 */
export type CrudListApi<T> = (params: any) => Promise<ApiResponse<PageResponse<T>>>

/**
 * 通用 CRUD 逻辑组合式函数
 * 封装加载、分页、搜索、删除确认等通用能力
 * 参数形式统一为 (api, options)
 *
 * @typeParam T 列表项类型
 * @param api 列表查询接口
 * @param options 配置项
 */
export function useCrud<T = any>(api: CrudListApi<T>, options: UseCrudOptions = {}) {
  /** 加载状态 */
  const loading = ref(false)
  /** 表格数据 */
  const tableData = ref<T[]>([])
  /** 总记录数 */
  const total = ref(0)

  // 分页与查询条件
  const defaultSize = options.defaultSize ?? 10
  const query: CrudQuery = reactive({
    page: 1,
    size: defaultSize,
    ...(options.defaultQuery || {})
  })

  // 保存初始查询条件，用于 reset 时还原
  const initialQuery = { ...query }

  /**
   * 加载列表数据
   */
  async function loadData(): Promise<void> {
    loading.value = true
    try {
      const res = await api({ ...query })
      const page = res.data
      // 兼容后端返回结构：优先 list/count，其次 records/total/totalCount
      tableData.value = (page?.list || (page as any)?.records || []) as T[]
      total.value = (page as any)?.count || page?.total || (page as any)?.totalCount || 0
    } catch (e) {
      // 错误已由请求拦截器统一提示
      tableData.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  /** 翻页 */
  function handlePageChange(page: number): void {
    query.page = page
    loadData()
  }

  /** 调整每页条数 */
  function handleSizeChange(size: number): void {
    query.size = size
    query.page = 1
    loadData()
  }

  /** 搜索（重置到第一页） */
  function handleSearch(): void {
    query.page = 1
    loadData()
  }

  /** 重置查询条件 */
  function handleReset(): void {
    Object.keys(query).forEach((k) => {
      // eslint-disable-next-line no-extra-semi
      ;(query as any)[k] = (initialQuery as any)[k]
    })
    query.page = 1
    loadData()
  }

  /**
   * 删除确认并执行
   * @param row 行数据，需包含 id
   * @param idKey 主键字段名，默认 id
   */
  async function handleDelete(row: any, idKey: string = 'id'): Promise<void> {
    const id = row?.[idKey]
    if (id === undefined || id === null || !options.delete) {
      ElMessage.warning('未配置删除接口或缺少主键')
      return
    }
    try {
      await ElMessageBox.confirm('确定删除该条记录吗？', '提示', {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      })
    } catch {
      // 用户取消
      return
    }
    await options.delete(id)
    ElMessage.success('删除成功')
    // 若当前页删除后为空且非第一页，回退一页
    if (tableData.value.length === 1 && query.page > 1) {
      query.page -= 1
    }
    loadData()
  }

  return {
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
  }
}
