<template>
  <div class="page-card">
    <!-- 工具栏 -->
    <div class="toolbar">
      <el-input
        v-model="searchForm.orderNo"
        placeholder="按订单号搜索"
        clearable
        style="width: 240px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 150px">
        <el-option v-for="(item, key) in statusMap" :key="key" :label="item.label" :value="key" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <!-- 订单表格 -->
    <el-table :data="list" v-loading="loading" stripe>
      <el-table-column prop="orderNo" label="订单号" min-width="180" show-overflow-tooltip />
      <el-table-column prop="userId" label="用户ID" width="90" />
      <el-table-column label="金额" width="110" align="right">
        <template #default="{ row }"><span class="price-text">¥{{ money(row.totalAmount) }}</span></template>
      </el-table-column>
      <el-table-column label="类型" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.orderType === 1 ? 'danger' : 'info'" size="small" effect="plain">
            {{ row.orderType === 1 ? '秒杀' : '普通' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusMap[row.status]?.type || 'info'" size="small">
            {{ statusMap[row.status]?.label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="trackingNo" label="物流单号" width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.trackingNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="下单时间" width="165" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openDetail(row)">详情</el-button>
          <el-button type="warning" link size="small" @click="openEdit(row)">发货/编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="loadData"
        @size-change="handleSizeChange"
      />
    </div>

    <!-- 订单详情弹窗 -->
    <el-dialog v-model="detailVisible" title="订单详情" width="640" :close-on-click-modal="false">
      <template v-if="detail">
        <div class="detail-section">
          <div class="detail-title">订单信息</div>
          <div class="detail-grid">
            <div class="detail-item"><span class="k">订单号</span><span class="v">{{ detail.orderNo }}</span></div>
            <div class="detail-item"><span class="k">订单状态</span>
              <span class="v"><el-tag :type="statusMap[detail.status]?.type || 'info'" size="small">{{ statusMap[detail.status]?.label || detail.status }}</el-tag></span>
            </div>
            <div class="detail-item"><span class="k">实付金额</span><span class="v price-text">¥{{ money(detail.payAmount) }}</span></div>
            <div class="detail-item"><span class="k">下单时间</span><span class="v">{{ fmt(detail.createTime) }}</span></div>
            <div class="detail-item"><span class="k">支付时间</span><span class="v">{{ fmt(detail.payTime) }}</span></div>
            <div class="detail-item"><span class="k">订单类型</span><span class="v">{{ detail.orderType === 1 ? '秒杀订单' : '普通订单' }}</span></div>
          </div>
        </div>
        <div class="detail-section">
          <div class="detail-title">收货信息</div>
          <div class="detail-grid" v-if="detail.address">
            <div class="detail-item"><span class="k">收货人</span><span class="v">{{ detail.address.receiverName }}（{{ detail.address.receiverPhone }}）</span></div>
            <div class="detail-item"><span class="k">收货地址</span><span class="v">{{ detail.address.fullAddress }}</span></div>
          </div>
          <div v-else class="v">-</div>
        </div>
        <div class="detail-section">
          <div class="detail-title">商品明细</div>
          <el-table :data="detail.items || []" size="small" stripe>
            <el-table-column label="商品" min-width="220">
              <template #default="{ row: it }">
                <div class="item-cell">
                  <el-image v-if="it.productImage" :src="it.productImage" fit="cover" class="item-img" :preview-src-list="[it.productImage]" preview-teleported />
                  <span v-else class="item-img item-img--empty">无图</span>
                  <span class="item-name">{{ it.productName }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="quantity" label="数量" width="70" align="center" />
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row: it }">¥{{ money(it.productPrice) }}</template>
            </el-table-column>
            <el-table-column label="小计" width="110" align="right">
              <template #default="{ row: it }"><span class="price-text">¥{{ money(it.subtotal) }}</span></template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-dialog>

    <!-- 发货 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" title="订单发货 / 编辑" width="520" :close-on-click-modal="false">
      <div class="order-info">
        <span>订单号：<b>{{ form.orderNo }}</b></span>
        <span>金额：<b style="color:#E1251B;">¥{{ money(form.totalAmount) }}</b></span>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="订单状态" prop="status">
          <el-select v-model="form.status" style="width: 100%">
            <el-option v-for="(item, key) in statusMap" :key="key" :label="item.label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="物流单号">
          <el-input v-model="form.trackingNo" placeholder="发货后填写物流单号" clearable />
        </el-form-item>
        <el-form-item label="物流公司">
          <el-input v-model="form.logisticsCompany" placeholder="如：顺丰速运、中通快递" clearable />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" placeholder="管理员备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { Order } from '@/api'
import { ORDER_STATUS } from '@/constants/order'

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const searchForm = reactive({ orderNo: '', status: undefined })

const detailVisible = ref(false)
const detail = ref(null)

const dialogVisible = ref(false)
const formRef = ref()
const form = reactive({
  id: undefined, orderNo: '', totalAmount: 0,
  status: ORDER_STATUS.WAIT_PAY, trackingNo: '', logisticsCompany: '', remark: '',
})

const rules = {
  status: [{ required: true, message: '请选择订单状态', trigger: 'change' }],
}

// 后端 OrderStatus：0待付款/1已付款/2已发货/3已完成/4已取消/5已关闭
const statusMap = {
  [ORDER_STATUS.WAIT_PAY]: { label: '待付款', type: 'warning' },
  [ORDER_STATUS.PAID]: { label: '已付款', type: 'primary' },
  [ORDER_STATUS.SHIPPED]: { label: '已发货', type: 'info' },
  [ORDER_STATUS.COMPLETED]: { label: '已完成', type: 'success' },
  [ORDER_STATUS.CANCELLED]: { label: '已取消', type: 'danger' },
  [ORDER_STATUS.CLOSED]: { label: '已关闭', type: 'info' },
}

function money(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}
function fmt(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

async function loadData() {
  loading.value = true
  try {
    const data = await Order.adminList({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      orderNo: searchForm.orderNo || undefined,
      status: searchForm.status === '' || searchForm.status === undefined ? undefined : searchForm.status,
    })
    list.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
  } finally {
    loading.value = false
  }
}

function handleSearch() { pageNum.value = 1; loadData() }
function handleReset() { searchForm.orderNo = ''; searchForm.status = undefined; handleSearch() }
function handleSizeChange() { pageNum.value = 1; loadData() }

function openDetail(row) {
  detail.value = row
  detailVisible.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id, orderNo: row.orderNo || '', totalAmount: row.totalAmount || 0,
    status: row.status, trackingNo: row.trackingNo || '',
    logisticsCompany: row.logisticsCompany || '', remark: row.remark || '',
  })
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  try { await formRef.value.validate() } catch (e) { return }
  saving.value = true
  try {
    await Order.adminUpdate({
      id: form.id,
      status: form.status,
      trackingNo: form.trackingNo || undefined,
      logisticsCompany: form.logisticsCompany || undefined,
      remark: form.remark || undefined,
    })
    ElMessage.success('订单更新成功')
    dialogVisible.value = false
    loadData()
  } catch (e) {
  } finally {
    saving.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.page-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  border: 1px solid #f2ece7;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.05);
}
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 18px; flex-wrap: wrap; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.price-text { color: #E1251B; font-weight: 700; }
.order-info {
  display: flex;
  justify-content: space-between;
  background: #f7f9fc;
  border: 1px solid #f2ece7;
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 16px;
  font-size: 13px;
  color: #5c524b;
}
.detail-section { margin-bottom: 16px; }
.detail-title {
  font-size: 13px;
  font-weight: 700;
  color: #2e2620;
  margin-bottom: 10px;
  padding-left: 8px;
  border-left: 3px solid #ff5000;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 16px;
  background: #f9fafc;
  border-radius: 8px;
  padding: 12px 14px;
}
.detail-item { display: flex; gap: 8px; font-size: 13px; }
.detail-item .k { color: #a89a8f; flex-shrink: 0; }
.detail-item .v { color: #3a2e26; word-break: break-all; }
.item-cell { display: flex; align-items: center; gap: 10px; }
.item-img {
  width: 40px; height: 40px;
  border-radius: 6px;
  flex-shrink: 0;
  object-fit: cover;
  background: #f5f7fa;
}
.item-img--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  color: #a89a8f;
}
.item-name { font-size: 13px; color: #3a2e26; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
