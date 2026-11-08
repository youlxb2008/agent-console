package com.youlxb2008.admin.modules.note.kb.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
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
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.exceptions.BusinessException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Lucene 全文索引 Manager（知识库 BM25 检索路）
 * <p>
 * 索引粒度 = 一篇笔记一个文档（非分块粒度），字段：
 * <ul>
 *   <li>nid（StringField，STORED）——回查笔记的唯一键，也用于覆写时按 nid 整篇替换</li>
 *   <li>notebookId（StringField，不存）——检索范围过滤（不展示，省索引体积）</li>
 *   <li>title（TextField，STORED，权重 ×3）——标题命中优先于正文命中</li>
 *   <li>content（TextField，STORED）——整篇 md 原文</li>
 * </ul>
 * 中文分词：SmartChineseAnalyzer（Lucene 官方自带，零外部词典配置）。
 * <p>
 * 写侧（indexNote/deleteNote）由 KnowledgeIndexService 单线程串行调用——IndexWriter 本身线程安全，
 * 串行只为与 t_note_chunk 落库保持同一次序；读侧（search）每次用 DirectoryReader.open(writer)
 * 拿近实时（NRT）reader，无需手动 reopen。
 * <p>
 * 索引目录：kb.index-root（默认 {data-root}/kb-index），删除目录即可安全重建。
 */
@Slf4j
@Component
public class LuceneIndexManager {

	/** 字段名：笔记对外引用键（精确匹配，覆写定位/结果回查） */
	private static final String F_NID = "nid";
	/** 字段名：笔记本 id（精确过滤，不 STORED） */
	private static final String F_NOTEBOOK = "notebookId";
	/** 字段名：标题（全文，权重高于正文） */
	private static final String F_TITLE = "title";
	/** 字段名：正文（全文，md 原文） */
	private static final String F_CONTENT = "content";

	/** 标题命中权重（3 倍于正文：知识库检索里标题强相关往往就是目标笔记） */
	private static final float TITLE_BOOST = 3.0f;

	/** 索引目录（kb.index-root，默认跟随数据根目录） */
	@Value("${kb.index-root:${web.data-root:./data}/kb-index}")
	private String indexRoot;

	/** 中文分析器（线程安全，全局单例；查询侧与写入侧共用保证分词一致） */
	private final Analyzer analyzer = new SmartChineseAnalyzer();

	private IndexWriter writer;

	/**
	 * 启动初始化：建目录 + 打开 IndexWriter
	 * <p>
	 * CREATE_OR_OPEN：目录不存在则新建，存在则续用（增量索引随笔记写入持续累积）。
	 */
	@PostConstruct
	public void init() {
		try {
			Path dir = Paths.get(indexRoot);
			Files.createDirectories(dir);
			Directory directory = FSDirectory.open(dir);
			IndexWriterConfig config = new IndexWriterConfig(analyzer);
			writer = new IndexWriter(directory, config);
			log.info("Lucene 知识库索引就绪：{}（现有文档 {} 篇）", indexRoot, writer.getDocStats().numDocs);
		} catch (IOException e) {
			// 索引打不开不阻断应用启动：BM25 检索降级不可用，但笔记主链路不受影响
			log.error("Lucene 索引初始化失败（BM25 检索不可用）：{}", e.getMessage());
		}
	}

