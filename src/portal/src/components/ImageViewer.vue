<template>
  <!--
    图形放大查看器（全屏层）
    从 门户（packages/-portal/src/components/StoragePreview.vue 的 imageViewer 段）照搬：
    - 打开方式：父组件调用 ref.open({ type, src | rawSvg+dataUrl, name })
    - 支持：滚轮缩放（0.2~6x）、按钮缩放、拖拽平移、双击重置
    - 下载：SVG 类型提供 SVG 矢量 + PNG 高清两种；图片类型直接下载原文件
    图标用内联 SVG 替代 的 iconify 依赖（agent-console portal 无 iconify）
  -->
  <Teleport to="body">
    <div
      v-if="state.visible"
      class="image-viewer-overlay"
      @click.self="close"
      @wheel.prevent="onImgWheel"
    >
      <!-- 图形内容（图片或 mermaid SVG）：统一用 <img> 渲染，支持拖拽平移 + 滚轮缩放 -->
      <img
        :src="state.type === 'svg' ? state.dataUrl : state.src"
        :alt="state.name"
        class="image-viewer-img"
        :style="imgViewerStyle"
        draggable="false"
        @pointerdown.stop="onImgDragStart"
        @dblclick="reset"
      />

      <!-- 底部工具栏：缩放 / 重置 / 下载 / 关闭 -->
      <div class="image-viewer-toolbar" @click.stop>
        <button
          class="iv-btn"
          title="缩小"
          :disabled="state.scale <= 0.2"
          @click="zoomOut"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
            <line x1="6" y1="12" x2="18" y2="12" />
          </svg>
        </button>
        <span class="iv-scale">{{ Math.round(state.scale * 100) }}%</span>
        <button
          class="iv-btn"
          title="放大"
          :disabled="state.scale >= 6"
          @click="zoomIn"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
            <line x1="12" y1="6" x2="12" y2="18" />
            <line x1="6" y1="12" x2="18" y2="12" />
          </svg>
        </button>
        <button class="iv-btn" title="重置" @click="reset">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 12a9 9 0 1 0 3-6.7" />
            <path d="M3 4v5h5" />
          </svg>
        </button>
        <!-- SVG 类型：提供 SVG 和 PNG 两种下载（文字标签，避免图标缺失不可见） -->
        <template v-if="state.type === 'svg'">
          <button
            class="iv-btn iv-download iv-btn-text"
            title="下载 SVG 矢量图"
            @click="downloadSvg"
          >
            SVG
          </button>
          <button
            class="iv-btn iv-download iv-btn-text"
            title="下载 PNG 高清图"
            @click="downloadPng"
          >
            PNG
          </button>
        </template>
        <!-- 图片类型：直接下载原文件 -->
        <button
          v-else
          class="iv-btn iv-download"
          title="下载图片"
          @click="downloadImage"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 3v12" />
            <path d="m7 10 5 5 5-5" />
            <path d="M5 21h14" />
          </svg>
        </button>
        <button class="iv-btn iv-close" title="关闭" @click="close">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
            <line x1="6" y1="6" x2="18" y2="18" />
            <line x1="18" y1="6" x2="6" y2="18" />
          </svg>
        </button>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'

/**
 * 打开查看器参数
 * - type='img'：传 src（普通图片 URL）
 * - type='svg'：传 rawSvg（SVG 源码，用于下载）+ dataUrl（供 <img> 渲染）
 */
export interface ImageViewerOpenOptions {
  type: 'img' | 'svg'
  src?: string
  rawSvg?: string
  dataUrl?: string
  name?: string
}

/* 图形查看器状态（StoragePreview.vue imageViewer） */
const state = reactive({
  visible: false,
  /* 内容类型：img=普通图片，svg=mermaid 图表 */
  type: 'img' as 'img' | 'svg',
  /* 普通图片的 URL */
  src: '',
  /* mermaid SVG 的原始 XML 字符串（用于下载） */
  rawSvg: '',
  /* mermaid SVG 转 data URL（用于 <img> 渲染，保证尺寸正确） */
  dataUrl: '',
  name: 'image',
  scale: 1,
  x: 0,
  y: 0,
})

/* 打开查看器 */
function open(opts: ImageViewerOpenOptions): void {
  state.type = opts.type
  state.src = opts.src || ''
  state.rawSvg = opts.rawSvg || ''
  state.dataUrl = opts.dataUrl || ''
  state.name = opts.name || 'image'
  reset()
  state.visible = true
}

/* 关闭查看器 */
function close(): void {
  state.visible = false
}

/* 重置缩放与平移 */
function reset(): void {
  state.scale = 1
  state.x = 0
  state.y = 0
}

/* 查看器 transform 样式：缩放 + 平移 */
const imgViewerStyle = computed(() => ({
  transform: `translate(${state.x}px, ${state.y}px) scale(${state.scale})`,
}))

/* 滚轮缩放：向上滚放大，向下滚缩小，范围 0.2x ~ 6x */
function onImgWheel(e: WheelEvent): void {
  const delta = e.deltaY < 0 ? 0.15 : -0.15
  state.scale = Math.min(6, Math.max(0.2, +(state.scale + delta).toFixed(2)))
}

