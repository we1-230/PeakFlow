<template>
  <div class="mall-main checkout-page">
    <!-- 未登录 -->
    <div v-if="!auth.isLoggedIn" class="empty-state">
      <p>请先登录后再结算</p>
      <el-button type="primary" class="btn-primary" @click="goLogin">去登录</el-button>
    </div>

    <!-- 无选中商品 -->
    <div v-else-if="!loading && items.length === 0" class="empty-state">
      <p>没有选中的商品，无法结算</p>
      <el-button type="primary" class="btn-primary" @click="router.push('/cart')">去购物车选择商品</el-button>
    </div>

    <template v-else>
      <div class="section-title">确认订单</div>

      <!-- 收货地址 -->
      <div class="checkout-block">
        <div class="block-title">收货地址</div>
        <div v-if="addresses.length" class="address-list">
          <div
            v-for="addr in addresses"
            :key="addr.id"
            class="address-card"
            :class="{ active: addr.id === selectedAddressId }"
            @click="selectedAddressId = addr.id"
          >
            <el-radio v-model="selectedAddressId" :value="addr.id" class="address-radio" />
            <div class="address-info">
              <div class="address-name">
                {{ addr.receiverName }}
                <span class="address-phone">{{ addr.receiverPhone }}</span>
                <el-tag v-if="addr.isDefault" type="danger" size="small">默认</el-tag>
              </div>
              <div class="address-detail">{{ fullAddress(addr) }}</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无收货地址，请先添加" :image-size="80" />
      </div>

      <!-- 商品清单 -->
      <div class="checkout-block">
        <div class="block-title">商品清单</div>
        <div class="summary-list">
          <div v-for="item in items" :key="item.productId" class="summary-item">
            <img v-if="item.imageUrl" :src="resolveImage(item.imageUrl, IMG_SIZE.mini)" class="summary-img" loading="lazy" decoding="async" :alt="item.productName" @error="$event.target.style.display='none'" />
            <div v-else class="img-placeholder">{{ (item.productName || '?').slice(0, 1) }}</div>
            <span class="summary-name">{{ item.productName }}</span>
            <span class="summary-price"><span class="price-display"><span class="symbol">¥</span>{{ Number(item.price ?? 0).toFixed(2) }}</span></span>
            <span class="summary-qty">× {{ item.quantity }}</span>
          </div>
        </div>
      </div>

      <!-- 金额 & 提交 -->
      <div class="checkout-footer">
        <div class="footer-total">
          应付总额：<span class="price-display"><span class="symbol">¥</span><span class="amount">{{ totalAmount.toFixed(2) }}</span></span>
        </div>
        <button class="btn-primary btn-large" :disabled="submitting" @click="submitOrder">
          {{ submitting ? '提交中...' : '提交订单' }}
        </button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { resolveImage, IMG_SIZE } from '@/utils/image'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Cart, Order, Address, Product } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const cartStore = useCartStore()

const items = ref([])
const addresses = ref([])
const selectedAddressId = ref(null)
const loading = ref(false)
const submitting = ref(false)

// 是否为「立即购买」模式（商品详情页点“立即购买”跳转，携带 productId/quantity）
const isBuyNow = () => !!route.query.productId

const loadItems = async () => {
  const pid = route.query.productId
  const qty = parseInt(route.query.quantity, 10) || 1

  if (pid) {
    // 立即购买模式：只加载当前指定商品，绝不混入购物车或历史选中项
    const p = await Product.detail(pid)
    if (!p) {
      ElMessage.error('商品不存在或已下架')
      items.value = []
      return
    }
    items.value = [{
      productId: p.id,
      productName: p.name,
      imageUrl: p.image || '',
      price: Number(p.price ?? 0),
      quantity: qty,
    }]
  } else {
    // 购物车结算模式：只取购物车中「已选中」且「非秒杀」的普通商品
    // 勾选态由前端维护（后端 /cart/list 只回传 selected，没有修改勾选的接口）
    const cartItems = await Cart.list()
    cartStore.initFromList(cartItems)
    items.value = (cartItems || [])
      .filter(i => cartStore.isSelected(i.productId) && Number(i.isSeckill) !== 1)
      .map(i => ({
        productId: i.productId,
        productName: i.productName,
        imageUrl: i.imageUrl,
        price: Number(i.price ?? 0),
        quantity: i.quantity,
      }))
  }
}

