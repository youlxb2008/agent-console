/**
 * Markdown 渲染 composable
 * 封装 marked 解析 + DOMPurify 消毒，提供安全的 HTML 渲染能力
 *
 * 额外能力：支持 ```mermaid 代码块
 *   - renderMarkdown 阶段把 mermaid 源码输出为 <div class="mermaid"> 占位
 *   - 由 runMermaid 在 DOM 插入后异步替换为 SVG（配合 MarkdownView 组件使用）
 *
 * Markdown 渲染组合式函数（marked + DOMPurify + mermaid 占位）。
 */
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { computed } from 'vue'
import type { Ref } from 'vue'

/* 全局配置 marked：启用 GFM + 换行即 <br>，贴合笔记类输入习惯 */
marked.setOptions({
  /* GitHub Flavored Markdown：支持表格、删除线、任务列表等 */
  gfm: true,
  /* 单换行符转为 <br>，符合编辑器所见即所得 */
  breaks: true,
})

/*
 * 注册 mermaid 代码块自定义渲染
 * - ```mermaid 代码块 → <div class="mermaid">占位（内部保留转义后的图表定义）
 * - 其他代码块返回 false，交回 marked 默认 renderer（带 <pre><code> 高亮结构）
 */
marked.use({
  renderer: {
    code({ text, lang }) {
      /* lang 形如 "mermaid" 或 "mermaid flowchart"，仅匹配首段关键词 */
      if (lang && lang.trim().toLowerCase().startsWith('mermaid')) {
        return `<div class="mermaid">${escapeHtml(text)}</div>`
      }
      /* 返回 false 回退到 marked 默认代码块渲染 */
      return false
    },
  },
})

/** HTML 转义：仅处理会破坏文本节点的字符（& < >），保证 mermaid 源码安全注入 */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/* mermaid 模块懒加载句柄（动态 import 按需载入，避免首屏体积） */
let mermaidPromise: Promise<typeof import('mermaid')['default']> | null = null

/**
 * 懒加载 mermaid 并完成一次性初始化
 * - 使用 default 主题（浅色）
 * - 关闭 startOnLoad，统一由 runMermaid 手动触发
 * 导出给 docx 导出器复用：导出时直接调 mermaid.render 产出 SVG 光栅化为 PNG，
 * 与页面共用同一实例/初始化，避免重复加载
 */
export function getMermaid() {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then((m) => {
      const mermaid = m.default
      mermaid.initialize({ startOnLoad: false, theme: 'default' })
      return mermaid
    })
  }
  return mermaidPromise
}

/**
 * 渲染容器内所有未处理的 mermaid 占位为 SVG
 * 在 MarkdownView 等 DOM 更新后调用；已渲染节点打标避免重复处理
 * @param root 容纳 .mermaid 元素的根节点
 */
export async function runMermaid(root: HTMLElement): Promise<void> {
  /* 选取所有未处理的 mermaid 占位 */
  const nodes = Array.from(
    root.querySelectorAll<HTMLElement>('.mermaid:not([data-processed])'),
  )
  if (nodes.length === 0) return
  /* 先打标，防止并发重复渲染 */
  nodes.forEach((n) => n.setAttribute('data-processed', ''))
  try {
    const mermaid = await getMermaid()
    /* mermaid.run 会读取每个节点的 textContent 作为图表定义并替换为 SVG */
    await mermaid.run({ nodes })
    /* 渲染完成后统一约束默认展示尺寸（防止大图把正文撑得过高过宽） */
    constrainMermaidSize(nodes)
  } catch (e) {
    /* 单个图表语法错误不应阻塞其他图表；mermaid 会在节点内保留错误信息 */
    console.warn('[mermaid] 渲染失败:', e)
  }
}

/* ============ mermaid 默认展示尺寸约束 ============ */
/* 横向图最大宽度占比：内容区宽度的 80%（左右留白，视觉不顶满边） */
const MERMAID_MAX_WIDTH_RATIO = 0.8
/* 纵向图最大高度：视口高度的 70%（正文一屏内基本看全，细节交给点击放大） */
const MERMAID_MAX_HEIGHT_VH = 0.7

/**
 * 约束 mermaid SVG 的默认展示尺寸（按图表原始纵横比分两种情况）：
 * - 横向图（宽 ≥ 高）：宽度最大为内容区 80%，高度按比例自适应；
 *   mermaid 内联的 max-width:{自然宽}px 继续兜底，小图不会被放大。
 * - 纵向图（高 > 宽）：先按「满宽渲染的自然高」判断，超过 70vh 才限高 70vh
 *   （宽度按比例收缩，max-width:100% 兜底极窄容器）；小图维持自然尺寸不放大。
 * 两类图统一块级居中呈现。点击图形仍可通过 ImageViewer 放大查看原始尺寸。
 * @param nodes 本次渲染的 .mermaid 容器列表
 */
function constrainMermaidSize(nodes: HTMLElement[]): void {
  for (const wrap of nodes) {
    const svg = wrap.querySelector('svg')
    /* 渲染失败的占位没有 svg（mermaid 保留了错误文本），跳过 */
    if (!svg) continue
    /* 容器尚未完成布局（clientWidth=0，如隐藏 Tab）时无法判断，保持默认行为 */
    const wrapWidth = wrap.clientWidth
    if (wrapWidth <= 0) continue
    /* viewBox 即图表原始坐标系，用于判定横向/纵向与计算自然高度 */
    const vb = svg.viewBox.baseVal
    if (!vb.width || !vb.height) continue

    /* 统一块级居中（mermaid 默认 inline 展示会偏左） */
    svg.style.display = 'block'
    svg.style.margin = '0 auto'

    if (vb.width >= vb.height) {
      /* 横向图：宽 80% + 高按 viewBox 比例自适应；内联 max-width 保证不超自然尺寸 */
      svg.style.width = `${MERMAID_MAX_WIDTH_RATIO * 100}%`
      svg.style.height = 'auto'
    } else {
      /* 纵向图：满宽渲染的自然高 = 容器宽 × (高/宽)，超过 70vh 才需要限高 */
      const naturalHeight = wrapWidth * (vb.height / vb.width)
      if (naturalHeight > window.innerHeight * MERMAID_MAX_HEIGHT_VH) {
        svg.style.height = `${MERMAID_MAX_HEIGHT_VH * 100}vh`
        svg.style.width = 'auto'
        /* 极窄容器下按比例算出的宽可能溢出，兜底不超出容器 */
        svg.style.maxWidth = '100%'
      }
    }
  }
}

/**
 * 将 Markdown 字符串渲染为安全的 HTML
 * 解析流程：marked 转换 → DOMPurify 过滤危险标签/属性（防 XSS）
 * @param source 原始 Markdown 文本
 * @returns 消毒后的 HTML 字符串；输入为空时返回空串
 */
export function renderMarkdown(source: string): string {
  if (!source || !source.trim()) return ''
  /* marked.parse 同步返回 string（未开启 async） */
  const rawHtml = marked.parse(source) as string
  /* DOMPurify 清洗：移除 <script>/onerror 等危险内容；保留 target 与 class（mermaid 占位需要） */
  return DOMPurify.sanitize(rawHtml, {
    ADD_ATTR: ['target', 'class'],
  })
}

/**
 * 响应式 Markdown 渲染：输入 Ref 变化时自动重新渲染
 * @param source 响应式 Markdown 源文本
 * @returns 响应式 HTML 字符串
 */
export function useMarkdown(source: Ref<string>) {
  const html = computed(() => renderMarkdown(source.value))
  return { html }
}
