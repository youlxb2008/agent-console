package com.youlxb2008.admin.modules.system.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 用户视图对象（Controller 出参）
 * 隐藏 password、userType 等敏感字段，统一前端字段命名
 */
@Data
public class UserVO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private String remark;
    /** 状态：0-正常 1-禁用 */
    private Integer status;
    /** 是否需要强制修改密码 */
    private Boolean mustChangePassword;
    private Date lastLoginTime;
    /** 创建时间（统一前端字段名） */
    private Date createTime;
    /** 角色列表（包含 id/name/code，供前端角色列渲染和分配角色弹窗回显） */
    private List<RoleItem> roles;

    /**
     * 角色简要信息（仅暴露前端需要的字段）
     */
    @Data
    public static class RoleItem {
        private Integer id;
        private String name;
        private String code;

        public RoleItem() {}

        public RoleItem(Integer id, String name, String code) {
            this.id = id;
            this.name = name;
            this.code = code;
        }
    }
}