const loadAddresses = async () => {
  const list = await Address.list()
  addresses.value = list || []
  const def = list.find(a => a.isDefault) || list[0]
  selectedAddressId.value = def ? def.id : null
}

onMounted(async () => {
  if (!auth.isLoggedIn) return
  loading.value = true
  try {
    await Promise.all([loadItems(), loadAddresses()])
  } finally {
    loading.value = false
  }
})

const fullAddress = (addr) => [addr.province, addr.city, addr.district, addr.detailAddress].filter(Boolean).join(' ')

const totalAmount = computed(() =>
  items.value.reduce((sum, i) => sum + Number(i.price ?? 0) * i.quantity, 0)
)

const goLogin = () => router.push({ path: '/login', query: { redirect: route.fullPath } })

const submitOrder = async () => {
  if (!selectedAddressId.value) {
    ElMessage.warning('请选择收货地址')
    return
  }
  if (items.value.length === 0) {
    ElMessage.warning('没有选中的商品')
    return
  }
  submitting.value = true
  try {
    // 显式传 addressId + items，后端据此精确创建订单，只包含当前展示的商品
    const order = await Order.create({
      addressId: selectedAddressId.value,
      items: items.value.map(i => ({ productId: i.productId, quantity: i.quantity })),
    })
    ElMessage.success('订单提交成功')
    router.push(`/order/${order.id}`)
  } catch (e) {
    // 错误已由 axios 拦截器统一提示，此处静默避免重复提示与 unhandled rejection
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.checkout-page { min-height: 60vh; }

.checkout-block { background: #fff; border-radius: var(--radius); padding: 20px; margin-bottom: 16px; box-shadow: var(--shadow); }
.block-title { font-size: 16px; font-weight: 600; margin-bottom: 16px; display: flex; align-items: center; gap: 8px; }
.block-title::before { content: ''; width: 4px; height: 16px; background: var(--primary); border-radius: 2px; }

.address-list { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.address-card { display: flex; align-items: flex-start; gap: 10px; border: 1px solid var(--border); border-radius: var(--radius); padding: 14px; cursor: pointer; transition: all 0.15s; }
.address-card:hover { border-color: var(--primary); }
.address-card.active { border-color: var(--primary); background: var(--primary-light); }
.address-radio { margin-top: 2px; }
.address-info { flex: 1; }
.address-name { font-weight: 600; display: flex; align-items: center; gap: 8px; }
.address-phone { color: var(--text2); font-weight: 400; }
.address-detail { color: var(--text2); font-size: 13px; margin-top: 6px; line-height: 1.5; }

.summary-list { display: flex; flex-direction: column; }
.summary-item { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px dashed var(--border); }
.summary-item:last-child { border-bottom: none; }
.summary-img { width: 56px; height: 56px; border-radius: 4px; object-fit: cover; flex-shrink: 0; }
.img-placeholder { width: 56px; height: 56px; border-radius: 4px; background: #f2f2f2; display: flex; align-items: center; justify-content: center; color: #bbb; font-size: 20px; flex-shrink: 0; }
.summary-name { flex: 1; font-size: 14px; color: var(--text); }
.summary-price { width: 110px; text-align: right; }
.summary-qty { width: 60px; text-align: right; color: var(--text2); }

.checkout-footer { display: flex; align-items: center; justify-content: flex-end; gap: 24px; background: #fff; border-radius: var(--radius); padding: 16px 24px; box-shadow: var(--shadow); }
.footer-total { font-size: 14px; color: var(--text); }
.footer-total .amount { font-size: 24px; }
</style>
