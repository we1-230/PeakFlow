-- 回补秒杀库存：仅当 Key 存在时执行 INCRBY
-- Key 过期/被清后凭空 INCR 会制造"幽灵库存"（凭空多出可抢名额），必须先判存在
local key = KEYS[1]
local quantity = tonumber(ARGV[1])

if redis.call('exists', key) == 1 then
    return redis.call('incrby', key, quantity)
end

return -1
