package com.youlxb2008.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件
     */
    @Bean
    PaginationInnerInterceptor paginationInterceptor() {
        return new PaginationInnerInterceptor();
    }
}

