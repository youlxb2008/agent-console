/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // Vue 单文件组件类型声明，使 .vue 文件可被 TS 识别
  const component: DefineComponent<{}, {}, any>
  export default component
}

// 扩展 ImportMetaEnv，使 import.meta.env 有类型提示
interface ImportMetaEnv {
  /** 后端 API 基础地址（dev 代理目标） */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
