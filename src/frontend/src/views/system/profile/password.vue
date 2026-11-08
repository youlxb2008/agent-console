<!--
  修改密码
  ----------
  参照 NodeAdmin 风格改造：
  - 新密码要求 >=8 位
  - 不能与旧密码相同校验
  - 修改成功后：清空所有 store + 重置动态路由 + 跳转登录页
  - CSS 使用普通 CSS（不用 SCSS）
-->
<template>
  <div class="na-page">
    <PageHeader title="修改密码" />

    <!-- 强制改密引导（醒目提示 + 步骤引导） -->
    <div v-if="userStore.mustChangePassword" class="force-change-guide">
      <div class="guide-icon">
        <el-icon :size="40"><Warning /></el-icon>
      </div>
      <div class="guide-content">
        <div class="guide-title">首次登录，请修改初始密码</div>
        <div class="guide-desc">您的账号使用的是系统初始密码，为了账号安全，请先修改密码后才能继续使用系统。</div>
        <div class="guide-steps">
          <span class="guide-step"> <span class="step-num">1</span>输入当前密码 </span>
          <span class="guide-arrow">→</span>
          <span class="guide-step"> <span class="step-num">2</span>设置新密码 </span>
          <span class="guide-arrow">→</span>
          <span class="guide-step"> <span class="step-num">3</span>确认并提交 </span>
        </div>
      </div>
    </div>

    <!-- 主动改密时给一个轻提示，避免用户改密码时不知道要点什么 -->
    <div v-else class="normal-change-hint">
      <el-icon><InfoFilled /></el-icon>
      <span>为了账号安全，建议定期修改密码（≥ 8 位，包含字母数字符号）</span>
    </div>

    <el-card shadow="never" class="password-card" :class="{ 'force-mode': userStore.mustChangePassword }">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" style="max-width: 500px">
        <el-form-item label="旧密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入旧密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="新密码 ≥ 8 位" />
          <div class="field-hint">建议使用字母、数字、符号组合，长度 ≥ 8 位</div>
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit"> 修改密码 </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Warning, InfoFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { post } from '@/api/request'
import { useUserStore } from '@/store/user'
import { usePermissStore } from '@/store/permiss'
import { useMenuStore } from '@/store/menu'
import { useTagsStore } from '@/store/tags'
import { resetRouter, setAuthenticated } from '@/router'

const router = useRouter()
const userStore = useUserStore()
const permissStore = usePermissStore()
const menuStore = useMenuStore()
const tagsStore = useTagsStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)

/** 表单模型 */
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

/**
 * 表单校验规则
 * - newPassword 校验：8 位以上，且不能与 oldPassword 相同
 * - confirmPassword 校验：与 newPassword 一致
 */
const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '新密码至少 8 位', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        // 与旧密码相同时拒绝（避免无效修改）
        if (value && value === form.oldPassword) {
          callback(new Error('新密码不能与旧密码相同'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

/**
 * 提交修改密码
 * - 强制改密场景：清除标记后跳首页，保持当前会话
 * - 主动改密场景：清空 store 与动态路由，跳转登录页重新登录
 */
async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await post('/system/user/update-password', {
      oldPassword: form.oldPassword,
      newPassword: form.newPassword
    })
    // 判断是否为强制改密场景
    if (userStore.mustChangePassword) {
      // 强制改密：清除标记，继续使用当前会话
      userStore.mustChangePassword = false
      ElMessage.success('密码修改成功')
      router.push('/')
    } else {
      // 主动改密：需要重新登录
      ElMessage.success('密码修改成功，请重新登录')
      userStore.clear()
      permissStore.clear()
      menuStore.clear()
      tagsStore.clear()
      resetRouter()
      setAuthenticated(false)
      router.push('/login')
    }
  } catch (err: unknown) {
    // 错误由 request.ts 拦截器统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.password-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.password-card :deep(.el-card__body) {
  padding: 24px;
}

/* 强制改密模式下卡片加主题色左边框 */
.password-card.force-mode {
  border-left: 3px solid var(--na-primary);
}

/* ===== 强制改密引导卡片 ===== */
.force-change-guide {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px;
  margin-bottom: 16px;
  background: linear-gradient(135deg, var(--na-primary-light-9) 0%, var(--na-primary-light-8) 100%);
  border: 1px solid var(--na-primary-light-8);
  border-radius: 8px;
  border-left: 3px solid var(--na-primary);
}

.guide-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: var(--na-primary-light-8);
  color: var(--na-primary);
}

.guide-content {
  flex: 1;
  min-width: 0;
}

.guide-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 6px;
}

.guide-desc {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-bottom: 12px;
}

.guide-steps {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.guide-step {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--na-primary);
  color: #ffffff;
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.guide-arrow {
  color: var(--el-text-color-placeholder);
  font-size: 14px;
}

/* ===== 主动改密轻提示 ===== */
.normal-change-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 16px;
  padding: 10px 14px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  border-left: 3px solid var(--na-primary);
}

.normal-change-hint .el-icon {
  color: var(--na-primary);
  font-size: 14px;
}

.field-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  line-height: 1.5;
}
</style>
