package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testSave() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    private ExecutorService es = Executors.newFixedThreadPool(300);

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);


        Runnable task = () -> {
            try {
                for (int i = 0; i < 100; i++) {
                    long id = redisIdWorker.nextId("order");
                    System.out.println("id = " + id);
                }

            } finally {
                latch.countDown();
            }
        };
        long beginTime = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();

        long endTime = System.currentTimeMillis();
        System.out.println("time = " + (endTime - beginTime));
    }

    @Test

    public void createToken() {
        // 1. 使用配置文件路径
        String filePath = "D:\\token.txt";

        // 2. 使用try-with-resources确保资源关闭
        try (PrintWriter printWriter = new PrintWriter(new FileWriter(filePath))) {
            List<User> list = userService.list();

            if (CollectionUtils.isEmpty(list)) {
                log.info("用户列表为空");
                return;
            }

            // 3. 可选：使用批量操作提高Redis性能
            // stringRedisTemplate.executePipelined(...)

            for (User user : list) {
                if (user == null) {
                    log.warn("发现null用户，跳过");
                    continue;
                }

                try {
                    // 4. 生成token
                    String token = UUID.randomUUID().toString().replace("-", ""); // 移除连字符

                    // 5. 转换为DTO
                    UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

                    // 6. 转换为Map，安全处理null值
                    Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                            CopyOptions.create()
                                    .setIgnoreNullValue(true)
                                    .setFieldValueEditor((fieldName, fieldValue) -> {
                                        if (fieldValue == null) {
                                            return "";
                                        }
                                        // 特殊类型处理
                                        if (fieldValue instanceof Date) {
                                            return String.valueOf(((Date) fieldValue).getTime());
                                        }
                                        return fieldValue.toString();
                                    }));

                    // 7. 存储到Redis
                    String tokenKey = LOGIN_USER_KEY + token;
                    stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                    stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

                    // 8. 写入文件
                    printWriter.println(token);

                } catch (Exception e) {
                    log.error("处理用户 {} 失败: {}", user.getId(), e.getMessage());
                    // 继续处理其他用户
                }
            }

            // 9. 循环结束后flush一次
            printWriter.flush();

        } catch (IOException e) {
            log.error("文件写入失败: {}", e.getMessage());
            throw new RuntimeException("生成token文件失败", e);
        }
    }

    @Test
    public void clearSet() {
        // 1. 获取 Set 操作对象
        SetOperations<String, String> setOps = stringRedisTemplate.opsForSet();
        String key = "seckill:order:23";

        // 2. 获取 Set 里所有成员
        Set<String> members = setOps.members(key);

// 3. 逐个删除所有成员（保留 key 本身）
        if (members != null && !members.isEmpty()) {
            setOps.remove(key, members.toArray());
        }
    }
}
