package com.example.flashsale.config;

import com.example.flashsale.util.JwtUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> seckillRateLimitScript;
    private final SeckillRateLimitProperties rateLimitProperties;

    public WebConfig(
            JwtUtil jwtUtil,
            StringRedisTemplate stringRedisTemplate,
            RedisScript<Long> seckillRateLimitScript,
            SeckillRateLimitProperties rateLimitProperties) {
        this.jwtUtil = jwtUtil;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillRateLimitScript = seckillRateLimitScript;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthenticationInterceptor(jwtUtil))
            .addPathPatterns("/api/seckill/activities/*/token",
                "/api/seckill/activities/*/buy/*", "/api/orders/**");
        registry.addInterceptor(new SeckillRateLimitInterceptor(
                stringRedisTemplate, seckillRateLimitScript, rateLimitProperties))
            .addPathPatterns("/api/seckill/activities/*/buy/*");
    }
}