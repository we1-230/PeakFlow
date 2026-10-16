<template>
  <div class="page-card">
    <!-- 工具栏 -->
    <div class="toolbar">
      <el-input
        v-model="searchForm.refundNo"
        placeholder="按退款单号搜索"
        clearable
        style="width: 240px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-input
        v-model="searchForm.orderNo"
        placeholder="按订单号搜索"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 150px">
        <el-option v-for="(item, key) in statusMap" :key="key" :label="item.label" :value="Number(key)" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <!-- 退款单表格 -->
    <el-table :data="list" v-loading="loading" stripe>
      <el-table-column prop="refundNo" label="退款单号" min-width="195" show-overflow-tooltip />
      <el-table-column prop="orderNo" label="订单号" min-width="180" show-overflow-tooltip />
      <el-table-column label="退款金额" width="110" align="right">
        <template #default="{ row }"><span class="price-text">¥{{ money(row.refundAmount) }}</span></template>
      </el-table-column>
      <el-table-column label="类型" width="105" align="center">
        <template #default="{ row }">
          <el-tag :type="row.refundType === 2 ? 'warning' : 'info'" size="small" effect="plain">
            {{ row.refundTypeDesc || (row.refundType === 2 ? '退货退款' : '仅退款') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="statusMap[row.status]?.type || 'info'" size="small">
            {{ statusMap[row.status]?.label || row.statusDesc || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="退款原因" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reason || '-' }}</template>
      </el-table-column>
      <el-table-column label="申请时间" width="165">
        <template #default="{ row }">{{ fmt(row.applyTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" type="primary" link size="small" @click="openAudit(row)">审核</el-button>
          <el-button v-if="row.canConfirmReturn" type="success" link size="small" @click="handleConfirmReturn(row)">确认收货</el-button>
          <el-button v-if="row.status === 3" type="warning" link size="small" @click="handleRetry(row)">重试</el-button>
          <el-button v-if="row.status === 3" type="danger" link size="small" @click="handleClose(row)">作废</el-button>
          <el-button type="info" link size="small" @click="openDetail(row)">详情</el-button>
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

    <!-- 审核弹窗 -->
    <el-dialog v-model="auditVisible" title="退款审核" width="520" :close-on-click-modal="false">
      <div class="refund-info">
        <span>退款单号：<b>{{ auditForm.refundNo }}</b></span>
        <span>退款金额：<b class="price-text">¥{{ money(auditForm.refundAmount) }}</b></span>
      </div>
      <el-form label-width="90px">
        <el-form-item label="审核结果">
          <el-radio-group v-model="auditForm.approved">
            <el-radio :value="true">同意退款</el-radio>
            <el-radio :value="false">驳回申请</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="审核备注">
          <el-input v-model="auditForm.remark" type="textarea" :rows="3" placeholder="请填写审核备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditVisible = false">取消</el-button>
        <el-button type="primary" :loading="auditSaving" @click="submitAudit">提交审核</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="退款单详情" width="560" :close-on-click-modal="false">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="退款单号">{{ detail.refundNo }}</el-descriptions-item>
        <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="退款金额">¥{{ money(detail.refundAmount) }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ detail.refundTypeDesc || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusMap[detail.status]?.label || detail.statusDesc || '-' }}</el-descriptions-item>
        <el-descriptions-item label="退款原因">{{ detail.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审核人">{{ detail.auditorName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审核备注">{{ detail.auditRemark || '-' }}</el-descriptions-item>
        <el-descriptions-item label="失败原因">{{ detail.failReason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="第三方退款流水号">{{ detail.refundTradeNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="申请时间">{{ fmt(detail.applyTime) }}</el-descriptions-item>
        <el-descriptions-item label="退款完成时间">{{ fmt(detail.refundTime) }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { Refund } from '@/api'

const loading = ref(false)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const searchForm = reactive({ refundNo: '', orderNo: '', status: undefined })

const auditVisible = ref(false)
const auditSaving = ref(false)
const auditForm = reactive({ refundId: undefined, refundNo: '', refundAmount: 0, approved: true, remark: '' })

const detailVisible = ref(false)
const detail = ref({})

// 后端 RefundStatus：0待审核/1退款中/2退款成功/3退款失败/4已驳回/5用户已撤销/6已作废/7待用户退货
const statusMap = {
  0: { label: '待审核', type: 'warning' },
  1: { label: '退款中', type: 'primary' },
  2: { label: '退款成功', type: 'success' },
  3: { label: '退款失败', type: 'danger' },
  4: { label: '已驳回', type: 'info' },
  5: { label: '用户已撤销', type: 'info' },
  6: { label: '已作废', type: 'info' },
  7: { label: '待用户退货', type: 'warning' },
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
    const data = await Refund.adminList({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      refundNo: searchForm.refundNo || undefined,
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
function handleReset() { searchForm.refundNo = ''; searchForm.orderNo = ''; searchForm.status = undefined; handleSearch() }
function handleSizeChange() { pageNum.value = 1; loadData() }

function openAudit(row) {
  Object.assign(auditForm, {
    refundId: row.id, refundNo: row.refundNo, refundAmount: row.refundAmount,
    approved: true, remark: '',
  })
  auditVisible.value = true
}

async function submitAudit() {
  auditSaving.value = true
  try {
    await Refund.adminAudit({
      refundId: auditForm.refundId,
      approved: auditForm.approved,
      remark: auditForm.remark,
    })
    ElMessage.success(auditForm.approved ? '已通过退款审核' : '已驳回退款申请')
    auditVisible.value = false
    loadData()
  } catch (e) {
  } finally {
    auditSaving.value = false
  }
}

// 售后专用：管理员确认收到退货 → 触发退款
async function handleConfirmReturn(row) {
  try {
    await ElMessageBox.confirm(
      `确认已收到退款单 ${row.refundNo} 对应的退货？确认后将发起退款。`,
      '确认收到退货',
      { type: 'warning', confirmButtonText: '确认收货', cancelButtonText: '取消' },
    )
  } catch (e) { return }
  try {
    await Refund.adminConfirmReturn(row.id)
    ElMessage.success('已确认收到退货，退款已发起')
    loadData()
  } catch (e) {}
}

// 仅「退款失败」可重试
async function handleRetry(row) {
  try {
    await ElMessageBox.confirm(
      `确定重新发起退款单 ${row.refundNo} 的退款？`,
      '重试退款',
      { type: 'warning', confirmButtonText: '重新发起', cancelButtonText: '取消' },
    )
  } catch (e) { return }
  try {
    await Refund.adminRetry(row.id)
    ElMessage.success('已重新发起退款')
    loadData()
  } catch (e) {}
}

// 仅「退款失败」可作废（渠道确实退不了时收口）
async function handleClose(row) {
  let remark = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写作废原因', '作废退款单', {
      confirmButtonText: '确认作废',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：渠道已确认无法退回',
      inputValidator: (v) => (v && v.trim() ? true : '请填写作废原因'),
    })
    remark = value
  } catch (e) { return }
  try {
    await Refund.adminClose(row.id, remark)
    ElMessage.success('退款单已作废')
    loadData()
  } catch (e) {}
}

async function openDetail(row) {
  try {
    detail.value = (await Refund.adminDetail(row.id)) || {}
    detailVisible.value = true
  } catch (e) {}
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
.refund-info {
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
</style>