/* 工具栏按钮缩放 */
function zoomIn(): void {
  state.scale = Math.min(6, +(state.scale + 0.2).toFixed(2))
}
function zoomOut(): void {
  state.scale = Math.max(0.2, +(state.scale - 0.2).toFixed(2))
}

/* 图片拖拽平移：按住图片拖动改变 translate 坐标 */
function onImgDragStart(e: PointerEvent): void {
  e.preventDefault()
  const startX = e.clientX
  const startY = e.clientY
  const startTx = state.x
  const startTy = state.y
  function onMove(ev: PointerEvent): void {
    state.x = startTx + (ev.clientX - startX)
    state.y = startTy + (ev.clientY - startY)
  }
  function onUp(): void {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

/* 触发浏览器下载（通用工具函数） */
function triggerBlobDownload(blob: Blob, filename: string): void {
  const blobUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = blobUrl
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(blobUrl)
}

/* 下载 SVG 源码文件 */
function downloadSvg(): void {
  if (!state.rawSvg) return
  const blob = new Blob([state.rawSvg], {
    type: 'image/svg+xml;charset=utf-8',
  })
  triggerBlobDownload(blob, (state.name || 'diagram') + '.svg')
}

/* 将 SVG 光栅化为 PNG 并下载（照搬：viewBox 取矢量尺寸 → Canvas 1:1 光栅化） */
function downloadPng(): void {
  if (!state.dataUrl) return
  const img = new Image()
  img.crossOrigin = 'anonymous'
  img.onload = () => {
    let w = 0
    let h = 0
    const vbMatch = state.rawSvg.match(/viewBox=["']([\d.\s-]+)["']/)
    if (vbMatch) {
      const parts = vbMatch[1].split(/[\s,]+/).map(Number)
      if (parts.length === 4) {
        w = parts[2]
        h = parts[3]
      }
    }
    if (!w) w = img.naturalWidth || 1920
    if (!h) h = img.naturalHeight || 1080

    const MAX_EDGE = 16384
    let scale = 1
    if (w * scale > MAX_EDGE) scale = MAX_EDGE / w
    if (h * scale > MAX_EDGE) scale = Math.min(scale, MAX_EDGE / h)

    const canvas = document.createElement('canvas')
    canvas.width = Math.round(w * scale)
    canvas.height = Math.round(h * scale)
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    canvas.toBlob((blob) => {
      if (blob) {
        triggerBlobDownload(blob, (state.name || 'diagram') + '.png')
      }
    }, 'image/png')
  }
  img.onerror = () => {
    console.error('[PNG 下载] SVG 转 PNG 失败')
  }
  img.src = state.dataUrl
}

/* 下载普通图片（非 SVG 类型）：优先 fetch + blob，失败降级 a 标签 */
async function downloadImage(): Promise<void> {
  if (!state.src) return
  try {
    const res = await fetch(state.src, { credentials: 'include' })
    if (!res.ok) throw new Error('下载失败')
    const blob = await res.blob()
    triggerBlobDownload(blob, state.name)
  } catch {
    const a = document.createElement('a')
    a.href = state.src
    a.download = state.name
    a.target = '_blank'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  }
}

defineExpose({ open })
</script>

<style scoped>
.image-viewer-overlay {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  animation: iv-fade-in 0.2s ease;
  overflow: hidden;
}

@keyframes iv-fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* 查看器中的图片（含 mermaid SVG via data URL）白色卡片包裹 */
.image-viewer-img {
  max-width: 90vw;
  max-height: 80vh;
  object-fit: contain;
  border-radius: 8px;
  cursor: grab;
  transition: transform 0.05s linear;
  user-select: none;
  background: #ffffff;
  padding: 24px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.3);
}
.image-viewer-img:active {
  cursor: grabbing;
}

/* 底部工具栏：缩放控制 + 下载 + 关闭 */
.image-viewer-toolbar {
  position: fixed;
  bottom: 28px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: rgba(40, 40, 40, 0.85);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 14px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  z-index: 10001;
}

/* 工具栏按钮 */
.iv-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.85);
  cursor: pointer;
  font-size: 18px;
  transition: background 0.15s ease;
}
.iv-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.12);
}
.iv-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

/* 缩放百分比文字 */
.iv-scale {
  min-width: 48px;
  text-align: center;
  color: rgba(255, 255, 255, 0.7);
  font-size: 0.82rem;
  font-variant-numeric: tabular-nums;
}

/* 下载按钮高亮 */
.iv-download {
  color: #4ade80;
}
.iv-download:hover:not(:disabled) {
  background: rgba(74, 222, 128, 0.15);
}

/* 文字标签按钮（SVG/PNG 下载） */
.iv-btn-text {
  width: auto;
  min-width: 36px;
  padding: 0 10px;
  font-size: 0.78rem;
  font-weight: 600;
  letter-spacing: 0.5px;
}

/* 关闭按钮高亮 */
.iv-close {
  color: #f87171;
}
.iv-close:hover:not(:disabled) {
  background: rgba(248, 113, 113, 0.15);
}

/* 移动端适配 */
@media (max-width: 768px) {
  .image-viewer-toolbar {
    bottom: 16px;
    gap: 4px;
    padding: 6px 8px;
  }
  .iv-btn {
    width: 32px;
    height: 32px;
    font-size: 16px;
  }
}
</style>
