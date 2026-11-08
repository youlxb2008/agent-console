/*
 * docxExporter.ts —— 笔记 Markdown → Word(.docx) 纯前端导出器
 *
 * 为什么在前端做：mermaid 流程图只有浏览器能渲染成 SVG，
 * 后端只有 markdown 源文本；导出时在此处调 mermaid.render 产出 SVG，
 * 再光栅化为 PNG 嵌入 Word，与页面展示效果一致。
 *
 * 整体流程（exportNoteToDocx）：
 *   1. 预处理 markdown（剥 BOM/front matter/首行 h1，与页面渲染口径一致）
 *   2. marked.lexer 切成 token 流
 *   3. 异步遍历 token → docx 块（标题/段落/代码/引用/列表/表格/分隔线/图片/mermaid 图）
 *   4. Document + Packer.toBlob 返回 Blob，由调用方触发浏览器下载
 *
 * 图片嵌入：内嵌 <img> 与 mermaid 图统一走 ImageRun；
 *   - png/jpg/gif/bmp 原始字节直接嵌入
 *   - svg/webp 等先经 <img>+canvas 光栅化成 png 再嵌入
 */
import {
  AlignmentType,
  BorderStyle,
  Document,
  ExternalHyperlink,
  HeadingLevel,
  ImageRun,
  Packer,
  Paragraph,
  ShadingType,
  Table,
  TableCell,
  TableRow,
  TextRun,
  UnderlineType,
  VerticalAlign,
  WidthType,
  type ParagraphChild,
} from 'docx'
import { marked, type Token, type Tokens } from 'marked'
import { getMermaid } from '@/composables/useMarkdown'

/* ============ 导出配色（与门户亮色主题对齐，Word 里观感与页面一致） ============ */
const COLOR_TEXT = '1F2430' // 正文
const COLOR_TITLE = '0F172A' // 大标题/一二级标题
const COLOR_META = '8A94A6' // 元信息行、弱化文字
const COLOR_BORDER = 'D9DEE7' // 表格、代码块边框、分隔线
const COLOR_CODE_TEXT = '24292F' // 代码文字
const COLOR_CODE_BG = 'F6F8FA' // 代码底色（同页面 --code-pre-bg 亮色值）
const COLOR_QUOTE_BAR = 'C3CBD8' // 引用块左侧竖条
const COLOR_LINK = '2563EB' // 超链接（同页面 --accent 亮色值）

/* 正文字体：西文 Calibri，中文微软雅黑；代码用 Consolas */
const BODY_FONT = { ascii: 'Calibri', hAnsi: 'Calibri', eastAsia: 'Microsoft YaHei' }
const CODE_FONT = { ascii: 'Consolas', hAnsi: 'Consolas' }

/** 图片在 A4 正文区的最大显示尺寸（px，96dpi）：正文宽约 600px，高限防止跨页 */
const IMG_MAX_W = 600
const IMG_MAX_H = 700
/** mermaid 光栅化倍率：2x 提升打印清晰度 */
const MERMAID_SCALE = 2

/* ============ 基础类型与工具 ============ */

/** 可直接嵌入 docx 的图片资源（data 为原始/转换后的字节） */
interface ImageAsset {
  type: 'png' | 'jpg' | 'gif' | 'bmp'
  data: Uint8Array
  width: number
  height: number
}

/** 行内文本样式（递归处理 strong/em/del 嵌套时向下传递） */
interface InlineFlags {
  bold?: boolean
  italic?: boolean
  strike?: boolean
  /** 处于超链接内：生成的 TextRun 挂 Hyperlink 字符样式（蓝字下划线） */
  hyperlink?: boolean
}

/** docx 块级元素：段落或表格 */
type DocBlock = Paragraph | Table

/** base64 字符串 → 字节（canvas toDataURL 与 ImageRun.data 的桥接） */
function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64)
  const arr = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i)
  return arr
}

/**
 * 按文件头魔数嗅探图片格式
 * @returns docx 支持的类型；null 表示需要光栅化转换（svg/webp 等）
 */
