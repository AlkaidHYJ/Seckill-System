package com.example.flashsale.config;

import com.example.flashsale.util.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;

    public WebConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthenticationInterceptor(jwtUtil))
                .addPathPatterns("/api/seckill/activities/*/buy", "/api/orders/**");
    }
}