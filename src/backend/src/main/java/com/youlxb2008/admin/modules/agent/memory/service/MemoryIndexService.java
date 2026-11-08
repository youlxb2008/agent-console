package com.youlxb2008.admin.modules.agent.memory.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.youlxb2008.admin.modules.agent.memory.entity.Memory;
import com.youlxb2008.admin.modules.agent.memory.event.MemoryIndexEvent;
import com.youlxb2008.admin.modules.agent.memory.manager.MemoryIndexManager;

import lombok.extern.slf4j.Slf4j;

/**
 * 记忆索引 Service（M7.1，KB 写侧同款事件机制）
 * <p>
 * 职责：把 t_memory 行同步到 Lucene 独立索引（{@link MemoryIndexManager}）。
 * 触发方式：监听 {@link MemoryIndexEvent}（MemoryService 写侧发布），
 * <b>事务提交后</b>（AFTER_COMMIT，无事务时 fallback 执行）提交单线程 daemon
 * 执行器异步消费——不阻塞 agent 写侧主链路，失败只告警（索引是派生数据，
 * 任何失败不影响记忆本身；可用 {@link #indexNow}/{@link #removeNow} 手工补偿）。
 * <p>
 * 归档/被取代只改 status（DB 实时读，检索结果带标记），索引文档不变；
 * 逻辑删除移除文档（is_deleted=1 不参与检索）。
 */
@Slf4j
@Service
public class MemoryIndexService {

	@Autowired
	private MemoryIndexManager memoryIndexManager;

	@Autowired
	private MemoryService memoryService;

	/** 索引单线程执行器（daemon：不阻 JVM 退出；串行消费事件避免并发覆写乱序） */
	private final ExecutorService indexer = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "memory-indexer");
		t.setDaemon(true);
		return t;
	});

	/**
	 * 记忆写入/更新/恢复 → 提交异步索引任务（不入队失败仅告警，不影响写侧）
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onMemorySaved(MemoryIndexEvent event) {
		if (event.type() != MemoryIndexEvent.Type.SAVE) {
			return;
		}
		Long id = event.memoryId();
		indexer.submit(() -> {
			try {
				indexNow(id);
			} catch (Exception e) {
				log.warn("记忆增量索引失败 id={}：{}", id, e.getMessage());
			}
		});
	}

	/**
	 * 记忆逻辑删除 → 提交异步清理任务
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onMemoryDeleted(MemoryIndexEvent event) {
		if (event.type() != MemoryIndexEvent.Type.DELETE) {
			return;
		}
		Long id = event.memoryId();
		indexer.submit(() -> {
			try {
				removeNow(id);
			} catch (Exception e) {
				log.warn("记忆索引清理失败 id={}：{}", id, e.getMessage());
			}
		});
	}

	/**
	 * 同步索引一条记忆（读 DB 最新行 → 重建索引文档；行不存在/已软删则清理残留）
	 * <p>
	 * public 供单测与手工补偿直接调用（事件路为异步，测试需确定性）。
	 *
	 * @param id 记忆 id
	 */
	public void indexNow(Long id) {
		Memory memory = memoryService.getById(id);
		if (memory == null) {
			// 已被并发删除：直接清掉可能残留的旧索引
			removeNow(id);
			return;
		}
		memoryIndexManager.indexMemory(memory.getId(), memory.getAgentId(), memory.getTitle(),
				memory.getTags(), memory.getContentMd(), memory.getWeight() == null ? 0 : memory.getWeight());
	}

	/**
	 * 同步移除一条记忆的索引文档（逻辑删除时调用；文档不存在时静默幂等）
	 *
	 * @param id 记忆 id
	 */
	public void removeNow(Long id) {
		memoryIndexManager.removeMemory(id);
	}

	/**
	 * 全量重建当前索引（按 DB 全量未删行重建；索引损坏时手工补偿用）
	 * <p>
	 * list() 由 @TableLogic 自动过滤 is_deleted=1。
	 *
	 * @return 重建条数
	 */
	public int rebuildAll() {
		var rows = memoryService.list();
		for (Memory memory : rows) {
			memoryIndexManager.indexMemory(memory.getId(), memory.getAgentId(), memory.getTitle(),
					memory.getTags(), memory.getContentMd(), memory.getWeight() == null ? 0 : memory.getWeight());
		}
		log.info("记忆索引全量重建完成：{} 条", rows.size());
		return rows.size();
	}
}
