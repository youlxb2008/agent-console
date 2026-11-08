package com.youlxb2008.admin.modules.note.kb.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.kb.entity.NoteChunk;
import com.youlxb2008.admin.modules.note.kb.event.NoteDeletedEvent;
import com.youlxb2008.admin.modules.note.kb.event.NoteSavedEvent;
import com.youlxb2008.admin.modules.note.kb.manager.DashScopeClient;
import com.youlxb2008.admin.modules.note.kb.mapper.NoteChunkMapper;
import com.youlxb2008.admin.modules.note.kb.vo.RebuildStatusVO;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识库索引 Service（KB 写侧核心）
 * <p>
 * 职责：把 md 笔记加工成可检索的派生数据，两路同步维护——
 * <ol>
 *   <li>t_note_chunk 分块表：文本块 + 百炼 embedding（语义检索路/RAG 上下文）</li>
 *   <li>Lucene 全文索引：整篇 title+content（BM25 关键词检索路）</li>
 * </ol>
 * 触发方式：
 * <ul>
 *   <li>增量：监听 {@link NoteSavedEvent}/{@link NoteDeletedEvent}（NoteStorageManager 发布），
 *       单线程 daemon 执行器异步消费——不阻塞 agent 写侧主链路，失败只告警</li>
 *   <li>全量：管理端 rebuild 接口（配好 key 后补向量 / 索引损坏重建 / 换模型重建）</li>
 * </ul>
 * 降级策略：DashScope key 未配置 → 文本块照落、embedding 置 NULL（BM25 仍可用），
 * Lucene 初始化失败 → 分块表照写（向量路仍可用）；KB 是派生数据，任何失败不影响笔记本身。
 */
@Slf4j
@Service
public class KnowledgeIndexService extends ServiceImpl<NoteChunkMapper, NoteChunk> {

	/** 分块目标长度（字符；中文 500 字 ≈ embedding 模型舒适窗口） */
	private static final int CHUNK_TARGET_CHARS = 500;
	/** 相邻块重叠长度（字符）：保住被切断的句子上下文，提高召回连续性 */
	private static final int CHUNK_OVERLAP_CHARS = 60;
	/** 单段超过该长度先硬切再聚合（防止一个巨型代码块撑爆单块） */
	private static final int SEGMENT_SPLIT_CHARS = 1000;
	/** 分块文本落库上限（与表列 VARCHAR(2048) 对齐，留余量） */
	private static final int CHUNK_MAX_CHARS = 2000;

	@Autowired
	private NoteStorageManager noteStorageManager;

	@Autowired
	private NoteMetaService noteMetaService;

	@Autowired
	private DashScopeClient dashScopeClient;

	@Autowired
	private com.youlxb2008.admin.modules.note.kb.manager.LuceneIndexManager luceneIndexManager;

