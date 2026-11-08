package com.youlxb2008.admin.modules.agent.memory.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent 长期记忆条目实体（M7.1，任务书 docs/任务排期/M7.1-memory-module.md）
 * 对应数据库表 t_memory。
 * <p>
 * 语义（任务书 §2.1）：
 * <ul>
 *   <li>结构化记忆：memType 五类（fact/decision/preference/lesson/event）+
 *       source 三源（dialogue/self/system），title 一句话摘要 + contentMd 结论正文</li>
 *   <li>生命周期：status 1=active / 2=archived / 3=superseded；
 *       superseded_by 指向取代本条的新记忆 id（链式，详情接口沿链回溯最新版）</li>
 *   <li>衰减元数据：weight（memoryDecayJob 全量重算）、access_count/last_accessed_at
 *       （检索命中即刷新）、confirmed_at（confirm 接口刷新）</li>
 *   <li>对外引用键：数字 id 自增即对外键（任务书 API 路径 /memories/{id} 口径，
 *       与 todo 的 d_ 字符串键刻意不同）</li>
 *   <li>软删模型：is_deleted=1（@TableLogic），全链路查询 is_deleted=0（fail-closed）；
 *       归属：agent_id 单值私域，跨 agent 访问一律 404（不泄露存在性）</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_memory")
public class Memory {

	private static final long serialVersionUID = 1L;

	/** 主键 ID，自增（即对外引用键，API 路径 /memories/{id}） */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 归属 agent（t_agent.id），每 agent 私域记忆库 */
	private Long agentId;

	/** 记忆类型：fact/decision/preference/lesson/event（白名单五值） */
	private String memType;

	/** 来源：dialogue/self/system（白名单三值） */
	private String source;

	/** 一句话摘要（≤255 字符） */
	private String title;

	/** 结论正文（TEXT，直接入库，真相源即本行） */
	private String contentMd;

	/** 逗号分隔标签（≤512 字符，对齐笔记/待办 tags 惯例） */
	private String tags;

	/** AC 笔记深链指针（可空，单向引用不回写） */
	private String refUrl;

	/** 生命周期：1=active 2=archived 3=superseded */
	private Integer status;

	/** 取代本条的新记忆 id（status=3 时有值；链式回溯入口） */
	private Long supersededBy;

	/** 衰减权重（memoryDecayJob 全量重算；检索同分 tie-breaker） */
	private Integer weight;

	/** 权重保底线（M7.1-M3a：0=不保底；衰减重算落库 weight = max(公式值, weight_floor)） */
	private Integer weightFloor;

	/** 检索命中次数（search 命中即 +1） */
	private Integer accessCount;

	/** 最后命中时间（search 命中即刷新） */
	private Date lastAccessedAt;

	/** 最后确认时间（confirm 接口刷新） */
	private Date confirmedAt;

	/** 预留字段（本版本未用） */
	private Date expiredAt;

	/** 创建时间 */
	private Date createTime;

	/** 更新时间 */
	private Date updateTime;

	/** 逻辑删除：0=未删除 1=已删除（@TableLogic，全链路 fail-closed） */
	@TableLogic
	private Integer isDeleted;

	/** 所属 agent 展示名（非表字段：管理端列表回填用） */
	@TableField(exist = false)
	private String agentName;
}
