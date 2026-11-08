package com.youlxb2008.admin.modules.agent.memory.event;

/**
 * 记忆索引事件（M7.1，对齐 KnowledgeIndexService 的事件机制）
 * <p>
 * MemoryService 写侧发布，MemoryIndexService 监听（事务提交后异步消费）：
 * <ul>
 *   <li>SAVE：写入/更新/恢复后重建该条索引文档（幂等覆写）</li>
 *   <li>DELETE：逻辑删除后移除该条索引文档（is_deleted=1 不参与检索）</li>
 * </ul>
 * 归档/被取代只改 status（DB 实时读），索引文档不变——检索结果带 status 标记。
 *
 * @param type      事件类型 SAVE/DELETE
 * @param memoryId  记忆 id
 */
public record MemoryIndexEvent(Type type, Long memoryId) {

	/** 事件类型 */
	public enum Type {
		/** 写入/更新/恢复 → 重建索引文档 */
		SAVE,
		/** 逻辑删除 → 移除索引文档 */
		DELETE
	}

	public static MemoryIndexEvent save(Long memoryId) {
		return new MemoryIndexEvent(Type.SAVE, memoryId);
	}

	public static MemoryIndexEvent delete(Long memoryId) {
		return new MemoryIndexEvent(Type.DELETE, memoryId);
	}
}
