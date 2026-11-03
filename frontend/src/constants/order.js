/**
 * 订单领域后端契约常量
 *
 * 与后端保持严格一致，禁止前端自行臆造状态枚举或字段。
 * 来源：后端订单领域常量与实体（OrderStatus / Order / SeckillResultVo）
 */

/** 订单状态码（后端 OrderStatus，Integer） */
export const ORDER_STATUS = {
  WAIT_PAY: 0,    // 待付款
  PAID: 1,        // 已付款
  SHIPPED: 2,     // 已发货
  COMPLETED: 3,   // 已完成
  CANCELLED: 4,   // 已取消
  CLOSED: 5,      // 已关闭
  REFUNDED: 6,    // 已退款
}

/** 订单状态码 → 后端 desc（中文），用于展示，与 OrderStatus.getDesc() 完全一致 */
export const ORDER_STATUS_DESC = {
  0: '待付款',
  1: '已付款',
  2: '已发货',
  3: '已完成',
  4: '已取消',
  5: '已关闭',
  6: '已退款',
}

/** 订单类型（后端 Order.orderType，Integer） */
export const ORDER_TYPE = {
  NORMAL: 0,   // 普通订单
  SECKILL: 1,  // 秒杀订单
}

/** 订单类型 → 中文，用于展示 */
export const ORDER_TYPE_DESC = {
  0: '普通订单',
  1: '秒杀订单',
}

/** 秒杀结果状态（后端 SeckillResultVo，Integer） */
export const SECKILL_RESULT_STATUS = {
  WAITING: 0,   // 排队中
  SUCCESS: 1,   // 成功
  FAIL: 2,      // 失败
}

/** 后端业务规则：仅「待付款」可支付/取消，「已发货」可确认收货，「已付款/已发货/已完成」可申请退款 */
export function canPay(status) {
  return Number(status) === ORDER_STATUS.WAIT_PAY
}
export function canCancel(status) {
  return Number(status) === ORDER_STATUS.WAIT_PAY
}
export function canConfirm(status) {
  return Number(status) === ORDER_STATUS.SHIPPED
}
export function canRefund(status) {
  const s = Number(status)
  // 已付款=仅退款；已发货/已完成=退货退款（后端按订单状态推导退款类型）
  return s === ORDER_STATUS.PAID || s === ORDER_STATUS.SHIPPED || s === ORDER_STATUS.COMPLETED
}
/** 是否需要填写退货物流：已发货/已完成的订单申请退款时，后端要求退货物流公司与退货单号 */
export function needReturnLogistics(status) {
  const s = Number(status)
  return s === ORDER_STATUS.SHIPPED || s === ORDER_STATUS.COMPLETED
}

/**
 * 秒杀限购件数。
 *
 * 与后端一致：order 表 `uk_seckill_user_product` 唯一索引基于
 * `seckill_slot = concat(user_id, ':', seckill_product_id)`，
 * 同一用户同一秒杀商品只能占 1 个名额；且 /seckill/order 接口
 * 不接受数量参数（固定 1 件）。前端数量框不得放开多件。
 */
export const SECKILL_BUY_LIMIT = 1
