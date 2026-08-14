package com.ensp.nms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 无 Docker 部署时由后端托管 frontend 构建目录（./web），实现单端口访问。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Value("${nms.web.static-dir:}")
    private String staticDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!StringUtils.hasText(staticDir)) {
            return;
        }
        Path dir = Path.of(staticDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return;
        }
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = super.getResource(resourcePath, location);
                        if (requested != null && requested.exists()) {
                            return requested;
                        }
                        // Vue Router history：无实体文件时回退 index.html
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws/")) {
                            return null;
                        }
                        return new FileSystemResource(dir.resolve("index.html"));
                    }
                });
    }
}
