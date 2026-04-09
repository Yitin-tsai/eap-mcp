package com.eap.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class EapMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(EapMcpApplication.class, args);
    }
}
