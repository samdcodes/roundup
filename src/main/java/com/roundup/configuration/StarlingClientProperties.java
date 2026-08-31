package com.roundup.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("starling")
public record StarlingClientProperties(@NotNull URI baseUrl, @NotBlank String accessToken, @NotBlank String userAgent) {
}
