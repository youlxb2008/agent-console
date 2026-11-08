#!/usr/bin/env node
/**
 * 门户端造数脚本：目标（objective）+ 台账（ledger）
 *
 * 用途：为已有 agent（企业知识/智能助手/数仓三类演示身份）补充「目标 todolist」与「台账流水」演示数据，
 *       填充门户端 ObjectiveView（/agents/:id/objectives）与 LedgerView（/ledger 聚合、单 agent 流水）。
 *
 * 接口（契约 docs/接口契约/api.md M4.1 / M4.2）：
 *   1. 登录 admin 拿 JWT                       → POST /system/user/login（旧 system 域，无 /api 前缀）
 *   2. 查询启用 agent 列表                      → GET  /api/portal/agents
 *   3. 管理员代建目标（agentId 走 query）        → POST  /api/objective/admin/objectives?agentId={id}
 *      - create 仅写「描述」段且 progress 固定 0；进度由 PATCH descMd 后全文解析 checkbox 重算
 *      - 因此两步走：create（纯描述）→ PATCH（描述 + checkbox 子项 + status）
 *   4. 管理员代记台账（agentId 走 query）        → POST /api/ledger/admin/ledger?agentId={id}
 *      - 部分台账 refObjectiveId 引用刚建的目标 oid，门户台账可跳转目标页
 *
 * 幂等：清理重建模式——按标题匹配本次演示数据，先软删旧目标/台账再重建，可安全重复执行。
 * 说明：使用 Node 内置 fetch，规避 PowerShell 中文编码乱码问题。
 */
const BASE = 'http://localhost:8000/api'
const LOGIN_URL = 'http://localhost:8000/system/user/login'

/* ===== 演示数据：按 agent name 分组（name 与 /api/portal/agents 返回一致） =====
 * 目标两段式：desc = 纯描述文字；subs = checkbox 子项（done 决定进度，PATCH 后重算）
 * 台账 type 五类：progress 任务推进 / observation 巡检发现 / decision 拍板决策 / memo 备忘 / session 会话小结
 */
