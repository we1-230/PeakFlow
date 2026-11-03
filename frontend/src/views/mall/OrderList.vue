<template>
  <div class="mall-main order-list-page">
    <!-- 未登录 -->
    <div v-if="!auth.isLoggedIn" class="empty-state">
      <p>请先登录后再查看订单</p>
      <el-button type="primary" class="btn-primary" @click="goLogin">去登录</el-button>
    </div>

    <template v-else>
      <div class="page-head">
        <div class="section-title">我的订单</div>
      </div>

      <!-- 状态标签页 -->
      <div class="status-tabs">
        <div
          v-for="tab in tabs"
          :key="tab.value"
          class="status-tab"
          :class="{ active: currentStatus === tab.value }"
          @click="handleStatusChange(tab.value)"
        >{{ tab.label }}</div>
      </div>

      <div class="order-area">
        <el-skeleton v-if="loading" :rows="5" animated />
        <template v-else>
          <!-- 空状态 -->
          <div v-if="orders.length === 0" class="empty-state">
            <p>暂无相关订单</p>
          </div>

          <template v-else>
            <!-- 订单卡片 -->
            <div v-for="order in orders" :key="order.id" class="order-card" @click="goDetail(order)">
              <div class="head">
                <div class="head-left">
                  <span class="order-no">订单号：{{ order.orderNo }}</span>
                  <span class="order-time">{{ order.createTime }}</span>
                </div>
                <span class="status-badge" :class="statusClass(order)">{{ order.statusDesc }}</span>
              </div>
              <div class="body">
                <div v-for="item in order.items || []" :key="item.productId" class="item-row">
                  <img v-if="item.productImage" :src="resolveImage(item.productImage, IMG_SIZE.mini)" loading="lazy" decoding="async" />
                  <div v-else class="img-placeholder">{{ (item.productName || '?').slice(0, 1) }}</div>
                  <span class="item-name">{{ item.productName }}</span>
                  <span class="item-price">¥{{ Number(item.productPrice).toFixed(2) }}</span>
                  <span class="item-qty">× {{ item.quantity }}</span>
                </div>
              </div>
              <div class="foot">
                <span class="foot-total">
                  共 {{ totalCount(order) }} 件商品，实付：<span class="price-display"><span class="symbol">¥</span><span class="amount">{{ Number(order.payAmount ?? order.totalAmount).toFixed(2) }}</span></span>
                </span>
                <div class="foot-actions" @click.stop>
                  <template v-if="canPay(order.status)">
                    <button class="btn-primary" @click="handlePay(order)">去支付</button>
                    <button class="btn-cancel" @click="handleCancel(order)">取消订单</button>
                  </template>
                  <template v-if="canRefund(order.status)">
                    <button class="btn-cancel" @click="handleRefund(order)">{{ needReturnLogistics(order.status) ? '退货退款' : '退款' }}</button>
                  </template>
                  <template v-if="canConfirm(order.status)">
                    <button class="btn-primary" @click="handleConfirm(order)">确认收货</button>
                  </template>
                </div>
              </div>
            </div>

            <!-- 分页 -->
            <div class="pagination-wrap">
              <el-pagination
                background
                layout="prev, pager, next, total"
                :total="total"
                v-model:current-page="pageNum"
                :page-size="pageSize"
                @current-change="handlePageChange"
              />
            </div>
          </template>
        </template>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { resolveImage, IMG_SIZE } from '@/utils/image'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Order, Pay, Refund } from '@/api'
import { ORDER_STATUS, canPay, canConfirm, canRefund, needReturnLogistics } from '@/constants/order'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const tabs = [
  { label: '全部', value: null },
  { label: '待付款', value: ORDER_STATUS.WAIT_PAY },
  { label: '已付款', value: ORDER_STATUS.PAID },
  { label: '已发货', value: ORDER_STATUS.SHIPPED },
  { label: '已完成', value: ORDER_STATUS.COMPLETED },
  { label: '已取消', value: ORDER_STATUS.CANCELLED },
  { label: '已关闭', value: ORDER_STATUS.CLOSED },
  { label: '已退款', value: ORDER_STATUS.REFUNDED },
]

const orders = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const currentStatus = ref(null)
const loading = ref(false)

