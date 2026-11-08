package com.youlxb2008.admin.modules.agent.vo;

import lombok.Data;

/**
 * agent 域：POST /api/agent/attachments 上传响应（M5.1-R1）
 * <p>
 * url 为 MinIO 绝对直链（{MINIO_ENDPOINT}/{MINIO_BUCKET}/{objectKey}，桶 public-read 匿名可读），
 * 门户/管理端 markdown 直接 {@code ![](url)} 可显示（不做预签名/nginx 反代，用户拍板）。
 * object key = {MINIO_PATH_PREFIX}/agent-{id}/{yyyyMM}/{uuid}.{ext}。
 */
@Data
public class AttachmentUploadVO {

	/** MinIO 绝对直链，如 http://minio-host:19000/agent-console-attachment/x7k/agent-1/202609/xxx.png */
	private String url;

	/** 原始文件名 */
	private String fileName;

	/** 文件大小（字节） */
	private long size;

	public AttachmentUploadVO() {
	}

	public AttachmentUploadVO(String url, String fileName, long size) {
		this.url = url;
		this.fileName = fileName;
		this.size = size;
	}
}
