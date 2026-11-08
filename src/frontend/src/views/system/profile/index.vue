<!--
  个人中心 - 基本信息
  -------------------
  与 管理端 dashboard 一致的设计风格：
  - 顶部欢迎横幅：渐变 + 光斑 + 问候语 + 头像
  - 下方左右两栏卡片：左"账户资料"网格，右"账号安全"快捷入口
  - 头像支持上传与移除（auto-upload，调用 uploadImage）
  - 资料编辑支持用户名/昵称/邮箱/手机号
  - 颜色全部使用 CSS 变量，兼容所有主题
  - 使用 PostCSS 嵌套写法
-->
<template>
  <div class="profile">
    <!-- ============== 欢迎横幅 ============== -->
    <div class="welcome-banner">
      <div class="welcome-bg">
        <div class="welcome-orb welcome-orb-1" />
        <div class="welcome-orb welcome-orb-2" />
        <div class="welcome-orb welcome-orb-3" />
      </div>
      <div class="welcome-content">
        <div class="welcome-left">
          <div class="welcome-greeting">{{ greeting }},</div>
          <div class="welcome-title">{{ form.nickname || form.username || '未命名用户' }}</div>
          <div class="welcome-sub">欢迎使用 管理端 · 今天是个高效工作日</div>
        </div>
        <div class="welcome-right">
          <el-upload
            :show-file-list="false"
            :auto-upload="true"
            :http-request="handleUpload"
            :before-upload="beforeAvatarUpload"
            accept="image/*"
            class="avatar-upload"
          >
            <div class="avatar-edit">
              <el-avatar :size="80" :src="resolveAvatarUrl(form.avatar)" class="profile-avatar">
                {{ avatarInitial }}
              </el-avatar>
              <div class="avatar-mask">
                <Icon icon="ph:camera" class="avatar-mask-icon" />
                <span class="avatar-mask-text">更换</span>
              </div>
            </div>
          </el-upload>
          <div class="avatar-actions">
            <el-link v-if="form.avatar" type="danger" :underline="false" @click="form.avatar = ''">
              <Icon icon="ph:trash" class="avatar-action-icon" />
              移除头像
            </el-link>
            <span v-else class="avatar-tip">支持 JPG / PNG · ≤5MB</span>
          </div>
        </div>
      </div>
    </div>

    <el-row :gutter="16" class="profile-row">
      <!-- ============== 左：账户资料 ============== -->
      <el-col :xs="24" :md="16">
        <el-card v-loading="loading" shadow="hover" class="profile-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">
                <Icon icon="ph:user-circle" class="card-title-icon" />
                账户资料
              </span>
              <span class="card-subtitle">修改后点击底部保存即可生效</span>
            </div>
          </template>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="profile-form">
            <div class="form-grid">
              <el-form-item label="用户名" class="form-field">
                <el-input :model-value="form.username" disabled>
                  <template #prefix
                    ><el-icon><User /></el-icon
                  ></template>
                </el-input>
                <div class="field-tip">用户名不可修改（系统唯一标识）</div>
              </el-form-item>

              <el-form-item label="昵称" prop="nickname" class="form-field">
                <el-input v-model="form.nickname" placeholder="请输入昵称" maxlength="32" show-word-limit clearable>
                  <template #prefix
                    ><el-icon><EditPen /></el-icon
                  ></template>
                </el-input>
                <div class="field-tip">用于在系统内显示，建议填写真实姓名或花名</div>
              </el-form-item>

              <el-form-item label="邮箱" prop="email" class="form-field">
                <el-input v-model="form.email" placeholder="请输入邮箱" clearable>
                  <template #prefix
                    ><el-icon><Message /></el-icon
                  ></template>
                </el-input>
                <div class="field-tip">用于接收系统通知与找回密码</div>
              </el-form-item>

              <el-form-item label="手机号" prop="phone" class="form-field">
                <el-input v-model="form.phone" placeholder="请输入手机号" maxlength="11" clearable>
                  <template #prefix
                    ><el-icon><Iphone /></el-icon
                  ></template>
                </el-input>
                <div class="field-tip">用于登录验证与安全提醒</div>
              </el-form-item>

              <!-- 个人简介：跨整行，填充左卡空白 -->
              <el-form-item class="form-field form-field-full">
                <template #label>
                  <span class="bio-label">
                    <el-icon><ChatLineRound /></el-icon>
                    个人简介
                  </span>
                </template>
                <el-input
                  :model-value="bioText"
                  type="textarea"
                  :rows="4"
                  placeholder="简单介绍一下自己吧～（暂未开放编辑，仅展示）"
                  disabled
                />
                <div class="field-tip">个人简介将在后续版本开放编辑，敬请期待</div>
              </el-form-item>
            </div>

            <!-- 底部保存按钮 -->
            <div class="form-footer">
              <el-button size="large" @click="resetForm"> 重置 </el-button>
              <el-button type="primary" size="large" :loading="submitting" @click="submit"> 保存修改 </el-button>
            </div>
          </el-form>
        </el-card>
      </el-col>

      <!-- ============== 右：账号信息 + 安全入口 ============== -->
      <el-col :xs="24" :md="8" class="right-column">
        <!-- 账号信息卡 -->
        <el-card shadow="hover" class="profile-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">
                <Icon icon="ph:identification-card" class="card-title-icon" />
                账号信息
              </span>
            </div>
          </template>
          <div class="info-grid">
            <div class="info-item">
              <span class="info-label">拥有角色</span>
              <div class="info-value">
                <span v-for="r in roleNames" :key="r" class="role-tag">
                  <el-icon :size="11"><Star /></el-icon>
                  {{ r }}
                </span>
                <span v-if="!roleNames.length" class="info-empty">暂无角色</span>
              </div>
            </div>
            <div class="info-item">
              <span class="info-label">上次登录</span>
              <span class="info-value">{{ lastLoginText }}</span>
            </div>
          </div>
        </el-card>

        <!-- 账号安全卡：自然高度布局，不强制撑高 -->
        <el-card shadow="hover" class="profile-card security-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">
                <Icon icon="ph:shield-check" class="card-title-icon" />
                账号安全
              </span>
            </div>
          </template>
          <div class="security-list">
            <div class="security-item" @click="$router.push('/profile/password')">
              <div class="security-icon-wrap">
                <Icon icon="ph:key" class="security-icon" />
              </div>
              <div class="security-text">
                <div class="security-name">修改密码</div>
                <div class="security-desc">建议每 90 天更换一次</div>
              </div>
              <Icon icon="ph:arrow-right" class="security-arrow" />
            </div>
            <div class="security-item" @click="$router.push('/profile/devices')">
              <div class="security-icon-wrap">
                <Icon icon="ph:device-mobile" class="security-icon" />
              </div>
              <div class="security-text">
                <div class="security-name">登录设备</div>
                <div class="security-desc">查看并管理活跃登录</div>
              </div>
              <Icon icon="ph:arrow-right" class="security-arrow" />
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { User, EditPen, Check, Star, Message, Iphone, ChatLineRound } from '@element-plus/icons-vue'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import { getCurrentUser } from '@/api/auth'
import { updateProfile } from '@/api/system/user'
import { uploadImage } from '@/api/system/upload'
import { useUserStore } from '@/store/user'