function sniffImageType(b: Uint8Array): 'png' | 'jpg' | 'gif' | 'bmp' | null {
  if (b.length < 4) return null
  if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'png' // \x89PNG
  if (b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'jpg' // JPEG SOI
  if (b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46) return 'gif' // GIF8
  if (b[0] === 0x42 && b[1] === 0x4d) return 'bmp' // BM
  return null
}

/** 显示尺寸等比缩放，限制在 maxW×maxH 内，至少 1px */
function fitSize(w: number, h: number, maxW: number, maxH: number): { width: number; height: number } {
  if (w <= maxW && h <= maxH) return { width: Math.max(1, w), height: Math.max(1, h) }
  const ratio = Math.min(maxW / w, maxH / h)
  return { width: Math.max(1, Math.round(w * ratio)), height: Math.max(1, Math.round(h * ratio)) }
}

/** 用 Blob URL 解码图片元素（拿 naturalWidth/Height、供 canvas 绘制） */
function decodeImage(blob: Blob): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(blob)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      resolve(img)
    }
    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('image decode failed'))
    }
    img.src = url
  })
}

/**
 * 把任意可解码图片（blob）光栅化成 PNG 字节
 * @param scale 放大倍率（mermaid 用 2x；普通图片 1x）
 */
async function rasterizeBlob(
  blob: Blob,
  scale: number,
): Promise<{ data: Uint8Array; width: number; height: number } | null> {
  try {
    const img = await decodeImage(blob)
    const w = img.naturalWidth || img.width
    const h = img.naturalHeight || img.height
    if (!w || !h) return null
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(w * scale))
    canvas.height = Math.max(1, Math.round(h * scale))
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    // 垫白底：透明 PNG 嵌入 Word 后不透出页面深色底，观感与打印一致
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    const dataUrl = canvas.toDataURL('image/png')
    return { data: base64ToBytes(dataUrl.slice(dataUrl.indexOf(',') + 1)), width: w, height: h }
  } catch {
    return null
  }
}

/* ============ mermaid → PNG ============ */

/** mermaid 图序号（render 的容器 id 需文档内唯一） */
let mermaidSeq = 0

/**
 * mermaid 源码 → PNG 图片资源
 * 复用页面同一 mermaid 实例（getMermaid 懒加载 + default 主题），
 * 渲染失败时抛错由调用方降级为普通代码块展示源码。
 */
