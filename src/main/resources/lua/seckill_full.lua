local stock = redis.call('GET', KEYS[1])
if not stock or tonumber(stock) <= 0 then
    return 1
end

if redis.call('SETNX', KEYS[2], '1') == 0 then
    return 2
end

redis.call('DECR', KEYS[1])
redis.call('SET', KEYS[3], 'CREATING', 'EX', ARGV[2])
redis.call('SET', KEYS[4], ARGV[3], 'EX', ARGV[2])
redis.call('SET', KEYS[5], ARGV[4], 'EX', ARGV[2])
redis.call('EXPIRE', KEYS[2], ARGV[1])
return 0
