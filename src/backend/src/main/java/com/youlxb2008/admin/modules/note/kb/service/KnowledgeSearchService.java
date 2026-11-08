package com.youlxb2008.admin.modules.note.kb.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.kb.entity.NoteChunk;
import com.youlxb2008.admin.modules.note.kb.manager.DashScopeClient;
import com.youlxb2008.admin.modules.note.kb.manager.LuceneIndexManager;
import com.youlxb2008.admin.modules.note.kb.vo.KbAskVO;
import com.youlxb2008.admin.modules.note.kb.vo.KbHitVO;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识库检索 Service（KB 读侧：混合检索 + RAG 问答）
 * <p>
 * 混合检索三路召回（互补：标题=精确、BM25=关键词、向量=语义），RRF 融合排序：
 * <pre>
 *   score(d) = Σ 1 / (K + rank_r(d))    K=60（业界标准，弱化各路内部分数量纲差异）
 * </pre>
 * 任一路失败/未配置自动降级跳过，不会让整个检索挂掉：
 * <ul>
 *   <li>标题路：t_note_meta 内存 contains（无任何外部依赖，永在）</li>
 *   <li>BM25 路：Lucene（索引未建好/初始化失败 → 跳过）</li>
 *   <li>向量路：百炼 embedding（key 未配置/接口失败 → 跳过）</li>
 * </ul>
 * RAG 问答（ask）：检索 top6 → 编号资料拼 prompt → qwen 生成答案 → 附引用笔记列表。
 */
@Slf4j
@Service
public class KnowledgeSearchService {

	/** RRF 平滑常数（rank 从 1 起，K 越大各路权重越平均） */
	private static final int RRF_K = 60;
	/** 每路召回深度（融合前每路取 top20，兼顾覆盖与计算量） */
	private static final int RECALL_TOP_N = 20;
	/** 命中片段长度（字符；前端下拉/问答上下文的展示截断） */
	private static final int SNIPPET_CHARS = 160;
	/** RAG 引用资料条数 */
	private static final int ASK_CONTEXT_HITS = 6;

	@Autowired
	private NoteMetaService noteMetaService;

	@Autowired
	private KnowledgeIndexService knowledgeIndexService;

	@Autowired
	private LuceneIndexManager luceneIndexManager;

	@Autowired
	private DashScopeClient dashScopeClient;

	/* ============================================================
	 * 混合检索
	 * ============================================================ */

	/**
	 * 混合检索（单笔记本范围版：标题 + BM25 + 语义向量 → RRF 融合）
	 *
	 * @param query      查询词（自然语言）
	 * @param notebookId 范围过滤（null=全库搜）
	 * @param limit      返回条数上限（钳制 [1,20]）
	 * @return 命中列表（融合分降序）
	 */
	public List<KbHitVO> hybridSearch(String query, Long notebookId, int limit) {
		// 单值转集合，与集合版共用同一核心实现（null 保持 null=全库语义）
		return doSearch(query, notebookId == null ? null : List.of(notebookId), limit);
	}

	/**
	 * 混合检索（笔记本集合范围版：agent 域数据隔离用）
	 * <p>
	 * 权限模型：调用方（AgentApiController）已按 agent↔笔记本关联解析出
	 * 可见 notebookId 集合后传入；本方法只负责在该范围内检索。
	 * <b>空集合=无任何关联笔记本 → 直接返回空结果（fail-closed，绝不退化成全库）</b>。
	 *
	 * @param query       查询词（自然语言）
	 * @param notebookIds 可见笔记本 id 集合（null=全库；空集合=无权限范围 → 空结果）
	 * @param limit       返回条数上限（钳制 [1,20]）
	 * @return 命中列表（融合分降序）
	 */
	public List<KbHitVO> hybridSearch(String query, Collection<Long> notebookIds, int limit) {
		return doSearch(query, notebookIds, limit);
	}

