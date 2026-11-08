package com.youlxb2008.admin.modules.note.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;

/**
 * 笔记索引 Mapper（t_note_meta，v3 迁入 note 域）
 * 基于 MyBatis-Plus BaseMapper，提供基础 CRUD 能力（物理删除模型，无 @TableLogic）
 */
public interface NoteMetaMapper extends BaseMapper<NoteMeta> {
}
