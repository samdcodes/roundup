package com.roundup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(info = @Info(title = "Starling Seven-Day Round-Up API", version = "v1"))
public class RoundUpApplication {

    static void main(String[] args) {
        SpringApplication.run(RoundUpApplication.class, args);
    }
}
