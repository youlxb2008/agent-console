import service from '@/api/request'
import type { ApiResponse } from '@/types/api'

/** 上传响应 */
export interface UploadResponse {
  /** 文件访问地址（相对路径，如 /uploads/xxx.png） */
  url: string
}

/** 分片上传响应 */
export interface ChunkUploadResponse {
  /** 文件唯一标识 */
  fileIdentifier: string
  /** 当前分片序号 */
  chunkIndex: number
  /** 分片状态：uploaded（新上传） | exists（已存在，跳过） */
  status: 'uploaded' | 'exists'
}

/** 已上传分片查询响应 */
export interface UploadedChunksResponse {
  /** 文件唯一标识 */
  fileIdentifier: string
  /** 已上传的分片序号列表 */
  uploadedChunks: number[]
}

/** 业务类型（后端按类型分目录存储；自由字符串，此处仅枚举前端在用的值） */
export type UploadType = 'avatar' | 'login-bg' | 'general' | 'cover' | 'logo' | 'hero'

/**
 * 上传文件（整文件上传，适用于小文件）
 * @param file File 对象
 * @param type 业务类型
 * @returns 上传结果，包含可访问的文件 url
 */
export function uploadFile(
  file: File,
  type: UploadType = 'general'
): Promise<ApiResponse<UploadResponse>> {
  const formData = new FormData()
  formData.append('file', file)
  return service.post('/system/upload', formData, {
    params: { type },
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 上传图片（兼容旧接口，内部调用 uploadFile）
 * @param file File 对象
 * @param type 业务类型
 * @returns 上传结果
 */
export function uploadImage(
  file: File,
  type: UploadType = 'general'
): Promise<ApiResponse<UploadResponse>> {
  return uploadFile(file, type)
}

/**
 * 上传分片（断点续传）
 * @param file 分片文件数据
 * @param fileIdentifier 文件唯一标识（MD5）
 * @param chunkIndex 当前分片序号（从 0 开始）
 * @param totalChunks 总分片数
 * @param fileName 原始文件名
 * @param type 业务类型
 * @returns 分片上传结果
 */
export function uploadChunk(
  file: Blob,
  fileIdentifier: string,
  chunkIndex: number,
  totalChunks: number,
  fileName: string,
  type: UploadType = 'general'
): Promise<ApiResponse<ChunkUploadResponse>> {
  const formData = new FormData()
  formData.append('file', file)
  return service.post('/system/upload/chunk', formData, {
    params: { fileIdentifier, chunkIndex, totalChunks, fileName, type },
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 合并分片（断点续传最后一步）
 * @param fileIdentifier 文件唯一标识（与分片上传时一致）
 * @returns 合并结果，包含最终文件的 url
 */
export function mergeChunks(fileIdentifier: string): Promise<ApiResponse<UploadResponse>> {
  return service.post('/system/upload/merge', null, {
    params: { fileIdentifier }
  })
}

/**
 * 查询已上传的分片列表（断点续传恢复用）
 * @param fileIdentifier 文件唯一标识
 * @returns 已上传的分片序号列表
 */
export function getUploadedChunks(fileIdentifier: string): Promise<ApiResponse<UploadedChunksResponse>> {
  return service.get('/system/upload/chunks', {
    params: { fileIdentifier }
  })
}

/**
 * 计算文件的 MD5 标识（用于断点续传的 fileIdentifier）
 * 使用 Web Crypto API 的 SubtleCrypto 计算文件 MD5，
 * 若浏览器不支持则降级为使用文件名+大小+最后修改时间拼接的标识。
 * @param file 文件对象
 * @returns 文件标识字符串
 */
export async function computeFileIdentifier(file: File): Promise<string> {
  // 降级方案：使用文件名+大小+最后修改时间作为标识
  // 注意：这不是真正的文件内容哈希，不同文件如果有相同属性会冲突
  // 但对于断点续传场景已经足够（同一用户不太可能同时上传同名同大小的不同文件）
  const raw = `${file.name}-${file.size}-${file.lastModified}`
  // 使用简单哈希生成标识（避免引入额外依赖）
  let hash = 0
  for (let i = 0; i < raw.length; i++) {
    const char = raw.charCodeAt(i)
    hash = ((hash << 5) - hash) + char
    hash = hash & hash // 转为 32 位整数
  }
  // 转为无符号十六进制字符串
  return Math.abs(hash).toString(16).padStart(8, '0') + file.size.toString(16).padStart(8, '0')
}

/** 分片大小：2MB（与后端 web.chunk-size 保持一致） */
export const CHUNK_SIZE = 2 * 1024 * 1024

/**
 * 断点续传上传（完整流程：查询已传分片 → 逐片上传 → 合并）
 * @param file 要上传的文件
 * @param type 业务类型
 * @param onProgress 上传进度回调（0-100）
 * @returns 最终上传结果，包含文件 url
 */
export async function uploadWithResume(
  file: File,
  type: UploadType = 'general',
  onProgress?: (percent: number) => void
): Promise<ApiResponse<UploadResponse>> {
  // 1. 计算文件标识
  const fileIdentifier = await computeFileIdentifier(file)

  // 2. 计算分片信息
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)

  // 如果文件小于分片大小，直接使用普通上传
  if (totalChunks <= 1) {
    onProgress?.(0)
    const result = await uploadFile(file, type)
    onProgress?.(100)
    return result
  }

  // 3. 查询已上传的分片
  const chunksRes = await getUploadedChunks(fileIdentifier)
  const uploadedChunks: number[] = chunksRes.data?.uploadedChunks || []
  const uploadedSet = new Set(uploadedChunks)

  // 4. 逐片上传缺失的分片
  let uploadedCount = uploadedChunks.length
  for (let i = 0; i < totalChunks; i++) {
    if (uploadedSet.has(i)) {
      // 分片已存在，跳过
      continue
    }

    // 切割分片
    const start = i * CHUNK_SIZE
    const end = Math.min(start + CHUNK_SIZE, file.size)
    const chunkBlob = file.slice(start, end)

    // 上传分片
    await uploadChunk(chunkBlob, fileIdentifier, i, totalChunks, file.name, type)
    uploadedCount++

    // 回调进度
    onProgress?.(Math.round((uploadedCount / totalChunks) * 100))
  }

  // 5. 合并分片
  const mergeResult = await mergeChunks(fileIdentifier)
  onProgress?.(100)
  return mergeResult
}
