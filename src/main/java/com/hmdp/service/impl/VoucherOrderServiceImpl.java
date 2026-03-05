package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.根据id查询获得秒杀券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动还未开始");
        }
        //3.秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已经结束");
        }
        //4.是否库存不足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        //5.减去库存并且创建订单
        Long id = UserHolder.getUser().getId();
        synchronized (id.toString().intern()){
            IVoucherOrderService proxy =  (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long id = UserHolder.getUser().getId();
        int count = query().eq("user_id", id).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("已下一单，不能重复下单");
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1").setSql("update_time = NOW()" )
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if(!success){
            return Result.fail("库存不足");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        voucherOrder.setId(redisIdWorker.nextId("order"))
                .setUserId(id)
                .setVoucherId(voucherId);
        save(voucherOrder);
        //7.返回订单Id
        return Result.ok(voucherOrder.getId());
    }
}
