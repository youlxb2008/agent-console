#!/usr/bin/env node
/**
 * 门户端演示数据造数脚本 v3.5（独立笔记本实体 + agent↔笔记本 N:N）
 *
 * 用途：为门户端（portal）造一批「笔记本 → agent → 笔记」数据，便于本地联调
 * 门户端 HomeView（笔记本卡片/聚合）、NotesView（目录树/搜索/tags/recent/content）。
 *
 * 接口（契约 docs/接口契约/api.md v4.0）：
 *   1. 登录 admin 拿 JWT           → POST /system/user/login（旧 system 域，无 /api 前缀）
 *   2. 创建 3 个笔记本（active）    → POST /api/system/notebook
 *   3. 创建 3 个 active agent      → POST /api/agent/admin/agents
 *   4. 关联 notebook↔agent（全量覆盖） → PUT /api/system/notebook/{id}/agents
 *   5. 为每 agent 生成 key        → POST /api/agent/admin/keys（明文仅返回一次）
 *   6. 用 key 写笔记（必填 notebookId）→ POST /api/agent/notes（X-API-Key 鉴权）
 *   7. 验证门户只读接口（notebooks/树/search/tags/recent/content）
 *
 * 说明：
 *   - 后端接口本身带 /api 前缀（notebook/agent/portal 域），登录接口不带 /api。
 *   - 使用 Node 内置 fetch，规避 PowerShell 中文编码乱码问题。
 *   - 幂等：先按 name 清理已存在的演示笔记本/agent（软删/逻辑删）再重建。
 */
const BASE = 'http://localhost:8000/api'
const LOGIN_URL = 'http://localhost:8000/system/user/login'

// 演示数据：笔记本 → 关联 agent → 每个 agent 维护的笔记
const NAVY = 'ph:books'
const DEMO_NOTEBOOKS = [
  {
    name: '技术知识库',
    description: '开发团队的技术文档、组件规范与踩坑记录。',
    cover: '',
    status: 1,
    agents: ['alpha', 'bravo'], // 关联的 agent（按 name 引用）
  },
  {
    name: '运营数据室',
    description: '运营周报、监控大盘与数据分析沉淀。',
    cover: '',
    status: 1,
    agents: ['gamma', 'alpha'],
  },
  {
    name: 'Team 协作手册',
    description: '团队协作流程、会议纪要与快速上手指南。',
    cover: '',
    status: 1,
    agents: ['alpha', 'bravo', 'gamma'],
  },
  {
    name: '内部资料库（已隐藏）',
    description: 'hidden=0 仅管理端可见，不出现在门户。',
    cover: '',
    status: 0,
    agents: ['alpha'],
  },
]

