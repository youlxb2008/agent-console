package com.youlxb2008.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.youlxb2008.admin.utils.jwt.resolver.CurrentUserArgumentResolver;

import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

/**
 * Web MVC 配置
 * <p>
 * 1. 注册自定义参数解析器，使 Controller 方法参数上的 {@code @CurrentUser} 注解生效。
 * 2. 配置静态资源映射，将 /uploads/** 指向上传文件所在的磁盘目录，使上传后的文件可直接通过 HTTP 访问。
 */
@Configuration
@RequiredArgsConstructor
public class MvcConfig implements WebMvcConfigurer {

    /** 数据根目录配置（自动派生上传目录路径） */
    private final DataRootConfig dataRootConfig;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    /**
     * 静态资源映射
     * <p>
     * 将 URL 前缀 /uploads/** 映射到磁盘上传目录 file:{data-root}/uploads/，
     * 使上传后的文件可通过 http://host/uploads/... 直接访问。
     * 设置一年缓存以减少浏览器对已上传文件的重复请求。
     *
     * @param registry 资源处理器注册器
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 从 DataRootConfig 获取上传目录（已自动派生和创建）
        String uploadDir = dataRootConfig.getUploadDir();
        // file: 协议路径末尾必须以斜杠结尾，否则资源解析会失败
        String diskLocation = "file:" + uploadDir;
        if (!diskLocation.endsWith("/")) {
            diskLocation = diskLocation + "/";
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(diskLocation)
                // 资源链缓存控制：一年
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic());
    }
}
