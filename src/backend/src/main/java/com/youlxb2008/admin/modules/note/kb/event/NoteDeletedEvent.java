package com.youlxb2008.admin.modules.note.kb.event;

/**
 * 笔记删除完成事件（知识库索引钩子）
 * <p>
 * 发布点：NoteStorageManager.deleteFully（文件 + 索引 + 关联行全部删除后）。
 * 消费点：KnowledgeIndexService 异步清理 t_note_chunk 分块行 + Lucene 文档，
 * 保证已删笔记不再出现在知识库检索/RAG 引用中。
 */
public record NoteDeletedEvent(String nid) {
}