async function mermaidToPng(definition: string, index: number): Promise<ImageAsset | null> {
  const mermaid = await getMermaid()
  // 为什么用 %%{init}%% 指令而不是 initialize 覆盖全局配置：
  // 探针实测 mermaid v11 里 initialize({ flowchart: { htmlLabels: false } })
  // 无效（SVG 仍含 foreignObject），只有顶层 htmlLabels 键（v8 遗留键）真正生效。
  // 必须关 htmlLabels 的原因：开启时 mermaid 用 <foreignObject> 包 HTML 标签，
  // SVG 经 <img> 解码后 Chromium 出于安全限制对含 foreignObject 的图污染
  // canvas（Tainted），toDataURL 必然抛错、光栅化失败；关闭后 label 用纯
  // <text> 渲染，普通文本观感几乎一致（仅 label 内 markdown 语法不再生效）。
  // 逐图前缀指令不碰全局配置、页面展示不受影响；若笔记源码自带 %%{init}%%，
  // 其后会覆盖本指令（尊重用户显式配置，属预期行为）。
  const src = '%%{init: {"htmlLabels": false}}%%\n' + definition
  // mermaid v11：render 渲染到隐藏容器并返回 svg 字符串
  const { svg } = await mermaid.render(`docx-mermaid-${index}`, src)
  // 解析 viewBox 取原始逻辑尺寸；minX/minY 可为负（如时序图 "-50 -10 ..."），
  // 宽高恒为正——旧正则 [\d.]+ 匹配不上负号会导致时序图整体降级
  const m = svg.match(/viewBox="\s*(-?[\d.]+)[\s,]+(-?[\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/i)
  const w = m ? parseFloat(m[3]) : 0
  const h = m ? parseFloat(m[4]) : 0
  if (!(w > 0 && h > 0)) return null
  // 归一化 svg 根标签：剥掉已有的 width/height（mermaid 常输出 width="100%"）
  // 与 style（max-width 会干扰按原始尺寸解码）后写入显式宽高。
  // 注意必须先剥再加——直接追加会产生重复 width 属性，XML 非法导致 <img> 加载失败
  const normalized = svg.replace(/<svg\b([^>]*)>/i, (_whole, attrs: string) => {
    const cleaned = attrs.replace(/\s(?:width|height|style)\s*=\s*"[^"]*"/gi, '')
    return `<svg${cleaned} width="${w}" height="${h}">`
  })
  const png = await rasterizeBlob(
    new Blob([normalized], { type: 'image/svg+xml;charset=utf-8' }),
    MERMAID_SCALE,
  )
  if (!png) return null
  return { type: 'png', data: png.data, width: png.width, height: png.height }
}

/* ============ 内嵌图片 → ImageRun ============ */

/**
 * 下载并转换 markdown 内嵌图片为可嵌入资源
 * - data: URL 直接解码；相对路径（/uploads/...）补 origin 后同源 fetch
 * - png/jpg/gif/bmp 原字节直用；svg/webp 等光栅化成 png
 * @returns 失败返回 null（调用方降级为占位文字）
 */
async function fetchImageAsset(src: string): Promise<ImageAsset | null> {
  try {
    let bytes: Uint8Array
    if (src.startsWith('data:')) {
      const comma = src.indexOf(',')
      // 仅支持 base64 形式的 data URL（URL 编码形式少见，直接放弃）
      if (comma < 0 || !src.slice(0, comma).includes(';base64')) return null
      bytes = base64ToBytes(src.slice(comma + 1))
    } else {
      // 相对路径补 origin：生产 nginx 同源直出 /uploads，开发经 vite 代理
      const url = /^https?:\/\//i.test(src) ? src : new URL(src, location.origin).href
      const res = await fetch(url)
      if (!res.ok) return null
      bytes = new Uint8Array(await res.arrayBuffer())
    }
    const sniffed = sniffImageType(bytes)
    if (sniffed) {
      // docx 原生支持的类型：原字节直用，只需解码拿尺寸
      const img = await decodeImage(new Blob([bytes]))
      const w = img.naturalWidth || img.width
      const h = img.naturalHeight || img.height
      if (!w || !h) return null
      return { type: sniffed, data: bytes, width: w, height: h }
    }
    // svg/webp 等不支持的类型：光栅化成 png 再嵌入
    const isSvg = bytes.length > 4 && bytes[0] === 0x3c /* '<' */
    const rastered = await rasterizeBlob(
      new Blob([bytes], { type: isSvg ? 'image/svg+xml;charset=utf-8' : 'image/webp' }),
      1,
    )
    if (!rastered) return null
    return { type: 'png', data: rastered.data, width: rastered.width, height: rastered.height }
  } catch {
    return null
  }
}

/** 内嵌图片 → ImageRun（限宽等比缩放）；失败返回 null */
async function imageRunFromSrc(src: string): Promise<ImageRun | null> {
  const asset = await fetchImageAsset(src)
  if (!asset) return null
  const { width, height } = fitSize(asset.width, asset.height, IMG_MAX_W, IMG_MAX_H)
  return new ImageRun({ type: asset.type, data: asset.data, transformation: { width, height } })
}

/* ============ 行内 token → Run 列表 ============ */

/**
 * 行内 token 流 → ParagraphChild 列表
 * 覆盖：text/strong/em/del/br/codespan/link/image/html/escape；
 * 未知类型兜底按纯文本输出。加粗/斜体/删除线通过 flags 向下传递。
 */
async function inlineRuns(tokens: Token[] | undefined, flags: InlineFlags = {}): Promise<ParagraphChild[]> {
  if (!tokens || tokens.length === 0) return []
  const out: ParagraphChild[] = []
  for (const t of tokens) {
    switch (t.type) {
      case 'text': {
        // gfm 下 text 可能还带子 token（嵌套样式/实体）
        const sub = (t as Tokens.Text).tokens
        if (sub?.length) {
          out.push(...(await inlineRuns(sub, flags)))
        } else {
          out.push(
            new TextRun({
              text: t.text,
              bold: flags.bold,
              italics: flags.italic,
              strike: flags.strike,
              // 链接内文字挂 Hyperlink 字符样式（蓝字下划线），普通文字不带
              style: flags.hyperlink ? 'Hyperlink' : undefined,
            }),
          )
        }
        break
      }
      case 'strong':
        out.push(...(await inlineRuns((t as Tokens.Strong).tokens, { ...flags, bold: true })))
        break
      case 'em':
        out.push(...(await inlineRuns((t as Tokens.Em).tokens, { ...flags, italic: true })))
        break
      case 'del':
        out.push(...(await inlineRuns((t as Tokens.Del).tokens, { ...flags, strike: true })))
        break
      case 'br':
        // breaks:true 下软换行成为独立 br token
        out.push(new TextRun({ break: 1 }))
        break
      case 'codespan':
        // 行内代码：等宽字体 + 灰底
        out.push(
          new TextRun({
            text: (t as Tokens.Codespan).text,
            font: CODE_FONT,
            size: 20,
            color: COLOR_CODE_TEXT,
            shading: { type: ShadingType.CLEAR, fill: COLOR_CODE_BG },
          }),
        )
        break
      case 'link': {
        // 超链接：ExternalHyperlink 包住链接文字，内部递归时挂 hyperlink 标记
        const lk = t as Tokens.Link
        const children = await inlineRuns(lk.tokens, { ...flags, hyperlink: true })
        out.push(
          new ExternalHyperlink({
            link: lk.href,
            children: children.length ? children : [new TextRun({ text: lk.href, style: 'Hyperlink' })],
          }),
        )
        break
      }
      case 'image': {
        // 行内图片：嵌入成功直接输出 ImageRun；失败降级为占位文字
        const im = t as Tokens.Image
        const run = im.href ? await imageRunFromSrc(im.href) : null
        if (run) {
          out.push(run)
        } else {
          out.push(
            new TextRun({
              text: im.text ? `[图片：${im.text}]` : `[图片] ${im.href}`,
              italics: true,
              color: COLOR_META,
            }),
          )
        }
        break
      }
      case 'html': {
        // 行内 html（如 <br>/<sub>）：剥标签保留文本
        const txt = (t as Tokens.HTML).text.replace(/<[^>]*>/g, '')
        if (txt) {
          out.push(
            new TextRun({
              text: txt,
              bold: flags.bold,
              italics: flags.italic,
              strike: flags.strike,
              style: flags.hyperlink ? 'Hyperlink' : undefined,
            }),
          )
        }
        break
      }
      default: {
        // escape 等：有 text 就按纯文本兜底
        if ('text' in t && t.text) {
          out.push(
            new TextRun({
              text: t.text,
              bold: flags.bold,
              italics: flags.italic,
              strike: flags.strike,
              style: flags.hyperlink ? 'Hyperlink' : undefined,
            }),
          )
        }
      }
    }
  }
  return out
}

/* ============ 块级 token → docx 块 ============ */

/** 标题深度 → docx HeadingLevel（1-6 之外钳制） */
function headingLevel(depth: number): (typeof HeadingLevel)[keyof typeof HeadingLevel] {
  const map = [
    HeadingLevel.HEADING_1,
    HeadingLevel.HEADING_2,
    HeadingLevel.HEADING_3,
    HeadingLevel.HEADING_4,
    HeadingLevel.HEADING_5,
    HeadingLevel.HEADING_6,
  ]
  return map[Math.min(5, Math.max(0, depth - 1))]
}

/** 引用块段落的附加样式（左竖条 + 缩进），由 ctx.quote 开启 */
interface BlockCtx {
  quote: boolean
}
function quoteExtras(): { indent: { left: number }; border: { left: { style: typeof BorderStyle.SINGLE; size: number; color: string; space: number } } } {
  return {
    indent: { left: 360 },
    border: { left: { style: BorderStyle.SINGLE, size: 18, color: COLOR_QUOTE_BAR, space: 8 } },
  }
}

/** 分隔线（---/hr）：底部细线段落 */
function hrParagraph(): Paragraph {
  return new Paragraph({
    border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: COLOR_BORDER, space: 1 } },
    spacing: { before: 40, after: 200 },
    children: [],
  })
}

