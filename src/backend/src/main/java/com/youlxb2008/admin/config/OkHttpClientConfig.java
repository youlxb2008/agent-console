package com.youlxb2008.admin.config;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import okhttp3.OkHttpClient;


@Configuration
public class OkHttpClientConfig {

	@Bean
	OkHttpClient okHttpClient() {
		OkHttpClient client =  new OkHttpClient.Builder()
        	    .connectTimeout(60, TimeUnit.SECONDS)   // 连接超时时间
        	    .readTimeout(120, TimeUnit.SECONDS)      // 读取超时时间
        	    .writeTimeout(120, TimeUnit.SECONDS)     // 写入超时时间
        	    .build();
		return client;
	}
}
