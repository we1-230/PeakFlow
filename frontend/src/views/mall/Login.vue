<template>
  <div class="auth-page">
    <!-- 顶部：干净的白底品牌栏（不做厚重橙黄渐变，避免“廉价促销”观感） -->
    <header class="auth-topbar">
      <div class="auth-topbar-inner">
        <BrandLogo size="sm" />
        <router-link to="/" class="topbar-back">
          <el-icon><ArrowLeft /></el-icon><span>返回商城</span>
        </router-link>
      </div>
    </header>

    <main class="auth-main">
      <section class="auth-card">
        <div class="auth-head">
          <h1 class="auth-title">{{ activeTab === 'login' ? '欢迎回来' : '创建账号' }}</h1>
          <p class="auth-sub">
            {{ activeTab === 'login' ? '登录后即可下单、查看订单与参与限时秒杀' : '注册后即可开始购物' }}
          </p>
        </div>

        <el-tabs v-model="activeTab" class="auth-tabs" stretch>
          <!-- 登录 -->
          <el-tab-pane label="登录" name="login">
            <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" @keyup.enter="handleLogin">
              <el-form-item prop="account">
                <el-input v-model="loginForm.account" placeholder="用户名 / 手机号 / 邮箱" size="large" clearable>
                  <template #prefix><el-icon><User /></el-icon></template>
                </el-input>
              </el-form-item>
              <el-form-item prop="password">
                <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" size="large" show-password>
                  <template #prefix><el-icon><Lock /></el-icon></template>
                </el-input>
              </el-form-item>
              <el-form-item v-if="captchaVisible" prop="captchaCode">
                <div class="captcha-row">
                  <el-input v-model="loginForm.captchaCode" placeholder="请输入图形验证码" size="large" maxlength="4" clearable />
                  <img v-if="captchaImg" :src="captchaImg" class="captcha-img" title="看不清？点击刷新" @click="loadCaptcha" />
                </div>
              </el-form-item>
              <el-button type="primary" size="large" class="btn-submit" :loading="loginLoading" @click="handleLogin">
                登 录
              </el-button>
            </el-form>
          </el-tab-pane>

          <!-- 注册 -->
          <el-tab-pane label="注册" name="register">
            <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules">
              <div class="form-row">
                <el-form-item prop="username">
                  <el-input v-model="registerForm.username" placeholder="用户名" size="large" clearable>
                    <template #prefix><el-icon><User /></el-icon></template>
                  </el-input>
                </el-form-item>
                <el-form-item prop="nickname">
                  <el-input v-model="registerForm.nickname" placeholder="昵称" size="large" clearable>
                    <template #prefix><el-icon><Postcard /></el-icon></template>
                  </el-input>
                </el-form-item>
              </div>
              <div class="form-row">
                <el-form-item prop="phone">
                  <el-input v-model="registerForm.phone" placeholder="手机号" size="large" maxlength="11" clearable>
                    <template #prefix><el-icon><Phone /></el-icon></template>
                  </el-input>
                </el-form-item>
                <el-form-item prop="email">
                  <el-input v-model="registerForm.email" placeholder="邮箱" size="large" clearable>
                    <template #prefix><el-icon><Message /></el-icon></template>
                  </el-input>
                </el-form-item>
              </div>
              <el-form-item prop="password">
                <el-input v-model="registerForm.password" type="password" placeholder="密码（至少 6 位）" size="large" show-password>
                  <template #prefix><el-icon><Lock /></el-icon></template>
                </el-input>
              </el-form-item>
              <el-form-item prop="confirmPassword">
                <el-input v-model="registerForm.confirmPassword" type="password" placeholder="再次输入密码" size="large" show-password>
                  <template #prefix><el-icon><Lock /></el-icon></template>
                </el-input>
              </el-form-item>
              <el-button type="primary" size="large" class="btn-submit" :loading="registerLoading" @click="handleRegister">
                注 册
              </el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>

        <!-- 协议勾选：未勾选时阻止登录/注册；两个协议名可点击查看全文
             ⚠️ 协议链接必须放在 el-checkbox 的 label 之外：
             放进 label 里时，<a> 的点击会与「点文字即勾选」冲突
             （阻止冒泡会让勾选框失灵，不阻止则点协议名会误勾选）。 -->
        <div class="agreement-row">
          <el-checkbox v-model="agreed" class="agreement-check" />
          <span class="agreement-text">
            已阅读并同意
            <a class="agreement-link" @click.prevent="openAgreement('user')">《用户协议》</a>
            与
            <a class="agreement-link" @click.prevent="openAgreement('privacy')">《隐私政策》</a>
          </span>
        </div>
      </section>
    </main>

    <!-- 协议全文弹窗（点击协议名打开；风格与登录卡片保持一致） -->
    <el-dialog
      v-model="agreementVisible"
      :title="currentAgreement.title"
      width="640px"
      class="agreement-dialog"
      align-center
      append-to-body
    >
      <div class="agreement-meta">
        <span class="agreement-badge">{{ currentAgreement.title }}</span>
        <span class="agreement-version">版本 {{ currentAgreement.version }} · 更新于 {{ currentAgreement.updatedAt }}</span>
      </div>
      <div class="agreement-body">
        <section v-for="(sec, i) in currentAgreement.sections" :key="i" class="agreement-section">
          <h3 class="agreement-h3">{{ sec.heading }}</h3>
          <p v-for="(para, j) in sec.paragraphs" :key="j" class="agreement-p">{{ para }}</p>
        </section>
      </div>
      <template #footer>
        <div class="agreement-footer">
          <el-button @click="agreementVisible = false">关闭</el-button>
          <el-button type="primary" class="agreement-agree-btn" @click="agreeFromDialog">
            我已阅读并同意
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Message, Phone, Postcard, ArrowLeft } from '@element-plus/icons-vue'
import BrandLogo from '@/components/BrandLogo.vue'
import { Auth } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { AGREEMENTS, USER_AGREEMENT } from '@/constants/agreements'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const activeTab = ref('login')
const agreed = ref(false)  // 协议勾选状态

