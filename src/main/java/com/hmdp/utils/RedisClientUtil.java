package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class RedisClientUtil {

    private final StringRedisTemplate stringRedisTemplate;
    public RedisClientUtil(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value , Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWitnLogicExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }

    public <R,ID> R  queryWithPassThrough(String keyPrefix, Class<R> type, ID id, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String Key = keyPrefix + id;
        //1.redis查询店铺信息
        String Json = stringRedisTemplate.opsForValue().get(Key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(Json)){
            R r  = JSONUtil.toBean(Json, type);
            return r;
        }
        if(Json != null){
            return null;
        }
        R r = dbFallback.apply(id);
        if(r == null){
            stringRedisTemplate.opsForValue().set(Key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
            this.set(Key, r, time, unit);
            return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type,Function<ID,R> dbFallback ,Long time ,TimeUnit unit) throws InterruptedException {
        String Key = keyPrefix + id;
        //1.redis查询店铺信息
        String Json = stringRedisTemplate.opsForValue().get(Key);
        //2.判断是否存在
        if(StrUtil.isBlank(Json)){
            return null;
        }

        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        String key = LOCK_SHOP_KEY +  id;
        boolean isLock = tryLock(key);
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    saveR2Redis(Key,r1,time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(key);
                }
            });
        }

        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    private <R> void saveR2Redis(String key,R r , Long time, TimeUnit unit) throws InterruptedException {

        RedisData redisData = new RedisData();
        redisData.setData(r);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));


    }
}