// 每个 agent 的档案 + 各自维护的笔记（notebook 用 name 引用）
const DEMO_AGENTS = [
  {
    name: 'alpha',
    displayName: 'Alpha',
    description: '全能助理，负责日常记录、巡检与知识整理。',
    notes: [
      {
        notebook: '技术知识库',
        dirPath: '工作日志/巡检记录',
        title: '2026-08-29 巡检报告',
        tags: '巡检,日报',
        contentMd: [
          '# 2026-08-29 巡检报告',
          '',
          '## 概览',
          '- **巡检范围**：生产环境 3 台节点',
          '- **结果**：全部正常，无告警',
          '',
          '## 明细',
          '| 节点 | CPU | 内存 | 状态 |',
          '|------|-----|------|------|',
          '| node-01 | 32% | 61% | 正常 |',
          '| node-02 | 48% | 74% | 正常 |',
          '| node-03 | 21% | 55% | 正常 |',
          '',
          '> 建议：node-02 内存偏高，下周重点关注。'
        ].join('\n')
      },
      {
        notebook: '技术知识库',
        dirPath: '工作日志',
        title: '2026-08-30 晨会纪要',
        tags: '会议',
        contentMd: [
          '# 2026-08-30 晨会纪要',
          '',
          '## 待办',
          '1. 完成门户端 notebook 维度联调',
          '2. 补充 agent↔notebook 关联测试用例',
          '',
          '## 结论',
          '门户端 v3.5 已按契约联调，无阻塞项。'
        ].join('\n')
      },
      {
        notebook: 'Team 协作手册',
        dirPath: '',
        title: '快速上手',
        tags: '教程',
        contentMd: [
          '# 快速上手',
          '',
          '> 本文档介绍 agent-console 门户端的一键本地启动方式。',
          '',
          '## 启动命令',
          '```bash',
          'pnpm dev',
          '```',
          '',
          '启动后访问 http://localhost:12488 查看门户端。',
          '',
          '## 组织方式',
          '- 「笔记管理」下含笔记列表与笔记本列表两个入口',
          '- 「日志管理」下含审计日志',
          '- 门户按笔记本维度浏览与检索'
        ].join('\n')
      }
    ]
  },
  {
    name: 'bravo',
    displayName: 'Bravo',
    description: '前端开发助手，关注技术文档、组件设计与工程实践。',
    notes: [
      {
        notebook: '技术知识库',
        dirPath: '前端开发',
        title: '组件规范',
        tags: '前端,规范',
        contentMd: [
          '# 组件规范',
          '',
          '## 命名',
          '- 组件文件：PascalCase',
          '- Props 使用 `camelCase`',
          '',
          '## 风格',
          '- 推荐 Composition API 与 `<script setup>`'
        ].join('\n')
      },
      {
        notebook: '技术知识库',
        dirPath: '前端开发',
        title: 'ElementPlus 踩坑记录',
        tags: '前端,踩坑',
        contentMd: [
          '# ElementPlus 踩坑记录',
          '',
          '1. 按需导入需配合 unplugin 插件',
          '2. `el-form` 校验回显需手动触发',
          '',
          '## 小结',
          '遇到组件行为异常先查文档，再看 issue。'
        ].join('\n')
      },
      {
        notebook: 'Team 协作手册',
        dirPath: '工程实践',
        title: 'Vite 构建优化实践',
        tags: '工程,Vite',
        contentMd: [
          '# Vite 构建优化实践',
          '',
          '## 分包策略',
          '将 vue/element-plus/echarts 拆为独立 chunk，提升首屏加载。'
        ].join('\n')
      }
    ]
  },
  {
    name: 'gamma',
    displayName: 'Gamma',
    description: '数据分析助手，产出监控报告与运营统计。',
    notes: [
      {
        notebook: '运营数据室',
        dirPath: '数据分析/周报',
        title: '2026-08 第35周运营周报',
        tags: '周报,数据',
        contentMd: [
          '# 2026-08 第 35 周运营周报',
          '',
          '## 关键指标',
          '- **新增用户**：+18%',
          '- **次日留存率**：42%',
          '',
          '## 洞察',
          '本周活跃稳步上升，建议加大对内容推荐位的曝光。'
        ].join('\n')
      },
      {
        notebook: '运营数据室',
        dirPath: '数据分析',
        title: '监控大盘设计',
        tags: '数据,可视化',
        contentMd: [
          '# 监控大盘设计',
          '',
          '> 面向运营的一站式可视化看板。',
          '',
          '## 模块',
          '- 核心指标卡片',
          '- 趋势折线图',
          '- 异常事件列表'
        ].join('\n')
      }
    ]
  }
]

/** 统一请求封装：自动带 JSON 头，返回解析后的 body */
async function call(method, path, { token, apiKey, body } = {}) {
  const headers = { Accept: 'application/json' }
  if (body) headers['Content-Type'] = 'application/json; charset=utf-8'
  if (token) headers.Authorization = token
  if (apiKey) headers['X-API-Key'] = apiKey

  // path 以 http 开头视为完整 URL（如登录接口不带 /api 前缀），否则拼 BASE
  const url = /^https?:\/\//.test(path) ? path : BASE + path
  const res = await fetch(url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  })
  const text = await res.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    throw new Error(`HTTP ${res.status} 非JSON响应: ${text}`)
  }
  if (res.status >= 400 || json.success === false) {
    throw new Error(`HTTP ${res.status} 业务失败: ${json.msg || text}`)
  }
  return json
}

