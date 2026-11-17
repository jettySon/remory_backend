package com.remory.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(RemoryApplication.class, args);
    }
}
