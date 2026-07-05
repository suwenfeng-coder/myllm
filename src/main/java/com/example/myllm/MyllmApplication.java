package com.example.myllm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MyllmApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyllmApplication.class, args);
    }
}
