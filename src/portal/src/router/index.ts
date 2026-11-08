import { createRouter, createWebHistory } from 'vue-router'
import { siteTitle } from '@/store/site'

/**
 * 门户端路由（M3.8 RESTful 化：资源 ID 走路径）
 * - /                            HomeView 门户首页（Hero + KPI + 双板块入口 + 最近动态）
 * - /notebooks                   NotebooksView 笔记本承接页（卡片网格）
 * - /notebooks/{notebookId}       NotesView 笔记本态（笔记页）
 * - /notebooks/{id}/notes/{nid}   笔记深链接：nid 直达并展开树节点（纯 ASCII 短键，改名不断链）
 * - /agents                       AgentsView 智能体列表（卡片网格）
 * - /agents/{agentId}             AgentDetailView 智能体详情（目标 todolist + 台账流水双 Tab）
 * - /agents/{id}/objectives|ledger 旧路由 redirect 到详情页对应 Tab
 * - /ledger                       LedgerView 全部 agent 台账聚合流
 * - /objectives                   ObjectivesView 全部 agent 私域目标聚合（按 agent 分组卡片）
 * - /bus                          BusView 消息流转（agent 消息总线投递流水总览，M1 门户包）
 * - /notebook?notebookId=&nid=    旧 query 形态：redirect 兼容重定向到新路径
 * - /?notebookId=...              旧版深链：beforeEnter 兼容重定向到新路径
 * - 其余                          404
 * 🔥 v1 /notes、/notes/:id 已删除；v3.5 的 PortalView 壳由拆分路由替代
 */
const router = createRouter({
  // 门户挂站点根，history 模式由 nginx try_files 兜底
  history: createWebHistory('/'),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: '首页' },
      // M3.8：旧深链 /?notebookId={id}[&nid={n_xxx}] 兼容重定向 → /notebooks/{id}[/notes/{nid}]
      beforeEnter: (to) => {
        if (to.query.notebookId) {
          return legacyNotebookRedirect(to.query.notebookId, to.query.nid)
        }
      }
    },
    {
      // 笔记本承接页：顶部导航「笔记本」的专属落地页（卡片网格，点击进入笔记页）
      path: '/notebooks',
      name: 'notebooks',
      component: () => import('@/views/NotebooksView.vue'),
      meta: { title: '笔记本' }
    },
    {
      // M3.8：笔记本视图 /notebooks/{notebookId}（nid 可选深链 /notebooks/{id}/notes/{nid}，同组件）
      path: '/notebooks/:notebookId(\\d+)',
      name: 'notebook',
      component: () => import('@/views/NotesView.vue'),
      meta: { title: '笔记' }
    },
    {
      // M3.8：笔记深链 /notebooks/{notebookId}/notes/{nid}（同一 NotesView 组件，nid 直达）
      path: '/notebooks/:notebookId(\\d+)/notes/:nid',
      name: 'notebook-note',
      component: () => import('@/views/NotesView.vue'),
      meta: { title: '笔记' }
    },
    {
      // 智能体列表页：全部启用 agent 卡片网格，点击进入各自详情页
      path: '/agents',
      name: 'agents',
      component: () => import('@/views/AgentsView.vue'),
      meta: { title: '智能体' }
    },
    {
      // 智能体详情页：该 agent 的目标 todolist + 台账流水（?tab=objectives|ledger 可深链）
      path: '/agents/:agentId(\\d+)',
      name: 'agent-detail',
      component: () => import('@/views/AgentDetailView.vue'),
      meta: { title: '智能体' }
    },
    {
      // 旧 M4.1 目标页 → redirect 到智能体详情页目标 Tab（收藏夹/历史链接不死）
      path: '/agents/:agentId(\\d+)/objectives',
      redirect: (to) => ({ path: `/agents/${to.params.agentId}`, query: { tab: 'objectives' } })
    },
    {
      // 旧 M4.2 单 agent 台账页 → redirect 到智能体详情页台账 Tab
      path: '/agents/:agentId(\\d+)/ledger',
      redirect: (to) => ({ path: `/agents/${to.params.agentId}`, query: { tab: 'ledger' } })
    },
    {
      // M4.2：台账聚合视图 /ledger（全部 agent 流水，顶部 agent 切换器）
      path: '/ledger',
      name: 'ledger',
      component: () => import('@/views/LedgerView.vue'),
      meta: { title: '台账' }
    },
    {
      // 目标聚合视图 /objectives（全部 agent 私域目标，按 agent 分组卡片）
      path: '/objectives',
      name: 'objectives',
      component: () => import('@/views/ObjectivesView.vue'),
      meta: { title: '目标' }
    },
    {
      // M1 门户包：消息流转视图 /bus（agent 消息总线投递流水总览，匿名只读）
      path: '/bus',
      name: 'bus',
      component: () => import('@/views/BusView.vue'),
      meta: { title: '消息流转' }
    },
    {
      // M3.8：旧 /notebook?notebookId=&nid= 兼容重定向（收藏夹/历史链接不死）
      path: '/notebook',
      redirect: (to) => {
        if (!to.query.notebookId) return '/'
        return legacyNotebookRedirect(to.query.notebookId, to.query.nid)
      }
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { title: '页面不存在' }
    }
  ],
  // 切换路由后滚动到顶部
  scrollBehavior: () => ({ top: 0 })
})

/** M3.8：旧 query 形态深链（?notebookId=&nid=）→ 新 RESTful 路径 /notebooks/{id}[/notes/{nid}] */
function legacyNotebookRedirect(notebookId: unknown, nid: unknown): { path: string } {
  const id = String(notebookId)
  const n = typeof nid === 'string' && nid ? nid : ''
  return n ? { path: `/notebooks/${id}/notes/${n}` } : { path: `/notebooks/${id}` }
}

// 同步页面标题（站点标题由 store 动态维护）
router.afterEach((to) => {
  const title = String(to.meta?.title || '')
  document.title = title ? `${title} - ${siteTitle.value}` : siteTitle.value
})

export default router