	/**
	 * 混合检索核心实现（单值/集合两版共用）
	 * <p>
	 * 三路召回全部按 notebookIds 范围过滤：标题路内存 contains、BM25 路 Lucene
	 * TermInSetQuery、向量路 SQL IN——保证任一路都不会把范围外内容召回。
	 */
	private List<KbHitVO> doSearch(String query, Collection<Long> notebookIds, int limit) {
		int capped = Math.max(1, Math.min(20, limit));
		if (query == null || query.isBlank()) {
			return List.of();
		}
		// 权限范围守卫：显式空集合（agent 未关联任何笔记本）= 无可搜范围 → 空结果
		if (notebookIds != null && notebookIds.isEmpty()) {
			return List.of();
		}
		String q = query.trim();

		// ① 标题路：范围内 meta 内存 contains（query 小写化，与既有门户搜索口径一致）
		List<NoteMeta> metas = notebookIds == null
				? noteMetaService.list()
				: noteMetaService.list().stream()
						.filter(m -> notebookIds.contains(m.getNotebookId()))
						.toList();
		Map<String, Integer> titleRank = rankByTitle(metas, q);

		// ② BM25 路：Lucene（不可用/异常自动跳过）
		Map<String, Integer> bm25Rank = new LinkedHashMap<>();
		try {
			for (var hit : luceneIndexManager.search(q, notebookIds, RECALL_TOP_N)) {
				bm25Rank.put(hit.getNid(), bm25Rank.size() + 1);
			}
		} catch (Exception e) {
			log.warn("BM25 检索路降级跳过：{}", e.getMessage());
		}

		// ③ 向量路：query embedding → 候选块余弦 → 每笔记取最高块（兼做 snippet 来源）
		Map<String, Integer> vectorRank = new LinkedHashMap<>();
		Map<String, String> vectorSnippets = new HashMap<>();
		try {
			searchByVector(q, notebookIds, vectorRank, vectorSnippets);
		} catch (Exception e) {
			// key 未配置（BusinessException）或接口失败：语义路静默降级
			log.warn("语义检索路降级跳过：{}", e.getMessage());
		}

		// ④ RRF 融合：三路 rank → 累加 1/(K+rank)
		Map<String, Double> scores = new HashMap<>();
		Set<String> sourceTitle = titleRank.keySet();
		Set<String> sourceBm25 = bm25Rank.keySet();
		Set<String> sourceVector = vectorRank.keySet();
		accumulate(scores, titleRank);
		accumulate(scores, bm25Rank);
		accumulate(scores, vectorRank);

		// ⑤ 组装输出：meta 回填标题/目录，snippet 优先向量命中块
		List<KbHitVO> out = new ArrayList<>();
		scores.entrySet().stream()
				.sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
				.limit(capped)
				.forEach(e -> {
					String nid = e.getKey();
					NoteMeta meta = metas.stream().filter(m -> nid.equals(m.getNid())).findFirst().orElse(null);
					if (meta == null) {
						return; // 块/索引残留但 meta 已删（理论不发生，防御）
					}
					KbHitVO hit = new KbHitVO();
					hit.setNid(nid);
					hit.setNotebookId(meta.getNotebookId());
					hit.setTitle(meta.getTitle());
					hit.setDirPath(meta.getDirPath());
					hit.setScore(round3(e.getValue()));
					hit.setSnippet(pickSnippet(nid, vectorSnippets));
					List<String> sources = new ArrayList<>();
					if (sourceTitle.contains(nid)) {
						sources.add("title");
					}
					if (sourceBm25.contains(nid)) {
						sources.add("bm25");
					}
					if (sourceVector.contains(nid)) {
						sources.add("vector");
					}
					hit.setSources(sources);
					out.add(hit);
				});
		return out;
	}

	/* ============================================================
	 * RAG 问答
	 * ============================================================ */

	/**
	 * RAG 问答（单笔记本范围版）：检索 top6 相关片段 → 拼 prompt → 百炼 qwen 生成答案
	 *
	 * @param question   用户问题
	 * @param notebookId 范围过滤（null=全库）
	 * @return 答案 + 引用列表
	 */
	public KbAskVO ask(String question, Long notebookId) {
		// 单值转集合，与集合版共用同一实现（null 保持 null=全库语义）
		return ask(question, notebookId == null ? null : List.of(notebookId));
	}