async function main() {
  // 1. 登录 admin，拿到管理端 JWT
  const login = await call('POST', LOGIN_URL, {
    body: { username: 'admin', password: '123456' }
  })
  const token = login.data.accessToken
  console.log(`✅ 登录成功，token 前 10 位: ${token.slice(0, 10)}`)

  // 2. 幂等清理：删除已存在的演示笔记本（软删，name 会改写释放唯一键）
  const nbPage = await call('GET', '/system/notebook/page?page=1&size=100', { token })
  const nbRecords = nbPage.data.list || nbPage.data.records || []
  const demoNbNames = DEMO_NOTEBOOKS.map((n) => n.name)
  for (const nb of nbRecords) {
    if (demoNbNames.includes(nb.name)) {
      await call('DELETE', `/system/notebook/${nb.id}`, { token })
      console.log(`🧹 已清理旧笔记本: ${nb.name} (id=${nb.id})`)
    }
  }

  // 幂等清理：删除已存在的演示 agent（逻辑删）
  const page = await call('GET', '/agent/admin/agents?page=1&size=100', { token })
  const records = page.data.list || page.data.records || []
  const demoNames = DEMO_AGENTS.map((d) => d.name)
  for (const agent of records) {
    if (demoNames.includes(agent.name)) {
      await call('DELETE', `/agent/admin/agents/${agent.id}`, { token })
      console.log(`🧹 已清理旧 agent: ${agent.name} (id=${agent.id})`)
    }
  }

  // 3. 创建笔记本（name → id 映射）
  const notebookIdByName = {}
  for (const nb of DEMO_NOTEBOOKS) {
    const created = await call('POST', '/system/notebook', {
      token,
      body: {
        name: nb.name,
        description: nb.description,
        cover: nb.cover,
        status: nb.status
      }
    })
    notebookIdByName[nb.name] = created.data.id
    console.log(`📚 创建笔记本: ${nb.name} (id=${created.data.id}, status=${nb.status})`)
  }

  // 4. 创建 agent（name → id 映射）
  const agentIdByName = {}
  for (const agent of DEMO_AGENTS) {
    const created = await call('POST', '/agent/admin/agents', {
      token,
      body: {
        name: agent.name,
        displayName: agent.displayName,
        description: agent.description,
        status: 1
      }
    })
    agentIdByName[agent.name] = created.data.id
    console.log(`🧑‍🚀 创建 agent: ${agent.name} (id=${created.data.id})`)
  }

  // 5. 关联 notebook↔agent（全量覆盖）
  for (const nb of DEMO_NOTEBOOKS) {
    const ids = nb.agents.map((n) => agentIdByName[n])
    await call('PUT', `/system/notebook/${notebookIdByName[nb.name]}/agents`, {
      token,
      body: { agentIds: ids }
    })
    console.log(`🔗 关联: ${nb.name} ← ${nb.agents.join(', ')}`)
  }

  // 6. 逐 agent：生成 key → 写笔记（必填 notebookId）
  for (const agent of DEMO_AGENTS) {
    const keyRes = await call('POST', '/agent/admin/keys', {
      token,
      body: { agentId: agentIdByName[agent.name], scopes: ['notes:write', 'audit:read'] }
    })
    const plainKey = keyRes.data.key
    console.log(`🔑 生成 key: ${agent.name} (${keyRes.data.keyPrefix})`)

    for (const note of agent.notes) {
      const wrote = await call('POST', '/agent/notes', {
        apiKey: plainKey,
        body: {
          notebookId: notebookIdByName[note.notebook],
          dirPath: note.dirPath,
          title: note.title,
          contentMd: note.contentMd,
          tags: note.tags
        }
      })
      console.log(`   📝 写入笔记: [${note.notebook}] ${note.dirPath || '/'}${note.title} (nid=${wrote.data.nid})`)
    }
  }

  // 7. 验证门户只读接口（notebooks 聚合 / 树 / search / tags / recent / content）
  const portalNb = await call('GET', '/portal/notebooks')
  console.log('\n📣 门户笔记本概览（status 聚合，hidden 应不出现）:')
  console.log(JSON.stringify(portalNb.data, null, 2))

  const activeNb = portalNb.data[0]
  if (activeNb) {
    const tree = await call('GET', `/portal/notebooks/${activeNb.id}/notes/tree`)
    console.log(`\n🌲 目录树 [${activeNb.name}]:`)
    console.log(JSON.stringify(tree.data, null, 2))

    const recent = await call('GET', `/portal/notebooks/${activeNb.id}/notes/recent?limit=5`)
    console.log(`\n⏰ 最近更新(top5) [${activeNb.name}]:`)
    for (const n of recent.data) console.log(`   - [${n.title}] ${n.updateTime}`)

    if (recent.data.length) {
      const nid = recent.data[0].nid
      const content = await call('GET', `/portal/notes/${nid}`)
      console.log(`\n📄 笔记内容 [${content.data.title}] (notebookId=${content.data.notebookId}, 贡献者=${content.data.contributors.map((c) => c.name).join(',')})`)
    }

    const tags = await call('GET', `/portal/notebooks/${activeNb.id}/notes/tags`)
    console.log(`\n🏷️  tags 聚合 [${activeNb.name}]:`, tags.data.map((t) => `${t.tag}=${t.count}`).join(', '))

    const search = await call('GET', `/portal/notebooks/${activeNb.id}/notes/search?keyword=巡检`)
    console.log(`🔍 搜索『巡检』:`, search.data.map((n) => n.title).join(', '))
  }

  // 8. 验证管理端审计日志存在 notebook.* 动作记录（agent 域 GET /api/agent/admin/audit）
  const audit = await call('GET', '/agent/admin/audit?page=1&size=20', { token })
  const auditList = audit.data.list || audit.data.records || audit.data || []
  console.log('\n🛡️  最近审计(action 抽样):', auditList.slice(0, 8).map((a) => a.action).join(', '))

  console.log('\n🎉 v3.5 造数完成！浏览器验证：门户 http://localhost:12488 ，管理端 http://localhost:12388/login')
}

main().catch((e) => {
  console.error('\n❌ 造数失败:', e.message)
  process.exit(1)
})
