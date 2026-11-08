package com.youlxb2008.admin.modules.system.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserLoginReq implements Serializable {
    private static final long serialVersionUID = -1L;

    private String username;
    private String password;
}
