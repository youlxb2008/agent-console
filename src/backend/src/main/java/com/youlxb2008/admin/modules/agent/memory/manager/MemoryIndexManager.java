package com.youlxb2008.admin.modules.agent.memory.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.exceptions.BusinessException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 记忆 Lucene 全文索引 Manager（M7.1，BM25 检索路）
 * <p>
 * 与知识库 {@code LuceneIndexManager} 同款模式（SmartCN 分词 + NRT reader + BM25），
 * 但为 t_memory 的<b>独立索引实例</b>（独立目录 memory.index-root，字段集不同）：
 * <ul>
 *   <li>memId（StringField，STORED）——记忆数字 id，覆写定位/结果回查</li>
 *   <li>agentId（StringField，不存）——检索范围过滤（agent 私域 fail-closed）</li>
 *   <li>title（TextField，STORED，权重 ×3）——标题命中优先</li>
 *   <li>tags（TextField，STORED）——标签参与全文检索</li>
 *   <li>content（TextField，STORED）——contentMd 结论正文</li>
 *   <li>weight（NumericDocValuesField）——同分 tie-breaker（weight 高者前）</li>
 * </ul>
 * <p>
 * 设计决策（任务书 §2.3「参数化索引路径或实例化两个实例」）：选择<b>独立 Manager 类</b>
 * 而非参数化改造知识库 LuceneIndexManager——两者字段集不同（记忆多 tags/weight、
 * 主键是数字 id 而非 nid 字符串、范围过滤是 agentId 而非 notebookId），参数化会把
 * KB 类改成泛化形态、回归面大；独立类零碰 KB 文件，笔记 KB 现有行为硬保障零变化。
 * <p>
 * 排序：BM25 相关度为主，同分 weight 高者前（Sort: FIELD_SCORE → weight 降序）。
 * 归档/被取代条目照常在索引中（status 由 DB 实时读，检索结果带标记）；
 * 逻辑删除时移除文档（is_deleted=1 不参与检索）。
 */
@Slf4j
@Component
public class MemoryIndexManager {

	/** 字段名：记忆 id（精确匹配，覆写定位/结果回查） */
	private static final String F_ID = "memId";
	/** 字段名：agent id（精确过滤，不 STORED） */
	private static final String F_AGENT = "agentId";
	/** 字段名：标题（全文，权重高于正文） */
	private static final String F_TITLE = "title";
	/** 字段名：标签（全文） */
	private static final String F_TAGS = "tags";
	/** 字段名：正文（全文，contentMd） */
	private static final String F_CONTENT = "content";
	/** 字段名：衰减权重（DocValues，同分排序用） */
	private static final String F_WEIGHT = "weight";

	/** 标题命中权重（3 倍于正文，对齐知识库索引） */
	private static final float TITLE_BOOST = 3.0f;

	/** 索引目录（memory.index-root，默认跟随数据根目录） */
	@Value("${memory.index-root:${web.data-root:./data}/memory-index}")
	private String indexRoot;

	/** 中文分析器（线程安全，全局单例；查询侧与写入侧共用保证分词一致） */
	private final Analyzer analyzer = new SmartChineseAnalyzer();

	private IndexWriter writer;

	/**
	 * 启动初始化：建目录 + 打开 IndexWriter（CREATE_OR_OPEN，增量累积）
	 */
	@PostConstruct
	public void init() {
		try {
			Path dir = Paths.get(indexRoot);
			Files.createDirectories(dir);
			Directory directory = FSDirectory.open(dir);
			IndexWriterConfig config = new IndexWriterConfig(analyzer);
			writer = new IndexWriter(directory, config);
			log.info("Lucene 记忆索引就绪：{}（现有文档 {} 条）", indexRoot, writer.getDocStats().numDocs);
		} catch (IOException e) {
			// 索引打不开不阻断应用启动：记忆检索降级不可用，记忆主链路不受影响
			log.error("Lucene 记忆索引初始化失败（记忆检索不可用）：{}", e.getMessage());
		}
	}