	/**
	 * RAG 问答（笔记本集合范围版：agent 域数据隔离用）
	 * <p>
	 * 检索范围由调用方按 agent↔笔记本关联解析后传入；
	 * 空集合 = 无关联笔记本 → 检索为空 → 走「未检索到」固定话术（不烧 token）。
	 *
	 * @param question    用户问题
	 * @param notebookIds 可见笔记本 id 集合（null=全库；空集合=无权限范围 → 空结果话术）
	 * @return 答案 + 引用列表
	 */
	public KbAskVO ask(String question, Collection<Long> notebookIds) {
		if (question == null || question.isBlank()) {
			throw new BusinessException("问题不能为空");
		}
		if (dashScopeClient.apiKeyOrNull() == null) {
			throw new BusinessException("未配置百炼 API Key（系统配置项 " + DashScopeClient.OPTION_API_KEY + "）");
		}
		// ① 检索相关片段（复用混合检索核心，向量路给语义相关性；空集合在 doSearch 内守卫）
		List<KbHitVO> hits = doSearch(question, notebookIds, ASK_CONTEXT_HITS);
		if (hits.isEmpty()) {
			// 无相关资料：不烧 token 调模型，直接固定话术返回
			KbAskVO vo = new KbAskVO();
			vo.setAnswer("知识库中未检索到与该问题相关的内容，请换个问法或先补充相关笔记。");
			vo.setReferences(List.of());
			return vo;
		}

		// ② 拼上下文：编号资料（标题 + 片段），prompt 引用编号与 references 下标对应
		StringBuilder context = new StringBuilder();
		for (int i = 0; i < hits.size(); i++) {
			KbHitVO h = hits.get(i);
			context.append("[").append(i + 1).append("] 《").append(h.getTitle()).append("》\n")
					.append(h.getSnippet() == null ? "" : h.getSnippet()).append("\n\n");
		}
		String system = "你是企业知识库问答助手。请仅依据下面提供的编号资料回答用户问题："
				+ "答案中使用资料编号标注来源（如 [1][3]）；资料中没有的内容直接说明知识库暂无相关记录，"
			+ "不要编造；用中文、条理清晰地回答，适当使用 markdown 列表。";
		String user = "参考资料：\n" + context + "\n用户问题：" + question.trim();

		// ③ 调模型生成（失败抛业务异常给前端提示）
		String answer = dashScopeClient.chat(system, user);

		// ④ 组装引用（与 prompt 编号一致，前端可点击跳原文核对）
		KbAskVO vo = new KbAskVO();
		vo.setAnswer(answer);
		List<KbAskVO.Reference> refs = new ArrayList<>(hits.size());
		for (KbHitVO h : hits) {
			KbAskVO.Reference r = new KbAskVO.Reference();
			r.setNid(h.getNid());
			r.setNotebookId(h.getNotebookId());
			r.setTitle(h.getTitle());
			r.setDirPath(h.getDirPath());
			refs.add(r);
		}
		vo.setReferences(refs);
		return vo;
	}

	/* ==================== 内部实现 ==================== */