	/** 索引单线程执行器（daemon：不阻 JVM 退出；串行消费事件避免并发覆写乱序） */
	private final ExecutorService indexer = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "kb-indexer");
		t.setDaemon(true);
		return t;
	});

	/* ---------- 全量重建进度（内存态，重启即失；进程内轮询足够） ---------- */
	private volatile boolean rebuilding = false;
	private volatile int rebuildTotal = 0;
	private final AtomicInteger rebuildDone = new AtomicInteger();
	private final AtomicInteger rebuildFailed = new AtomicInteger();
	private volatile String rebuildMessage = "";

	/* ============================================================
	 * 事件监听（增量索引入口）
	 * ============================================================ */

	/** 笔记写入/覆写 → 提交异步索引任务（不入队失败仅告警，不影响写侧） */
	@EventListener
	public void onNoteSaved(NoteSavedEvent event) {
		String nid = event.nid();
		indexer.submit(() -> {
			try {
				indexNote(nid);
			} catch (Exception e) {
				log.warn("笔记 KB 增量索引失败 nid={}：{}", nid, e.getMessage());
			}
		});
	}

	/** 笔记删除 → 提交异步清理任务 */
	@EventListener
	public void onNoteDeleted(NoteDeletedEvent event) {
		String nid = event.nid();
		indexer.submit(() -> {
			try {
				removeNote(nid);
			} catch (Exception e) {
				log.warn("笔记 KB 索引清理失败 nid={}：{}", nid, e.getMessage());
			}
		});
	}

	/* ============================================================
	 * 单篇索引 / 清理
	 * ============================================================ */

	/**
	 * 索引一篇笔记：读 meta + md 原文 → 分块 → （有 key 时）批量向量化 → 整体替换该 nid 的块 + Lucene 文档
	 *
	 * @param nid 笔记对外引用键
	 */
	public void indexNote(String nid) {
		NoteMeta meta = noteMetaService.findByNid(nid);
		if (meta == null) {
			// 笔记已被并发删除：直接清掉可能残留的旧索引
			removeNote(nid);
			return;
		}
		// 读 md 原文（孤儿索引行/文件缺失 → 404 异常，向上抛由调用方记日志）
		String content = noteStorageManager.readRaw(nid);
		List<String> pieces = chunkText(content);

		// 无 key 降级：文本块照写（embedding=NULL），BM25/后续 rebuild 可补
		String apiKey = dashScopeClient.apiKeyOrNull();
		List<float[]> vectors = null;
		if (apiKey != null && !pieces.isEmpty()) {
			vectors = dashScopeClient.embed(pieces);
		}

		// ① 替换 t_note_chunk（先删该 nid 全部旧块再插新块，覆写语义幂等）
		this.remove(new QueryWrapper<NoteChunk>().eq("nid", nid));
		if (!pieces.isEmpty()) {
			List<NoteChunk> rows = new ArrayList<>(pieces.size());
			for (int i = 0; i < pieces.size(); i++) {
				NoteChunk c = new NoteChunk();
				c.setNid(nid);
				c.setNotebookId(meta.getNotebookId());
				c.setChunkNo(i);
				c.setContent(pieces.get(i));
				if (vectors != null) {
					c.setEmbedding(serializeVector(vectors.get(i)));
					c.setEmbedModel(dashScopeClient.embeddingModelName());
				}
				rows.add(c);
			}
			this.saveBatch(rows);
		}

		// ② Lucene 全文索引（初始化失败时内部抛业务异常 → 捕获降级，分块表已写成功）
		try {
			luceneIndexManager.indexNote(nid, meta.getNotebookId(), meta.getTitle(), content);
		} catch (Exception e) {
			log.warn("Lucene 索引写入降级跳过 nid={}：{}", nid, e.getMessage());
		}
		log.debug("笔记 KB 索引完成 nid={} chunks={} vectorized={}", nid, pieces.size(), vectors != null);
	}

	/**
	 * 清理一篇笔记的全部 KB 索引（分块表 + Lucene）
	 *
	 * @param nid 笔记对外引用键
	 */
	public void removeNote(String nid) {
		this.remove(new QueryWrapper<NoteChunk>().eq("nid", nid));
		try {
			luceneIndexManager.deleteNote(nid);
		} catch (Exception e) {
			log.warn("Lucene 索引删除降级跳过 nid={}：{}", nid, e.getMessage());
		}
	}

	/* ============================================================
	 * 全量重建（管理端触发）
	 * ============================================================ */

	/**
	 * 全量重建所有笔记的 KB 索引（异步执行，立即返回）
	 * <p>
	 * 适用：初次接入/配好 key 补向量/换 embedding 模型/索引损坏。
	 * 已在重建中 → 拒绝重复触发。逐笔记调 indexNote（内部串行），进度写内存态供 status 轮询。
	 */
	public synchronized void rebuildAll() {
		if (rebuilding) {
			throw new IllegalStateException("重建任务进行中，请等待完成");
		}
		List<NoteMeta> all = noteMetaService.list();
		rebuildTotal = all.size();
		rebuildDone.set(0);
		rebuildFailed.set(0);
		rebuildMessage = "";
		rebuilding = true;
		indexer.submit(() -> {
			try {
				for (NoteMeta meta : all) {
					try {
						indexNote(meta.getNid());
						rebuildDone.incrementAndGet();
					} catch (Exception e) {
						// 单篇失败不中断整批：记录失败数继续下一篇（孤儿索引行等属预期）
						rebuildFailed.incrementAndGet();
						log.warn("重建失败 nid={}：{}", meta.getNid(), e.getMessage());
					}
				}
				rebuildMessage = "完成：成功 " + rebuildDone.get() + " 篇，失败 " + rebuildFailed.get() + " 篇";
				log.info("KB 全量重建完成：{}", rebuildMessage);
			} finally {
				rebuilding = false;
			}
		});
	}

	/** 重建进度查询（供管理端轮询；顺带返回 key 配置态提示前端） */
	public RebuildStatusVO status() {
		RebuildStatusVO vo = new RebuildStatusVO();
		vo.setRebuilding(rebuilding);
		vo.setTotal(rebuildTotal);
		vo.setDone(rebuildDone.get());
		vo.setFailed(rebuildFailed.get());
		vo.setMessage(rebuildMessage);
		vo.setKeyConfigured(dashScopeClient.apiKeyOrNull() != null);
		vo.setLuceneAvailable(luceneIndexManager.available());
		return vo;
	}

	/* ============================================================
	 * 分块算法
	 * ============================================================ */

	/**
	 * md 全文 → 分块列表（目标 ~500 字/块，块间重叠 ~60 字）
	 * <p>
	 * 步骤：
	 * <ol>
	 *   <li>按空行切段（段落 = 连续非空行；md 的段落/代码块天然以空行分隔）</li>
	 *   <li>超长段（&gt;1000 字）硬切成 ~500 字小段</li>
	 *   <li>贪心聚合相邻段至 ~500 字成块</li>
	 *   <li>块间拼接时带上前块尾部 ~60 字重叠（保句子上下文）</li>
	 * </ol>
	 * 空文本返回空列表（调用方不落块）。
	 */
	List<String> chunkText(String md) {
		if (md == null || md.isBlank()) {
			return List.of();
		}
		// ① 切段：空行为界，保留段内换行（代码块结构）
		List<String> segments = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String line : md.split("\n", -1)) {
			if (line.isBlank()) {
				if (!current.isEmpty()) {
					segments.add(current.toString().trim());
					current.setLength(0);
				}
			} else {
				current.append(line).append('\n');
			}
		}
		if (!current.isEmpty()) {
			segments.add(current.toString().trim());
		}

		// ② 超长段硬切（步长 CHUNK_TARGET，末片自然收尾）
		List<String> fine = new ArrayList<>();
		for (String seg : segments) {
			if (seg.length() <= SEGMENT_SPLIT_CHARS) {
				fine.add(seg);
			} else {
				for (int i = 0; i < seg.length(); i += CHUNK_TARGET_CHARS) {
					fine.add(seg.substring(i, Math.min(seg.length(), i + CHUNK_TARGET_CHARS)));
				}
			}
		}

		// ③ 贪心聚合：累计到 ~CHUNK_TARGET 即收块（允许块略超目标但不硬切段落）
		List<String> blocks = new ArrayList<>();
		StringBuilder buf = new StringBuilder();
		for (String seg : fine) {
			if (!buf.isEmpty()) {
				buf.append("\n\n");
			}
			buf.append(seg);
			if (buf.length() >= CHUNK_TARGET_CHARS) {
				blocks.add(buf.toString());
				buf.setLength(0);
			}
		}
		if (!buf.isEmpty()) {
			blocks.add(buf.toString());
		}

		// ④ 块间重叠：后块头部拼前块尾部（截断至落库上限）
		List<String> overlapped = new ArrayList<>(blocks.size());
		for (int i = 0; i < blocks.size(); i++) {
			String piece = blocks.get(i);
			if (i > 0) {
				String prev = blocks.get(i - 1);
				String tail = prev.substring(Math.max(0, prev.length() - CHUNK_OVERLAP_CHARS));
				piece = tail + "\n" + piece;
			}
			if (piece.length() > CHUNK_MAX_CHARS) {
				piece = piece.substring(0, CHUNK_MAX_CHARS);
			}
			overlapped.add(piece);
		}
		return overlapped;
	}

	/** float[] → 小端 byte[]（t_note_chunk.embedding 存储格式；读取用 {@link #deserializeVector}） */
	static byte[] serializeVector(float[] vector) {
		ByteBuffer buffer = ByteBuffer.allocate(Float.BYTES * vector.length).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : vector) {
			buffer.putFloat(v);
		}
		return buffer.array();
	}

	/** byte[] → float[]（与序列化同端序，维度不匹配时抛错防御脏数据） */
	static float[] deserializeVector(byte[] bytes) {
		float[] vector = new float[bytes.length / Float.BYTES];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
		return vector;
	}
}
