import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 门户端独立工程：base '/' 部署到站点根（nginx location / → 门户 dist）
export default defineConfig(({ mode }) => {
  // 加载 .env.development 中的环境变量
  const env = loadEnv(mode, process.cwd(), '')
  // 后端地址，默认 http://localhost:8000
  const target = env.VITE_API_BASE_URL || 'http://localhost:8000'
  // 管理端 Vite dev 地址（本机管理端独立跑在 12388，由门户 12488 出一体化入口）
  const adminTarget = env.VITE_ADMIN_DEV_URL || 'http://localhost:12388'

  return {
    base: '/',
    resolve: {
      alias: {
        // @ 指向 src 目录，便于模块引用
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    plugins: [vue()],
    server: {
      // 专属端口 12488，与管理端（12388）错开
      port: 12488,
      strictPort: true,
      host: true,
      proxy: {
        // /admin/* 代理到管理端 Vite dev（一体机复刻本地版：12488=/ 门户、/admin 管理端、/api 后端）
        // 管理端 base=/admin/，需带前缀转发（rewrite 会破坏其静态资源与路由，故不 rewrite）
        '/admin': {
          target: adminTarget,
          changeOrigin: true
        },
        // v3.5 笔记本域 controller 自带 /api 前缀（/api/system/notebook/*）→ 原样透传
        // 必须排在 /api/system 之前（vite 按 key 长度降序匹配，天然更精确优先）
        '/api/system/notebook': {
          target,
          changeOrigin: true
        },
        // 旧 system 域 controller 无 /api 前缀（/system/*，如 /system/site）→ 剥掉 /api 转发
        // 对应生产 nginx location /api/system/ { proxy_pass .../system/; }
        '/api/system': {
          target,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api/, '')
        },
        // agent 域（/api/agent/*）、note 域（/api/note/*）、portal 域（/api/portal/*）
        // 均自带 /api 前缀 → 保留前缀原样透传（对应生产 nginx location /api/）
        '/api': {
          target,
          changeOrigin: true
        },
        // 上传文件静态资源（笔记内嵌图片 /uploads/...）→ 后端 MvcConfig 映射的磁盘目录
        // 对应生产 nginx location /uploads/；此前 dev 缺此规则：页面破图、docx 导出也无法 fetch 图片
        '/uploads': {
          target,
          changeOrigin: true
        }
      }
    }
  }
})