/** 普通代码块 → 逐行 Paragraph（灰底 + 细边框 + 等宽字体）；相邻同款边框 Word 自动合并成一个框 */
function codeParagraphs(text: string): Paragraph[] {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  // 去掉围栏尾部产生的空行
  while (lines.length && lines[lines.length - 1] === '') lines.pop()
  const line = { style: BorderStyle.SINGLE, size: 4, color: COLOR_BORDER, space: 4 }
  return lines.map((ln, i) =>
    new Paragraph({
      shading: { type: ShadingType.CLEAR, fill: COLOR_CODE_BG },
      border: { top: line, bottom: line, left: line, right: line },
      spacing: { before: i === 0 ? 80 : 0, after: i === lines.length - 1 ? 160 : 0, line: 240 },
      indent: { left: 120, right: 120 },
      // 空行给一个空格占位，否则 Word 不渲染该行底色
      children: [new TextRun({ text: ln.length ? ln : ' ', font: CODE_FONT, size: 18, color: COLOR_CODE_TEXT })],
    }),
  )
}

/**
 * code token → 块列表
 * - mermaid：渲染为 PNG 居中嵌入；失败降级为普通代码块展示源码
 * - 其他语言：逐行灰底代码块
 */
async function codeBlockParagraphs(tok: Tokens.Code): Promise<DocBlock[]> {
  const lang = (tok.lang || '').trim().toLowerCase()
  if (lang.startsWith('mermaid')) {
    try {
      const png = await mermaidToPng(tok.text, ++mermaidSeq)
      if (png) {
        const { width, height } = fitSize(png.width, png.height, IMG_MAX_W, IMG_MAX_H)
        return [
          new Paragraph({
            alignment: AlignmentType.CENTER,
            spacing: { before: 80, after: 160 },
            children: [new ImageRun({ type: 'png', data: png.data, transformation: { width, height } })],
          }),
        ]
      }
    } catch {
      // 渲染失败（语法错误等）→ 落到下方源码展示，保证内容不丢
    }
  }
  return codeParagraphs(tok.text)
}

