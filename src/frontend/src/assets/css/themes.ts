/**
 * 主题预设库（每个主题都有独特色调 + header/sidebar 配色风格）
 *
 * 两个独立维度组合：
 * 1. 主题色（5 套）：indigo/emerald/rose/amber/slate
 *    - 决定 primary + success/warning/danger/info
 *    - 决定按钮/标签/输入框焦点/分页/表格选中 等组件颜色
 *
 * 2. 布局风格（2 套）：dark（暗色）/ light（明色）
 *    - 决定 header/sidebar 背景色、文字色、菜单色
 *    - dark 模式：深蓝灰背景 + 白字（默认）
 *    - light 模式：白色背景 + 深灰字
 *
 * 设计原则：每个主题都有自己的"性格"，不仅是 primary 主色不同，
 * 4 个功能色（success/warning/danger/info）都要从主色色相出发，
 * 做【相邻色相 + 中等饱和度】的微调，而不是跨色系跳跃：
 * - 色相偏移控制在 ±60° 以内（同色系感）
 * - 饱和度控制在 60~80%（避免过艳或过素）
 * - 明度控制在 45~65%（中等明度，不刺眼不沉闷）
 *
 * 5 套主题色彩性格：
 * - indigo  靛蓝紫  | 优雅冷静  | 全功能色标准对比
 * - emerald 翡翠绿  | 清新自然  | success 同系翠绿、warning 暖橙
 * - rose    玫瑰红  | 温暖活力  | success 蓝绿、warning 暖橙
 * - amber   琥珀橙  | 阳光商务  | success 墨绿、warning 明黄
 * - slate   石板蓝  | 低调内敛  | success 蓝绿、warning 冷橙
 *
 * 2 套布局风格：
 * - dark：header/sidebar 深色背景（#0f172a / #1e293b），文字白色
 * - light：header/sidebar 白色背景，文字深灰（#334155），菜单 hover 浅灰
 *
 * 主色和功能色都不能撞色。每个主题的 4 个功能色都从主色色相出发调整：
 * - 同一色相明度变化（明亮变体）
 * - 相邻色相偏移（避免撞色但保持协调）
 *
 * 主题应用原理：通过 JS 设置 document.documentElement.style 的 CSS 变量
 * 覆盖 variables.css 中的默认主题色，Element Plus 组件自动跟随。
 *
 * 新增主题只需在此处添加一项，无需修改 variables.css 或 main.css。
 */

/**
 * 布局风格：决定 header/sidebar 的明暗
 * - dark  深色：白字 + 深背景
 * - light 明色：深字 + 浅背景
 */
export type LayoutMode = 'dark' | 'light' | 'navy' | 'tinted'

/**
 * 单个主题的 header/sidebar 配色风格
 * 与主题色独立，可独立切换
 */
export interface LayoutColors {
  /** header 背景色 */
  headerBg: string
  /** header 文字色（标题、按钮） */
  headerText: string
  /** header 边框色（用于阴影/分割线） */
  headerBorder: string
  /** sidebar 背景色 */
  sidebarBg: string
  /** sidebar 文字色（菜单项默认） */
  sidebarText: string
  /** sidebar 激活态文字色 */
  sidebarTextActive: string
  /** sidebar 边框色（菜单项之间） */
  sidebarBorder: string
  /** sidebar hover 背景（基于主色的半透明） */
  hoverBg: string
  /** sidebar active 背景（基于主色的半透明） */
  activeBg: string
}

/**
 * 主题色板（决定 Element Plus 组件颜色）
 */
export interface ThemePalette {
  /** primary 主色（Hex） */
  primary: string
  /** primary 悬停色 */
  primaryHover: string
  /** primary 按下色 */
  primaryActive: string
  /** primary 浅色（light-8，用作浅背景） */
  primaryLight8: string
  /** primary 更浅色（light-9，用作更浅背景） */
  primaryLight9: string
  /** success 主色（与 primary 必须有明显区分度） */
  success: string
  /** warning 主色（与 primary 必须有明显区分度） */
  warning: string
  /** danger 主色（与 primary 必须有明显区分度） */
  danger: string
  /** info 主色 */
  info: string
}

/**
 * 完整主题：色板 + header/sidebar 配色风格（4 种布局）
 */
