package com.youlxb2008.admin.modules.system.vo;

import java.util.List;

import lombok.Data;

@Data
public class UserLoginResp {
	private String avatar;
    /** 用户名 */
	private String username;
    /** 昵称 */
	private String nickname;
    /** 当前登录用户的角色 */
	private List<String> roles;
    /** 按钮级别权限 */
	private List<String> permissions;
    /** `token` */
	private String  accessToken;
    /** 用于调用刷新`accessToken`的接口时所需的`token` */
	private String  refreshToken;
    /** `accessToken`的过期时间（格式'xxxx/xx/xx xx:xx:xx'） */
	private long  expires;
    /** 是否需要强制修改密码 */
	private boolean mustChangePassword;
}
