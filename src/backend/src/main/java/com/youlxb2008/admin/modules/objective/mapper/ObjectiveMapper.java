package com.youlxb2008.admin.modules.objective.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.objective.entity.Objective;

/**
 * 目标索引 Mapper（M4.1，t_objective）
 * <p>
 * 纯 BaseMapper：聚合/筛选用 LambdaQueryWrapper（@TableLogic 自动过滤 is_deleted=1）。
 */
public interface ObjectiveMapper extends BaseMapper<Objective> {
}