/** 嵌套列表递归渲染（有序 "1. " / 无序 "• " / 任务 ☑☐），层级缩进 360 twip/级 */
async function listBlocks(tok: Tokens.List, level: number): Promise<DocBlock[]> {
  const out: DocBlock[] = []
  // 有序列表起始号：marked 里 start 可能是 string（如 "5."），统一转数字
  const start = tok.ordered ? Number(tok.start ?? 1) : 1
  for (let i = 0; i < tok.items.length; i++) {
    const item = tok.items[i]
    // 前缀：有序 "1. "；无序 "• "；任务列表换成勾选框字符
    let marker = tok.ordered ? `${start + i}. ` : '• '
    if (item.task) marker = item.checked ? '☑ ' : '☐ '
    let first = true
    for (const bt of item.tokens) {
      if (bt.type === 'space') continue
      if (bt.type === 'list') {
        // 嵌套列表：层级 +1
        out.push(...(await listBlocks(bt as Tokens.List, level + 1)))
        continue
      }
      if (bt.type === 'code') {
        out.push(...(await codeBlockParagraphs(bt as Tokens.Code)))
        continue
      }
      // 松散列表项是 paragraph、紧凑列表项是 text，两者 .tokens 都是行内 token
      const inlineTokens =
        bt.type === 'paragraph' ? (bt as Tokens.Paragraph).tokens : ((bt as Tokens.Text).tokens ?? [bt])
      const runs = await inlineRuns(inlineTokens)
      if (first) {
        runs.unshift(new TextRun({ text: marker }))
        first = false
      }
      out.push(
        new Paragraph({
          children: runs,
          indent: { left: 360 * (level + 1) },
          spacing: { after: 60, line: 300 },
        }),
      )
    }
    // 极端情况：item 内没有任何可渲染块，补一行前缀避免丢序号
    if (first) {
      out.push(
        new Paragraph({
          children: [new TextRun({ text: marker })],
          indent: { left: 360 * (level + 1) },
          spacing: { after: 60, line: 300 },
        }),
      )
    }
  }
  return out
}

