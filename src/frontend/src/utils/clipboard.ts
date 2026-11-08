/**
 * 统一剪贴板复制工具（全项目复制场景必须使用本函数，禁止直接调用 navigator.clipboard）
 *
 * 背景（见 workspace 规则 clipboard-compatibility.md）：
 * navigator.clipboard.writeText 仅在 Secure Context（HTTPS / localhost）下可用，
 * 部署到非 localhost 的 HTTP 环境时调用会被浏览器静默拒绝，
 * 仅 try/catch 包裹无法感知失败——因此必须先判断 isSecureContext 再走降级方案。
 *
 * @param text 要复制到剪贴板的文本
 * @returns true=复制成功 false=复制失败（调用方据此提示用户手动复制）
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  // 1. 优先使用 Clipboard API——但必须先确认处于安全上下文，
  //    HTTP 环境（isSecureContext=false）下对象存在但调用会静默失败
  try {
    if (navigator.clipboard?.writeText && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // Clipboard API 失败（如权限被拒），继续走降级方案
  }

  // 2. 降级方案：临时 textarea + document.execCommand('copy')
  //    兼容 HTTP 内网环境与旧浏览器
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '') // 防止移动端弹出虚拟键盘
    ta.style.position = 'fixed'
    ta.style.top = '0'
    ta.style.left = '0'
    ta.style.opacity = '0' // 视觉隐藏但保持可交互
    document.body.appendChild(ta)
    ta.focus() // 部分浏览器无焦点时 execCommand 会静默失败，必须先 focus
    ta.select()
    ta.setSelectionRange(0, text.length) // iOS Safari 需要显式设置选区
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
