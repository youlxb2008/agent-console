#!/usr/bin/env node
/**
 * 启动后端 Spring Boot 服务
 *
 * 职责：
 *   1. 兜底设置 JAVA_HOME（新终端可能未配置环境变量）
 *   2. 通过 Maven 启动 spring-boot:run
 *
 * 使用方式：node scripts/start-api.mjs
 * 在根目录 pnpm dev:api 中被调用
 */
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { platform } from 'node:os'
import { resolve } from 'node:path'
import { localEnvValue, LOCAL_ENV_FILE, loadLocalEnv } from '../config/load-env.mjs'

const isWin = platform() === 'win32'

/* 本地环境变量（来自 src/config/.env.local，git 不提交）
 * 生效路径：
 *   - H2 数据库文件 → ${DATA_ROOT}/db/webadmin
 *   - 笔记目录      → ${DATA_ROOT}/notes（未单独设置 NOTES_ROOT 时跟随，见 application-dev.yml notes.root）
 * 优先级：已设置的系统环境变量 > .env.local 文件值 > 兜底默认值
 */
const devDataRoot = localEnvValue('DATA_ROOT', 'H:/data_space/webadminData')
const devNotesRoot = localEnvValue('NOTES_ROOT', `${devDataRoot}/notes`)

/* 兜底：如果当前环境未设置 JAVA_HOME，尝试常见路径 */
const JAVA_HOME_CANDIDATES = isWin
  ? ['C:\\Program Files\\Java\\jdk-17', 'C:\\Program Files\\Java\\jdk-21', 'C:\\Program Files\\Java\\jdk-11']
  : ['/usr/lib/jvm/java-17-openjdk', '/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home']

let javaHome = process.env.JAVA_HOME
if (!javaHome) {
  javaHome = JAVA_HOME_CANDIDATES.find((p) => existsSync(p)) || ''
  if (javaHome) {
    console.log(`\x1b[33m[start-api]\x1b[0m JAVA_HOME 未设置，使用默认路径：${javaHome}`)
  } else {
    console.error('\x1b[31m[start-api]\x1b[0m 未找到 JDK，请手动设置 JAVA_HOME 环境变量')
    process.exit(1)
  }
}

/* 后端项目目录（相对于本脚本位置：../backend） */
const backendDir = resolve(import.meta.dirname, '..', 'backend')

/* 启动 Maven 命令 */
const mvnCmd = isWin ? 'mvn.cmd' : 'mvn'
const args = ['spring-boot:run', '-s', 'settings.xml']

console.log(`\x1b[32m[start-api]\x1b[0m 启动后端：cd backend && mvn spring-boot:run -s settings.xml`)
console.log(`\x1b[90m[start-api]\x1b[0m 本地环境文件=${LOCAL_ENV_FILE}（已加载键：${Object.keys(loadLocalEnv()).join(', ') || '无'}）`)
console.log(`\x1b[90m[start-api]\x1b[0m 数据根目录(DATA_ROOT)=${devDataRoot}`)
console.log(`\x1b[90m[start-api]\x1b[0m 笔记根目录(NOTES_ROOT)=${devNotesRoot}`)

/* spawn 子进程，stdio 直通父进程（concurrently 会捕获输出并染色）
 * Windows 下执行 .cmd/.bat 文件必须设置 shell: true，否则报 spawn EINVAL */
const child = spawn(mvnCmd, args, {
  cwd: backendDir,
  env: {
    ...process.env,
    JAVA_HOME: javaHome,
    JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8',
    DATA_ROOT: devDataRoot,
    NOTES_ROOT: devNotesRoot,
  },
  stdio: 'inherit',
  shell: isWin,
})

/* 子进程退出时同步退出当前进程 */
child.on('exit', (code) => {
  process.exit(code ?? 0)
})