/** table token → docx Table（表头加粗灰底，支持对齐） */
async function tableFromToken(tok: Tokens.Table): Promise<Table> {
  // 统一细边框（0.5pt）
  const thin = { style: BorderStyle.SINGLE, size: 4, color: COLOR_BORDER }
  const borders = {
    top: thin,
    bottom: thin,
    left: thin,
    right: thin,
    insideHorizontal: thin,
    insideVertical: thin,
  }
  // 单元格 → 段落（对齐映射：center/right/left）
  const cellPara = async (tokens: Token[], align: string | null, bold: boolean): Promise<Paragraph> =>
    new Paragraph({
      alignment: align === 'center' ? AlignmentType.CENTER : align === 'right' ? AlignmentType.RIGHT : undefined,
      children: await inlineRuns(tokens, { bold }),
      spacing: { line: 260 },
    })
  // 表头行：单元格段落异步构建（内部要跑 inlineRuns），先 Promise.all 收齐
  const headerCells: TableCell[] = await Promise.all(
    tok.header.map(
      async (c) =>
        new TableCell({
          shading: { type: ShadingType.CLEAR, fill: 'EEF2F8' },
          verticalAlign: VerticalAlign.CENTER,
          children: [await cellPara(c.tokens, c.align ?? null, true)],
        }),
    ),
  )
  const headerRow = new TableRow({ tableHeader: true, children: headerCells })
  // 数据行：同表头逐格异步构建
  const bodyRows: TableRow[] = await Promise.all(
    tok.rows.map(async (row) => {
      const cells: TableCell[] = await Promise.all(
        row.map(
          async (c) =>
            new TableCell({
              children: [await cellPara(c.tokens, c.align ?? null, false)],
            }),
        ),
      )
      return new TableRow({ children: cells })
    }),
  )
  return new Table({ width: { size: 100, type: WidthType.PERCENTAGE }, borders, rows: [headerRow, ...bodyRows] })
}

/**
 * 块级 token 流遍历入口
 * @param ctx.quote 为 true 时段落加左竖条 + 缩进（blockquote 内部）
 */
async function blocksFromTokens(tokens: Token[], ctx: BlockCtx): Promise<DocBlock[]> {
  const out: DocBlock[] = []
  for (const tok of tokens) {
    switch (tok.type) {
      case 'heading':
        out.push(
          new Paragraph({
            heading: headingLevel((tok as Tokens.Heading).depth),
            ...(ctx.quote ? quoteExtras() : {}),
            children: await inlineRuns((tok as Tokens.Heading).tokens),
          }),
        )
        break
      case 'paragraph':
        out.push(
          new Paragraph({
            ...(ctx.quote ? quoteExtras() : {}),
            spacing: { after: 120, line: 300 },
            children: await inlineRuns((tok as Tokens.Paragraph).tokens),
          }),
        )
        break
      case 'code':
        out.push(...(await codeBlockParagraphs(tok as Tokens.Code)))
        break
      case 'blockquote':
        // 递归渲染引用内部块，ctx.quote 传递竖条样式
        out.push(...(await blocksFromTokens((tok as Tokens.Blockquote).tokens, { quote: true })))
        break
      case 'list':
        out.push(...(await listBlocks(tok as Tokens.List, 0)))
        break
      case 'table':
        out.push(await tableFromToken(tok as Tokens.Table))
        break
      case 'hr':
        out.push(hrParagraph())
        break
      case 'space':
      case 'def':
        // 空行 / 链接定义：无可视内容
        break
      case 'html': {
        // 块级 html（如 <div>/<br>）：剥标签保留文本，剩余为空则丢弃
        const txt = (tok as Tokens.HTML).text.replace(/<[^>]*>/g, '').trim()
        if (txt) {
          out.push(new Paragraph({ ...(ctx.quote ? quoteExtras() : {}), spacing: { after: 120, line: 300 }, children: [new TextRun(txt)] }))
        }
        break
      }
      default: {
        // 未知块级 token：按纯文本逐行兜底，保证内容不丢
        const txt = (tok as { text?: string }).text ?? ''
        for (const ln of txt.replace(/\r\n?/g, '\n').split('\n')) {
          if (!ln.trim()) continue
          out.push(new Paragraph({ ...(ctx.quote ? quoteExtras() : {}), spacing: { after: 120, line: 300 }, children: [new TextRun(ln)] }))
        }
      }
    }
  }
  return out
}

