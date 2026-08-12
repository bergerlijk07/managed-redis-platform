package io.platform.redis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ManagedRedisPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManagedRedisPlatformApplication.class, args);
    }
}
