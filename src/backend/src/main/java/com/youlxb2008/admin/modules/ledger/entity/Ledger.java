package com.youlxb2008.admin.modules.ledger.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 台账索引实体（M4.2，agent 私域流水 "我做过/想过/决定过什么"）
 * 对应数据库表 t_ledger（docs/版本/agent-v4_20260831 §1.4）
 * <p>
 * 5 种类型（type）：progress进度 / observation观察 / decision决策 / memo备忘 / session会话小结。
 * body 双轨制：≤500 字存 body_inline；>500 字写 LEDGER_ROOT/agent-{id}/ledger-{lid}.md + 存 body_path。
 * 列表/流接口不读文件（只回 title/type/时间/引用），详情接口读正文（inline 优先，否则读 md）。
 * 软删模型：is_deleted=1（@TableLogic），有 body_path 时文件保留不删。
 * 归属：agent_id 单值私域（每 agent 独立流水线）；无 update_time 列（流水按 create_time 排序，记账时间即序）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_ledger")
public class Ledger {

	private static final long serialVersionUID = 1L;

	/** 主键 ID，自增（纯内部，不外露） */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 对外引用键：l_ + 10 位 base62，随机生成终身不变 */
	private String lid;

	/** 归属 agent（t_agent.id），每 agent 私域流水 */
	private Long agentId;

	/** 类型：progress/observation/decision/memo/session */
	private String type;

	/** 条目标题 */
	private String title;

	/** 短正文（≤500 字直接存库；>500 字时为 null） */
	private String bodyInline;

	/** 长正文 md 路径 LEDGER_ROOT/agent-{id}/ledger-{lid}.md（>500 字；短正文时为 null） */
	private String bodyPath;

	/** 关联任务 tid（可选，事件归因单选引用列，可并存） */
	private String refTaskId;

	/** 关联需求 rid（可选） */
	private String refRequirementId;

	/** 关联目标 oid（可选） */
	private String refObjectiveId;

	/** 关联笔记 nid（可选） */
	private String refNoteNid;

	/** 创建时间（流水时间，即记账时刻；无 update_time 列） */
	private Date createTime;

	/** 逻辑删除：0=未删除 1=已删除（@TableLogic，全链路 fail-closed） */
	@TableLogic
	private Integer isDeleted;

	/** 所属 agent 展示名（非表字段：管理端/门户聚合回填用，来自 t_agent 联查） */
	@TableField(exist = false)
	private String agentName;
}
