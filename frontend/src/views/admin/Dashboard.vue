<template>
  <div class="dashboard">
    <!-- ============ 顶部统计卡片 ============ -->
    <div class="stat-grid">
      <div class="stat-card" v-for="c in cards" :key="c.label">
        <div class="stat-top">
          <span class="stat-icon" :style="{ background: c.bg, color: c.color }">
            <el-icon :size="20"><component :is="c.icon" /></el-icon>
          </span>
          <span class="stat-label">{{ c.label }}</span>
        </div>
        <div class="stat-value" :style="{ color: c.color }">{{ c.value }}</div>
        <div class="stat-sub">{{ c.sub }}</div>
      </div>
    </div>

    <!-- ============ 第二行：趋势图 + 状态分布 ============ -->
    <div class="row-2">
      <!-- 近7日订单趋势 -->
      <div class="panel trend-panel">
        <div class="panel-header">
          <div class="panel-title">近 7 日订单趋势</div>
          <div class="legend">
            <span class="legend-item"><i class="dot dot-order"></i>订单数</span>
            <span class="legend-item"><i class="dot dot-gmv"></i>成交额(¥)</span>
          </div>
        </div>
        <!-- 手绘 SVG 双线趋势图 -->
        <svg v-if="trendPoints.length" class="trend-svg" viewBox="0 0 620 240" preserveAspectRatio="none">
          <!-- 网格线 -->
          <g v-for="gy in 4" :key="gy">
            <line :x1="PAD_L" :y1="PAD_T + (H - PAD_T - PAD_B) * (gy - 1) / 3"
                  :x2="620 - PAD_R" :y2="PAD_T + (H - PAD_T - PAD_B) * (gy - 1) / 3"
                  stroke="#f2ece7" stroke-width="1"/>
          </g>
          <!-- 面积填充（金额线下方） -->
          <polygon :points="areaPoints" fill="rgba(255,80,0,0.08)" stroke="none"/>
          <!-- 金额折线 -->
          <polyline :points="gmvLine" fill="none" stroke="#ff5000" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
          <!-- 订单数折线 -->
          <polyline :points="cntLine" fill="none" stroke="#ff6a1a" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
          <!-- 圆点 -->
          <g v-for="(p, i) in trendPoints" :key="'c' + i">
            <circle :cx="p.x" :cy="p.cy" r="3.5" fill="#fff" stroke="#ff6a1a" stroke-width="2"/>
          </g>
          <g v-for="(p, i) in trendPoints" :key="'g' + i">
            <circle :cx="p.x" :cy="p.gy" r="3.5" fill="#fff" stroke="#ff5000" stroke-width="2"/>
          </g>
          <!-- 数值标签 -->
          <text v-for="(p, i) in trendPoints" :key="'gc' + i"
                :x="p.x" :y="p.gy - 8" text-anchor="middle" class="trend-tag"
                fill="#ff5000">{{ p.gmv > 0 ? shortNum(p.gmv) : '' }}</text>
          <!-- 日期轴 -->
          <text v-for="(p, i) in trendPoints" :key="'d' + i"
                :x="p.x" :y="H - 6" text-anchor="middle" class="trend-date"
                fill="#a2968b">{{ p.date }}</text>
        </svg>
        <div v-else class="chart-empty">暂无趋势数据</div>
      </div>

      <!-- 订单状态分布 -->
      <div class="panel dist-panel">
        <div class="panel-header"><div class="panel-title">订单状态分布</div></div>
        <div v-if="statusDist.length" class="dist-list">
          <div v-for="item in statusDist" :key="item.status" class="dist-row">
            <span class="dist-name">{{ item.statusDesc }}</span>
            <div class="dist-bar">
              <div class="dist-fill" :style="{ width: distPercent(item.count) + '%', background: statusColor(item.status) }"></div>
            </div>
            <span class="dist-count">{{ item.count }}</span>
          </div>
        </div>
        <div v-else class="chart-empty">暂无订单</div>
      </div>
    </div>

    <!-- ============ 第三行：最近订单 ============ -->
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">最近订单</div>
        <el-button text type="primary" size="small" @click="router.push('/admin/orders')">查看全部 →</el-button>
      </div>
      <el-table :data="recentOrders" v-loading="ordersLoading" stripe>
        <el-table-column prop="orderNo" label="订单号" min-width="180" show-overflow-tooltip />
        <el-table-column prop="userId" label="用户ID" width="90" />
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">¥{{ money(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type || 'info'" size="small">
              {{ statusMap[row.status]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="订单类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.orderType === 1 ? 'danger' : 'info'" size="small" effect="plain">
              {{ row.orderType === 1 ? '秒杀' : '普通' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="下单时间" width="165" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Admin, Order } from '@/api'
import { ORDER_STATUS } from '@/constants/order'
import { User, Goods, Timer, Menu, List, Money, DataAnalysis } from '@element-plus/icons-vue'

const router = useRouter()
const ordersLoading = ref(false)
const recentOrders = ref([])
const stats = reactive({
  userTotal: 0, userActive: 0, userBanned: 0,
  productTotal: 0, seckillTotal: 0, categoryTotal: 0,
  orderTotal: 0, gmv: 0, todayOrders: 0, todayGmv: 0,
  orderStatusDist: [], trend7: [],
})

const cards = computed(() => [
  { label: '用户总数', icon: User, color: '#ff5000', bg: 'rgba(255,80,0,.12)',
    value: stats.userTotal, sub: `正常 ${stats.userActive} · 封禁 ${stats.userBanned}` },
  { label: '商品总数', icon: Goods, color: '#ff6a1a', bg: 'rgba(255,106,26,.12)',
    value: stats.productTotal, sub: '全部在售商品' },
  { label: '秒杀商品', icon: Timer, color: '#e60039', bg: 'rgba(230,0,57,.12)',
    value: stats.seckillTotal, sub: '限时抢购专场' },
  { label: '分类总数', icon: Menu, color: '#8b5cf6', bg: 'rgba(139,92,246,.12)',
    value: stats.categoryTotal, sub: '商品分类体系' },
  { label: '订单总数', icon: List, color: '#ff7a1a', bg: 'rgba(255,122,26,.12)',
    value: stats.orderTotal, sub: `今日 +${stats.todayOrders}` },
  { label: '累计成交额', icon: Money, color: '#16a34a', bg: 'rgba(22,163,74,.12)',
    value: '¥' + money(stats.gmv), sub: `今日 +¥${money(stats.todayGmv)}` },
])

const statusMap = {
  [ORDER_STATUS.WAIT_PAY]: { label: '待付款', type: 'warning' },
  [ORDER_STATUS.PAID]: { label: '已付款', type: 'primary' },
  [ORDER_STATUS.SHIPPED]: { label: '已发货', type: 'info' },
  [ORDER_STATUS.COMPLETED]: { label: '已完成', type: 'success' },
  [ORDER_STATUS.CANCELLED]: { label: '已取消', type: 'danger' },
  [ORDER_STATUS.CLOSED]: { label: '已关闭', type: 'info' },
}

const statusDist = computed(() => stats.orderStatusDist || [])
const statusColor = s => ({
  0: '#e6a23c', 1: '#ff5000', 2: '#909399', 3: '#67c23a', 4: '#f56c6c', 5: '#909399',
}[s] || '#909399')

function money(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}
function shortNum(v) {
  const n = Number(v)
  if (n >= 10000) return (n / 10000).toFixed(1) + 'w'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(Math.round(n))
}

/* ============ SVG 趋势图计算 ============ */
const W = 620, H = 240, PAD_L = 36, PAD_R = 14, PAD_T = 26, PAD_B = 30

const trendPoints = computed(() => {
  const list = stats.trend7 || []
  if (!list.length) return []
  const innerW = W - PAD_L - PAD_R
  const innerH = H - PAD_T - PAD_B
  const maxCnt = Math.max(...list.map(d => Number(d.count) || 0), 1)
  const maxGmv = Math.max(...list.map(d => Number(d.amount) || 0), 1)
  const cx = i => PAD_L + (innerW * i) / Math.max(list.length - 1, 1)
  return list.map((d, i) => ({
    date: String(d.date || '').slice(5),       // MM-DD
    x: cx(i),
    cy: PAD_T + innerH - (innerH * (Number(d.count) || 0)) / maxCnt,
    gy: PAD_T + innerH - (innerH * (Number(d.amount) || 0)) / maxGmv,
    count: Number(d.count) || 0,
    gmv: Number(d.amount) || 0,
  }))
})
const cntLine = computed(() => trendPoints.value.map(p => `${p.x},${p.cy}`).join(' '))
const gmvLine = computed(() => trendPoints.value.map(p => `${p.x},${p.gy}`).join(' '))
const areaPoints = computed(() => {
  const pts = trendPoints.value
  if (!pts.length) return ''
  return `${PAD_L},${H - PAD_B} ` + pts.map(p => `${p.x},${p.gy}`).join(' ') + ` ${W - PAD_R},${H - PAD_B}`
})

function distPercent(count) {
  const total = statusDist.value.reduce((s, d) => s + Number(d.count || 0), 0)
  if (!total) return 0
  return Math.round((Number(count) / total) * 100)
}

/* ============ 数据加载 ============ */
async function loadStats() {
  try {
    const d = await Admin.stats()
    Object.assign(stats, {
      userTotal: d.userTotal || 0,
      userActive: d.userActive || 0,
      userBanned: d.userBanned || 0,
      productTotal: d.productTotal || 0,
      seckillTotal: d.seckillTotal || 0,
      categoryTotal: d.categoryTotal || 0,
      orderTotal: d.orderTotal || 0,
      gmv: d.gmv || 0,
      todayOrders: d.todayOrders || 0,
      todayGmv: d.todayGmv || 0,
      orderStatusDist: d.orderStatusDist || [],
      trend7: d.trend7 || [],
    })
  } catch (e) {
    ElMessage.error('统计数据加载失败')
  }
}

async function loadRecentOrders() {
  ordersLoading.value = true
  try {
    const d = await Order.adminList({ pageNum: 1, pageSize: 6 })
    recentOrders.value = d.list || []
  } catch (e) {
    // 拦截器已提示
  } finally {
    ordersLoading.value = false
  }
}

defineExpose({ reload: loadStats })

onMounted(() => {
  loadStats()
  loadRecentOrders()
})
</script>

<style scoped>
.dashboard { display: flex; flex-direction: column; gap: 16px; }

/* 统计卡片 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 14px;
}
.stat-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px 18px;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.05);
  border: 1px solid #f2ece7;
  transition: transform .2s, box-shadow .2s;
}
.stat-card:hover { transform: translateY(-3px); box-shadow: 0 8px 20px rgba(15, 23, 42, 0.08); }
.stat-top { display: flex; align-items: center; gap: 8px; }
.stat-icon {
  width: 36px; height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stat-label { font-size: 13px; color: #83766c; }
.stat-value { font-size: 26px; font-weight: 800; margin: 10px 0 4px; letter-spacing: -0.5px; }
.stat-sub { font-size: 12px; color: #a89a8f; }

/* 第二行 */
.row-2 { display: grid; grid-template-columns: 2fr 1fr; gap: 14px; }
.panel {
  background: #fff;
  border-radius: 12px;
  padding: 18px 20px;
  border: 1px solid #f2ece7;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.05);
}
.panel-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.panel-title { font-size: 15px; font-weight: 700; color: #2e2620; }

/* 图例 */
.legend { display: flex; gap: 14px; font-size: 12px; color: #83766c; }
.legend-item { display: inline-flex; align-items: center; gap: 5px; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot-order { background: #ff6a1a; }
.dot-gmv { background: #ff5000; }

/* SVG 图 */
.trend-svg { width: 100%; height: 240px; display: block; }
.trend-tag { font-size: 10px; font-weight: 600; }
.trend-date { font-size: 11px; }

/* 状态分布 */
.dist-list { display: flex; flex-direction: column; gap: 14px; padding-top: 4px; }
.dist-row { display: flex; align-items: center; gap: 10px; }
.dist-name { width: 56px; font-size: 13px; color: #5c524b; flex-shrink: 0; }
.dist-bar {
  flex: 1;
  height: 10px;
  background: #f0f2f7;
  border-radius: 5px;
  overflow: hidden;
}
.dist-fill { height: 100%; border-radius: 5px; transition: width .6s ease; }
.dist-count { width: 30px; text-align: right; font-size: 13px; font-weight: 700; color: #2e2620; }

.chart-empty { text-align: center; color: #a89a8f; padding: 60px 0; font-size: 13px; }

/* 响应式 */
@media (max-width: 1400px) { .stat-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 1000px) { .row-2 { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .stat-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
