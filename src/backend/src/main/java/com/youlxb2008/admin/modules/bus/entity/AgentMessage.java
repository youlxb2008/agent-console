package com.youlxb2008.admin.modules.bus.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent 消息总线消息实体（M1，方案 §3.1）
 * <p>
 * 对应数据库表 t_agent_message。本表为终态真相源（MySQL），
 * Redis Stream 仅作投递队列（ac:agent-bus:stream:{toAgentId}，按目标分片）。
 * <p>
 * status 状态机：0 待投递 / 1 已送达（协议层，WS send 成功）/ 2 已读（M2 预留）/ 3 失败。
 * 注意：本表时间列名为 create_time/update_time（与 agent 域一致，非管理端常见的 created_at），
 * 故不继承 BaseEntity，时间字段在本类显式声明。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_agent_message")
public class AgentMessage {

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键，服务端生成 UUID */
    private String messageId;

    /** 生产者 agent id（由 X-API-Key 推导，body 不可指定，防伪造） */
    private Long fromAgentId;

    /** 消费者 agent id（逻辑关联 t_agent.id） */
    private Long toAgentId;

    /** 消息类型：notify/alert（M1）；request M1 禁用（422） */
    private String msgType;

    /** 场景标签：alpha-exception / patrol-alert 等 */
    private String topic;

    /** 标题 */
    private String title;

    /** 消息体 JSON，≤8KB */
    private String payload;

    /**
     * 目标会话；空 = dispatcher 复用固定会话 bus-agent{fromId}-{type}；
     * 强制 bus-* 命名空间（禁注入收件人既有会话）；投递成功后回填实际 sessionKey
     */
    private String sessionKey;

    /** 指定本条消息处理模型；空 = 收件人默认 preset；须经目标路由 models 白名单校验 */
    private String model;

    /**
     * 消息级回音时限（分钟，1~1440，bus-watchdog-fuse）；空 = 走全局默认
     * （env BUS_FUSE_DEFAULT_MINUTES，缺省 30）。超时扫描判据见 BusFuseService。
     */
    private Integer expectReplyMinutes;

    /** 状态：0待投递/1已送达(协议层)/2已读(M2)/3失败 */
    private Integer status;

    /**
     * 实际投递通道：ws（gateway 常连接）/ api（HTTP 直调）——dispatcher 投递成功时回填；
     * api 目标缺 BUS_API_TARGET 配置降级 ws 时也写 ws（last_error 留备注）。
     * 历史行（本列上线前）NULL，前端显示「—」（bus-ui-v2）。
     */
    private String channel;

    /** 投递重试次数（指数退避 1s/2s/4s/8s/16s，≥5 → status=3） */
    private Integer retryCount;

    /** 最近一次投递失败原因（管理端可见，≤500 字符） */
    private String lastError;

    /** 送达时间（协议层） */
    private Date deliveredAt;

    /** 已读时间（M2 预留） */
    private Date readAt;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