defineOptions({ name: 'Profile' })

const userStore = useUserStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const submitting = ref(false)
const uploading = ref(false)

/** 编辑表单 */
const form = reactive({
  username: '',
  nickname: '',
  email: '',
  phone: '',
  avatar: ''
})

/** 校验规则 */
const rules: FormRules = {
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }]
}

/** 头像首字母（avatar 为空时由 el-avatar 显示） */
const avatarInitial = computed(() => {
  const name = form.nickname || form.username
  return name ? name.charAt(0).toUpperCase() : 'U'
})

/** 问候语（随当前时间变化） */
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

/** 角色名称列表 */
const roleNames = computed<string[]>(() => {
  const roles = (userStore.userInfo as any)?.roles
  if (Array.isArray(roles)) {
    return roles.map((r: any) => (typeof r === 'string' ? r : r.name || r.code || ''))
  }
  return []
})

/** 上次登录文本 */
const lastLoginText = computed(() => {
  const t = userStore.userInfo?.lastLoginTime
  return t || '本机首次登录'
})

/** 个人简介文本（演示用，可后续扩展） */
const bioText = computed(() => {
  const uname = form.username || '用户'
  const nickname = form.nickname || ''
  return nickname
    ? `我是 ${nickname}（${uname}），正在使用 管理端 管理平台。`
    : `我是 ${uname}，正在使用 管理端 管理平台。`
})

/** 最大上传大小：5MB */
const MAX_UPLOAD_SIZE = 5 * 1024 * 1024

/**
 * 解析头像 URL：对相对路径拼接 API base URL
 * @param avatar 头像路径（相对路径或完整 URL）
 * @returns 可用于 img src 的完整 URL，空值返回 undefined
 */