export interface Theme {
  /** 主题唯一标识（存数据库 site_theme 字段） */
  id: string
  /** 主题中文名 */
  name: string
  /** 主题描述（一句话） */
  description: string
  /** 主题色板 */
  palette: ThemePalette
  /** 预览用主色（与 palette.primary 相同，单独暴露便于选择器 UI） */
  preview: string
  /** 预览副色（用 primaryLight8） */
  previewSoft: string
  /** 暗色 header/sidebar 配色（深蓝灰） */
  layoutDark: LayoutColors
  /** 亮色 header/sidebar 配色（白色） */
  layoutLight: LayoutColors
  /** 深蓝 header/sidebar 配色（纯深蓝，商务感） */
  layoutNavy: LayoutColors
  /** 主题色渲染 header/sidebar 配色（带主题色浅底） */
  layoutTinted: LayoutColors
}

/**
 * 构造通用的 hover/active 背景色（基于主色 + 透明度）
 * 注意：这两个值依赖主色，不能放在 LayoutColors 静态字段里
 */
function hoverBg(primary: string): string {
  return hexToRgba(primary, 0.08)
}
function activeBg(primary: string): string {
  return hexToRgba(primary, 0.15)
}

/** hex → rgba 工具（用于 hover/active 透明度背景） */
function hexToRgba(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/**
 * 全部主题预设列表
 * 顺序即站点设置页面的展示顺序
 */
export const THEMES: Theme[] = [
  {
    /* 靛蓝紫：优雅冷静，紫蓝色调
     * 功能色全部用 Tailwind 500 标配色，与紫色形成清晰对比 */
    id: 'indigo',
    name: '靛蓝紫',
    description: '现代科技风，优雅高级',
    palette: {
      primary: '#6366f1',
      primaryHover: '#818cf8',
      primaryActive: '#4f46e5',
      primaryLight8: '#e0e7ff',
      primaryLight9: '#eef2ff',
      success: '#10b981',
      warning: '#f59e0b',
      danger: '#f43f5e',
      info: '#64748b'
    },
    preview: '#6366f1',
    previewSoft: '#e0e7ff',
    layoutDark: {
      headerBg: '#1e293b',
      headerText: '#f1f5f9',
      headerBorder: 'rgba(255, 255, 255, 0.06)',
      sidebarBg: '#0f172a',
      sidebarText: '#cbd5e1',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#6366f1', 0.08),
      activeBg: hexToRgba('#6366f1', 0.15)
    },
    layoutLight: {
      headerBg: '#ffffff',
      headerText: '#1e293b',
      headerBorder: 'rgba(0, 0, 0, 0.06)',
      sidebarBg: '#ffffff',
      sidebarText: '#475569',
      sidebarTextActive: '#6366f1',
      sidebarBorder: 'rgba(0, 0, 0, 0.06)',
      hoverBg: hexToRgba('#6366f1', 0.08),
      activeBg: hexToRgba('#6366f1', 0.1)
    },
    /* 深蓝商务风：纯深蓝背景，与主题色无关 */
    layoutNavy: {
      headerBg: '#1e3a5f',
      headerText: '#e2e8f0',
      headerBorder: 'rgba(255, 255, 255, 0.08)',
      sidebarBg: '#0c2340',
      sidebarText: '#94a3b8',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#6366f1', 0.1),
      activeBg: hexToRgba('#6366f1', 0.18)
    },
    /* 主题色渲染风：header/sidebar 带主题色浅底 */
    layoutTinted: {
      headerBg: '#eef2ff',
      headerText: '#4f46e5',
      headerBorder: '#e0e7ff',
      sidebarBg: '#eef2ff',
      sidebarText: '#475569',
      sidebarTextActive: '#6366f1',
      sidebarBorder: '#e0e7ff',
      hoverBg: hexToRgba('#6366f1', 0.1),
      activeBg: hexToRgba('#6366f1', 0.15)
    }
  },
  {
    /* 翡翠绿：清新自然，绿色调
     * success 同色系偏亮（emerald-400），warning 暖琥珀 */
    id: 'emerald',
    name: '翡翠绿',
    description: '清新自然，舒适护眼',
    palette: {
      primary: '#10b981',
      primaryHover: '#34d399',
      primaryActive: '#059669',
      primaryLight8: '#d1fae5',
      primaryLight9: '#ecfdf5',
      success: '#34d399',
      warning: '#d97706',
      danger: '#f43f5e',
      info: '#64748b'
    },
    preview: '#10b981',
    previewSoft: '#d1fae5',
    layoutDark: {
      headerBg: '#1e293b',
      headerText: '#f1f5f9',
      headerBorder: 'rgba(255, 255, 255, 0.06)',
      sidebarBg: '#0f172a',
      sidebarText: '#cbd5e1',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#10b981', 0.08),
      activeBg: hexToRgba('#10b981', 0.15)
    },
    layoutLight: {
      headerBg: '#ffffff',
      headerText: '#1e293b',
      headerBorder: 'rgba(0, 0, 0, 0.06)',
      sidebarBg: '#ffffff',
      sidebarText: '#475569',
      sidebarTextActive: '#10b981',
      sidebarBorder: 'rgba(0, 0, 0, 0.06)',
      hoverBg: hexToRgba('#10b981', 0.08),
      activeBg: hexToRgba('#10b981', 0.1)
    },
    /* 深蓝商务风：纯深蓝背景，与主题色无关 */
    layoutNavy: {
      headerBg: '#1e3a5f',
      headerText: '#e2e8f0',
      headerBorder: 'rgba(255, 255, 255, 0.08)',
      sidebarBg: '#0c2340',
      sidebarText: '#94a3b8',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#10b981', 0.1),
      activeBg: hexToRgba('#10b981', 0.18)
    },
    /* 主题色渲染风：header/sidebar 带主题色浅底 */
    layoutTinted: {
      headerBg: '#ecfdf5',
      headerText: '#059669',
      headerBorder: '#d1fae5',
      sidebarBg: '#ecfdf5',
      sidebarText: '#475569',
      sidebarTextActive: '#10b981',
      sidebarBorder: '#d1fae5',
      hoverBg: hexToRgba('#10b981', 0.1),
      activeBg: hexToRgba('#10b981', 0.15)
    }
  },
  {
    /* 玫瑰红：温暖活力，红粉色调
     * success 蓝绿（teal-500）、warning 暖橙（orange-600）、danger 酒红 */
    id: 'rose',
    name: '玫瑰红',
    description: '温暖活力，年轻时尚',
    palette: {
      primary: '#f43f5e',
      primaryHover: '#fb7185',
      primaryActive: '#e11d48',
      primaryLight8: '#fecdd3',
      primaryLight9: '#fff1f2',
      success: '#14b8a6',
      warning: '#ea580c',
      danger: '#be123c',
      info: '#475569'
    },
    preview: '#f43f5e',
    previewSoft: '#fecdd3',
    layoutDark: {
      headerBg: '#1e293b',
      headerText: '#f1f5f9',
      headerBorder: 'rgba(255, 255, 255, 0.06)',
      sidebarBg: '#0f172a',
      sidebarText: '#cbd5e1',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#f43f5e', 0.08),
      activeBg: hexToRgba('#f43f5e', 0.15)
    },
    layoutLight: {
      headerBg: '#ffffff',
      headerText: '#1e293b',
      headerBorder: 'rgba(0, 0, 0, 0.06)',
      sidebarBg: '#ffffff',
      sidebarText: '#475569',
      sidebarTextActive: '#f43f5e',
      sidebarBorder: 'rgba(0, 0, 0, 0.06)',
      hoverBg: hexToRgba('#f43f5e', 0.08),
      activeBg: hexToRgba('#f43f5e', 0.1)
    },
    /* 深蓝商务风：纯深蓝背景，与主题色无关 */
    layoutNavy: {
      headerBg: '#1e3a5f',
      headerText: '#e2e8f0',
      headerBorder: 'rgba(255, 255, 255, 0.08)',
      sidebarBg: '#0c2340',
      sidebarText: '#94a3b8',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#f43f5e', 0.1),
      activeBg: hexToRgba('#f43f5e', 0.18)
    },
    /* 主题色渲染风：header/sidebar 带主题色浅底 */
    layoutTinted: {
      headerBg: '#fff1f2',
      headerText: '#e11d48',
      headerBorder: '#fecdd3',
      sidebarBg: '#fff1f2',
      sidebarText: '#475569',
      sidebarTextActive: '#f43f5e',
      sidebarBorder: '#fecdd3',
      hoverBg: hexToRgba('#f43f5e', 0.1),
      activeBg: hexToRgba('#f43f5e', 0.15)
    }
  },
  {
    /* 琥珀橙：阳光商务，橙黄色调
     * warning 用深琥珀（amber-600）而非明黄，避免撞色 */
    id: 'amber',
    name: '琥珀橙',
    description: '阳光商务，温暖醒目',
    palette: {
      primary: '#f59e0b',
      primaryHover: '#fbbf24',
      primaryActive: '#d97706',
      primaryLight8: '#fef3c7',
      primaryLight9: '#fffbeb',
      success: '#10b981',
      warning: '#d97706',
      danger: '#f43f5e',
      info: '#64748b'
    },
    preview: '#f59e0b',
    previewSoft: '#fef3c7',
    layoutDark: {
      headerBg: '#1e293b',
      headerText: '#f1f5f9',
      headerBorder: 'rgba(255, 255, 255, 0.06)',
      sidebarBg: '#0f172a',
      sidebarText: '#cbd5e1',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#f59e0b', 0.08),
      activeBg: hexToRgba('#f59e0b', 0.15)
    },
    layoutLight: {
      headerBg: '#ffffff',
      headerText: '#1e293b',
      headerBorder: 'rgba(0, 0, 0, 0.06)',
      sidebarBg: '#ffffff',
      sidebarText: '#475569',
      sidebarTextActive: '#f59e0b',
      sidebarBorder: 'rgba(0, 0, 0, 0.06)',
      hoverBg: hexToRgba('#f59e0b', 0.08),
      activeBg: hexToRgba('#f59e0b', 0.1)
    },
    /* 深蓝商务风：纯深蓝背景，与主题色无关 */
    layoutNavy: {
      headerBg: '#1e3a5f',
      headerText: '#e2e8f0',
      headerBorder: 'rgba(255, 255, 255, 0.08)',
      sidebarBg: '#0c2340',
      sidebarText: '#94a3b8',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#f59e0b', 0.1),
      activeBg: hexToRgba('#f59e0b', 0.18)
    },
    /* 主题色渲染风：header/sidebar 带主题色浅底 */
    layoutTinted: {
      headerBg: '#fffbeb',
      headerText: '#d97706',
      headerBorder: '#fef3c7',
      sidebarBg: '#fffbeb',
      sidebarText: '#475569',
      sidebarTextActive: '#f59e0b',
      sidebarBorder: '#fef3c7',
      hoverBg: hexToRgba('#f59e0b', 0.1),
      activeBg: hexToRgba('#f59e0b', 0.15)
    }
  },
  {
    /* 石板蓝：低调内敛，蓝灰色调
     * danger 用冷玫红（rose-500）而非纯红，更克制 */
    id: 'slate',
    name: '石板蓝',
    description: '低调内敛，商务专业',
    palette: {
      primary: '#475569',
      primaryHover: '#64748b',
      primaryActive: '#334155',
      primaryLight8: '#e2e8f0',
      primaryLight9: '#f1f5f9',
      success: '#14b8a6',
      warning: '#ea580c',
      danger: '#f43f5e',
      info: '#64748b'
    },
    preview: '#475569',
    previewSoft: '#e2e8f0',
    layoutDark: {
      headerBg: '#1e293b',
      headerText: '#f1f5f9',
      headerBorder: 'rgba(255, 255, 255, 0.06)',
      sidebarBg: '#0f172a',
      sidebarText: '#cbd5e1',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#475569', 0.12),
      activeBg: hexToRgba('#475569', 0.22)
    },
    layoutLight: {
      headerBg: '#ffffff',
      headerText: '#1e293b',
      headerBorder: 'rgba(0, 0, 0, 0.06)',
      sidebarBg: '#f8fafc',
      sidebarText: '#475569',
      sidebarTextActive: '#475569',
      sidebarBorder: 'rgba(0, 0, 0, 0.06)',
      hoverBg: hexToRgba('#475569', 0.08),
      activeBg: hexToRgba('#475569', 0.14)
    },
    /* 深蓝商务风：纯深蓝背景，与主题色无关 */
    layoutNavy: {
      headerBg: '#1e3a5f',
      headerText: '#e2e8f0',
      headerBorder: 'rgba(255, 255, 255, 0.08)',
      sidebarBg: '#0c2340',
      sidebarText: '#94a3b8',
      sidebarTextActive: '#ffffff',
      sidebarBorder: 'rgba(255, 255, 255, 0.06)',
      hoverBg: hexToRgba('#475569', 0.12),
      activeBg: hexToRgba('#475569', 0.22)
    },
    /* 主题色渲染风：header/sidebar 带主题色浅底 */
    layoutTinted: {
      headerBg: '#f1f5f9',
      headerText: '#334155',
      headerBorder: '#e2e8f0',
      sidebarBg: '#f1f5f9',
      sidebarText: '#475569',
      sidebarTextActive: '#475569',
      sidebarBorder: '#e2e8f0',
      hoverBg: hexToRgba('#475569', 0.1),
      activeBg: hexToRgba('#475569', 0.15)
    }
  }
]

/**
 * 根据主题 id 查找主题对象
 * @param id 主题 id（如 'indigo'）
 * @returns 主题对象，未找到时返回默认 indigo 主题
 */
export function findTheme(id: string | null | undefined): Theme {
  if (!id) return THEMES[0]
  return THEMES.find((t) => t.id === id) || THEMES[0]
}

/**
 * 默认主题 id（站点配置缺失时使用）
 */
export const DEFAULT_THEME_ID = 'indigo'
