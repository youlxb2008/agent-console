package com.youlxb2008.admin.modules.agent.vo;

import lombok.Data;

/**
 * 门户：站点信息（契约 api.md §3，GET /site）
 * <p>
 * 只读 t_option 白名单键（site_title / site_description / portal_texture），缺省给默认值。
 */
@Data
public class PortalSiteVO {

	private String siteTitle;

	private String siteDescription;

	/**
	 * 门户质感方案（aurora 柔光 / relief 浮雕 / grid 网格）。
	 * 由管理平台站点设置配置，门户端只读展示、不做本地切换。
	 */
	private String portalTexture;
}
