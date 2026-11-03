local key = KEYS[1]
local quantity = tonumber(ARGV[1])

-- 库存 Key 不存在（过期/未预热）：返回 -2 交由调用方从 DB 重建后重试，不能当 0 处理
local stock = redis.call('get', key)
if stock == false then
    return -2
end

stock = tonumber(stock)

-- 库存充足则原子扣减
if stock >= quantity then
    redis.call('decrby', key, quantity)
    return stock - quantity
end

-- 库存不足
return -1
