import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置：前端所有 API 请求统一走 /api 前缀，由代理转发到 Java 后端
export default defineConfig(({ mode }) => {
  // 加载 .env.development 中的环境变量
  const env = loadEnv(mode, process.cwd(), '')
  // 后端地址，默认 http://localhost:8000
  const target = env.VITE_API_BASE_URL || 'http://localhost:8000'

  return {
    // M1.4：门户端与管理端同域部署，管理端挂 /admin 子路径
    base: '/admin/',
    resolve: {
      alias: {
        // @ 指向 src 目录，便于模块引用
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    plugins: [
      vue(),
      // Element Plus API（ElMessage/ElMessageBox 等）按需自动导入
      AutoImport({
        resolvers: [ElementPlusResolver()],
        // 自动导入 vue/vue-router/pinia 常用 API
        imports: ['vue', 'vue-router', 'pinia'],
        dts: 'src/auto-imports.d.ts'
      }),
      // Element Plus 组件按需自动注册
      Components({
        resolvers: [ElementPlusResolver()],
        dts: 'src/components.d.ts'
      })
    ],
    server: {
      // 专属端口 12388，strictPort 确保端口被占用时报错而非自动切换
      port: 12388,
      strictPort: true,
      host: true,
      proxy: {
        // v3.5 笔记本域 controller 自带 /api 前缀（/api/system/notebook/*）→ 原样透传
        // 必须排在 /api/system 之前（vite 按 key 长度降序匹配，天然更精确优先）
        '/api/system/notebook': {
          target,
          changeOrigin: true
        },
        // /api/system/* 代理到 Java 后端，剥掉 /api/system 前缀 → /system/*
        // 对应生产 nginx location /api/system/ { proxy_pass .../system/; }
        // system 模块 Controller 无前缀（如 /system/user），必须剥前缀
        '/api/system': {
          target,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api\/system/, '/system')
        },
        // 其余 /api/* 代理到 Java 后端，保留 /api 前缀原样透传
        // 对应生产 nginx location /api/ { proxy_pass .../api/; }
        // agent 管理域（/api/agent/admin/*）与门户域（/api/portal/*）Controller 本身带 /api 前缀，
        // 绝不能像 system 模块那样剥掉，否则全部 404
        '/api': {
          target,
          changeOrigin: true
        },
        // /uploads/* 代理到 Java 后端静态资源
        // 后端静态资源映射即为 /uploads/**，因此不需要 rewrite
        '/uploads': {
          target,
          changeOrigin: true
        }
      }
    },
    build: {
      target: 'es2015',
      sourcemap: false,
      // 分包策略，优化首屏加载
      rollupOptions: {
        output: {
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            element: ['element-plus', '@element-plus/icons-vue'],
            echarts: ['echarts']
          }
        }
      }
    }
  }
})
