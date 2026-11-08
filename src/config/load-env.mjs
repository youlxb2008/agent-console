#!/usr/bin/env node
/**
 * 本地环境文件加载器
 * -------------------------------------------------
 * 负责读取 src/config/.env.local（git 不提交的本地配置），
 * 为启动脚本（start-api / start-web / start-portal 等）提供统一的环境变量入口。
 *
 * 优先级：已存在的进程环境变量 > .env.local 文件值 > 调用方兜底默认值
 * （与 dotenv 默认行为一致：不覆盖已设置的系统环境变量）
 *
 * .env.local 中部分常用项：
 *   DATA_ROOT=本地 H2 数据库目录（H2 库文件落 {DATA_ROOT}/db/webadmin）
 *   NOTES_ROOT=笔记文件根目录（不设置时跟随 {DATA_ROOT}/notes）
 */
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/** .env.local 文件绝对路径 */
export const LOCAL_ENV_FILE = resolve(import.meta.dirname, '.env.local')

/**
 * 解析 KEY=VALUE 格式文本（支持 # 注释行、空行、引号取值、CRLF/LF 换行）
 * @param {string} content 文件内容
 * @returns {Record<string, string>}
 */
export function parseEnvFile(content) {
  const env = {}
  for (const raw of content.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq <= 0) continue
    const key = line.slice(0, eq).trim()
    let value = line.slice(eq + 1).trim()
    // 去掉成对引号
    if (value.length >= 2 && (value.startsWith('"') && value.endsWith('"') || value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    // 去掉行尾内联注释（引号外：# 开头）
    const hash = value.search(/#/)
    if (hash >= 0) {
      const before = value.slice(0, hash)
      // 仅当 # 前为空白时才视为注释（避免 URL 等含 # 的值被截断）
      if (/\s$/.test(before)) value = before.trimEnd()
    }
    if (key && value !== undefined) env[key] = value
  }
  return env
}

/**
 * 加载 src/config/.env.local（文件缺失返回空对象并打印提示）
 * @returns {Record<string, string>}
 */
export function loadLocalEnv() {
  if (!existsSync(LOCAL_ENV_FILE)) {
    console.warn(`[load-env] 未找到本地环境文件：${LOCAL_ENV_FILE}`)
    console.warn(`[load-env] 请复制 src/config/.env.example 为 src/config/.env.local 并按本机调整\n`)
    return {}
  }
  try {
    return parseEnvFile(readFileSync(LOCAL_ENV_FILE, 'utf-8'))
  } catch (err) {
    console.warn(`[load-env] 读取 ${LOCAL_ENV_FILE} 失败：${err.message}，已忽略\n`)
    return {}
  }
}

/**
 * 读取本地配置项（进程环境变量优先，其次 .env.local，最后兜底默认值）
 * @param {string} key 配置项名称，如 DATA_ROOT
 * @param {string} [fallback=''] 兜底默认值
 * @returns {string}
 */
export function localEnvValue(key, fallback = '') {
  return process.env[key] ?? loadLocalEnv()[key] ?? fallback
}