package com.youlxb2008.admin.modules.bus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.bus.entity.BusTopicWatch;

/**
 * 总线 topic 等待段状态 Mapper（bus-watchdog-fuse）
 * <p>
 * 由 WebApplication 的 @MapperScan("com.youlxb2008.admin.**.mapper") 扫描，
 * 基础 CRUD（selectOne/delete/updateById）由 MyBatis-Plus BaseMapper 提供，
 * 访问方统一走 BusFuseService（扫描器单线程维护，无并发竞争）。
 */
public interface BusTopicWatchMapper extends BaseMapper<BusTopicWatch> {
}
