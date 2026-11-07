package dev.skeletonkey;

import dev.skeletonkey.config.SkeletonProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SkeletonProperties.class)
public class SkeletonKeyApplication {

  public static void main(String[] args) {
    SpringApplication.run(SkeletonKeyApplication.class, args);
  }
}
