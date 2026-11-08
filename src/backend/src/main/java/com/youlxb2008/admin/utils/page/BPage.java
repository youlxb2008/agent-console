package com.youlxb2008.admin.utils.page;

import java.util.List;

import org.springframework.data.domain.Page;

import com.baomidou.mybatisplus.core.metadata.IPage;

import lombok.Data;

@Data
public class BPage {
	private Long curPage;
	private Long count;
	private Long pageSize;
	private List<?> list; 
	
	
	public static BPage of(IPage<?> page) {
		BPage bPage = new BPage();
		bPage.setCurPage(page.getCurrent());
		bPage.setCount(page.getTotal());
		bPage.setList(page.getRecords());
		bPage.setPageSize(page.getSize());
		return bPage;
	}
	
	public static BPage of(Page<?> page) {
		BPage bPage = new BPage();
		bPage.setCurPage(page.getNumber() + 0L);
		bPage.setCount(page.getTotalElements());
		bPage.setList(page.getContent());
		bPage.setPageSize(page.getSize() + 0L);
		return bPage;
	}
	
}