/* ============ 对外入口 ============ */

export interface ExportNoteDocxOptions {
  /** 文档标题（与页面 doc-title 同源：note.title） */
  title: string
  /** 笔记 markdown 原文（详情接口 contentMd 原样传入） */
  contentMd: string
  /** 标题下元信息行（如 “2026-09-04 21:20:05 · 测试 · 1055 字”），缺省不输出 */
  metaLine?: string
}

/**
 * 预处理 markdown 源文：
 * 1. 剥 BOM；2. 兼容剥离 yaml front matter；3. 剥正文首个 h1（标题独立展示，避免重复）
 */
function prepareMarkdown(md: string): string {
  let out = md.replace(/^\uFEFF/, '')
  out = out.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n?/, '')
  out = out.replace(/^\s*#\s+[^\n]*\n+/, '')
  return out
}

/**
 * 把一篇笔记导出为 .docx 的 Blob
 *
 * @param opts.title    文档标题（文档头大标题）
 * @param opts.contentMd markdown 原文
 * @param opts.metaLine 标题下弱化元信息行（时间/目录/字数），可省
 * @returns docx 二进制 Blob，调用方用 a[download] 触发浏览器下载
 */
export async function exportNoteToDocx(opts: ExportNoteDocxOptions): Promise<Blob> {
  const tokens = marked.lexer(prepareMarkdown(opts.contentMd ?? ''))
  const children: DocBlock[] = []
  // 文档头：大标题 + 元信息行 + 分隔线
  children.push(
    new Paragraph({ heading: HeadingLevel.TITLE, children: [new TextRun({ text: opts.title || '未命名笔记' })] }),
  )
  if (opts.metaLine) {
    children.push(
      new Paragraph({
        spacing: { after: 60 },
        children: [new TextRun({ text: opts.metaLine, size: 18, color: COLOR_META })],
      }),
    )
  }
  children.push(hrParagraph())
  // 正文 token → docx 块
  children.push(...(await blocksFromTokens(tokens, { quote: false })))

  const doc = new Document({
    creator: 'Agent Console 门户',
    title: opts.title,
    styles: {
      default: {
        document: { run: { font: BODY_FONT, size: 22, color: COLOR_TEXT } },
        title: {
          run: { font: BODY_FONT, size: 44, bold: true, color: COLOR_TITLE },
          paragraph: { spacing: { after: 120 } },
        },
        heading1: {
          run: { font: BODY_FONT, size: 34, bold: true, color: COLOR_TITLE },
          paragraph: { spacing: { before: 320, after: 140 } },
        },
        heading2: {
          run: { font: BODY_FONT, size: 29, bold: true, color: COLOR_TITLE },
          paragraph: { spacing: { before: 280, after: 120 } },
        },
        heading3: {
          run: { font: BODY_FONT, size: 25, bold: true, color: COLOR_TITLE },
          paragraph: { spacing: { before: 240, after: 100 } },
        },
        heading4: {
          run: { font: BODY_FONT, size: 22, bold: true, color: COLOR_TEXT },
          paragraph: { spacing: { before: 200, after: 80 } },
        },
        heading5: {
          run: { font: BODY_FONT, size: 22, bold: true, color: COLOR_TEXT },
          paragraph: { spacing: { before: 160, after: 80 } },
        },
        heading6: {
          run: { font: BODY_FONT, size: 22, bold: true, color: COLOR_META },
          paragraph: { spacing: { before: 160, after: 80 } },
        },
        // 超链接字符样式：蓝色 + 下划线（ExternalHyperlink 子 run 挂 style: 'Hyperlink'）
        hyperlink: { run: { color: COLOR_LINK, underline: { type: UnderlineType.SINGLE } } },
      },
    },
    sections: [
      {
        properties: { page: { margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
        children,
      },
    ],
  })
  return Packer.toBlob(doc)
}
