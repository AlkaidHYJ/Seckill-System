local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local userLimit = tonumber(ARGV[3])
local ipLimit = tonumber(ARGV[4])
local member = ARGV[5]

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, now - window)

if redis.call('ZCARD', KEYS[1]) >= userLimit then
    return 1
end
if redis.call('ZCARD', KEYS[2]) >= ipLimit then
    return 2
end

redis.call('ZADD', KEYS[1], now, member .. ':user')
redis.call('ZADD', KEYS[2], now, member .. ':ip')
redis.call('EXPIRE', KEYS[1], 2)
redis.call('EXPIRE', KEYS[2], 2)
return 0