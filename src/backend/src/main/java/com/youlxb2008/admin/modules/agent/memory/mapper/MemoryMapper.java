package com.youlxb2008.admin.modules.agent.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.agent.memory.entity.Memory;

/**
 * agent 长期记忆 Mapper（M7.1）
 * <p>
 * 纯 BaseMapper（单表 CRUD + LambdaQuery），无自定义 SQL；
 * 检索命中计数走 UpdateWrapper setSql 原子递增。
 */
public interface MemoryMapper extends BaseMapper<Memory> {
}