// ---------- 协议弹窗 ----------
const agreementVisible = ref(false)
const currentAgreement = ref(USER_AGREEMENT)

// 点击《用户协议》/《隐私政策》：打开对应全文（不改变勾选态，避免误触视为同意）
function openAgreement(key) {
  currentAgreement.value = AGREEMENTS[key] || USER_AGREEMENT
  agreementVisible.value = true
}

// 在弹窗里点「我已阅读并同意」：关闭弹窗并自动勾选，省去用户再找复选框
function agreeFromDialog() {
  agreed.value = true
  agreementVisible.value = false
}

// ---------- Login ----------
const loginFormRef = ref(null)
const loginLoading = ref(false)

const loginForm = reactive({
  account: '',
  password: '',
  captchaCode: ''
})

// 图形验证码：登录失败 2 次后后端要求带验证码，此时才展示输入框（可点击图片刷新）
const captchaVisible = ref(false)
const captchaImg = ref('')
const captchaUuid = ref('')

async function loadCaptcha() {
  try {
    const data = await Auth.captcha()
    captchaUuid.value = data?.captchaUuid || ''
    captchaImg.value = data?.imageBase64 || ''
    captchaVisible.value = true
  } catch (e) {
    // 拿不到验证码时不影响其它错误提示
  }
}

const loginRules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }]
}

async function handleLogin() {
  if (!agreed.value) {
    ElMessage.warning('请先勾选同意《用户协议》与《隐私政策》')
    return
  }
  if (!loginFormRef.value) return
  await loginFormRef.value.validate(async (valid) => {
    if (!valid) return
    loginLoading.value = true
    try {
      // 直接调 store.login，由 store 统一发 API 请求 + 存 token（不再二次调 Auth.login）
      const captcha = captchaVisible.value
        ? { captchaUuid: captchaUuid.value, captchaCode: loginForm.captchaCode }
        : null
      await auth.login(loginForm.account, loginForm.password, captcha)
      ElMessage.success('登录成功')
      const redirect = route.query.redirect || '/'
      router.push(redirect)
    } catch (e) {
      ElMessage.error(e.message || '登录失败，请检查账号和密码')
      // 后端要求图形验证码（失败次数达阈值）时，把验证码输入框亮出来并刷新一张
      if (/验证码/.test(e.message || '')) {
        captchaVisible.value = true
        loginForm.captchaCode = ''
        loadCaptcha()
      }
    } finally {
      loginLoading.value = false
    }
  })
}

// ---------- Register ----------
const registerFormRef = ref(null)
const registerLoading = ref(false)

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
  phone: '',
  nickname: ''
})

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名 3-20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' }
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号', trigger: 'blur' }
  ],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }]
}

async function handleRegister() {
  if (!agreed.value) {
    ElMessage.warning('请先勾选同意《用户协议》与《隐私政策》')
    return
  }
  if (!registerFormRef.value) return
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return
    registerLoading.value = true
    try {
      await Auth.register({
        username: registerForm.username,
        password: registerForm.password,
        email: registerForm.email,
        phone: registerForm.phone,
        nickname: registerForm.nickname
      })
      ElMessage.success('注册成功，请登录')
      activeTab.value = 'login'
      loginForm.account = registerForm.username
      loginForm.password = ''
      registerFormRef.value.resetFields()
    } catch (e) {
      ElMessage.error(e.message || '注册失败')
    } finally {
      registerLoading.value = false
    }
  })
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f7f7f5;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
}

