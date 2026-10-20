package com.example.flashsale.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "seckill.rate-limit")
public class SeckillRateLimitProperties {

    private long userPerSecond = 5L;
    private long ipPerSecond = 20L;

    public long getUserPerSecond() {
        return userPerSecond;
    }

    public void setUserPerSecond(long userPerSecond) {
        this.userPerSecond = userPerSecond;
    }

    public long getIpPerSecond() {
        return ipPerSecond;
    }

    public void setIpPerSecond(long ipPerSecond) {
        this.ipPerSecond = ipPerSecond;
    }
}