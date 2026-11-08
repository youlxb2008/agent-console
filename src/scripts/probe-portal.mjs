// 验证门户 dev 页面在 1280px 下的渲染
const BASE = 'http://localhost:12488'
const API = 'http://localhost:12488/api/portal'

async function probe(url, opts = {}) {
  try {
    const r = await fetch(url, opts)
    return { url, status: r.status, ok: r.ok }
  } catch (e) {
    return { url, status: 0, err: e.message }
  }
}

async function main() {
  // 1. 主页面是否 200
  const home = await probe(BASE + '/')
  console.log('home:', home.status, home.ok ? 'OK' : 'FAIL')

  // 2. 关键 API 端点（M3.8 RESTful：notebookId 走路径）
  const apis = [
    '/agents',
    '/notebooks/1/notes/recent?limit=3',
    '/notebooks/1/notes/by-tag?tag=数据',
    '/site',
  ]
  for (const p of apis) {
    const r = await probe(API + p)
    console.log('api', p, '=>', r.status, r.ok ? 'OK' : 'FAIL')
  }

  // 3. 抓首页 HTML 看 vue/portal 是否被加载（验证 Vite 服务 OK）
  const homeRes = await fetch(BASE + '/')
  const html = await homeRes.text()
  const hasVite = html.includes('/@vite/client') || html.includes('vite')
  console.log('vite-client in html:', hasVite ? 'OK' : 'FAIL')
  console.log('html length:', html.length, 'bytes')
}
main()
