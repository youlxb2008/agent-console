package com.youlxb2008.admin.modules.agent.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 管理端：调整记忆权重保底线请求（M7.1-M3a，PUT /api/memory/admin/memories/{id}/weight-floor）
 * <p>
 * weightFloor 0-100（越界 400）；0=不保底（衰减重算行为与 M7.1 完全一致）。
 */
@Data
public class MemoryWeightFloorReq {

	/** 权重保底线（0=不保底；衰减重算落库 weight = max(公式值, weightFloor)） */
	@NotNull(message = "weightFloor 不能为空")
	@Min(value = 0, message = "weightFloor 须在 0-100 内")
	@Max(value = 100, message = "weightFloor 须在 0-100 内")
	private Integer weightFloor;
}