/* ============ 顶部品牌栏（白底 + 细分割线，克制） ============ */
.auth-topbar {
  background: #fff;
  border-bottom: 1px solid #eeece9;
  position: sticky;
  top: 0;
  z-index: 10;
}
.auth-topbar-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 14px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.topbar-back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  color: #6f6760;
  text-decoration: none;
  padding: 7px 14px;
  border: 1px solid #e9e6e2;
  border-radius: 8px;
  transition: color .2s, border-color .2s, background .2s;
}
.topbar-back:hover { color: #ff5000; border-color: #ffd3bd; background: #fff8f4; }

/* ============ 居中卡片 ============ */
.auth-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 20px;
}
.auth-card {
  width: 100%;
  max-width: 420px;
  background: #fff;
  border: 1px solid #eeece9;
  border-radius: 16px;
  padding: 40px 36px 26px;
  box-shadow: 0 1px 2px rgba(23, 20, 18, .04), 0 12px 32px rgba(23, 20, 18, .06);
}
.auth-head { text-align: center; margin-bottom: 20px; }
.auth-title {
  font-size: 22px;
  font-weight: 700;
  color: #221d19;
  margin: 0 0 8px;
  letter-spacing: .5px;
}
.auth-sub { font-size: 13px; color: #8a8279; margin: 0; line-height: 1.6; }

/* ============ Tabs：细下划线，选中用主色 ============ */
.auth-tabs { --el-color-primary: #ff5000; }
.auth-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background-color: #f0eeec; }
.auth-tabs :deep(.el-tabs__item) {
  font-size: 14px;
  font-weight: 600;
  color: #8a8279;
  height: 42px;
  transition: color .2s;
}
.auth-tabs :deep(.el-tabs__item.is-active) { color: #ff5000; }
.auth-tabs :deep(.el-tabs__item:hover) { color: #ff6a1a; }
.auth-tabs :deep(.el-tabs__active-bar) { background-color: #ff5000; height: 2px; border-radius: 2px; }

/* ============ 表单 ============ */
:deep(.el-form-item) { margin-bottom: 16px; }
:deep(.el-form-item__error) { font-size: 12px; }
:deep(.el-input__wrapper) {
  border-radius: 10px;
  box-shadow: 0 0 0 1px #e9e6e2 inset;
  padding: 4px 14px;
  background: #fff;
  transition: box-shadow .2s;
}
:deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px #d9d4cf inset; }
:deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1.5px #ff5000 inset; }
:deep(.el-input__prefix) { color: #b8b1aa; }

/* 注册表单双列布局 */
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-row :deep(.el-form-item) { margin-bottom: 16px; }

/* ============ 图形验证码（风控触发后才显示） ============ */
.captcha-row { display: flex; gap: 10px; align-items: center; width: 100%; }
.captcha-img {
  height: 40px;
  width: 110px;
  border-radius: 8px;
  border: 1px solid #f0e2d8;
  cursor: pointer;
  object-fit: cover;
  flex: none;
}

/* ============ 主按钮：纯色、无花哨渐变 ============ */
.btn-submit {
  width: 100%;
  height: 46px;
  margin-top: 6px;
  border: none;
  border-radius: 10px;
  background: #ff5000;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  transition: background .2s;
}
.btn-submit:hover { background: #e64600; }
.btn-submit:active { background: #d13f00; }

/* ============ 协议勾选 ============ */
.agreement-row {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #f2f0ee;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}
.agreement-text {
  font-size: 12px;
  color: #8a8279;
  line-height: 1.5;
}
.agreement-check {
  font-size: 12px;
  color: #8a8279;
  margin-right: 2px;
  --el-checkbox-checked-bg-color: #ff5000;
  --el-checkbox-checked-input-border-color: #ff5000;
  --el-checkbox-checked-icon-color: #fff;
}
.agreement-link { color: #ff5000; cursor: pointer; margin: 0 2px; text-decoration: none; }
.agreement-link:hover { text-decoration: underline; }

/* ============ 协议全文弹窗（配色与登录卡片一致：橙主色 + 灰阶正文） ============ */
.agreement-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px dashed #ece6e0;
  margin-bottom: 14px;
}
.agreement-badge {
  font-size: 12px;
  color: #ff5000;
  background: #fff2e8;
  border: 1px solid #ffd6bd;
  border-radius: 10px;
  padding: 2px 10px;
}
.agreement-version { font-size: 12px; color: #a89a8f; }
.agreement-body {
  max-height: 52vh;
  overflow-y: auto;
  padding-right: 6px;
  line-height: 1.75;
}
.agreement-body::-webkit-scrollbar { width: 6px; }
.agreement-body::-webkit-scrollbar-thumb { background: #e6e0da; border-radius: 3px; }
.agreement-section { margin-bottom: 16px; }
.agreement-h3 {
  font-size: 14px;
  font-weight: 700;
  color: #3a2e26;
  margin-bottom: 6px;
}
.agreement-p {
  font-size: 13px;
  color: #6f6259;
  margin-bottom: 6px;
}
.agreement-footer { display: flex; justify-content: flex-end; gap: 10px; }
.agreement-agree-btn {
  --el-button-bg-color: #ff5000;
  --el-button-border-color: #ff5000;
  --el-button-hover-bg-color: #e64600;
  --el-button-hover-border-color: #e64600;
}
.agreement-link:hover { text-decoration: underline; }

/* ============ 响应式 ============ */
@media (max-width: 520px) {
  .auth-card { padding: 30px 22px 22px; }
  .form-row { grid-template-columns: 1fr; gap: 0; }
}
</style>