function resolveAvatarUrl(avatar: string | undefined): string | undefined {
  if (!avatar) return undefined
  if (avatar.startsWith('http')) return avatar
  const baseURL = import.meta.env.VITE_API_BASE_URL || ''
  return `${baseURL}${avatar}`
}

/** 上传前校验：仅允许图片且不超过 5MB */
function beforeAvatarUpload(file: File): boolean {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('只能上传图片文件')
    return false
  }
  if (file.size > MAX_UPLOAD_SIZE) {
    ElMessage.error('图片大小不能超过 5MB')
    return false
  }
  return true
}

/** 自定义头像上传 */
async function handleUpload(options: UploadRequestOptions) {
  const file = options.file as File
  uploading.value = true
  try {
    const res = await uploadImage(file, 'avatar')
    const url = res.data?.url
    if (url) {
      form.avatar = url
      ElMessage.success('头像上传成功')
    } else {
      ElMessage.error('上传成功但未返回图片地址')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '头像上传失败')
  } finally {
    uploading.value = false
  }
}

/** 加载当前用户信息 */
async function loadData() {
  loading.value = true
  try {
    const res = await getCurrentUser()
    const data = res.data as any
    if (data) {
      form.username = data.username || ''
      form.nickname = data.nickname || ''
      form.email = data.email || ''
      form.phone = data.phone || ''
      form.avatar = data.avatar || ''
    }
  } catch (e) {
    form.username = userStore.userInfo?.username || ''
    form.nickname = userStore.nickname
  } finally {
    loading.value = false
  }
}

/** 重置表单（重新拉取后端数据） */
async function resetForm() {
  await loadData()
  ElMessage.info('表单已重置')
}

/** 保存个人资料 */
async function submit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    await updateProfile({
      nickname: form.nickname,
      email: form.email,
      phone: form.phone,
      avatar: form.avatar
    })
    userStore.setUser({
      nickname: form.nickname,
      avatar: form.avatar
    })
    ElMessage.success('保存成功')
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
/* ============== 页面根容器 ============== */
.profile {
  padding: 4px;
}

/* ============== 欢迎横幅 ============== */
.welcome-banner {
  position: relative;
  border-radius: 12px;
  overflow: hidden;
  margin-bottom: 16px;
  background: var(--na-primary-gradient);
  padding: 32px 36px;
  min-height: 130px;
  display: flex;
  align-items: center;
}

.welcome-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.welcome-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(50px);
  opacity: 0.4;
}

.welcome-orb-1 {
  width: 200px;
  height: 200px;
  background: rgba(255, 255, 255, 0.3);
  top: -60px;
  right: 10%;
  animation: orb-float-1 12s ease-in-out infinite;
}

.welcome-orb-2 {
  width: 150px;
  height: 150px;
  background: rgba(129, 140, 248, 0.5);
  bottom: -40px;
  left: 20%;
  animation: orb-float-2 15s ease-in-out infinite;
}

.welcome-orb-3 {
  width: 100px;
  height: 100px;
  background: rgba(255, 255, 255, 0.25);
  top: 20px;
  left: 5%;
  animation: orb-float-3 10s ease-in-out infinite;
}

@keyframes orb-float-1 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(-30px, 20px);
  }
}
@keyframes orb-float-2 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(40px, -30px);
  }
}
@keyframes orb-float-3 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(20px, 30px);
  }
}

.welcome-content {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  flex-wrap: wrap;
  gap: 24px;
}

.welcome-left {
  flex: 1;
  min-width: 0;
}

.welcome-greeting {
  color: rgba(255, 255, 255, 0.85);
  font-size: 14px;
  margin-bottom: 4px;
  font-weight: 500;
  letter-spacing: 0.5px;
}

.welcome-title {
  color: #fff;
  font-size: 26px;
  font-weight: 700;
  margin: 0 0 6px;
  letter-spacing: 0.5px;
}

.welcome-sub {
  color: rgba(255, 255, 255, 0.85);
  font-size: 14px;
  margin: 0;
}

/* 右上头像区 */
.welcome-right {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.avatar-upload {
  display: block;
  cursor: pointer;
}

.avatar-edit {
  position: relative;
  width: 80px;
  height: 80px;
  border-radius: 50%;
  overflow: hidden;
  /* 关键：内容层设白色背景，避免透明处透出 banner 渐变颜色导致出现灰边 */
  background: #fff;
  /* 加粗白色描边 + 深阴影：在 banner 渐变背景上清晰醒目 */
  border: 5px solid #fff;
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.05),
    0 6px 18px rgba(0, 0, 0, 0.35);
  transition: transform 0.2s ease;
}