const loadOrders = async () => {
  loading.value = true
  try {
    const data = await Order.list({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      // 后端 OrderQueryDto.status 为 Integer，null 表示全部
      status: currentStatus.value === null ? undefined : currentStatus.value,
    })
    // 后端返回 PageVo：{ list, total, pages, pageNum, pageSize }
    orders.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (auth.isLoggedIn) loadOrders()
})

const handleStatusChange = (value) => {
  if (currentStatus.value === value) return
  currentStatus.value = value
  pageNum.value = 1
  loadOrders()
}

const handlePageChange = () => {
  loadOrders()
}

const statusClass = (order) => {
  const s = Number(order.status)
  if (s === ORDER_STATUS.WAIT_PAY) return 'pay'
  if (s === ORDER_STATUS.PAID) return 'ship'
  if (s === ORDER_STATUS.SHIPPED) return 'receive'
  if (s === ORDER_STATUS.COMPLETED) return 'done'
  if (s === ORDER_STATUS.CANCELLED) return 'cancel'
  return ''
}

const totalCount = (order) => (order.items || []).reduce((sum, i) => sum + i.quantity, 0)

const goDetail = (order) => router.push(`/order/${order.id}`)

const goLogin = () => router.push({ path: '/login', query: { redirect: route.fullPath } })

// 支付：先 prepay 拿收银台地址，再跳转第三方收银台完成收款；
// 收款结果由后端接收第三方异步回调入账，所以这里不能直接提示"支付成功"
const handlePay = async (order) => {
  try {
    const vo = await Pay.prepay(order.id)
    window.location.href = vo.cashierUrl
  } catch (e) {}
}

const handleCancel = (order) => {
  Order.cancel(order.id).then(() => {
    ElMessage.success('订单已取消')
    loadOrders()
  }).catch(() => {})
}

const handleConfirm = (order) => {
  Order.confirm(order.id).then(() => {
    ElMessage.success('确认收货成功')
    loadOrders()
  }).catch(() => {})
}

// 退款：后端语义是「提交退款申请 → 待审核」，不是立即退款到账
// 已发货/已完成的订单走「退货退款」，必须填写退货物流公司与退货单号（后端缺失会报 3040）
const handleRefund = async (order) => {
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
  const payload = { orderId: order.id, reason }
  if (needReturnLogistics(order.status)) {
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
    loadOrders()
  } catch (e) {}
}
</script>

<style scoped>
.order-list-page { min-height: 60vh; }

.page-head { display: flex; align-items: center; justify-content: space-between; }

.order-area { margin-top: 16px; }

.order-card { cursor: pointer; }
.order-card:hover { box-shadow: var(--shadow-hover); }

.head-left { display: flex; align-items: center; gap: 20px; }
.order-time { color: var(--text3); font-size: 12px; }

.status-badge { font-weight: 600; }
.status-badge.pay { color: var(--primary); }
.status-badge.ship { color: var(--orange); }
.status-badge.receive { color: #ff7a1a; }
.status-badge.done { color: #67c23a; }
.status-badge.cancel { color: var(--text3); }

.body .item-row img { width: 60px; height: 60px; border-radius: 4px; object-fit: cover; flex-shrink: 0; }
.img-placeholder { width: 60px; height: 60px; border-radius: 4px; background: #f2f2f2; display: flex; align-items: center; justify-content: center; color: #bbb; font-size: 20px; flex-shrink: 0; }
.item-name { flex: 1; font-size: 14px; }
.item-price { color: var(--text2); width: 110px; text-align: right; }
.item-qty { color: var(--text3); width: 60px; text-align: right; }

.order-card .foot { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; padding: 12px 16px; border-top: 1px solid var(--border); background: #fafafa; }
.foot-total { font-size: 13px; color: var(--text2); }
.foot-total .amount { font-size: 16px; }
.foot-actions { display: flex; gap: 10px; }
.btn-cancel { border: 1px solid var(--border); color: var(--text2); background: #fff; padding: 10px 24px; border-radius: 4px; transition: all 0.15s; }
.btn-cancel:hover { color: var(--primary); border-color: var(--primary); }

.pagination-wrap { display: flex; justify-content: center; padding: 24px 0 40px; }
</style>
