import * as XLSX from 'xlsx'

/**
 * 通用 Excel 导出工具
 * 支持简单表格数据一键导出
 */

/** 导出列定义 */
interface ExportColumn {
  /** 字段名（对应数据行的 key） */
  key: string
  /** 列标题（表头） */
  title: string
  /** 值格式化函数（可选），用于将原始值转换为展示文本 */
  formatter?: (value: any, row: any) => string | number
}

/**
 * 导出数据为 Excel 文件（.xlsx）
 *
 * @param filename 文件名（不含扩展名，会自动追加 .xlsx）
 * @param columns  列定义（决定表头与每列取值方式）
 * @param data     数据行数组
 *
 * @example
 * ```ts
 * exportToExcel('用户列表', [
 *   { key: 'username', title: '用户名' },
 *   { key: 'status',   title: '状态', formatter: (v) => v === 0 ? '正常' : '禁用' }
 * ], userList)
 * ```
 */
export function exportToExcel(filename: string, columns: ExportColumn[], data: Record<string, any>[]): void {
  // 1. 表头：从列定义中取 title
  const headers = columns.map((c) => c.title)

  // 2. 数据行：按列定义提取字段值，未提供值时填空字符串
  const rows = data.map((row) =>
    columns.map((c) => {
      const val = row[c.key]
      // 有 formatter 时优先使用 formatter 转换，便于格式化枚举/日期等
      return c.formatter ? c.formatter(val, row) : (val ?? '')
    })
  )

  // 3. 组装为 AOA（Array of Array），首行是表头
  const aoa = [headers, ...rows]

  // 4. 创建工作簿并写入数据
  const ws = XLSX.utils.aoa_to_sheet(aoa)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, 'Sheet1')

  // 5. 触发浏览器下载
  XLSX.writeFile(wb, `${filename}.xlsx`)
}