	/** 关闭释放（应用停机时由 Spring 回调） */
	@PreDestroy
	public void close() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException e) {
				log.warn("Lucene 记忆 IndexWriter 关闭失败：{}", e.getMessage());
			}
		}
	}

	/** 索引是否可用（初始化失败时 false，检索自动降级为空结果） */
	public boolean available() {
		return writer != null && writer.isOpen();
	}

	/**
	 * 写/覆写一条记忆的全文索引（先按 memId 删旧文档再重建，幂等）
	 *
	 * @param id      记忆 id
	 * @param agentId 归属 agent id（检索范围过滤）
	 * @param title   标题
	 * @param tags    逗号分隔标签（可空）
	 * @param content 结论正文（可空）
	 * @param weight  衰减权重（同分排序 tie-breaker）
	 */
	public void indexMemory(Long id, Long agentId, String title, String tags, String content, int weight) {
		ensureWriter();
		try {
			// 覆写语义：id 终身不变，按 id 先删后加保证整篇替换
			writer.deleteDocuments(new Term(F_ID, String.valueOf(id)));
			Document doc = new Document();
			doc.add(new StringField(F_ID, String.valueOf(id), Field.Store.YES));
			doc.add(new StringField(F_AGENT, String.valueOf(agentId), Field.Store.NO));
			doc.add(new TextField(F_TITLE, title == null ? "" : title, Field.Store.YES));
			doc.add(new TextField(F_TAGS, tags == null ? "" : tags, Field.Store.YES));
			doc.add(new TextField(F_CONTENT, content == null ? "" : content, Field.Store.YES));
			doc.add(new NumericDocValuesField(F_WEIGHT, weight));
			writer.addDocument(doc);
			writer.commit();
		} catch (IOException e) {
			throw new BusinessException("Lucene 记忆索引写入失败：" + e.getMessage());
		}
	}

	/**
	 * 删除一条记忆的全文索引（逻辑删除时调用；文档不存在时静默幂等）
	 *
	 * @param id 记忆 id
	 */
	public void removeMemory(Long id) {
		ensureWriter();
		try {
			writer.deleteDocuments(new Term(F_ID, String.valueOf(id)));
			writer.commit();
		} catch (IOException e) {
			throw new BusinessException("Lucene 记忆索引删除失败：" + e.getMessage());
		}
	}

	/**
	 * BM25 全文检索（单 agent 私域范围）
	 * <p>
	 * 查询词 SmartCN 分词后，标题×3 + 标签 + 正文 组合 SHOULD；agentId MUST 过滤
	 * （命中限定在当前 agent 私域内，fail-closed 防跨 agent 泄漏）。
	 * 排序：BM25 得分降序为主，同分 weight 高者前。
	 *
	 * @param query   查询词（自然语言，内部分词）
	 * @param agentId 范围过滤（null=非法调用，返回空结果）
	 * @param topN    返回条数上限
	 * @return 命中列表（相关度降序；score 仅用于排序参考）
	 */
	public List<MemoryHit> search(String query, Long agentId, int topN) {
		if (!available() || query == null || query.isBlank() || agentId == null) {
			return List.of();
		}
		try (DirectoryReader reader = DirectoryReader.open(writer)) {
			IndexSearcher searcher = new IndexSearcher(reader);
			Query q = buildQuery(query, agentId);
			if (q == null) {
				return List.of();
			}
			// 相关度为主，同分 weight 高者前（weight 为 NumericDocValuesField）
			Sort sort = new Sort(SortField.FIELD_SCORE, new SortField(F_WEIGHT, SortField.Type.INT, true));
			TopFieldDocs docs = searcher.search(q, topN, sort);
			List<MemoryHit> hits = new ArrayList<>(docs.scoreDocs.length);
			for (ScoreDoc sd : docs.scoreDocs) {
				// Lucene 8.x API：searcher.doc()（9.x 起才拆出 storedFields()）
				Document doc = searcher.doc(sd.doc);
				MemoryHit hit = new MemoryHit();
				hit.setId(Long.parseLong(doc.get(F_ID)));
				hit.setScore(sd.score);
				hits.add(hit);
			}
			return hits;
		} catch (IOException e) {
			log.warn("Lucene 记忆检索失败（降级返回空结果）：{}", e.getMessage());
			return List.of();
		} catch (NumberFormatException e) {
			log.warn("Lucene 记忆索引文档 id 非法（跳过）：{}", e.getMessage());
			return List.of();
		}
	}

	/* ==================== 内部实现 ==================== */

	/**
	 * 构建查询：查询词经 SmartCN 分词 → 每个词条在 title(×3)/tags/content 各建
	 * TermQuery（SHOULD）；agentId MUST 精确过滤（私域范围）。
	 * 分不出词（如纯符号）返回 null。
	 */
	private Query buildQuery(String query, Long agentId) throws IOException {
		List<String> tokens = tokenize(query);
		if (tokens.isEmpty()) {
			return null;
		}
		BooleanQuery.Builder contentBuilder = new BooleanQuery.Builder();
		for (String token : tokens) {
			contentBuilder.add(new BoostQuery(new TermQuery(new Term(F_TITLE, token)), TITLE_BOOST),
					BooleanClause.Occur.SHOULD);
			contentBuilder.add(new TermQuery(new Term(F_TAGS, token)), BooleanClause.Occur.SHOULD);
			contentBuilder.add(new TermQuery(new Term(F_CONTENT, token)), BooleanClause.Occur.SHOULD);
		}
		return new BooleanQuery.Builder()
				.add(contentBuilder.build(), BooleanClause.Occur.MUST)
				.add(new TermQuery(new Term(F_AGENT, String.valueOf(agentId))), BooleanClause.Occur.MUST)
				.build();
	}

	/** 查询词分词（SmartCN；与索引写入同款分析器，保证词项可命中） */
	private List<String> tokenize(String text) throws IOException {
		List<String> tokens = new ArrayList<>();
		var stream = analyzer.tokenStream(F_CONTENT, text);
		var termAttr = stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
		stream.reset();
		while (stream.incrementToken()) {
			String t = termAttr.toString();
			// 过滤单字符噪声词（如"的""了"），减少无意义命中
			if (t.length() > 1) {
				tokens.add(t);
			}
		}
		stream.close();
		return tokens;
	}

	/** writer 未就绪（初始化失败/已关闭）时抛业务异常 */
	private void ensureWriter() {
		if (!available()) {
			throw new BusinessException("Lucene 记忆索引不可用（初始化失败，检查 memory.index-root 目录）");
		}
	}

	/** Lucene 命中项（记忆 id + BM25 得分） */
	@Data
	public static class MemoryHit {
		/** 记忆 id */
		private Long id;
		/** BM25 得分（仅排序用） */
		private float score;
	}
}