const SEED_DATA = [
  {
    agentName: 'agent-fine', // 企业知识助手（演示）
    objectives: [
      {
        title: '完成月度经营分析大屏开发',
        period: '2026-Q3',
        targetDate: '2026-09-15',
        status: 1, // 进行中
        desc: [
          '面向管理层经营驾驶舱场景，基于 FineReport 完成月度经营分析大屏，覆盖营收、成本、人效三大主题。',
        ].join('\n'),
        subs: [
          { done: true, text: '指标口径对齐（财务/业务双方确认）' },
          { done: true, text: '数据集与 SQL 开发' },
          { done: true, text: '大屏布局与视觉稿评审' },
          { done: false, text: '图表联动与钻取配置' },
          { done: false, text: '移动端适配验收' },
        ],
        reflect: '指标口径必须先行冻结，避免返工。',
      },
      {
        title: '填报模板规范治理',
        period: '2026-Q3',
        targetDate: '2026-09-30',
        status: 1,
        desc: '收敛各业务线填报模板风格，统一命名、校验与提交流程，降低维护成本。',
        subs: [
          { done: true, text: '盘点存量模板（共 46 个）' },
          { done: false, text: '输出规范文档 v1' },
          { done: false, text: '试点 2 条业务线' },
        ],
        reflect: '存量盘点比预期多花 2 天，下次先定粒度标准。',
      },
      {
        title: '旧版报表迁移收尾',
        period: '2026-Q2',
        targetDate: '2026-08-20',
        status: 2, // 已完成
        desc: '将旧服务器上 12 张存量报表迁移至新集群并下线旧链路。',
        subs: [
          { done: true, text: '报表清单确认' },
          { done: true, text: '迁移与数据比对' },
          { done: true, text: '旧链接 302 下线' },
        ],
        reflect: '比对脚本复用性强，后续迁移直接复用。',
      },
    ],
    ledgers: [
      /* refObjectiveIdx：引用本 agent objectives 数组下标，建完目标后回填 oid */
      { type: 'progress', title: '大屏图表联动开发完成 60%，营收主题已联调', refObjectiveIdx: 0, body: '今日完成营收主题与明细表的联动钻取，剩余成本主题预计明日联调。' },
      { type: 'observation', title: '发现填报模板存在 3 处校验缺失', refObjectiveIdx: 1, body: '销售线索模板缺手机号格式校验，费用报销模板缺日期范围校验，已记录待修复。' },
      { type: 'decision', title: '拍板：大屏配色采用深色方案', refObjectiveIdx: 0, body: '与设计确认深色方案对比度更好，投影场景可读性更佳。' },
      { type: 'memo', title: '周五前提供 8 月经营数据快照给财务', body: '用于财务月度对账，注意口径使用已冻结版本 v1.2。' },
    ],
  },
  {
    agentName: 'agent-cloud', // 智能助手（演示）
    objectives: [
      {
        title: '智能助手意图识别准确率提升至 92%',
        period: '2026-Q3',
        targetDate: '2026-09-20',
        status: 1,
        desc: '针对近 30 天 badcase 复盘，优化意图识别模型的召回与排序，目标准确率从 87% 提升至 92%。',
        subs: [
          { done: true, text: 'badcase 标注与归因分析（1200 条）' },
          { done: true, text: '补充训练语料 800 条' },
          { done: false, text: '模型微调与离线评测' },
          { done: false, text: '灰度上线与 AB 验证' },
        ],
        reflect: '多轮对话场景的意图漂移是主要误差来源，需引入上下文特征。',
      },
      {
        title: '知识库语料季度轮换',
        period: '2026-Q3',
        targetDate: '2026-09-10',
        status: 1,
        desc: '按季度机制轮换知识库语料：下线过期公告、补充新产品文档、修正高频错误答案。',
        subs: [
          { done: true, text: '过期语料清单确认（37 篇）' },
          { done: false, text: '新产品 FAQ 撰写' },
          { done: false, text: '全量回归问答评测' },
        ],
        reflect: 'FAQ 撰写依赖产品文档到位，需提前一周催办。',
      },
      {
        title: '对话导出功能上线',
        period: '2026-Q2',
        targetDate: '2026-08-15',
        status: 2,
        desc: '支持会话一键导出 Markdown/CSV，便于用户归档与团队复盘。',
        subs: [
          { done: true, text: '导出格式设计' },
          { done: true, text: '开发与联调' },
          { done: true, text: '全量发布' },
        ],
        reflect: '小功能也要走完整灰度流程，本次灰度提前发现编码问题。',
      },
    ],
    ledgers: [
      { type: 'progress', title: '意图模型微调完成，离线准确率 90.8%', refObjectiveIdx: 0, body: '距离 92% 目标还差 1.2pp，计划补充多轮对话上下文特征再训一版。' },
      { type: 'observation', title: '灰度监控发现「转人工」误触发率上升', body: '新版本在寒暄场景误触发转人工，占比 0.8%，定位为阈值配置问题。' },
      { type: 'decision', title: '拍板：FAQ 冷启动先覆盖 Top50 高频问题', refObjectiveIdx: 1, body: '全量 FAQ 撰写周期长，先覆盖 Top50 快速止血，剩余分批补齐。' },
      { type: 'session', title: '与产品周会对齐 Q3 OKR 度量口径', body: '确认准确率统一采用人工抽检 + 线上埋点双口径，周会同步进展。' },
    ],
  },
  {
    agentName: 'agent-dw', // 数仓笔记本
    objectives: [
      {
        title: '数仓分层模型重构（ODS/DWD/DWS）',
        period: '2026-Q4',
        targetDate: '2026-10-31',
        status: 1,
        desc: '重构贴源层明细口径混乱问题，按 ODS → DWD → DWS 标准三层重构核心业务域模型。',
        subs: [
          { done: true, text: '现状盘点与血缘梳理' },
          { done: true, text: 'DWD 交易域模型设计评审' },
          { done: false, text: 'DWD 交易域落表与回刷' },
          { done: false, text: 'DWS 汇总层切换' },
          { done: false, text: '下游任务迁移验证' },
        ],
        reflect: '血缘梳理工具化后效率翻倍，重构前先补齐元数据。',
      },
      {
        title: '数据质量监控体系上线',
        period: '2026-Q3',
        targetDate: '2026-08-25',
        status: 2,
        desc: '为核心表接入完整性/唯一性/及时性三类质量规则，异常自动告警到值班群。',
        subs: [
          { done: true, text: '规则设计与阈值评审' },
          { done: true, text: '核心表接入（28 张）' },
          { done: true, text: '告警链路演练' },
        ],
        reflect: '阈值宁松勿紧，先上线再逐步收紧，避免告警疲劳。',
      },
    ],
    ledgers: [
      { type: 'progress', title: 'DWD 交易域回刷完成 70%（近 90 天分区）', refObjectiveIdx: 0, body: '回刷脚本幂等可断点重跑，剩余分区预计两日内完成。' },
      { type: 'observation', title: '巡检发现 dws_user_1d 与报表口径偏差 0.3%', body: '定位为渠道归属去重规则不一致，已提需求单统一口径。' },
      { type: 'decision', title: '拍板：回刷窗口避开月底结算（每月 25 日后暂停）', refObjectiveIdx: 0, body: '与财务结算任务错峰，避免 IO 争抢与口径干扰。' },
      { type: 'observation', title: '质量监控拦截一次源表断流', body: '凌晨 ODS 订单表断流 40 分钟，告警及时，值班按预案补数。' },
      { type: 'memo', title: '下周三组织分层模型评审会（邀请业务方）', body: '重点评审 DWS 汇总粒度是否满足报表与自助分析两类场景。' },
    ],
  },
]