	/** 关闭释放（应用停机时由 Spring 回调） */
	@PreDestroy
	public void close() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException e) {
				log.warn("Lucene IndexWriter 关闭失败：{}", e.getMessage());
			}
		}
	}

	/** 索引是否可用（初始化失败时 false，检索自动降级为纯向量路） */
	public boolean available() {
		return writer != null && writer.isOpen();
	}

	/**
	 * 写/覆写一篇笔记的全文索引（先按 nid 删旧文档再整篇重建，幂等）
	 *
	 * @param nid        笔记对外引用键
	 * @param notebookId 所属笔记本 id（检索范围过滤）
	 * @param title      标题
	 * @param content    md 正文全文
	 */
	public void indexNote(String nid, Long notebookId, String title, String content) {
		ensureWriter();
		try {
			// 覆写语义：nid 终身不变，按 nid 先删后加保证整篇替换
			writer.deleteDocuments(new Term(F_NID, nid));
			Document doc = new Document();
			doc.add(new StringField(F_NID, nid, Field.Store.YES));
			doc.add(new StringField(F_NOTEBOOK, String.valueOf(notebookId), Field.Store.NO));
			doc.add(new TextField(F_TITLE, title == null ? "" : title, Field.Store.YES));
			doc.add(new TextField(F_CONTENT, content == null ? "" : content, Field.Store.YES));
			writer.addDocument(doc);
			writer.commit();
		} catch (IOException e) {
			throw new BusinessException("Lucene 索引写入失败：" + e.getMessage());
		}
	}

	/**
	 * 删除一篇笔记的全文索引（笔记删除时调用；文档不存在时静默幂等）
	 *
	 * @param nid 笔记对外引用键
	 */
	public void deleteNote(String nid) {
		ensureWriter();
		try {
			writer.deleteDocuments(new Term(F_NID, nid));
			writer.commit();
		} catch (IOException e) {
			throw new BusinessException("Lucene 索引删除失败：" + e.getMessage());
		}
	}

	/**
	 * BM25 全文检索（单笔记本范围版，委托集合版）
	 *
	 * @param query      查询词（自然语言，内部分词）
	 * @param notebookId 范围过滤（null=全库）
	 * @param topN       返回条数上限
	 * @return 命中列表（BM25 得分降序；score 仅用于 RRF 排位，不回传前端）
	 */
	public List<LuceneHit> search(String query, Long notebookId, int topN) {
		// 单值转集合复用同一查询构建逻辑（null 保持 null=全库语义）
		return search(query, notebookId == null ? null : List.of(notebookId), topN);
	}

	/**
	 * BM25 全文检索（笔记本集合范围版，agent 域数据隔离用）
	 * <p>
	 * 查询词 SmartCN 分词后，标题×3 + 正文 组合 SHOULD；notebookIds 非空时
	 * MUST TermInSetQuery 过滤（命中限定在集合内）；<b>空集合=无可搜范围，
	 * 直接返回空结果（fail-closed 防范围泄漏）</b>。
	 *
	 * @param query       查询词（自然语言，内部分词）
	 * @param notebookIds 范围过滤集合（null=全库；空集合=无权限范围 → 空结果）
	 * @param topN        返回条数上限
	 * @return 命中列表（BM25 得分降序）
	 */
	public List<LuceneHit> search(String query, Collection<Long> notebookIds, int topN) {
		if (!available() || query == null || query.isBlank()) {
			return List.of();
		}
		try (DirectoryReader reader = DirectoryReader.open(writer)) {
			IndexSearcher searcher = new IndexSearcher(reader);
			Query q = buildQuery(query, notebookIds);
			if (q == null) {
				return List.of();
			}
			TopDocs docs = searcher.search(q, topN);
			List<LuceneHit> hits = new ArrayList<>(docs.scoreDocs.length);
			for (ScoreDoc sd : docs.scoreDocs) {
				// Lucene 8.x API：searcher.doc()（9.x 起才拆出 storedFields()）
				Document doc = searcher.doc(sd.doc);
				LuceneHit hit = new LuceneHit();
				hit.setNid(doc.get(F_NID));
				hit.setScore(sd.score);
				hits.add(hit);
			}
			return hits;
		} catch (IOException e) {
			log.warn("Lucene 检索失败（降级跳过 BM25 路）：{}", e.getMessage());
			return List.of();
		}
	}

	/* ==================== 内部实现 ==================== */

	/**
	 * 构建查询：查询词经 SmartCN 分词 → 每个词条在 title(×3)/content 各建 TermQuery（SHOULD）；
	 * notebookIds 非空 → 加 MUST TermInSetQuery 精确过滤（命中限定在集合内）；
	 * notebookIds 为<b>空集合</b> → 返回 null（无可搜范围，调用方得到空结果，防全库泄漏）。
	 * 分不出词（如纯符号）返回 null。
	 */
	private Query buildQuery(String query, Collection<Long> notebookIds) throws IOException {
		List<String> tokens = tokenize(query);
		if (tokens.isEmpty()) {
			return null;
		}
		BooleanQuery.Builder contentBuilder = new BooleanQuery.Builder();
		for (String token : tokens) {
			// 标题路：×3 加权；正文路：原权重；两路 SHOULD 叠加，命中越多分越高
			contentBuilder.add(new BoostQuery(new TermQuery(new Term(F_TITLE, token)), TITLE_BOOST),
					BooleanClause.Occur.SHOULD);
			contentBuilder.add(new TermQuery(new Term(F_CONTENT, token)), BooleanClause.Occur.SHOULD);
		}
		if (notebookIds == null) {
			return contentBuilder.build();
		}
		// 空集合守卫：无任何可搜范围 → null（search 侧返回空结果，绝不退化成全库检索）
		if (notebookIds.isEmpty()) {
			return null;
		}
		// 有范围：内容子查询 MUST + notebookId 集合 MUST（TermInSetQuery 等价 IN 过滤）
		List<BytesRef> terms = new ArrayList<>(notebookIds.size());
		for (Long id : notebookIds) {
			terms.add(new BytesRef(String.valueOf(id)));
		}
		return new BooleanQuery.Builder()
				.add(contentBuilder.build(), BooleanClause.Occur.MUST)
				.add(new TermInSetQuery(F_NOTEBOOK, terms), BooleanClause.Occur.MUST)
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
			throw new BusinessException("Lucene 索引不可用（初始化失败，检查 kb.index-root 目录）");
		}
	}

	/** Lucene 命中项（nid + BM25 得分） */
	@Data
	public static class LuceneHit {
		/** 笔记对外引用键 */
		private String nid;
		/** BM25 得分（仅排序用） */
		private float score;
	}
}
