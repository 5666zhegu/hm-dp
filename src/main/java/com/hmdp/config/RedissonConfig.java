package com.hmdp.config;

import io.lettuce.core.RedisClient;
import io.reactivex.rxjava3.core.Single;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.100.128:6379").setPassword("040905");
        return Redisson.create(config);
    }

}