.avatar-edit:hover {
  transform: scale(1.05);
}

.profile-avatar {
  background: var(--na-primary-hover);
  color: var(--na-text-inverse);
  font-size: 32px;
  font-weight: 600;
  width: 100% !important;
  height: 100% !important;
}

.avatar-mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #fff;
  opacity: 0;
  transition: opacity 0.2s ease;
  gap: 2px;
}

.avatar-edit:hover .avatar-mask {
  opacity: 1;
}

.avatar-mask-icon {
  font-size: 18px;
}

.avatar-mask-text {
  font-size: 11px;
  font-weight: 500;
}

.avatar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
}

.avatar-action-icon {
  font-size: 14px;
  vertical-align: -2px;
}

.avatar-tip {
  color: rgba(255, 255, 255, 0.7);
}

/* ============== 卡片公共样式 ============== */
.profile-row {
  /* el-row 默认 display: flex + align-items: stretch → 列高 = 行高 */
  align-items: stretch;
}

/* 右列：两张卡垂直堆叠，gap 控制间距 */
.right-column {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.profile-card {
  border-radius: 12px;
  border: 1px solid var(--na-border-lighter);
  box-shadow: var(--na-shadow-card);
  /* 自然高度，无 margin-bottom，避免滚动条 */
}

.profile-card :deep(.el-card__header) {
  padding: 16px 20px;
  border-bottom-color: var(--na-border-lighter);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--na-text-primary);
}

.card-title-icon {
  font-size: 18px;
  color: var(--na-primary);
}

.card-subtitle {
  font-size: 12px;
  color: var(--na-text-placeholder);
}

/* ============== 表单 ============== */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 24px;
}

.form-field {
  :deep(.el-form-item__label) {
    font-weight: 500;
    color: var(--na-text-regular);
    padding-bottom: 6px;
    font-size: 13px;
  }
}

/* form-field-full：跨整行的表单项（如个人简介 textarea） */
.form-field-full {
  grid-column: 1 / -1;

  .bio-label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-weight: 500;
    color: var(--na-text-regular);
    font-size: 13px;
  }

  :deep(.el-form-item__content) {
    line-height: 1.4;
  }
}

.field-tip {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--na-text-placeholder);
  line-height: 1.5;
}

.form-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
  padding-top: 20px;
  border-top: 1px solid var(--na-border-lighter);

  /* 按钮统一宽度 + 高度，视觉对齐 */
  :deep(.el-button) {
    min-width: 120px;
    justify-content: center;
  }
}

/* ============== 账号信息网格 ============== */
.info-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 0;
  border-bottom: 1px solid var(--na-border-extra-light);
}

.info-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.info-item:first-child {
  padding-top: 0;
}

.info-label {
  font-size: 12px;
  color: var(--na-text-placeholder);
}

.info-value {
  font-size: 14px;
  color: var(--na-text-primary);
  font-weight: 500;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.info-value.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  color: var(--na-text-regular);
}

.info-empty {
  color: var(--na-text-placeholder);
  font-weight: 400;
}

.role-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--na-primary);
  background: var(--na-primary-light-9);
  border: 1px solid var(--na-primary-light-8);
  border-radius: 12px;
}

/* ============== 安全入口 ============== */
.security-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.security-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 8px;
  margin: 0 -8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s ease;
}

.security-item:hover {
  background: var(--na-bg-hover);
}

.security-icon-wrap {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: var(--na-primary-light-9);
  flex-shrink: 0;
}

.security-icon {
  font-size: 20px;
  color: var(--na-primary);
}

.security-text {
  flex: 1;
  min-width: 0;
}

.security-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--na-text-primary);
  margin-bottom: 2px;
}

.security-desc {
  font-size: 12px;
  color: var(--na-text-placeholder);
}

.security-arrow {
  font-size: 16px;
  color: var(--na-text-placeholder);
  transition: transform 0.2s ease;
}

.security-item:hover .security-arrow {
  transform: translateX(2px);
  color: var(--na-primary);
}

/* ============== 响应式 ============== */
@media (max-width: 768px) {
  .welcome-banner {
    padding: 24px 20px;
  }

  .welcome-title {
    font-size: 20px;
  }

  .welcome-content {
    flex-direction: column;
    text-align: center;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
