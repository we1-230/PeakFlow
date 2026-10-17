<template>
  <div class="mall-main product-detail-page">
    <el-breadcrumb separator="/" class="breadcrumb">
      <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ path: '/products', query: product?.categoryId ? { categoryId: product.categoryId } : {} }">
        {{ categoryName || '全部商品' }}
      </el-breadcrumb-item>
      <el-breadcrumb-item>商品详情</el-breadcrumb-item>
    </el-breadcrumb>

    <el-skeleton v-if="loading" :rows="6" animated class="detail-skeleton" />

    <template v-else-if="product">
      <!-- Main Info -->
      <div class="detail-card">
        <!-- Left: Image -->
        <div class="detail-image">
          <div class="large-img-wrap">
            <img
              v-if="product.image"
              :src="product.image"
              :alt="product.name"
              @error="product.image = ''"
            />
            <div v-else class="img-placeholder-large">{{ product.name.slice(0, 1) }}</div>
            <span v-if="product.isSeckill" class="badge-seckill">⚡ 秒杀</span>
          </div>
        </div>

        <!-- Right: Info -->
        <div class="detail-info">
          <h1 class="product-name">{{ product.name }}</h1>

          <div class="price-row" :class="{ seckill: product.isSeckill }">
            <div class="price-display">
              <span class="symbol">¥</span>
              <span class="amount">{{ currentPrice.toFixed(2) }}</span>
            </div>
            <span v-if="hasOldPrice" class="price-old">¥{{ Number(product.price).toFixed(2) }}</span>
            <span v-if="product.isSeckill" class="seckill-tag">秒杀价</span>
          </div>

          <div class="meta-row">
            <span class="stock" :class="{ 'stock-low': availableStock > 0 && availableStock <= 10 }">
              {{ stockLabel }} {{ availableStock }} 件
              <em v-if="availableStock > 0 && availableStock <= 10">（仅剩少量，抓紧抢购）</em>
            </span>
          </div>

          <div class="stock-bar">
            <div
              class="stock-fill"
              :style="{ width: stockPercent + '%' }"
            ></div>
          </div>

          <!-- Quantity：秒杀限购 1 件（后端唯一索引 + 下单接口固定 1 件），故禁用步进但保留展示 -->
          <div class="qty-row">
            <span class="qty-label">数量</span>
            <el-input-number
              v-model="quantity"
              :min="1"
              :max="maxQuantity"
              :disabled="isSeckillProduct"
              size="large"
            />
            <span v-if="isSeckillProduct" class="qty-tip">秒杀限购 {{ SECKILL_BUY_LIMIT }} 件</span>
          </div>

          <!-- Actions -->
          <div class="action-row">
            <template v-if="product.isSeckill">
              <el-button class="btn-seckill btn-large" size="large" @click="handleSeckill">
                ⚡ 立即抢购
              </el-button>
              <span class="seckill-only-tip">秒杀商品仅支持秒杀通道购买，不可加入购物车</span>
            </template>
            <template v-else>
              <el-button
                class="btn-primary btn-large btn-cart-outline"
                size="large"
                plain
                @click="handleAddCart"
              >
                🛒 加入购物车
              </el-button>
            </template>
          </div>

          <div class="service-row">
            <span>✓ 正品保障</span>
            <span>✓ 七天无理由退换</span>
            <span>✓ 极速发货</span>
          </div>
        </div>
      </div>

      <!-- Detail Section -->
      <div class="detail-section">
        <el-tabs>
          <el-tab-pane label="商品详情">
            <div class="detail-content">
              <p v-if="product.description" class="detail-desc">{{ product.description }}</p>
              <div
                v-if="product.detail"
                class="detail-html"
                v-html="product.detail"
              ></div>
              <el-empty
                v-if="!product.description && !product.detail"
                description="暂无更多详情"
                :image-size="100"
              />
            </div>
          </el-tab-pane>
          <el-tab-pane label="商品参数">
            <div class="params-list">
              <div class="param-item"><span>商品编号</span><b>{{ product.id }}</b></div>
              <div class="param-item"><span>商品名称</span><b>{{ product.name }}</b></div>
              <div class="param-item"><span>所属分类</span><b>{{ categoryName || '-' }}</b></div>
              <div class="param-item"><span>{{ stockLabel }}</span><b>{{ availableStock }} 件</b></div>
              <div class="param-item"><span>原价</span><b>¥{{ Number(product.price).toFixed(2) }}</b></div>
              <div class="param-item" v-if="product.isSeckill">
                <span>秒杀价</span><b class="seckill-price">¥{{ Number(product.seckillPrice).toFixed(2) }}</b>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>

    <el-empty v-else description="商品不存在或已下架" class="empty-state">
      <el-button class="btn-primary" @click="$router.push('/')">返回首页</el-button>
    </el-empty>

    <!-- Seckill Address Select Dialog -->
    <el-dialog v-model="addressDialogVisible" title="选择收货地址" width="560px">
      <div v-loading="addressLoading" class="addr-select-list">
        <div
          v-for="addr in addressList"
          :key="addr.id"
          class="addr-select-item"
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
        <div v-if="addressList.length === 0 && !addressLoading" class="addr-empty">
          <p>暂无收货地址，请先添加地址</p>
          <el-button type="primary" @click="router.push('/address')">去添加</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="addressDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!selectedAddressId"
          :loading="ordering"
          @click="confirmSeckillOrder"
        >
          确认抢购
        </el-button>
      </template>
    </el-dialog>

    <!-- Seckill Polling Dialog -->
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
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { Product, Cart, Category, Seckill, Address } from '@/api'
import { SECKILL_RESULT_STATUS, SECKILL_BUY_LIMIT } from '@/constants/order'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
const auth = useAuthStore()
const cartStore = useCartStore()

