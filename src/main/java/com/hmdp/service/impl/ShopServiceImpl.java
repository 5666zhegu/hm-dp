package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisClientUtil;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisClientUtil redisClientUtil;
    @Override
    public Result queryById(Long id) throws InterruptedException {

        //返回空置，防止缓存穿透
        //Shop shop = queryWithPassThrough(id);
//        Shop shop = redisClientUtil.queryWithPassThrough(CACHE_SHOP_KEY, Shop.class, id, this::getById, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        //实现互斥锁，防止缓存击穿
//        Shop shop = queryWithMutex(id);
//        if(shop == null){
//            return Result.fail("店铺不存在");
//        }
//        return Result.ok(shop);
        //实现逻辑过期
//        Shop shop = queryWithLogicExpire(id);
        Shop shop = redisClientUtil.queryWithLogicExpire(CACHE_SHOP_KEY, id,Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);

    }

//    private static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);
//    public Shop queryWithLogicExpire(Long id) throws InterruptedException {
//        String Key = CACHE_SHOP_KEY + id;
//        //1.redis查询店铺信息
//        String shopJson = stringRedisTemplate.opsForValue().get(Key);
//        //2.判断是否存在
//        if(StrUtil.isBlank(shopJson)){
//            return null;
//        }
//
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        if(expireTime.isAfter(LocalDateTime.now())){
//            return shop;
//        }
//        String key = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(key);
//        if(isLock){
//            CACHE_REBUILD_EXCUTOR.submit(() -> {
//                try {
//                    saveShop2Redis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    unLock(key);
//                }
//            });
//        }
//
//        return shop;
//    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);

        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));


}




    public Shop queryWithMutex(Long id){
        String Key = CACHE_SHOP_KEY + id;
        //1.redis查询店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(Key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson != null){
            return null;
        }
        String key = LOCK_SHOP_KEY + id;

        Shop shop = null;
        try {
            boolean isLock = tryLock(key);
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = getById(id);
            Thread.sleep(200);
            if(shop == null){
                stringRedisTemplate.opsForValue().set(Key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(Key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(key);
        }

        return shop;
    }

//    public Shop queryWithPassThrough(Long id){
//        String Key = CACHE_SHOP_KEY + id;
//        //1.redis查询店铺信息
//        String shopJson = stringRedisTemplate.opsForValue().get(Key);
//        //2.判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        if(shopJson != null){
//            return null;
//        }
//        Shop shop = getById(id);
//        if(shop == null){
//            stringRedisTemplate.opsForValue().set(Key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        stringRedisTemplate.opsForValue().set(Key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
