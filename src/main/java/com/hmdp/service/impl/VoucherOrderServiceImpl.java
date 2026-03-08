package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> OREDER_SCRIPT;

    static {
        OREDER_SCRIPT = new DefaultRedisScript<>();
        OREDER_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        OREDER_SCRIPT.setResultType(Long.class);
    }

    String messageQueue = "voucher.orders";
    private IVoucherOrderService proxy;

    private static final ExecutorService ORDER_SERVICE = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        ORDER_SERVICE.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            try {
                while (true) {
                    //获取消息队列中的订单信息 xgroup group g1 c1 count 1 block 2000 stream voucher.orders >

                    List<MapRecord<String, Object, Object>> records =
                            stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(messageQueue,ReadOffset.lastConsumed())
                            );
                    if(records == null || records.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> list = records.get(0);
                    Map<Object, Object> value = list.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(messageQueue, "g1", list.getId());
                }
            } catch (Exception e) {
                log.error("处理订单异常", e);
                handlePendingList();
            }
        }

        private void handlePendingList()  {
            try {
                while (true) {
                    //获取消息队列中的订单信息 xgroup group g1 c1 count 1 block 2000 stream voucher.orders >

                    List<MapRecord<String, Object, Object>> records =
                            stringRedisTemplate.opsForStream().read(
                                    Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1),
                                    StreamOffset.create(messageQueue,ReadOffset.from("0"))
                            );
                    if(records == null || records.isEmpty()){
                        break;
                    }
                    MapRecord<String, Object, Object> list = records.get(0);
                    Map<Object, Object> value = list.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(messageQueue, "g1", list.getId());
                }
            } catch (Exception e) {
                log.error("处理pending-list订单异常", e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }

            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("不能重复下单");
            }
            try {
                proxy.createVoucherOrder(voucherOrder);

            } finally {

                lock.unlock();
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long id = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long execute = stringRedisTemplate.execute(OREDER_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                id.toString(),
                String.valueOf(orderId));
        int r = execute.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
//    public Result seckillVoucher(Long voucherId) {
//        //1.根据id查询获得秒杀券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //2.秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀活动还未开始");
//        }
//        //3.秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀活动已经结束");
//        }
//        //4.是否库存不足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        //5.减去库存并且创建订单
//        Long id = UserHolder.getUser().getId();
//        //1.创建锁对象
//        RLock lock = redissonClient.getLock("lock:order:" + id);
//        //2.获取锁
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("一人只能下单一次");
//        }
//        //3.释放锁
//        try {
//            IVoucherOrderService proxy =  (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//
//            lock.unlock();
//        }
//
//
//    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {


        seckillVoucherService.update()
                .setSql("stock = stock - 1").setSql("update_time = NOW()")
                .eq("voucher_id", voucherOrder.getVoucherId()).update();


        save(voucherOrder);

    }

}
