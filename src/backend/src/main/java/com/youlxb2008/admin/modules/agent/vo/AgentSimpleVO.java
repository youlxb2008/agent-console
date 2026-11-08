package com.youlxb2008.admin.modules.agent.vo;

import lombok.Data;

/**
 * agent 精简 VO（v3 新增）
 * <p>
 * 关联弹窗回显 / 笔记贡献者（contributors）共用：
 * 只含展示字段，属字段白名单（绝不暴露 key_hash/scopes/审计/IP）。
 */
@Data
public class AgentSimpleVO {

    /** agent id（切换笔记本深链用） */
    private Long id;

    /** agent 名（机器名） */
    private String name;

    /** 展示名（display_name 优先，无则回退 name；门户即笔记本名） */
    private String displayName;

    public AgentSimpleVO() {
    }

    public AgentSimpleVO(Long id, String name, String displayName) {
        this.id = id;
        this.name = name;
        this.displayName = displayName;
    }
}
