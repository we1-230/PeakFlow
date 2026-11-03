<template>
  <div class="mall-main order-detail-page">
    <!-- 未登录 -->
    <div v-if="!auth.isLoggedIn" class="empty-state">
      <p>请先登录后再查看订单</p>
      <el-button type="primary" class="btn-primary" @click="goLogin">去登录</el-button>
    </div>

    <!-- 加载中 -->
    <div v-else-if="loading" class="detail-loading">
      <el-skeleton :rows="6" animated />
    </div>

    <!-- 订单不存在 -->
    <div v-else-if="!order" class="empty-state">
      <p>订单不存在或已被删除</p>
      <el-button type="primary" class="btn-primary" @click="router.push('/orders')">返回订单列表</el-button>
    </div>

    <template v-else>
      <!-- 标题 & 操作 -->
      <div class="detail-header">
        <div class="section-title">订单详情</div>
        <div class="detail-actions">
          <template v-if="canPay(order.status)">
            <button class="btn-cancel" @click="handleCancel">取消订单</button>
            <button class="btn-primary" @click="handlePay">去支付</button>
          </template>
          <template v-if="canRefund(order.status)">
            <button class="btn-cancel" @click="handleRefund">{{ needReturnLogistics(order.status) ? '退货退款' : '退款' }}</button>
          </template>
          <template v-if="canConfirm(order.status)">
            <button class="btn-primary" @click="handleConfirm">确认收货</button>
          </template>
        </div>
      </div>

      <!-- 订单状态 -->
      <div class="detail-block status-block">
        <div class="status-text">
          <span class="status-desc" :class="statusClass(order)">{{ order.statusDesc }}</span>
          <span v-if="order.timeoutRemaining" class="timeout-tip">（距自动取消还有 {{ order.timeoutRemaining }} 秒）</span>
        </div>
        <div class="status-sub">订单号：{{ order.orderNo }}</div>
      </div>

      <!-- 收货信息 -->
      <div class="detail-block">
        <div class="block-title">收货信息</div>
        <div v-if="order.address" class="address-info">
          <p class="address-line"><b>{{ order.address.receiverName }}</b>　<span>{{ order.address.receiverPhone }}</span></p>
          <p class="address-line">{{ order.address.fullAddress }}</p>
        </div>
        <p v-else class="muted">暂无收货信息</p>
      </div>

      <!-- 订单信息 -->
      <div class="detail-block">
        <div class="block-title">订单信息</div>
        <el-descriptions :column="isMobile ? 1 : 2" border>
          <el-descriptions-item label="订单编号">{{ order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="订单状态">{{ order.statusDesc }}</el-descriptions-item>
          <el-descriptions-item label="下单时间">{{ order.createTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="支付时间">{{ order.payTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发货时间">{{ order.shippingTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ order.completeTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="取消时间">{{ order.cancelTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="订单类型">{{ ORDER_TYPE_DESC[order.orderType] || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 物流信息 -->
      <div v-if="order.trackingNo" class="detail-block">
        <div class="block-title">物流信息</div>
        <p class="tracking-line">物流公司：{{ order.logisticsCompany || '-' }}</p>
        <p class="tracking-line">运单号：{{ order.trackingNo }}</p>
      </div>

      <!-- 商品清单 -->
      <div class="detail-block">
        <div class="block-title">商品清单</div>
        <div v-for="item in order.items || []" :key="item.productId" class="item-row">
          <img v-if="item.productImage" :src="resolveImage(item.productImage, IMG_SIZE.mini)" class="item-img" loading="lazy" decoding="async" />
          <div v-else class="img-placeholder">{{ (item.productName || '?').slice(0, 1) }}</div>
          <span class="item-name">{{ item.productName }}</span>
          <span class="item-price">¥{{ Number(item.productPrice).toFixed(2) }}</span>
          <span class="item-qty">× {{ item.quantity }}</span>
          <span class="item-subtotal"><span class="price-display"><span class="symbol">¥</span>{{ Number(item.subtotal).toFixed(2) }}</span></span>
        </div>
      </div>

      <!-- 金额明细 -->
      <div class="detail-block">
        <div class="block-title">金额明细</div>
        <div class="amount-rows">
          <div class="amount-row"><span>商品总额</span><span>¥{{ Number(order.totalAmount).toFixed(2) }}</span></div>
          <div class="amount-row total">
            <span>实付金额</span>
            <span class="price-display"><span class="symbol">¥</span><span class="amount">{{ Number(order.payAmount ?? order.totalAmount).toFixed(2) }}</span></span>
          </div>
        </div>
      </div>

      <!-- 备注 -->
      <div v-if="order.remark" class="detail-block">
        <div class="block-title">订单备注</div>
        <p>{{ order.remark }}</p>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { resolveImage, IMG_SIZE } from '@/utils/image'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Order, Pay, Refund } from '@/api'
import { ORDER_STATUS, ORDER_TYPE_DESC, canPay, canConfirm, canRefund, needReturnLogistics } from '@/constants/order'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const order = ref(null)
const loading = ref(false)

// 移动端适配：窄屏下订单信息表降为单列（桌面保持 2 列不变）
const isMobile = window.innerWidth <= 768

const loadOrder = async () => {
  loading.value = true
  try {
    order.value = await Order.detail(route.params.id)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (auth.isLoggedIn) loadOrder()
})

const statusClass = (order) => {
  const s = Number(order.status)
  if (s === ORDER_STATUS.WAIT_PAY) return 'pay'
  if (s === ORDER_STATUS.PAID) return 'ship'
  if (s === ORDER_STATUS.SHIPPED) return 'receive'
  if (s === ORDER_STATUS.COMPLETED) return 'done'
  if (s === ORDER_STATUS.CANCELLED) return 'cancel'
  return ''
}

const goLogin = () => router.push({ path: '/login', query: { redirect: route.fullPath } })

// 支付：先 prepay 拿收银台地址，再跳转第三方收银台完成收款；
// 收款结果由后端接收第三方异步回调入账，所以这里不能直接提示"支付成功"
const handlePay = async () => {
  try {
    const vo = await Pay.prepay(order.value.id)
    window.location.href = vo.cashierUrl
  } catch (e) {}
}

const handleCancel = () => {
  Order.cancel(order.value.id).then(() => {
    ElMessage.success('订单已取消')
    loadOrder()
  }).catch(() => {})
}

const handleConfirm = () => {
  Order.confirm(order.value.id).then(() => {
    ElMessage.success('确认收货成功')
    loadOrder()
  }).catch(() => {})
}

// 退款：后端语义是「提交退款申请 → 待审核」，不是立即退款到账
// 已发货/已完成的订单走「退货退款」，必须填写退货物流公司与退货单号（后端缺失会报 3040）
const handleRefund = async () => {
  let reason = '拍错了，不想要了'
  try {
    const { value } = await ElMessageBox.prompt('请输入退款原因', '申请退款', {
      confirmButtonText: '提交申请',
      cancelButtonText: '取消',
      inputValue: reason,
      inputPlaceholder: '例如：拍错了，不想要了',
      inputValidator: (v) => (v && v.trim() ? true : '请填写退款原因'),
    })
    reason = value
  } catch (e) {
    return // 用户取消
  }
  const payload = { orderId: order.value.id, reason }
  if (needReturnLogistics(order.value.status)) {
    try {
      const company = await ElMessageBox.prompt('请输入退货物流公司', '退货退款', {
        confirmButtonText: '下一步',
        cancelButtonText: '取消',
        inputValue: '顺丰速运',
        inputPlaceholder: '例如：顺丰速运',
        inputValidator: (v) => (v && v.trim() ? true : '请填写退货物流公司'),
      })
      payload.returnLogisticsCompany = company.value
      const tracking = await ElMessageBox.prompt('请输入退货物流单号', '退货退款', {
        confirmButtonText: '提交申请',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：SF1234567890123',
        inputValidator: (v) => (v && v.trim() ? true : '请填写退货物流单号'),
      })
      payload.returnTrackingNo = tracking.value
    } catch (e) {
      return // 用户取消
    }
  }
  try {
    await Refund.apply(payload)
    ElMessage.success('退款申请已提交，请等待审核')
    loadOrder()
  } catch (e) {}
}
</script>

<style scoped>
.order-detail-page { min-height: 60vh; }

.detail-loading { background: #fff; border-radius: var(--radius); padding: 24px; box-shadow: var(--shadow); }

.detail-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.detail-actions { display: flex; gap: 12px; }

.detail-block { background: #fff; border-radius: var(--radius); padding: 20px; margin-bottom: 16px; box-shadow: var(--shadow); }
.block-title { font-size: 16px; font-weight: 600; margin-bottom: 16px; display: flex; align-items: center; gap: 8px; }
.block-title::before { content: ''; width: 4px; height: 16px; background: var(--primary); border-radius: 2px; }

.status-block { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.status-text { display: flex; align-items: baseline; gap: 12px; }
.status-desc { font-size: 22px; font-weight: 700; }
.status-desc.pay { color: var(--primary); }
.status-desc.ship { color: var(--orange); }
.status-desc.receive { color: #ff7a1a; }
.status-desc.done { color: #67c23a; }
.status-desc.cancel { color: var(--text3); }
.timeout-tip { color: var(--text3); font-size: 13px; }
.status-sub { color: var(--text3); font-size: 13px; }

.address-line { line-height: 1.8; }
.muted { color: var(--text3); }
.tracking-line { line-height: 1.8; }

.item-row { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px dashed var(--border); }
.item-row:last-child { border-bottom: none; }
.item-img { width: 56px; height: 56px; border-radius: 4px; object-fit: cover; flex-shrink: 0; }
.img-placeholder { width: 56px; height: 56px; border-radius: 4px; background: #f2f2f2; display: flex; align-items: center; justify-content: center; color: #bbb; font-size: 20px; flex-shrink: 0; }
.item-name { flex: 1; font-size: 14px; }
.item-price { color: var(--text2); width: 110px; text-align: right; }
.item-qty { color: var(--text3); width: 60px; text-align: right; }
.item-subtotal { width: 120px; text-align: right; }

.amount-rows { max-width: 420px; margin-left: auto; }
.amount-row { display: flex; justify-content: space-between; padding: 8px 0; color: var(--text2); }
.amount-row.total { border-top: 1px dashed var(--border); margin-top: 8px; padding-top: 12px; font-size: 15px; color: var(--text); }

.btn-cancel { border: 1px solid var(--border); color: var(--text2); background: #fff; padding: 10px 24px; border-radius: 4px; transition: all 0.15s; }
.btn-cancel:hover { color: var(--primary); border-color: var(--primary); }
</style>
