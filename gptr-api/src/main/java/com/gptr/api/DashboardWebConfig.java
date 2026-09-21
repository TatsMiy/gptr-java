package com.gptr.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * OBS-2：观测台静态页目录入口。
 *
 * <p>Spring Boot 仅对根路径解析 welcome index.html；子目录 {@code /dashboard/}
 * 需显式转发到 {@code /dashboard/index.html}（资源在 classpath:/static/dashboard/）。
 */
@Configuration
public class DashboardWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/dashboard/").setViewName("forward:/dashboard/index.html");
    }
}