const route = useRoute()
const router = useRouter()

const product = ref(null)
const categories = ref([])
const loading = ref(false)
const quantity = ref(1)

const productId = computed(() => Number(route.params.id))
const categoryName = computed(() =>
  categories.value.find(c => c.id === product.value?.categoryId)?.name || ''
)

const currentPrice = computed(() => {
  const p = product.value
  if (!p) return 0
  // 秒杀商品显示秒杀价，普通商品显示原价（后端 Product 仅 price / seckillPrice 两个价格字段）
  if (p.isSeckill && p.seckillPrice != null) return Number(p.seckillPrice)
  return Number(p.price)
})

const hasOldPrice = computed(() => {
  const p = product.value
  return p && p.price != null && Number(p.price) > currentPrice.value
})

// ---------------------------------------------------------------------------
// 库存口径（2026-10-17 修复「秒杀详情一进来就显示已售完」）
//
// 后端 Product 有两个互不相干的库存字段：
//   · stock        —— 普通库存。秒杀商品该字段为 0（秒杀库存单独放在 seckillStock）
//   · seckillStock —— 秒杀库存。仅秒杀活动商品有值
// 之前模板/校验全读 stock ⇒ 秒杀商品（stock=0、seckillStock=50）进页面即弹
// 「该商品已售罄」、库存显示 0，而点抢购却成功（handleSeckill 读的是 seckillStock）。
// 现在统一按商品类型取正确口径，展示与校验同源，不再自相矛盾。
// ---------------------------------------------------------------------------
const isSeckillProduct = computed(() => Number(product.value?.isSeckill) === 1)

// 该商品「真实可售库存」：秒杀走秒杀库存，普通走普通库存
const availableStock = computed(() => {
  const p = product.value
  if (!p) return 0
  const raw = isSeckillProduct.value ? p.seckillStock : p.stock
  return Number(raw ?? 0)
})

// 库存文案前缀，避免把秒杀库存标成普通库存
const stockLabel = computed(() => (isSeckillProduct.value ? '秒杀库存' : '库存'))

const maxQuantity = computed(() => {
  const p = product.value
  if (!p) return 1
  // 秒杀限购 1 件：后端 order 表 uk_seckill_user_product 唯一索引按 (userId, productId)
  // 限购，且下单接口不接受数量参数（固定 1 件），前端不能放开多件
  if (isSeckillProduct.value) return SECKILL_BUY_LIMIT
  return Math.max(1, Number(p.stock ?? 1))
})

