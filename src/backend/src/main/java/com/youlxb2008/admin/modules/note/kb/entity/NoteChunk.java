package com.youlxb2008.admin.modules.note.kb.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库分块实体（t_note_chunk，一行 = 一篇笔记的一个分块）
 * <p>
 * KB（knowledge base）派生数据：真相源仍是 NOTES_ROOT/notes/{nid}.md 文件。
 * 覆写笔记时先 DELETE 该 nid 全部块再重新插入；任何时刻可通过管理端接口全量重建。
 * <ul>
 *   <li>content：分块纯文本（~500 字/块，块间重叠 ~60 字）——BM25 命中片段展示 + RAG 上下文</li>
 *   <li>embedding：float32[1024] 小端序列化字节（text-embedding-v3 默认 1024 维）；
 *       NULL = 尚未向量化（如 DashScope key 未配置时先落文本块，后补向量）</li>
 *   <li>embedModel：向量化所用模型名，换模型后重建索引的排查依据</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_note_chunk")
public class NoteChunk {

	private static final long serialVersionUID = 1L;

	/** 主键 ID，自增 */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 关联笔记对外引用键（t_note_meta.nid，逻辑外键） */
	private String nid;

	/** 所属笔记本 id（冗余自 t_note_meta，检索范围过滤用，免联表） */
	private Long notebookId;

	/** 块序号（同一 nid 内从 0 递增） */
	private Integer chunkNo;

	/** 分块纯文本（≤2000 字符） */
	private String content;

	/** 向量字节（float32 小端序列化，1024 维 = 4096 字节；NULL=未向量化） */
	private byte[] embedding;

	/** 向量模型名（如 text-embedding-v3） */
	private String embedModel;

	/** 首次写入时间 */
	private Date createTime;

	/** 最近更新时间 */
	private Date updateTime;
}
