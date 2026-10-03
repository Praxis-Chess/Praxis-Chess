package com.praxis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

import com.praxis.config.AiProperties;
import com.praxis.config.AppProperties;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties({AppProperties.class, AiProperties.class})
public class PraxisChessApplication {

    public static void main(String[] args) {
        SpringApplication.run(PraxisChessApplication.class, args);
    }
}
