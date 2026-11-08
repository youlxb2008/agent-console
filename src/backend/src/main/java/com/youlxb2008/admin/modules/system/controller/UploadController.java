package com.youlxb2008.admin.modules.system.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.youlxb2008.admin.exceptions.BaseException;
import com.youlxb2008.admin.modules.system.service.FileUploadService;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一文件上传 Controller
 * <p>
 * 提供以下接口：
 * 1. POST /system/upload          — 普通文件上传（整文件一次上传，适用于小文件）
 * 2. POST /system/upload/chunk    — 分片上传（断点续传，适用于大文件）
 * 3. POST /system/upload/merge    — 合并分片（断点续传最后一步）
 * 4. GET  /system/upload/chunks   — 查询已上传分片（断点续传恢复用）
 * <p>
 * 上传后的文件通过 /uploads/** 静态资源映射（见
 * {@link com.youlxb2008.admin.config.MvcConfig}）对外暴露访问。
 */
@RestController
@RequestMapping("/system/upload")
@Slf4j
@Tag(name = "文件上传", description = "统一文件上传接口（支持断点续传）")
@RequiredArgsConstructor
public class UploadController {

	/** 统一文件上传服务 */
	private final FileUploadService fileUploadService;

	/**
	 * 普通文件上传接口
	 * <p>
	 * 适用于小文件（图片、文档等），整文件一次上传，存储介质 MinIO（M5.1-R1）。
	 * 上传成功后返回 MinIO 绝对直链（{endpoint}/{bucket}/{objectKey}，桶 public-read）。
	 *
	 * @param file 上传的文件（multipart 表单字段名 file）
	 * @param type 业务类型：avatar（头像）、login-bg（登录背景）、general（通用）等
	 * @return 包含 url（MinIO 直链）字段的上传结果
	 */
	@Operation(summary = "上传文件（整文件，MinIO）")
	@RequiresUser
	@PostMapping
	public ResultVO upload(
			@Parameter(description = "上传的文件") @RequestParam("file") MultipartFile file,
			@Parameter(description = "业务类型：avatar/login-bg/general 等") @RequestParam(value = "type", defaultValue = "general") String type) {
		try {
			Map<String, String> result = fileUploadService.uploadFile(file, type);
			return ResultVO.getSuccessVO("上传成功").setData(result);
		} catch (IllegalArgumentException e) {
			// 业务校验失败（文件为空、大小超限、类型不支持等）
			return ResultVO.getFailedVO(e.getMessage());
		} catch (BaseException e) {
			// MinIO 存储不可达等系统级明确错误 → 交全局处理器返回 500 + 明确 msg（不做本地磁盘 fallback）
			throw e;
		} catch (RuntimeException e) {
			// 文件写入失败等系统异常
			log.error("文件上传失败", e);
			return ResultVO.getFailedVO("文件上传失败");
		}
	}

	/**
	 * 分片上传接口（断点续传）
	 * <p>
	 * 前端将大文件切分为等大分片后，逐片调用此接口上传。
	 * 每个分片存储在临时目录，等待全部分片上传完毕后调用 merge 接口合并。
	 * 如果某分片已存在（重复上传或断点续传），直接返回成功，不重复写入。
	 *
	 * @param file           分片文件数据
	 * @param fileIdentifier 文件唯一标识（前端根据文件内容计算的 MD5，用于标识同一文件的上传会话）
	 * @param chunkIndex     当前分片序号（从 0 开始）
	 * @param totalChunks    总分片数
	 * @param fileName       原始文件名
	 * @param type           业务类型
	 * @return 分片上传结果
	 */
	@Operation(summary = "分片上传（断点续传）")
	@RequiresUser
	@PostMapping("/chunk")
	public ResultVO uploadChunk(
			@Parameter(description = "分片文件数据") @RequestParam("file") MultipartFile file,
			@Parameter(description = "文件唯一标识（MD5）") @RequestParam("fileIdentifier") String fileIdentifier,
			@Parameter(description = "当前分片序号（从 0 开始）") @RequestParam("chunkIndex") int chunkIndex,
			@Parameter(description = "总分片数") @RequestParam("totalChunks") int totalChunks,
			@Parameter(description = "原始文件名") @RequestParam(value = "fileName", defaultValue = "unknown") String fileName,
			@Parameter(description = "业务类型") @RequestParam(value = "type", defaultValue = "general") String type) {
		try {
			Map<String, Object> result = fileUploadService.uploadChunk(file, fileIdentifier, chunkIndex, totalChunks, fileName, type);
			return ResultVO.getSuccessVO("分片上传成功").setData(result);
		} catch (IllegalArgumentException e) {
			return ResultVO.getFailedVO(e.getMessage());
		} catch (RuntimeException e) {
			log.error("分片上传失败: fileIdentifier={}, chunkIndex={}", fileIdentifier, chunkIndex, e);
			return ResultVO.getFailedVO("分片上传失败");
		}
	}

	/**
	 * 合并分片接口（断点续传最后一步）
	 * <p>
	 * 前端确认全部分片上传完毕后，调用此接口将所有分片合并为最终文件。
	 * 合并成功后自动删除临时分片，返回最终文件的 URL。
	 *
	 * @param fileIdentifier 文件唯一标识（与分片上传时一致）
	 * @return 包含 url 字段的合并结果
	 */
	@Operation(summary = "合并分片（断点续传完成）")
	@RequiresUser
	@PostMapping("/merge")
	public ResultVO mergeChunks(
			@Parameter(description = "文件唯一标识（MD5）") @RequestParam("fileIdentifier") String fileIdentifier) {
		try {
			Map<String, String> result = fileUploadService.mergeChunks(fileIdentifier);
			return ResultVO.getSuccessVO("文件合并成功").setData(result);
		} catch (IllegalArgumentException e) {
			return ResultVO.getFailedVO(e.getMessage());
		} catch (RuntimeException e) {
			log.error("分片合并失败: fileIdentifier={}", fileIdentifier, e);
			return ResultVO.getFailedVO("分片合并失败");
		}
	}

	/**
	 * 查询已上传的分片列表（断点续传恢复用）
	 * <p>
	 * 前端在恢复上传时，先调用此接口查询已上传的分片序号，
	 * 跳过已传分片，仅上传缺失的分片，实现断点续传。
	 *
	 * @param fileIdentifier 文件唯一标识
	 * @return 包含 uploadedChunks（已上传分片序号列表）的结果
	 */
	@Operation(summary = "查询已上传分片（断点续传恢复）")
	@RequiresUser
	@GetMapping("/chunks")
	public ResultVO getUploadedChunks(
			@Parameter(description = "文件唯一标识（MD5）") @RequestParam("fileIdentifier") String fileIdentifier) {
		Map<String, Object> result = fileUploadService.getUploadedChunks(fileIdentifier);
		return ResultVO.getSuccessVO("查询成功").setData(result);
	}
}
