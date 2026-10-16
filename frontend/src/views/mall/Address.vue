<template>
  <div class="address-page">
    <div class="page-header">
      <h2 class="section-title">收货地址管理</h2>
      <el-button type="primary" class="btn-primary" @click="openAddDialog">
        + 新增地址
      </el-button>
    </div>

    <!-- Address List -->
    <div v-loading="loading" class="address-list">
      <div
        v-for="addr in addressList"
        :key="addr.id"
        class="address-card"
      >
        <div class="address-card-body">
          <div class="addr-top">
            <span class="addr-name">{{ addr.receiverName }}</span>
            <span class="addr-phone">{{ addr.receiverPhone }}</span>
            <el-tag v-if="addr.isDefault" size="small" type="warning">默认</el-tag>
          </div>
          <div class="addr-text">
            {{ addr.province }} {{ addr.city }} {{ addr.district }} {{ addr.detailAddress }}
          </div>
        </div>
        <div class="address-card-actions">
          <el-button
            v-if="!addr.isDefault"
            text
            type="primary"
            @click="handleSetDefault(addr)"
          >
            设为默认
          </el-button>
          <el-button text type="primary" @click="openEditDialog(addr)">编辑</el-button>
          <el-button text type="danger" @click="handleDelete(addr)">删除</el-button>
        </div>
      </div>

      <!-- Empty -->
      <div v-if="!loading && addressList.length === 0" class="empty-state">
        <p>暂无收货地址</p>
        <el-button type="primary" class="btn-primary" @click="openAddDialog">
          添加第一个地址
        </el-button>
      </div>
    </div>

    <!-- Add / Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑地址' : '新增地址'"
      width="520px"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item label="收货人" prop="receiverName">
          <el-input v-model="form.receiverName" placeholder="请输入收货人姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="receiverPhone">
          <el-input v-model="form.receiverPhone" placeholder="请输入手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="省份" prop="province">
          <el-input v-model="form.province" placeholder="请输入省份" />
        </el-form-item>
        <el-form-item label="城市" prop="city">
          <el-input v-model="form.city" placeholder="请输入城市" />
        </el-form-item>
        <el-form-item label="区/县" prop="district">
          <el-input v-model="form.district" placeholder="请输入区/县" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detailAddress">
          <el-input
            v-model="form.detailAddress"
            type="textarea"
            :rows="2"
            placeholder="请输入详细地址"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEdit ? '保存' : '添加' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Address } from '@/api'

const loading = ref(false)
const addressList = ref([])

async function fetchList() {
  loading.value = true
  try {
    addressList.value = await Address.list()
  } catch (e) {
    ElMessage.error('获取地址列表失败')
  } finally {
    loading.value = false
  }
}

// ---------- Dialog ----------
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref(null)

const form = reactive({
  id: null,
  receiverName: '',
  receiverPhone: '',
  province: '',
  city: '',
  district: '',
  detailAddress: ''
})

const rules = {
  receiverName: [{ required: true, message: '请输入收货人姓名', trigger: 'blur' }],
  receiverPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号', trigger: 'blur' }
  ],
  province: [{ required: true, message: '请输入省份', trigger: 'blur' }],
  city: [{ required: true, message: '请输入城市', trigger: 'blur' }],
  district: [{ required: true, message: '请输入区/县', trigger: 'blur' }],
  detailAddress: [{ required: true, message: '请输入详细地址', trigger: 'blur' }]
}

function resetForm() {
  form.id = null
  form.receiverName = ''
  form.receiverPhone = ''
  form.province = ''
  form.city = ''
  form.district = ''
  form.detailAddress = ''
}

function openAddDialog() {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(addr) {
  isEdit.value = true
  form.id = addr.id
  form.receiverName = addr.receiverName
  form.receiverPhone = addr.receiverPhone
  form.province = addr.province
  form.city = addr.city
  form.district = addr.district
  form.detailAddress = addr.detailAddress
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (isEdit.value) {
        await Address.update({ ...form })
        ElMessage.success('地址修改成功')
      } else {
        await Address.add({ ...form })
        ElMessage.success('地址添加成功')
      }
      dialogVisible.value = false
      fetchList()
    } catch (e) {
      ElMessage.error(e.message || (isEdit.value ? '修改失败' : '添加失败'))
    } finally {
      submitting.value = false
    }
  })
}

// ---------- Delete ----------
async function handleDelete(addr) {
  await ElMessageBox.confirm(
    `确定删除该收货地址吗？（${addr.receiverName}）`,
    '删除确认',
    {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    }
  )
  try {
    await Address.remove(addr.id)
    ElMessage.success('删除成功')
    fetchList()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

// ---------- Set Default ----------
async function handleSetDefault(addr) {
  try {
    await Address.setDefault(addr.id)
    ElMessage.success('已设为默认地址')
    fetchList()
  } catch (e) {
    ElMessage.error('设置失败')
  }
}

// ---------- Init ----------
fetchList()
</script>

<style scoped>
.address-page {
  max-width: 900px;
  margin: 0 auto;
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.section-title {
  font-size: 24px;
  font-weight: 700;
  color: #333;
  margin: 0;
}

.btn-primary {
  background: #ff5000;
  border-color: #ff5000;
}

.btn-primary:hover {
  background: #e64600;
  border-color: #e64600;
}

/* Address List */
.address-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.address-card {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  padding: 16px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  transition: box-shadow 0.2s;
}

.address-card:hover {
  box-shadow: 0 4px 16px rgba(0,0,0,0.08);
}

.address-card-body {
  flex: 1;
}

.addr-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.addr-name {
  font-size: 16px;
  font-weight: 700;
  color: #333;
}

.addr-phone {
  font-size: 14px;
  color: #666;
}

.addr-text {
  font-size: 14px;
  color: #555;
  line-height: 1.5;
}

.address-card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

/* Empty */
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #999;
  font-size: 16px;
}

.empty-state .el-button {
  margin-top: 16px;
}
</style>
