import { ref } from 'vue'
import { portalApi } from '@/api/portal'

/**
 * 门户质感方案类型：与 main.css 的 html[data-texture='...'] 选择器一一对应
 * - aurora：柔光（彩色光晕 + 玻璃面板）
 * - relief：浮雕（极简立体卡片）
 * - grid：网格（16px 钴蓝网格 + 顶光）
 */
export type PortalTexture = 'aurora' | 'relief' | 'grid'

/** 合法质感白名单：后端只透传不校验，门户端在此兜底（非法值回退 aurora） */
const TEXTURES: PortalTexture[] = ['aurora', 'relief', 'grid']

/**
 * 质感缓存键：applyTexture 每次应用时写入 localStorage，
 * index.html 的防闪烁（FOUC）内联脚本在首帧前读取同一键还原质感，
 * 因此二次访问时刷新页面不会出现质感闪烁
 */
const TEXTURE_CACHE_KEY = 'portal-site-texture'

/**
 * 门户站点信息（模块级响应式，供顶栏 / 页面标题 / 页脚共享）
 * loadSite() 拉取 GET /api/portal/site 后写入
 */
/** v3：默认站点标题去 agent 术语（接口有配置时会被覆盖） */
export const siteTitle = ref('笔记本门户')
export const siteDescription = ref('')

/**
 * 当前门户质感方案（管理平台"站点设置"下发，门户端只读应用、不做本地切换）
 * 默认 aurora；实际值由 loadSite() 按配置覆盖并同步到 <html data-texture>
 */
export const portalTexture = ref<PortalTexture>('aurora')

/**
 * 应用质感：写 <html data-texture>（立即生效）+ 缓存到 localStorage（防闪烁脚本下次读取）
 * @param texture 已通过白名单校验的质感方案 id
 */
function applyTexture(texture: PortalTexture): void {
  portalTexture.value = texture
  document.documentElement.dataset.texture = texture
  try {
    localStorage.setItem(TEXTURE_CACHE_KEY, texture)
  } catch {
    /* 隐私模式等 localStorage 不可用场景：静默降级为仅当前会话生效 */
  }
}

/** in-flight 请求去重：App.vue onMounted 与 HomeView 双调用点共用同一次请求，避免重复打接口 */
let sitePromise: Promise<void> | null = null

/**
 * 实际拉取站点信息（标题 / 描述 / 质感）
 * - 标题、描述：仅在接口有值时覆盖默认值
 * - 质感：经白名单校验后应用（非法值回退 aurora）
 * - 失败静默：站点信息非关键数据，保留默认值不阻塞页面渲染
 */
function fetchSite(): Promise<void> {
  return portalApi
    .site()
    .then((site) => {
      if (site?.siteTitle) siteTitle.value = site.siteTitle
      if (site?.siteDescription) siteDescription.value = site.siteDescription
      // 质感兜底：后端只透传 t_option 的值，这里做白名单校验
      const tx = site?.portalTexture
      applyTexture(TEXTURES.includes(tx as PortalTexture) ? (tx as PortalTexture) : 'aurora')
    })
    .catch(() => {
      // 拉取失败：保持默认标题与 index.html 防闪烁脚本已写入的质感，不打断页面
    })
}

/**
 * 拉取站点信息（幂等 + in-flight 去重）
 * - 首次调用发起请求，并发/重复调用共享同一 Promise
 * - 失败后同样标记完成（不重试），下次整页刷新时自然重试
 */
export function loadSite(): Promise<void> {
  if (!sitePromise) {
    sitePromise = fetchSite()
  }
  return sitePromise
}
