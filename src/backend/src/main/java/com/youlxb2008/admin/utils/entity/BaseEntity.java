package com.youlxb2008.admin.utils.entity;

import java.io.Serializable;
import java.util.Date;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class BaseEntity implements Serializable{
	private static final long serialVersionUID = -4524594312225705178L;
	
	private Date createdAt;
}
