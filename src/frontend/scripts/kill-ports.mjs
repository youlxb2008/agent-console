#!/usr/bin/env node
/**
 * 启动前清理项目固定端口占用的进程
 * 跨平台支持 Windows / macOS / Linux
 *
 * 固定端口：
 *   - 12388 管理端 前端（Vite）
 *
 * 参考/scripts/kill-ports.mjs 实现
 */
import { execSync } from 'node:child_process'
import { platform } from 'node:os'

/* 项目固定使用的端口列表 */
const PORTS = [12388]
/* 是否为 Windows 环境（命令不同） */
const isWin = platform() === 'win32'

/**
 * 杀掉占用指定端口的进程
 * @param {number} port 端口号
 */
function killPort(port) {
  if (isWin) {
    /* Windows：用 netstat 查询监听该端口的 PID，再 taskkill 强制终止 */
    try {
      const output = execSync(`netstat -ano | findstr :${port}`, {
        encoding: 'utf8',
        stdio: ['pipe', 'pipe', 'ignore'],
      })
      /* netstat 输出按行解析，提取最后一列 PID（去重） */
      const pids = new Set()
      output
        .split('\n')
        .filter(Boolean)
        .forEach((line) => {
          const parts = line.trim().split(/\s+/)
          /* 仅处理 LISTENING 状态且端口匹配的行 */
          if (parts.length >= 5 && parts[1].endsWith(`:${port}`)) {
            const pid = parts[parts.length - 1]
            if (pid && pid !== '0') pids.add(pid)
          }
        })
      /* 逐个终止进程 */
      pids.forEach((pid) => {
        try {
          execSync(`taskkill /F /PID ${pid}`, { stdio: 'ignore' })
          console.log(`  \x1b[32m[kill-ports]\x1b[0m 已终止 PID ${pid}（端口 ${port}）`)
        } catch {
          /* 进程可能已退出，忽略 */
        }
      })
    } catch {
      /* netstat 无输出（端口未占用）会返回非零退出码，忽略 */
    }
  } else {
    /* macOS / Linux：lsof 查询监听端口的 PID 并 kill -9 */
    try {
      execSync(`lsof -ti:${port} | xargs kill -9`, { stdio: 'ignore' })
      console.log(`  \x1b[32m[kill-ports]\x1b[0m 已清理端口 ${port}`)
    } catch {
      /* 端口未被占用，忽略 */
    }
  }
}

console.log('\x1b[36m[kill-ports]\x1b[0m 检查端口占用情况...')
PORTS.forEach(killPort)
console.log('\x1b[36m[kill-ports]\x1b[0m 端口清理完成\n')
