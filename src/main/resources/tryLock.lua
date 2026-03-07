---`
--- Created by Prosper
--- DateTime: 2026/3/5 19:45
---
local key = KEYS[1];
local threadId = ARVG[1];
local releaseTime = ARGV[2];

if(redis.call('exists',key) == 0) then
    redis.call('hset',key, threadId,'1');
    redis.call('expire',key,releaseTime);
    return 1;
end;
if(redis.call('hexists',key,threadId)) then
    redis.call('hincrby',key,threadId,'1');
    redis.call('expire',key,releaseTime);
    return 1;
end
return 0;