/** 组装 descMd：描述 + checkbox 子项 + 反思（「## 描述」段内容，calcProgress 全文统计 checkbox） */
function buildDescMd(obj) {
  const parts = [obj.desc, '', ...obj.subs.map((s) => `- [${s.done ? 'x' : ' '}] ${s.text}`)]
  if (obj.reflect) parts.push('', `> 反思：${obj.reflect}`)
  return parts.join('\n')
}

/** 统一请求封装：自动带 JSON 头，返回解析后的 body */
async function call(method, path, { token, body } = {}) {
  const headers = { Accept: 'application/json' }
  if (body) headers['Content-Type'] = 'application/json; charset=utf-8'
  if (token) headers.Authorization = token

  // path 以 http 开头视为完整 URL（登录接口不带 /api 前缀），否则拼 BASE
  const url = /^https?:\/\//.test(path) ? path : BASE + path
  const res = await fetch(url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
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
    body: { username: 'admin', password: '123456' },
  })
  const token = login.data.accessToken
  console.log(`✅ 登录成功，token 前 10 位: ${token.slice(0, 10)}`)

  // 2. 查询启用 agent 列表，按 name 建立索引（造数目标只挂已有 agent，不重复建 agent）
  const agentsRes = await call('GET', '/portal/agents', { token })
  const agents = agentsRes.data || []
  const agentByByName = new Map(agents.map((a) => [a.name, a]))
  console.log(`🧑‍🚀 当前启用 agent: ${agents.map((a) => `${a.name}(id=${a.id})`).join(', ')}`)

  // 3. 逐 agent：清理旧演示数据 → 造目标 → 记台账
  for (const seed of SEED_DATA) {
    const agent = agentByByName.get(seed.agentName)
    if (!agent) {
      console.warn(`⚠️ 未找到 agent ${seed.agentName}，跳过（请先运行 seed-portal-demo.mjs）`)
      continue
    }

    /* --- 3.1 幂等清理：软删本 agent 下标题命中的旧目标（释放唯一标题，oid 同步换新） --- */
    const objTitles = new Set(seed.objectives.map((o) => o.title))
    const existObj = await call('GET', `/objective/admin/objectives/agents/${agent.id}/objectives`, { token })
    for (const o of existObj.data || []) {
      if (objTitles.has(o.title)) {
        await call('DELETE', `/objective/admin/objectives/${o.oid}`, { token })
        console.log(`   🧹 已清理旧目标: ${o.title} (oid=${o.oid})`)
      }
    }

    /* --- 3.2 造目标两步走：create 只写纯描述（progress 恒 0）→ PATCH 覆写描述段为
     *     「描述 + checkbox 子项 + 反思」并置状态，触发后端全文解析重算 progress --- */
    const oidByTitle = new Map()
    for (const obj of seed.objectives) {
      const created = await call('POST', `/objective/admin/objectives?agentId=${agent.id}`, {
        token,
        body: { title: obj.title, period: obj.period, targetDate: obj.targetDate, descMd: obj.desc },
      })
      const oid = created.data.oid
      oidByTitle.set(obj.title, oid)
      console.log(`   🎯 建目标: ${obj.title} (oid=${oid})`)

      // PATCH 描述段（含 checkbox）触发 progress 重算；仅改 status 不触发，故两者合并为一次请求
      await call('PATCH', `/objective/admin/objectives/${oid}`, {
        token,
        body: { status: obj.status, descMd: buildDescMd(obj) },
      })
      console.log(`   🔧 覆写子项并置状态 status=${obj.status}: ${obj.title}`)
    }

    /* --- 3.3 幂等清理：软删本 agent 下标题命中的旧台账（旧 refObjectiveId 已失效，需重建） --- */
    const ledTitles = new Set(seed.ledgers.map((l) => l.title))
    const existLedger = await call('GET', `/ledger/admin/ledger?agentId=${agent.id}&page=1&size=100`, { token })
    for (const l of existLedger.data.list || []) {
      if (ledTitles.has(l.title)) {
        await call('DELETE', `/ledger/admin/ledger/${l.lid}`, { token })
        console.log(`   🧹 已清理旧台账: ${l.title} (lid=${l.lid})`)
      }
    }

    /* --- 3.4 记台账：refObjectiveIdx 回填本组新目标 oid，门户台账可点击跳转目标页 --- */
    for (const led of seed.ledgers) {
      await call('POST', `/ledger/admin/ledger?agentId=${agent.id}`, {
        token,
        body: {
          type: led.type,
          title: led.title,
          body: led.body,
          refObjectiveId: led.refObjectiveIdx !== undefined ? oidByTitle.get(seed.objectives[led.refObjectiveIdx].title) || null : null,
        },
      })
      console.log(`   📒 记台账 [${led.type}]: ${led.title}`)
    }
  }

  // 4. 验证门户只读接口：目标 + 聚合台账
  for (const seed of SEED_DATA) {
    const agent = agentByByName.get(seed.agentName)
    if (!agent) continue
    const obj = await call('GET', `/portal/agents/${agent.id}/objectives`)
    console.log(`\n🎯 门户目标 [${agent.name}]: ${(obj.data || []).map((o) => `${o.title}(进度${o.progress}%,状态${o.status})`).join(' | ')}`)
  }
  const ledger = await call('GET', '/portal/ledger')
  console.log(`\n📒 门户聚合台账共 ${(ledger.data || []).length} 条（最新 3 条）:`)
  for (const it of (ledger.data || []).slice(0, 3)) {
    console.log(`   - [${it.type}] ${it.title} @ ${it.agentName} ${it.createTime}`)
  }

  console.log('\n🎉 目标 + 台账造数完成！浏览器验证：http://localhost:12488 （目标 / 台账页）')
}

main().catch((e) => {
  console.error('\n❌ 造数失败:', e.message)
  process.exit(1)
})
