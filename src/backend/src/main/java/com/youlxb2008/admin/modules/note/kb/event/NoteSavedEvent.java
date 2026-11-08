package com.youlxb2008.admin.modules.note.kb.event;

/**
 * 笔记写入/覆写成功事件（知识库索引钩子）
 * <p>
 * 发布点：NoteStorageManager.upsert 索引落库成功后（文件是真相源，索引成功才值得建 KB 块）。
 * 消费点：KnowledgeIndexService（单线程异步消费——读 md 全文、分块、向量化、写 Lucene，
 * 不阻塞 agent 写侧主链路；KB 是派生数据，失败只告警不影响笔记本身）。
 * <p>
 * 用 Spring 事件而非直接调用，避免 NoteStorageManager ↔ KnowledgeIndexService 循环依赖
 * （KB 索引服务需要 NoteStorageManager 读 md 原文）。
 */
public record NoteSavedEvent(String nid) {
}