	/**
	 * 向量语义检索：query 向量化 → 候选块余弦 → 每笔记取最高分块
	 * <p>
	 * 候选集 = 范围内已向量化全部分块（千篇量级 ≈ 数 MB 内存，无需外置向量库）。
	 * 范围过滤：notebookIds 非空时 SQL IN（空集合已在 doSearch 守卫，此处仍双保险短路）。
	 * 输出：rankMap（nid→名次）+ snippetMap（nid→最高分块文本）。
	 */
	private void searchByVector(String q, Collection<Long> notebookIds,
			Map<String, Integer> rankMap, Map<String, String> snippetMap) {
		// key 未配置时直接短路（embed 内部会抛错，这里给出更友好的降级日志）
		if (dashScopeClient.apiKeyOrNull() == null) {
			log.debug("语义检索路跳过：未配置百炼 API Key");
			return;
		}
		QueryWrapper<NoteChunk> wrapper = new QueryWrapper<>();
		// 只取已向量化的块（embedding NULL 的块不参与语义路）
		wrapper.isNotNull("embedding");
		if (notebookIds != null) {
			// 双保险：空集合直接短路，防止 in() 空列表生成非法/无过滤 SQL
			if (notebookIds.isEmpty()) {
				return;
			}
			wrapper.in("notebook_id", notebookIds);
		}
		List<NoteChunk> candidates = knowledgeIndexService.list(wrapper);
		if (candidates.isEmpty()) {
			return;
		}
		// query 向量化（单条）
		float[] queryVec = dashScopeClient.embed(List.of(q)).get(0);
		// 每笔记取最高余弦块
		Map<String, Double> best = new HashMap<>();
		Map<String, String> bestSnippet = new HashMap<>();
		for (NoteChunk chunk : candidates) {
			float[] vec;
			try {
				vec = KnowledgeIndexService.deserializeVector(chunk.getEmbedding());
			} catch (Exception e) {
				continue; // 脏数据块跳过
			}
			double sim = cosine(queryVec, vec);
			String nid = chunk.getNid();
			if (sim > best.getOrDefault(nid, -1.0)) {
				best.put(nid, sim);
				bestSnippet.put(nid, chunk.getContent());
			}
		}
		// 相似度降序取名次
		best.entrySet().stream()
				.sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
				.limit(RECALL_TOP_N)
				.forEach(e -> {
					rankMap.put(e.getKey(), rankMap.size() + 1);
					snippetMap.put(e.getKey(), e.getValue() == null ? null
							: truncate(bestSnippet.get(e.getKey()), SNIPPET_CHARS));
				});
	}

	/** 标题路：范围内 title 小写 contains，命中取名次（contains 顺序） */
	private Map<String, Integer> rankByTitle(List<NoteMeta> metas, String q) {
		Map<String, Integer> rank = new LinkedHashMap<>();
		String lower = q.toLowerCase();
		for (NoteMeta m : metas) {
			if (m.getTitle() != null && m.getTitle().toLowerCase().contains(lower)) {
				rank.put(m.getNid(), rank.size() + 1);
				if (rank.size() >= RECALL_TOP_N) {
					break;
				}
			}
		}
		return rank;
	}

	/** RRF 累加：rank 路 score += 1/(K+rank) */
	private void accumulate(Map<String, Double> scores, Map<String, Integer> rank) {
		for (Map.Entry<String, Integer> e : rank.entrySet()) {
			scores.merge(e.getKey(), 1.0 / (RRF_K + e.getValue()), Double::sum);
		}
	}

	/** snippet 选取：优先向量命中块；否则该笔记首块前缀；无块返回空串 */
	private String pickSnippet(String nid, Map<String, String> vectorSnippets) {
		String s = vectorSnippets.get(nid);
		if (s != null && !s.isBlank()) {
			return s;
		}
		NoteChunk first = knowledgeIndexService.getOne(new QueryWrapper<NoteChunk>()
				.eq("nid", nid).orderByAsc("chunk_no").last("LIMIT 1"));
		return first == null ? "" : truncate(first.getContent(), SNIPPET_CHARS);
	}

	/** 余弦相似度（显式归一化，不依赖上游向量是否已归一） */
	private double cosine(float[] a, float[] b) {
		if (a.length != b.length) {
			return -1;
		}
		double dot = 0, na = 0, nb = 0;
		for (int i = 0; i < a.length; i++) {
			dot += (double) a[i] * b[i];
			na += (double) a[i] * a[i];
			nb += (double) b[i] * b[i];
		}
		if (na == 0 || nb == 0) {
			return 0;
		}
		return dot / (Math.sqrt(na) * Math.sqrt(nb));
	}

	/** 文本截断（超长补省略号） */
	private String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}

	/** 保留 3 位小数 */
	private double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}
}
