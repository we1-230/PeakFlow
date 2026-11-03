<template>
  <div class="seckill-page">
    <!-- Banner -->
    <div class="seckill-banner">
      <div class="banner-content">
        <h1 class="banner-title">⚡ 限时秒杀</h1>
        <p class="banner-desc">精选好物 · 超低折扣 · 手慢无</p>
      </div>
    </div>

    <!-- Section Title -->
    <div class="section-bar">
      <h2 class="section-title">🔥 正在抢购</h2>
      <span class="section-tip">手慢无，赶紧下单！</span>
    </div>

    <!-- Product Grid -->
    <div v-loading="loading" class="product-grid">
      <div
        v-for="item in productList"
        :key="item.id"
        class="product-card"
      >
        <div class="card-image" @click="goDetail(item)">
          <img :src="resolveImage(item.image, IMG_SIZE.thumb)" :alt="item.name" loading="lazy" decoding="async" />
          <span v-if="item.soldCount > 100" class="hot-tag">HOT</span>
        </div>
        <div class="card-body">
          <h3 class="product-name" @click="goDetail(item)">{{ item.name }}</h3>
          <p class="product-desc">{{ item.description }}</p>
          <div class="price-display">
            <span class="seckill-price">¥{{ item.seckillPrice }}</span>
            <span class="original-price">¥{{ item.price }}</span>
          </div>
          <!-- Stock Progress -->
          <div class="stock-progress">
            <div class="progress-info">
              <span>剩余 {{ item.seckillStock }} 件</span>
            </div>
            <div class="progress-bar">
              <div
                class="progress-fill"
                :style="{ width: getProgressPercent(item) + '%' }"
              ></div>
            </div>
          </div>
          <button
            class="btn-seckill btn-large"
            :disabled="item.seckillStock <= 0"
            @click="handleSeckill(item)"
          >
            {{ item.seckillStock > 0 ? '⚡ 立即抢购' : '已抢光' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Empty State -->
    <div v-if="!loading && productList.length === 0" class="empty-state">
      <p>暂无秒杀商品，敬请期待~</p>
    </div>

    <!-- Pagination -->
    <div v-if="total > 0" class="pagination-wrap">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, prev, pager, next, sizes"
        @current-change="fetchList"
        @size-change="fetchList"
      />
    </div>

    <!-- Address Select Dialog -->
    <el-dialog
      v-model="addressDialogVisible"
      title="选择收货地址"
      width="560px"
    >
      <div v-loading="addressLoading" class="address-select-list">
        <div
          v-for="addr in addressList"
          :key="addr.id"
          class="address-select-item"
          :class="{ active: selectedAddressId === addr.id }"
          @click="selectedAddressId = addr.id"
        >
          <div class="addr-info">
            <span class="addr-name">{{ addr.receiverName }}</span>
            <span class="addr-phone">{{ addr.receiverPhone }}</span>
            <el-tag v-if="addr.isDefault" size="small" type="warning">默认</el-tag>
          </div>
          <div class="addr-detail">
            {{ addr.province }}{{ addr.city }}{{ addr.district }}{{ addr.detailAddress }}
          </div>
        </div>
        <div v-if="addressList.length === 0 && !addressLoading" class="empty-state">
          <p>暂无收货地址，请先添加地址</p>
          <el-button type="primary" @click="goAddAddress">去添加</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="addressDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!selectedAddressId"
          :loading="ordering"
          @click="confirmOrder"
        >
          确认抢购
        </el-button>
      </template>
    </el-dialog>

    <!-- Polling Loading Dialog -->
    <el-dialog
      v-model="pollingDialogVisible"
      :show-close="false"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      width="360px"
    >
      <div class="polling-content">
        <el-icon class="is-loading polling-icon"><Loading /></el-icon>
        <p class="polling-text">{{ pollingText }}</p>
        <p class="polling-subtext">正在排队抢购，请稍候...</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { resolveImage, IMG_SIZE } from '@/utils/image'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { Seckill } from '@/api'
import { Address } from '@/api'
import { SECKILL_RESULT_STATUS } from '@/constants/order'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

// ---------- Product List ----------
const loading = ref(false)
const productList = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

async function fetchList() {
  loading.value = true
  try {
    const res = await Seckill.list({ pageNum: pageNum.value, pageSize: pageSize.value })
    productList.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error('获取秒杀列表失败')
  } finally {
    loading.value = false
  }
}

function getProgressPercent(item) {
  const totalStock = item.soldCount + item.seckillStock
  if (totalStock === 0) return 0
  return Math.min(100, (item.soldCount / totalStock) * 100)
}

// ---------- Address Selection ----------
const addressDialogVisible = ref(false)
const addressLoading = ref(false)
const addressList = ref([])
const selectedAddressId = ref(null)
const currentProduct = ref(null)

async function loadAddresses() {
  addressLoading.value = true
  try {
    addressList.value = await Address.list()
    // auto-select default
    const def = addressList.value.find(a => a.isDefault)
    selectedAddressId.value = def ? def.id : (addressList.value[0]?.id || null)
  } catch (e) {
    ElMessage.error('获取地址列表失败')
  } finally {
    addressLoading.value = false
  }
}

async function handleSeckill(product) {
  // login check
  if (!auth.isLoggedIn) {
    await ElMessageBox.confirm('请先登录后再参与秒杀', '提示', {
      confirmButtonText: '去登录',
      cancelButtonText: '取消',
      type: 'warning'
    })
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    return
  }

  currentProduct.value = product
  selectedAddressId.value = null
  await loadAddresses()
  addressDialogVisible.value = true
}

function goAddAddress() {
  router.push('/address')
}

// 跳转商品详情（秒杀列表返回的 item 就是 Product，id 即商品 id）
function goDetail(item) {
  router.push(`/product/${item.id}`)
}

// ---------- Order + Polling ----------
const ordering = ref(false)
const pollingDialogVisible = ref(false)
const pollingText = ref('正在提交抢购请求...')
let pollTimer = null

async function confirmOrder() {
  if (!currentProduct.value || !selectedAddressId.value) return
  ordering.value = true
  try {
    const requestId = await Seckill.order(currentProduct.value.id, selectedAddressId.value)
    addressDialogVisible.value = false
    // start polling
    pollingText.value = '正在提交抢购请求...'
    pollingDialogVisible.value = true
    startPolling(requestId)
  } catch (e) {
    // axios 拦截器已显示错误消息，这里只做 UI 清理
    addressDialogVisible.value = false
  } finally {
    ordering.value = false
  }
}

function startPolling(requestId) {
  let elapsed = 0
  pollingText.value = '排队中，请稍候...'
  pollTimer = setInterval(async () => {
    elapsed += 1
    try {
      const result = await Seckill.result(requestId)
      if (result.status === SECKILL_RESULT_STATUS.SUCCESS) {
        // success
        stopPolling()
        pollingDialogVisible.value = false
        ElMessage.success('抢购成功！')
        router.push(`/order/${result.orderId}`)
      } else if (result.status === SECKILL_RESULT_STATUS.FAIL) {
        // failed
        stopPolling()
        pollingDialogVisible.value = false
        ElMessageBox.alert(result.failReason || '抢购失败，商品已售罄', '抢购失败', {
          confirmButtonText: '知道了',
          type: 'error'
        })
      } else {
        // still queuing
        if (elapsed >= 30) {
          stopPolling()
          pollingDialogVisible.value = false
          ElMessage.warning('排队超时，请稍后查看订单状态')
        } else {
          pollingText.value = `排队中... (${elapsed}s)`
        }
      }
    } catch (e) {
      stopPolling()
      pollingDialogVisible.value = false
      ElMessage.error('查询结果失败')
    }
  }, 1000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ---------- Lifecycle ----------
onMounted(() => {
  fetchList()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.seckill-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
}

/* Banner */
.seckill-banner {
  background: linear-gradient(135deg, #C81E1E 0%, #E1251B 50%, #F04E3E 100%);
  border-radius: 16px;
  padding: 48px 40px;
  margin-bottom: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}

.seckill-banner::before {
  content: '';
  position: absolute;
  top: -50%;
  right: -10%;
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, rgba(255,255,255,0.15) 0%, transparent 70%);
  border-radius: 50%;
}

.banner-content {
  text-align: center;
  position: relative;
  z-index: 1;
}

.banner-title {
  font-size: 42px;
  font-weight: 700;
  color: #fff;
  margin: 0 0 8px;
  text-shadow: 0 2px 8px rgba(0,0,0,0.2);
}

.banner-desc {
  font-size: 16px;
  color: rgba(255,255,255,0.9);
  margin: 0 0 20px;
}

/* Section Bar */
.section-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.section-title {
  font-size: 24px;
  font-weight: 700;
  color: #333;
  margin: 0;
}

.section-tip {
  font-size: 14px;
  color: #999;
}

/* Product Grid */
.product-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
}

.product-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  transition: transform 0.2s, box-shadow 0.2s;
  border: 1px solid #f0f0f0;
}

.product-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
}

.card-image {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  overflow: hidden;
  cursor: pointer;
}

.card-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.hot-tag {
  position: absolute;
  top: 8px;
  right: 8px;
  background: #E1251B;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 4px;
}

.card-body {
  padding: 12px;
}

.product-name {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin: 0 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.product-name:hover {
  color: #E1251B;
}

.product-desc {
  font-size: 12px;
  color: #999;
  margin: 0 0 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price-display {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;
}

.seckill-price {
  font-size: 22px;
  font-weight: 700;
  color: #E1251B;
}

.original-price {
  font-size: 13px;
  color: #bbb;
  text-decoration: line-through;
}

/* Stock Progress */
.stock-progress {
  margin-bottom: 12px;
}

.progress-info {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #999;
  margin-bottom: 4px;
}

.progress-bar {
  height: 16px;
  background: #f5f5f5;
  border-radius: 8px;
  overflow: hidden;
  position: relative;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #C81E1E, #E1251B);
  border-radius: 8px;
  transition: width 0.3s;
}

/* Buttons */
.btn-primary,
.btn-large,
.btn-seckill {
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
  transition: opacity 0.2s, transform 0.1s;
}

.btn-primary {
  background: #E1251B;
  color: #fff;
  padding: 8px 20px;
  font-size: 14px;
}

.btn-large {
  width: 100%;
  padding: 10px 0;
  font-size: 15px;
}

.btn-seckill {
  background: linear-gradient(135deg, #C81E1E, #E1251B);
  color: #fff;
}

.btn-seckill:hover:not(:disabled) {
  opacity: 0.9;
}

.btn-seckill:active:not(:disabled) {
  transform: scale(0.98);
}

.btn-seckill:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* Empty State */
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #999;
  font-size: 16px;
}

/* Pagination */
.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}

/* Address Select */
.address-select-list {
  max-height: 360px;
  overflow-y: auto;
}

.address-select-item {
  border: 2px solid #f0f0f0;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.address-select-item:hover {
  border-color: #F04E3E;
}

.address-select-item.active {
  border-color: #E1251B;
  background: #FDEDEC;
}

.addr-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.addr-name {
  font-weight: 600;
  font-size: 15px;
  color: #333;
}

.addr-phone {
  font-size: 13px;
  color: #666;
}

.addr-detail {
  font-size: 13px;
  color: #666;
}

/* Polling Dialog */
.polling-content {
  text-align: center;
  padding: 20px 0;
}

.polling-icon {
  font-size: 48px;
  color: #E1251B;
}

.polling-text {
  font-size: 18px;
  font-weight: 600;
  color: #333;
  margin: 16px 0 8px;
}

.polling-subtext {
  font-size: 14px;
  color: #999;
  margin: 0;
}

/* Responsive */
@media (max-width: 1200px) {
  .product-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}

@media (max-width: 900px) {
  .product-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 640px) {
  .product-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .banner-title {
    font-size: 28px;
  }
}
</style>
