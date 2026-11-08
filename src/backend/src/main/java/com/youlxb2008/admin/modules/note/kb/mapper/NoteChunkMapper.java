package com.youlxb2008.admin.modules.note.kb.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.note.kb.entity.NoteChunk;

/**
 * 知识库分块 Mapper（t_note_chunk）
 * <p>
 * 由 WebApplication 的 @MapperScan("com.youlxb2008.admin.**.mapper") 扫描装配；
 * 纯 BaseMapper：查询走 LambdaQueryWrapper（项目无 XML mapper 约定）。
 */
@Mapper
public interface NoteChunkMapper extends BaseMapper<NoteChunk> {
}
