package com.youlxb2008.admin.modules.system.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.modules.system.entity.SiteOption;
import com.youlxb2008.admin.modules.system.mapper.SiteOptionMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 站点配置 Service 实现类
 */
@Slf4j
@Service
public class SiteOptionService extends ServiceImpl<SiteOptionMapper, SiteOption> {

	/**
	 * 查询全部配置项并组装为 key-value Map
	 * 
	 * @return 配置项 Map
	 */
	public Map<String, String> getAllOptions() {
		// 使用 LinkedHashMap 保持插入顺序，便于前端展示
		Map<String, String> result = new LinkedHashMap<>();
		List<SiteOption> list = this.list();
		for (SiteOption option : list) {
			result.put(option.getOptionKey(), option.getOptionValue());
		}
		return result;
	}

	/**
	 * 批量保存配置项：已存在的 key 执行更新，不存在的执行新增
	 * 
	 * @param options 配置项 Map
	 */
	@DSTransactional
	public void saveOptions(Map<String, String> options) {
		if (options == null || options.isEmpty()) {
			// 空入参直接返回，避免无意义操作
			return;
		}
		for (Map.Entry<String, String> entry : options.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			// 根据 optionKey 查询是否已存在
			SiteOption existing = this.getOne(new QueryWrapper<SiteOption>().eq("option_key", key));
			if (existing != null) {
				// 已存在则更新值
				existing.setOptionValue(value);
				this.updateById(existing);
			} else {
				// 不存在则新增
				SiteOption option = new SiteOption();
				option.setOptionKey(key);
				option.setOptionValue(value);
				this.save(option);
			}
		}
		log.info("批量保存站点配置完成，共{}项", options.size());
	}
}
