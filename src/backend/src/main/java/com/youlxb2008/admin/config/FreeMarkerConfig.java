package com.youlxb2008.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.hutool.extra.template.engine.freemarker.FreemarkerEngine;

@Configuration
public class FreeMarkerConfig {
	@Bean
	FreemarkerEngine templateEngine() {
		return new FreemarkerEngine();
	}
}
