package com.youlxb2008.admin.utils.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.Data;

@Data
public class PageSearch {
	private Integer curPage = 1;
	private Integer pageSize = 10;
	private String order;
	private String orderKey;
	
	
	public <T> Page<T> toDBPage() {
		return new Page<T>(curPage, pageSize);
	}
}
