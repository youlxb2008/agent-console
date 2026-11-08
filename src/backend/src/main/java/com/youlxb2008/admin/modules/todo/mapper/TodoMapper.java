package com.youlxb2008.admin.modules.todo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.todo.entity.Todo;

/**
 * 待办 Mapper（M5.2，t_todo）
 * <p>
 * 纯 BaseMapper：分页/筛选用 LambdaQueryWrapper（@TableLogic 自动过滤 is_deleted=1）。
 */
public interface TodoMapper extends BaseMapper<Todo> {
}
