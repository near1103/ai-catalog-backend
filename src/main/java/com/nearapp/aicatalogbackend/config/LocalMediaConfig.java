package com.nearapp.aicatalogbackend.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
public class LocalMediaConfig implements WebMvcConfigurer {
  private final String directory;

  public LocalMediaConfig(@Value("${app.storage.local-directory:./uploads}") String directory) {
    this.directory = directory;
  }

  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/media/**")
        .addResourceLocations(Path.of(directory).toAbsolutePath().toUri().toString() + "/");
  }
}
