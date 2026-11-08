<!--
  在线设备管理
  ----------
  展示当前用户所有在线设备（refresh token 列表），
  支持踢下线指定设备（撤销 token）
-->
<template>
  <div class="na-page">
    <PageHeader
      title="在线设备"
      subtitle="管理您的登录设备，可踢下线可疑设备"
    />

    <el-card
      shadow="never"
      class="devices-card"
    >
      <div
        v-if="loading"
        style="text-align: center; padding: 40px"
      >
        <el-icon
          class="is-loading"
          :size="24"
        >
          <Loading />
        </el-icon>
      </div>

      <el-empty
        v-else-if="devices.length === 0"
        description="暂无在线设备"
      />

      <div
        v-else
        class="device-list"
      >
        <div
          v-for="device in devices"
          :key="device.id"
          class="device-item"
        >
          <div class="device-info">
            <div class="device-header">
              <el-icon
                :size="20"
                style="margin-right: 8px"
              >
                <Monitor v-if="device.deviceName === 'Chrome' || device.deviceName === 'Safari'" />
                <Iphone v-else-if="device.deviceName === 'iPhone'" />
                <Cellphone v-else-if="device.deviceName === 'Android'" />
                <Monitor v-else />
              </el-icon>
              <span class="device-name">{{ device.deviceName || '未知设备' }}</span>
              <el-tag
                v-if="device.current"
                type="success"
                size="small"
                style="margin-left: 8px"
              >
                当前设备
              </el-tag>
            </div>
            <div class="device-meta">
              <span>IP: {{ device.ipAddress || '未知' }}</span>
              <span>登录时间: {{ formatTime(device.createdAt) }}</span>
              <span>过期时间: {{ formatTime(device.expiresAt) }}</span>
            </div>
          </div>
          <div class="device-action">
            <el-popconfirm
              title="确定要踢下线该设备吗？"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleKick(device)"
            >
              <template #reference>
                <el-button
                  type="danger"
                  size="small"
                  :disabled="device.current"
                  plain
                >
                  踢下线
                </el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Monitor, Iphone, Cellphone, Loading } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { getOnlineDevices, kickDevice, type OnlineDevice } from '@/api/system/user'

const devices = ref<OnlineDevice[]>([])
const loading = ref(false)

/** 加载在线设备列表 */
async function loadDevices() {
  loading.value = true
  try {
    const res = await getOnlineDevices()
    devices.value = res.data || []
  } catch (e) {
    // 错误由拦截器处理
  } finally {
    loading.value = false
  }
}

/** 踢下线指定设备 */
async function handleKick(device: OnlineDevice) {
  try {
    await kickDevice(device.id)
    ElMessage.success(`已踢下线 ${device.deviceName || '未知设备'}`)
    // 刷新列表
    await loadDevices()
  } catch (e) {
    // 错误由拦截器处理
  }
}

/** 格式化时间 */
function formatTime(timeStr: string | undefined) {
  if (!timeStr) return '未知'
  try {
    const d = new Date(timeStr)
    return d.toLocaleString('zh-CN')
  } catch {
    return timeStr
  }
}

onMounted(() => {
  loadDevices()
})
</script>

<style scoped>
.devices-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
}

.device-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.device-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  transition: background-color 0.2s;
}

.device-item:hover {
  background-color: var(--el-fill-color-light);
}

.device-info {
  flex: 1;
}

.device-header {
  display: flex;
  align-items: center;
  margin-bottom: 6px;
}

.device-name {
  font-size: 15px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.device-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 28px;
}

.device-action {
  flex-shrink: 0;
  margin-left: 16px;
}
</style>
