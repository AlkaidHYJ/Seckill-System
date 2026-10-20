-- 超时回补 Lua：INCR 库存 + DEL 限购标记，幂等由回补标记 key 兜底。
-- KEYS[1]=seckill:stock:{activityId}  KEYS[2]=seckill:user:{activityId}:{userId}  KEYS[3]=seckill:refund:{orderNo}
-- ARGV[1]=标记 TTL 秒  ARGV[2]=orderNo
-- 返回 0=本次回补成功，1=已回补过（幂等跳过，供 DB 失败重试时重放）
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 1
end

redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[2])
redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[1])
return 0
