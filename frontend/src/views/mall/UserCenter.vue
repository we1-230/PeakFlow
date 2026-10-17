<template>
  <div class="user-center-page">
    <!-- User Info Card -->
    <div class="user-info-card">
      <div class="avatar-section">
        <el-upload
          :show-file-list="false"
          :before-upload="beforeAvatarUpload"
          :http-request="handleAvatarUpload"
          accept="image/*"
        >
          <div class="avatar-wrap">
            <el-avatar :size="80" :src="userInfo.avatar" :key="userInfo.avatar" />
            <div class="avatar-overlay">
              <el-icon><Camera /></el-icon>
            </div>
          </div>
        </el-upload>
      </div>
      <div class="user-meta">
        <div class="user-nickname-row">
          <template v-if="!editingNickname">
            <span class="user-nickname">{{ userInfo.nickname }}</span>
            <el-button text type="primary" @click="startEditNickname">
              <el-icon><Edit /></el-icon>
            </el-button>
          </template>
          <template v-else>
            <el-input
              v-model="tempNickname"
              size="small"
              style="width: 160px"
              @keyup.enter="saveNickname"
            />
            <el-button text type="success" @click="saveNickname">
              <el-icon><Check /></el-icon>
            </el-button>
            <el-button text type="info" @click="cancelEditNickname">
              <el-icon><Close /></el-icon>
            </el-button>
          </template>
        </div>
        <div class="user-sub">
          <span class="user-username">@{{ userInfo.username }}</span>
        </div>
      </div>
    </div>

    <!-- Tabs -->
    <el-tabs v-model="activeTab" class="user-tabs">
      <!-- 基本信息 -->
      <el-tab-pane label="基本信息" name="info">
        <div class="tab-content">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="头像">
              <div class="info-avatar-row">
                <el-avatar :size="48" :src="userInfo.avatar" :key="userInfo.avatar" />
                <el-upload
                  :show-file-list="false"
                  :before-upload="beforeAvatarUpload"
                  :http-request="handleAvatarUpload"
                  accept="image/*"
                >
                  <el-button size="small" type="primary">更换头像</el-button>
                </el-upload>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="昵称">
              <template v-if="!editingNickname">
                <span>{{ userInfo.nickname }}</span>
                <el-button text type="primary" size="small" @click="startEditNickname">修改</el-button>
              </template>
              <template v-else>
                <div class="inline-edit">
                  <el-input v-model="tempNickname" size="small" style="width: 180px" />
                  <el-button size="small" type="primary" @click="saveNickname">保存</el-button>
                  <el-button size="small" @click="cancelEditNickname">取消</el-button>
                </div>
              </template>
            </el-descriptions-item>
            <el-descriptions-item label="用户名">{{ userInfo.username }}</el-descriptions-item>
            <el-descriptions-item label="邮箱">{{ userInfo.email || '-' }}</el-descriptions-item>
            <el-descriptions-item label="手机号">{{ userInfo.phone || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="userInfo.status === 1 ? 'success' : 'danger'" size="small">
                {{ userInfo.status === 1 ? '正常' : '禁用' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="最后登录">
              {{ userInfo.lastLoginTime || '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-tab-pane>

      <!-- 修改密码 -->
      <el-tab-pane label="修改密码" name="password">
        <div class="tab-content password-tab">
          <el-form
            ref="pwdFormRef"
            :model="pwdForm"
            :rules="pwdRules"
            label-width="120px"
            style="max-width: 460px"
          >
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入原密码" />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="请输入新密码" />
            </el-form-item>
            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="changingPassword" @click="handleChangePassword">
                确认修改
              </el-button>
              <el-button @click="resetPwdForm">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-tab-pane>

      <!-- 登录日志 -->
      <el-tab-pane label="登录日志" name="logs">
        <div class="tab-content">
          <el-table
            v-loading="logsLoading"
            :data="loginLogs"
            border
            stripe
            style="width: 100%"
          >
            <el-table-column prop="id" label="序号" width="70" align="center" />
            <el-table-column prop="loginTime" label="登录时间" min-width="160" />
            <el-table-column prop="ip" label="IP地址" min-width="130" />
            <!--
              状态列说明（2026-10-17 修复「登录成功却显示失败」）：
              后端 login_log 表只有 id/user_id/username/ip/login_time/remark，
              没有 status/location/device 字段（见 UserLoginLog 实体与 01-schema.sql）。
              原实现读 row.status ⇒ 恒为 undefined ⇒ 三元判断落到「失败」，与事实相反。
              日志实际只在登录成功后异步写入（UserServiceImpl#login 第 10 步），
              所以这里按 remark 展示结果，缺省即「登录成功」。
            -->
            <el-table-column prop="remark" label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="row.remark && row.remark.includes('失败') ? 'danger' : 'success'" size="small">
                  {{ row.remark || '登录成功' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-wrap">
            <el-pagination
              v-model:current-page="logPage"
              v-model:page-size="logSize"
              :total="logTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, prev, pager, next, sizes"
              @current-change="fetchLoginLogs"
              @size-change="fetchLoginLogs"
            />
          </div>
        </div>
      </el-tab-pane>

      <!-- 收货地址 -->
      <el-tab-pane label="收货地址" name="address">
        <div class="tab-content">
          <div class="addr-header">
            <span class="addr-title">收货地址管理</span>
            <el-button type="primary" size="small" @click="openAddressAdd">+ 新增地址</el-button>
          </div>

          <div v-loading="addressLoading" class="addr-list">
            <div v-for="addr in addressList" :key="addr.id" class="addr-card">
              <div class="addr-card-body">
                <div class="addr-top">
                  <span class="addr-name">{{ addr.receiverName }}</span>
                  <span class="addr-phone">{{ addr.receiverPhone }}</span>
                  <el-tag v-if="addr.isDefault" size="small" type="warning">默认</el-tag>
                </div>
                <div class="addr-text">{{ addr.province }} {{ addr.city }} {{ addr.district }} {{ addr.detailAddress }}</div>
              </div>
              <div class="addr-actions">
                <el-button v-if="!addr.isDefault" text type="primary" size="small" @click="handleSetDefaultAddress(addr)">设为默认</el-button>
                <el-button text type="primary" size="small" @click="openAddressEdit(addr)">编辑</el-button>
                <el-button text type="danger" size="small" @click="handleAddressDelete(addr)">删除</el-button>
              </div>
            </div>
            <el-empty v-if="!addressLoading && addressList.length === 0" description="暂无收货地址" :image-size="80" />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 收货地址 新增/编辑弹窗 -->
    <el-dialog v-model="addressDialogVisible" :title="addressIsEdit ? '编辑地址' : '新增地址'" width="520px">
      <el-form ref="addressFormRef" :model="addressForm" :rules="addressRules" label-width="90px">
        <el-form-item label="收货人" prop="receiverName">
          <el-input v-model="addressForm.receiverName" placeholder="请输入收货人姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="receiverPhone">
          <el-input v-model="addressForm.receiverPhone" placeholder="请输入手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="省份" prop="province">
          <el-input v-model="addressForm.province" placeholder="请输入省份" />
        </el-form-item>
        <el-form-item label="城市" prop="city">
          <el-input v-model="addressForm.city" placeholder="请输入城市" />
        </el-form-item>
        <el-form-item label="区/县" prop="district">
          <el-input v-model="addressForm.district" placeholder="请输入区/县" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detailAddress">
          <el-input v-model="addressForm.detailAddress" type="textarea" :rows="2" placeholder="请输入详细地址" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addressDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="addressSubmitting" @click="handleAddressSubmit">
          {{ addressIsEdit ? '保存' : '添加' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Camera, Edit, Check, Close } from '@element-plus/icons-vue'
import { User, Address } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// ---------- User Info ----------
const userInfo = ref({
  id: null,
  username: '',
  nickname: '',
  email: '',
  phone: '',
  avatar: '',
  status: 1,
  lastLoginTime: ''
})

async function fetchUserInfo() {
  try {
    userInfo.value = await User.info()
  } catch (e) {
    ElMessage.error('获取用户信息失败')
  }
}

// ---------- Nickname Edit ----------
const editingNickname = ref(false)
const tempNickname = ref('')

function startEditNickname() {
  tempNickname.value = userInfo.value.nickname
  editingNickname.value = true
}

function cancelEditNickname() {
  editingNickname.value = false
  tempNickname.value = ''
}

async function saveNickname() {
  const name = tempNickname.value.trim()
  if (!name) {
    ElMessage.warning('昵称不能为空')
    return
  }
  try {
    await User.changeNickname(name)
    userInfo.value.nickname = name
    auth.updateNickname(name)
    editingNickname.value = false
    ElMessage.success('昵称修改成功')
  } catch (e) {
    ElMessage.error(e.message || '修改昵称失败')
  }
}

// ---------- Avatar Upload ----------
function beforeAvatarUpload(file) {
  const isImage = file.type.startsWith('image/')
  // 上限与服务端保持一致（5MB）。
  // 为什么不卡 2MB：服务端会把图压到最长边 400px（实测 1.6MB → 43KB），
  // 而手机原图普遍 3~8MB —— 卡 2MB 会让大量真实用户在选图时就被拒绝，
  // 而他其实只是想换个头像。
  const isLt5M = file.size / 1024 / 1024 <= 5
  if (!isImage) {
    ElMessage.error('只能上传图片文件（jpg / png / webp）')
    return false
  }
  if (!isLt5M) {
    ElMessage.error('图片大小不能超过 5MB，建议先用手机截图或压缩后再上传')
    return false
  }
  return true
}

async function handleAvatarUpload({ file }) {
  try {
    const url = await User.uploadAvatar(file)
    // 统一通过 auth store 更新（内部加时间戳破缓存），返回最终可用的头像 URL
    const finalUrl = auth.updateAvatar(url)
    // 同步更新本地 userInfo 头像，确保个人主页两处头像立即刷新
    userInfo.value = { ...userInfo.value, avatar: finalUrl }
    await nextTick()
    ElMessage.success('头像更新成功')
  } catch (e) {
    ElMessage.error(e.message || '头像上传失败')
  }
}

// ---------- Change Password ----------
const activeTab = ref('info')
const changingPassword = ref(false)
const pwdFormRef = ref(null)

const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== pwdForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

async function handleChangePassword() {
  if (!pwdFormRef.value) return
  await pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    changingPassword.value = true
    try {
      await User.changePassword({
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword,
        username: userInfo.value.username
      })
      ElMessage.success('密码修改成功')
      resetPwdForm()
    } catch (e) {
      ElMessage.error(e.message || '密码修改失败')
    } finally {
      changingPassword.value = false
    }
  })
}

function resetPwdForm() {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdFormRef.value?.clearValidate()
}

// ---------- Login Logs ----------
const logsLoading = ref(false)
const loginLogs = ref([])
const logPage = ref(1)
const logSize = ref(10)
const logTotal = ref(0)

async function fetchLoginLogs() {
  logsLoading.value = true
  try {
    const res = await User.loginLog({ pageNum: logPage.value, pageSize: logSize.value })
    loginLogs.value = res.list || []
    logTotal.value = res.total || 0
  } catch (e) {
    ElMessage.error('获取登录日志失败')
  } finally {
    logsLoading.value = false
  }
}

// ---------- 收货地址 ----------
const addressLoading = ref(false)
const addressList = ref([])
const addressDialogVisible = ref(false)
const addressIsEdit = ref(false)
const addressSubmitting = ref(false)
const addressFormRef = ref(null)

const addressForm = reactive({
  id: null,
  receiverName: '',
  receiverPhone: '',
  province: '',
  city: '',
  district: '',
  detailAddress: ''
})

const addressRules = {
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

async function fetchAddressList() {
  addressLoading.value = true
  try {
    addressList.value = await Address.list()
  } catch (e) {
    ElMessage.error('获取地址列表失败')
  } finally {
    addressLoading.value = false
  }
}

function resetAddressForm() {
  addressForm.id = null
  addressForm.receiverName = ''
  addressForm.receiverPhone = ''
  addressForm.province = ''
  addressForm.city = ''
  addressForm.district = ''
  addressForm.detailAddress = ''
}

function openAddressAdd() {
  addressIsEdit.value = false
  resetAddressForm()
  addressDialogVisible.value = true
}

function openAddressEdit(addr) {
  addressIsEdit.value = true
  addressForm.id = addr.id
  addressForm.receiverName = addr.receiverName
  addressForm.receiverPhone = addr.receiverPhone
  addressForm.province = addr.province
  addressForm.city = addr.city
  addressForm.district = addr.district
  addressForm.detailAddress = addr.detailAddress
  addressDialogVisible.value = true
}

async function handleAddressSubmit() {
  if (!addressFormRef.value) return
  await addressFormRef.value.validate(async (valid) => {
    if (!valid) return
    addressSubmitting.value = true
    try {
      if (addressIsEdit.value) {
        await Address.update({ ...addressForm })
        ElMessage.success('地址修改成功')
      } else {
        await Address.add({ ...addressForm })
        ElMessage.success('地址添加成功')
      }
      addressDialogVisible.value = false
      fetchAddressList()
    } catch (e) {
      ElMessage.error(e.message || (addressIsEdit.value ? '修改失败' : '添加失败'))
    } finally {
      addressSubmitting.value = false
    }
  })
}

async function handleAddressDelete(addr) {
  try {
    await ElMessageBox.confirm(`确定删除该收货地址吗？（${addr.receiverName}）`, '删除确认', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await Address.remove(addr.id)
    ElMessage.success('删除成功')
    fetchAddressList()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

async function handleSetDefaultAddress(addr) {
  try {
    await Address.setDefault(addr.id)
    ElMessage.success('已设为默认地址')
    fetchAddressList()
  } catch (e) {
    ElMessage.error('设置失败')
  }
}

// ---------- Init ----------
onMounted(() => {
  fetchUserInfo()
  fetchLoginLogs()
  fetchAddressList()
})
</script>

<style scoped>
.user-center-page {
  max-width: 900px;
  margin: 0 auto;
  padding: 20px;
}

/* User Info Card */
.user-info-card {
  background: linear-gradient(135deg, #fff7f2 0%, #fff 100%);
  border: 1px solid #ffeadb;
  border-radius: 14px;
  padding: 24px 32px;
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 24px;
}

.avatar-section .avatar-wrap {
  position: relative;
  cursor: pointer;
}

.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0,0,0,0.4);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 20px;
  opacity: 0;
  transition: opacity 0.2s;
}

.avatar-wrap:hover .avatar-overlay {
  opacity: 1;
}

.user-meta {
  flex: 1;
}

.user-nickname-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-nickname {
  font-size: 22px;
  font-weight: 700;
  color: #333;
}

.user-sub {
  margin-top: 4px;
}

.user-username {
  font-size: 14px;
  color: #999;
}

/* Tabs */
.user-tabs {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  padding: 0 20px;
}

.tab-content {
  padding: 20px 0;
}

/* Info Avatar Row */
.info-avatar-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.inline-edit {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Password Tab */
.password-tab {
  display: flex;
  justify-content: center;
}

/* Pagination */
.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

/* Address */
.addr-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.addr-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.addr-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.addr-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  padding: 14px 18px;
  transition: box-shadow 0.2s;
}
.addr-card:hover {
  box-shadow: 0 4px 16px rgba(0,0,0,0.08);
}
.addr-card-body {
  flex: 1;
}
.addr-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.addr-name {
  font-size: 15px;
  font-weight: 700;
  color: #333;
}
.addr-phone {
  font-size: 13px;
  color: #666;
}
.addr-text {
  font-size: 13px;
  color: #555;
  line-height: 1.5;
}
.addr-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
</style>
