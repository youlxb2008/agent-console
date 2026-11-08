package com.youlxb2008.admin.modules.ledger.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.ledger.entity.Ledger;

/**
 * 台账 Mapper（M4.2）
 * <p>
 * 由 WebApplication 的 @MapperScan("com.youlxb2008.admin.**.mapper") 扫描，
 * 无需 @Mapper 注解即可装配（与本域其他 mapper 一致）；此处标注仅为 IDE 提示。
 */
@Mapper
public interface LedgerMapper extends BaseMapper<Ledger> {
}
