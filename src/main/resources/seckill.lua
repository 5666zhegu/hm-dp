---
--- Created by Prosper
--- DateTime: 2026/3/6 14:54
---
local voucherId = ARGV[1];
local userId = ARGV[2];
local voucherOrderId = ARGV[3];

local stockKey = 'seckill:stock:' .. voucherId;
local orderKey = 'seckill:order:' .. voucherId;

if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1;
end;
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2;
end;
redis.call('incrby',stockKey,'-1');
redis.call('sadd',orderKey, userId);
redis.call('xadd','voucher.orders','*','id',voucherOrderId,'userId',userId,'voucherId',voucherId);
return 0;