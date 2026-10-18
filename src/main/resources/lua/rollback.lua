if redis.call('GET', KEYS[3]) ~= 'CREATING' then
    return 1
end

redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[2])
redis.call('SET', KEYS[3], 'FAILED', 'EX', ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[2])
return 0