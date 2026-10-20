local token = ARGV[1]
local tokenKey = KEYS[1]
local userId = ARGV[4]
local activityId = ARGV[5]
local expiresAt = redis.call('HGET', tokenKey, 'expiresAt')

if not expiresAt
        or redis.call('HGET', tokenKey, 'token') ~= token
        or redis.call('HGET', tokenKey, 'userId') ~= userId
        or redis.call('HGET', tokenKey, 'activityId') ~= activityId
        or redis.call('HGET', tokenKey, 'status') ~= 'READY'
        or tonumber(expiresAt) <= tonumber(redis.call('TIME')[1]) then
    return 3
end

local stock = redis.call('GET', KEYS[2])
if not stock or tonumber(stock) <= 0 then
    return 1
end

if redis.call('SETNX', KEYS[3], '1') == 0 then
    return 2
end

redis.call('HSET', tokenKey, 'status', 'USED')
redis.call('DECR', KEYS[2])
redis.call('SET', KEYS[4], 'CREATING', 'EX', ARGV[3])
redis.call('SET', KEYS[5], userId, 'EX', ARGV[3])
redis.call('SET', KEYS[6], activityId, 'EX', ARGV[3])
redis.call('EXPIRE', KEYS[3], ARGV[2])
return 0