const stockPercent = computed(() => {
  const p = product.value
  if (!p) return 0
  // 秒杀商品用秒杀库存做基数，否则 stock=0 时进度条恒为 0（与实际可售量矛盾）
  const remain = isSeckillProduct.value
    ? Number(p.seckillStock ?? 0)
    : Number(p.stock ?? 0)
  const sold = Number(p.soldCount ?? 0)
  const totalStock = remain + sold
  if (!totalStock) return 0
  return Math.min(100, Math.round((sold / totalStock) * 100))
})

async function loadProduct() {
  if (!productId.value) return
  loading.value = true
  quantity.value = 1
  try {
    const res = await Product.detail(productId.value)
    product.value = res
    // 售罄提示必须按「该商品的真实可售库存」判断：
    // 秒杀商品读 seckillStock，普通商品读 stock（详见 availableStock 注释）
    if (res && availableStock.value <= 0) {
      ElMessage.warning(isSeckillProduct.value ? '该秒杀商品已抢完' : '该商品已售罄')
    }
  } catch (e) {
    console.error(e)
    product.value = null
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  try {
    const res = await Category.list()
    categories.value = res || []
  } catch (e) {
    console.error(e)
  }
}

function checkLogin() {
  if (!auth.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push({
      path: '/login',
      query: { redirect: route.fullPath },
    })
    return false
  }
  return true
}

async function handleAddCart() {
  if (!checkLogin()) return
  const p = product.value
  if (!p || (p.stock ?? 1) <= 0) {
    ElMessage.warning('商品已售罄，无法加入购物车')
    return
  }
  try {
    await Cart.add(p.id, quantity.value)
    cartStore.refresh()   // 同步顶栏购物车角标
    ElMessage.success(`已加入购物车 ${quantity.value} 件`)
  } catch (e) {
    console.error(e)
    ElMessage.error('加入购物车失败，请重试')
  }
}

// ---------- Seckill Buy（详情页内直接抢购，不跳秒杀列表页） ----------
const addressDialogVisible = ref(false)
const addressLoading = ref(false)
const addressList = ref([])
const selectedAddressId = ref(null)

async function loadAddresses() {
  addressLoading.value = true
  try {
    addressList.value = await Address.list()
    const def = addressList.value.find(a => a.isDefault)
    selectedAddressId.value = def ? def.id : (addressList.value[0]?.id || null)
  } catch (e) {
    ElMessage.error('获取地址列表失败')
  } finally {
    addressLoading.value = false
  }
}

async function handleSeckill() {
  if (!checkLogin()) return
  const p = product.value
  if (!p) return
  // 与页面展示同源：读 availableStock（秒杀口径），避免「显示有货却提示抢完」或反之
  if (availableStock.value <= 0) {
    ElMessage.warning('秒杀商品已抢完')
    return
  }
  await loadAddresses()
  addressDialogVisible.value = true
}

const ordering = ref(false)
const pollingDialogVisible = ref(false)
const pollingText = ref('正在提交抢购请求...')
let pollTimer = null

async function confirmSeckillOrder() {
  if (!product.value || !selectedAddressId.value) return
  ordering.value = true
  try {
    const requestId = await Seckill.order(product.value.id, selectedAddressId.value)
    addressDialogVisible.value = false
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
        stopPolling()
        pollingDialogVisible.value = false
        ElMessage.success('抢购成功！')
        router.push(`/order/${result.orderId}`)
      } else if (result.status === SECKILL_RESULT_STATUS.FAIL) {
        stopPolling()
        pollingDialogVisible.value = false
        ElMessageBox.alert(result.failReason || '抢购失败，商品已售罄', '抢购失败', {
          confirmButtonText: '知道了',
          type: 'error'
        })
      } else if (elapsed >= 30) {
        stopPolling()
        pollingDialogVisible.value = false
        ElMessage.warning('排队超时，请稍后查看订单状态')
      } else {
        pollingText.value = `排队中... (${elapsed}s)`
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

watch(
  () => route.params.id,
  (val) => {
    if (val) loadProduct()
  }
)

onMounted(() => {
  loadCategories()
  loadProduct()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.product-detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.breadcrumb {
  background: #fff;
  border-radius: 10px;
  padding: 12px 16px;
}

.detail-skeleton {
  background: #fff;
  border-radius: 10px;
  padding: 24px;
}

/* Main Card */
.detail-card {
  display: flex;
  gap: 28px;
  background: #fff;
  border-radius: 10px;
  padding: 24px;
}

.detail-image {
  flex: 0 0 420px;
  width: 420px;
}

.large-img-wrap {
  position: relative;
  width: 420px;
  height: 420px;
  border-radius: 10px;
  overflow: hidden;
  background: #f7f7f7;
}

.large-img-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.img-placeholder-large {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 120px;
  font-weight: 700;
  color: #ff5000;
  background: linear-gradient(135deg, #fff2e8, #ffdfc4);
}

.badge-seckill {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 2;
}

/* Info */
.detail-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.product-name {
  font-size: 22px;
  font-weight: 600;
  color: #222;
  line-height: 1.5;
  margin: 0 0 16px;
}

.price-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  background: linear-gradient(90deg, #fff2e8, #fff9f5);
  border-radius: 8px;
  padding: 16px 20px;
  margin-bottom: 14px;
}

.price-row .price-display .amount {
  font-size: 36px;
}

.price-row .price-old {
  font-size: 15px;
}

.seckill-tag {
  background: linear-gradient(90deg, #C81E1E, #E1251B);
  color: #fff;
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 20px;
}

.meta-row {
  display: flex;
  gap: 24px;
  font-size: 13px;
  color: #888;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.stock-low {
  color: #E1251B;
}

.stock-low em {
  font-style: normal;
}

.stock-bar {
  max-width: 360px;
  margin-bottom: 22px;
}

/* Quantity */
.qty-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 26px;
}

.qty-label {
  font-size: 14px;
  color: #666;
}

/* 秒杀限购提示（仅秒杀商品显示，普通商品无此节点，桌面布局不受影响） */
.qty-tip {
  font-size: 12px;
  color: #ff5000;
  background: #fff2e8;
  border: 1px solid #ffd6bd;
  border-radius: 10px;
  padding: 2px 8px;
}

.qty-max {
  font-size: 13px;
  color: #999;
}

/* Actions */
.action-row {
  display: flex;
  gap: 14px;
  margin-bottom: 22px;
}

.seckill-only-tip {
  align-self: center;
  font-size: 12px;
  color: #999;
}

.btn-cart-outline {
  background: #fff;
  color: #ff5000;
  border-color: #ff5000;
}

.btn-cart-outline:hover {
  background: #fff2e8;
  color: #e64600;
  border-color: #e64600;
}

.btn-cart {
  color: #ff5000;
  border-color: #ff5000;
}

.btn-cart:hover {
  background: #fff2e8;
}

.service-row {
  display: flex;
  gap: 20px;
  font-size: 13px;
  color: #999;
  border-top: 1px dashed #eee;
  padding-top: 16px;
}

/* Detail Section */
.detail-section {
  background: #fff;
  border-radius: 10px;
  padding: 8px 24px 24px;
}

.detail-content {
  padding: 12px 0;
}

.detail-desc {
  font-size: 15px;
  color: #444;
  line-height: 1.8;
  margin: 0 0 16px;
}

.detail-html {
  font-size: 14px;
  color: #555;
  line-height: 1.8;
}

.detail-html :deep(img) {
  max-width: 100%;
  border-radius: 6px;
}

/* Params */
.params-list {
  padding: 12px 0;
}

.param-item {
  display: flex;
  gap: 24px;
  padding: 10px 0;
  border-bottom: 1px solid #f5f5f5;
  font-size: 14px;
}

.param-item span {
  color: #999;
  flex: 0 0 100px;
}

.param-item b {
  color: #333;
  font-weight: 500;
}

.seckill-price {
  color: #E1251B;
}

/* Seckill Address Select */
.addr-select-list {
  max-height: 360px;
  overflow-y: auto;
}

.addr-select-item {
  border: 2px solid #f0f0f0;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.addr-select-item:hover {
  border-color: #ff8a3d;
}

.addr-select-item.active {
  border-color: #ff5000;
  background: #f7efe6;
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

.addr-empty {
  text-align: center;
  padding: 30px 0;
  color: #999;
}

/* Seckill Polling */
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
</style>
