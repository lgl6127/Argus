<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  acceptInvitation,
  approveJoinRequest,
  createGroup,
  createInvitation,
  deleteGroups,
  fetchMyJoinRequests,
  fetchMySentInvitations,
  fetchGroupMembers,
  fetchGroups,
  fetchOwnerJoinRequests,
  leaveGroup,
  rejectInvitation,
  rejectJoinRequest,
  removeGroupMember,
  submitJoinRequest,
  type GroupItem,
  type GroupMemberItem,
  type JoinRequestItem,
  type MySentInvitationItem,
  type OwnerJoinRequestItem,
} from '@/api/group'
import { extractApiError } from '@/api/http'
import PageHeaderHero from '@/components/layout/PageHeaderHero.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import GroupStatsBar from './components/GroupStatsBar.vue'
import InvitationsTab from './components/InvitationsTab.vue'
import OwnedGroupsTab from './components/OwnedGroupsTab.vue'
import JoinedGroupsTab from './components/JoinedGroupsTab.vue'
import MyRequestsTab from './components/MyRequestsTab.vue'
import CreateGroupModal from './components/modals/CreateGroupModal.vue'
import JoinGroupModal from './components/modals/JoinGroupModal.vue'
import GroupManageModal from './components/modals/GroupManageModal.vue'

const appStore = useAppStore()
const authStore = useAuthStore()

// ── Core data ──
const ownedGroups = computed(() => appStore.ownedGroups)
const joinedGroups = computed(() => appStore.joinedGroups)
const pendingInvitations = computed(() => appStore.pendingInvitations)
const myJoinRequests = ref<JoinRequestItem[]>([])
const mySentInvitations = ref<MySentInvitationItem[]>([])

// ── Lazily loaded data ──
const groupMembers = ref<GroupMemberItem[]>([])
const ownerJoinRequests = ref<OwnerJoinRequestItem[]>([])

// ── UI state ──
const activeTab = ref<'invitations' | 'owned' | 'joined' | 'requests'>('invitations')
const isInitialLoading = ref(true)
const feedback = ref('')
const error = ref('')

// ── Modal visibility ──
const showCreateModal = ref(false)
const showJoinModal = ref(false)
const showManageModal = ref(false)
const manageMode = ref<'owner' | 'member'>('member')
const focusedGroup = ref<GroupItem | null>(null)

// ── Action loading ──
const actionIds = reactive({
  invitation: new Set<number>(),
  joinRequest: new Set<number>(),
  leaving: new Set<number>(),
  removing: new Set<string>(),
})
const isCreating = ref(false)
const isJoining = ref(false)
const isInviting = ref(false)
const isMembersLoading = ref(false)
const isRequestsLoading = ref(false)
const isMyRequestsLoading = ref(false)

// ── 删除小组（选择模式 + 单个/批量删除）──
const isDeleteMode = ref(false)
const selectedDeleteIds = ref<Set<number>>(new Set())
const isDeleting = ref(false)
const selectedDeleteGroups = computed(() =>
  ownedGroups.value.filter((g) => selectedDeleteIds.value.has(g.groupId)),
)
const canSubmitDelete = computed(() => !isDeleting.value && selectedDeleteIds.value.size > 0)

// 小组列表变化后剔除已不存在的勾选，避免提交无效 ID
watch(ownedGroups, (groups) => {
  if (selectedDeleteIds.value.size === 0) return
  const validIds = new Set(groups.map((g) => g.groupId))
  const pruned = new Set([...selectedDeleteIds.value].filter((id) => validIds.has(id)))
  if (pruned.size !== selectedDeleteIds.value.size) {
    selectedDeleteIds.value = pruned
  }
})

const currentUserLabel = computed(() => authStore.currentUser?.displayName ?? '用户')
const currentUserIdLabel = computed(() => authStore.currentUser?.userId?.toString() ?? '--')
const totalRequestCount = computed(() => myJoinRequests.value.length + mySentInvitations.value.length)

function computeDefaultTab() {
  if (pendingInvitations.value.length > 0) return 'invitations'
  if (ownedGroups.value.length > 0) return 'owned'
  return 'joined'
}

// ── Lifecycle ──
watch(
  () => authStore.currentUser?.userId,
  () => {
    exitDeleteMode()
    void loadInitialData()
  },
  { immediate: true },
)

async function loadInitialData() {
  isInitialLoading.value = true
  error.value = ''
  try {
    const [result, requests, sent] = await Promise.all([fetchGroups(), fetchMyJoinRequests(), fetchMySentInvitations()])
    appStore.applyGroupQueryResult(result)
    myJoinRequests.value = requests
    mySentInvitations.value = sent
    activeTab.value = computeDefaultTab()
  } catch (err) {
    error.value = extractApiError(err, '加载协作小组数据失败')
  } finally {
    isInitialLoading.value = false
  }
}

async function refreshWorkspace() {
  try {
    const [result, requests, sent] = await Promise.all([fetchGroups(), fetchMyJoinRequests(), fetchMySentInvitations()])
    appStore.applyGroupQueryResult(result)
    myJoinRequests.value = requests
    mySentInvitations.value = sent
  } catch (err) {
    error.value = extractApiError(err, '刷新数据失败')
  }
}

// ── Tab switching ──
function handleSelectTab(tab: string) {
  activeTab.value = tab as 'invitations' | 'owned' | 'joined' | 'requests'
  feedback.value = ''
  error.value = ''
  // 选择模式仅适用于「我拥有的组」，切换到其他 tab 时退出
  if (isDeleteMode.value) exitDeleteMode()
}

// ── Invitations ──
async function handleAcceptInvitation(invitationId: number) {
  const invitation = pendingInvitations.value.find((i) => i.invitationId === invitationId)
  actionIds.invitation.add(invitationId)
  error.value = ''
  feedback.value = ''
  try {
    await acceptInvitation(invitationId)
    feedback.value = '已接受邀请，该小组已加入「我加入的组」。'
    await refreshWorkspace()
    if (invitation && joinedGroups.value.length > 0) {
      activeTab.value = 'joined'
    }
  } catch (err) {
    error.value = extractApiError(err, '接受邀请失败')
  } finally {
    actionIds.invitation.delete(invitationId)
  }
}

async function handleRejectInvitation(invitationId: number) {
  actionIds.invitation.add(invitationId)
  error.value = ''
  feedback.value = ''
  try {
    await rejectInvitation(invitationId)
    feedback.value = '已拒绝邀请。'
    await refreshWorkspace()
  } catch (err) {
    error.value = extractApiError(err, '拒绝邀请失败')
  } finally {
    actionIds.invitation.delete(invitationId)
  }
}

// ── Create group ──
async function handleCreateGroup(payload: { name: string; description?: string }) {
  isCreating.value = true
  error.value = ''
  feedback.value = ''
  try {
    const groupId = await createGroup(payload)
    showCreateModal.value = false
    await refreshWorkspace()
    activeTab.value = 'owned'
    feedback.value = `已创建小组「${payload.name}」。`
    appStore.setCurrentGroupId(groupId)
  } catch (err) {
    error.value = extractApiError(err, '创建小组失败')
  } finally {
    isCreating.value = false
  }
}

// ── Join group ──
async function handleSubmitJoinRequest(groupCode: string) {
  isJoining.value = true
  error.value = ''
  feedback.value = ''
  try {
    await submitJoinRequest(groupCode)
    showJoinModal.value = false
    myJoinRequests.value = await fetchMyJoinRequests()
    feedback.value = `已提交加入申请（组织 ID: ${groupCode}），等待管理员审批。`
  } catch (err) {
    error.value = extractApiError(err, '提交加入申请失败')
  } finally {
    isJoining.value = false
  }
}

// ── Manage group ──
async function openManageModal(groupId: number, mode: 'owner' | 'member') {
  const group = mode === 'owner'
    ? ownedGroups.value.find((g) => g.groupId === groupId)
    : joinedGroups.value.find((g) => g.groupId === groupId)
  if (!group) return

  focusedGroup.value = group
  manageMode.value = mode
  showManageModal.value = true
  groupMembers.value = []
  ownerJoinRequests.value = []

  if (mode === 'owner') {
    await Promise.all([loadMembers(groupId), loadJoinRequests(groupId)])
  }
}

async function loadMembers(groupId: number) {
  isMembersLoading.value = true
  try {
    groupMembers.value = await fetchGroupMembers(groupId)
  } catch (err) {
    groupMembers.value = []
    error.value = extractApiError(err, '加载成员失败')
  } finally {
    isMembersLoading.value = false
  }
}

async function loadJoinRequests(groupId: number) {
  isRequestsLoading.value = true
  try {
    ownerJoinRequests.value = await fetchOwnerJoinRequests(groupId)
  } catch (err) {
    ownerJoinRequests.value = []
    error.value = extractApiError(err, '加载加入申请失败')
  } finally {
    isRequestsLoading.value = false
  }
}

// ── Invite member ──
async function handleInviteMember(userId: number) {
  if (!focusedGroup.value) return
  isInviting.value = true
  error.value = ''
  feedback.value = ''
  try {
    await createInvitation(focusedGroup.value.groupId, userId)
    feedback.value = `已向用户 #${userId} 发出邀请。`
  } catch (err) {
    error.value = extractApiError(err, '发起邀请失败')
  } finally {
    isInviting.value = false
  }
}

// ── Remove member ──
async function handleRemoveMember(userId: number) {
  if (!focusedGroup.value) return
  const key = `${focusedGroup.value.groupId}:${userId}`
  actionIds.removing.add(key)
  error.value = ''
  feedback.value = ''
  try {
    await removeGroupMember(focusedGroup.value.groupId, userId)
    await loadMembers(focusedGroup.value.groupId)
    feedback.value = `已移除成员 #${userId}。`
    await refreshWorkspace()
  } catch (err) {
    error.value = extractApiError(err, '移除成员失败')
  } finally {
    actionIds.removing.delete(key)
  }
}

// ── Join requests ──
async function handleApproveRequest(requestId: number) {
  if (!focusedGroup.value) return
  actionIds.joinRequest.add(requestId)
  error.value = ''
  feedback.value = ''
  try {
    await approveJoinRequest(focusedGroup.value.groupId, requestId)
    await loadJoinRequests(focusedGroup.value.groupId)
    await loadMembers(focusedGroup.value.groupId)
    feedback.value = '已通过加入申请。'
    await refreshWorkspace()
  } catch (err) {
    error.value = extractApiError(err, '通过申请失败')
  } finally {
    actionIds.joinRequest.delete(requestId)
  }
}

async function handleRejectRequest(requestId: number) {
  if (!focusedGroup.value) return
  actionIds.joinRequest.add(requestId)
  error.value = ''
  feedback.value = ''
  try {
    await rejectJoinRequest(focusedGroup.value.groupId, requestId)
    await loadJoinRequests(focusedGroup.value.groupId)
    feedback.value = '已拒绝加入申请。'
  } catch (err) {
    error.value = extractApiError(err, '拒绝申请失败')
  } finally {
    actionIds.joinRequest.delete(requestId)
  }
}

// ── Leave group ──
async function handleLeaveGroup(groupId: number) {
  const group = joinedGroups.value.find((g) => g.groupId === groupId)
  if (!group) return

  try {
    await ElMessageBox.confirm(
      `确定要退出「${group.groupName}」吗？退出后你将无法访问该小组的文档和问答。`,
      '确认退出',
      { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return // user cancelled
  }

  actionIds.leaving.add(groupId)
  error.value = ''
  feedback.value = ''
  try {
    await leaveGroup(groupId)
    showManageModal.value = false
    feedback.value = `已退出小组「${group.groupName}」。`
    await refreshWorkspace()
  } catch (err) {
    error.value = extractApiError(err, '退出小组失败')
  } finally {
    actionIds.leaving.delete(groupId)
  }
}

// ── Delete groups ──
function exitDeleteMode() {
  isDeleteMode.value = false
  selectedDeleteIds.value = new Set()
}

function toggleDeleteMode() {
  if (isDeleteMode.value) {
    exitDeleteMode()
    return
  }
  isDeleteMode.value = true
  selectedDeleteIds.value = new Set()
  error.value = ''
  feedback.value = ''
  // 选择模式作用于「我拥有的组」，切换 tab 保证勾选目标合法
  activeTab.value = 'owned'
}

function toggleGroupSelection(groupId: number) {
  const next = new Set(selectedDeleteIds.value)
  if (next.has(groupId)) {
    next.delete(groupId)
  } else {
    next.add(groupId)
  }
  selectedDeleteIds.value = next
}

function selectAllOwnedGroups() {
  // 全选 / 已全选时再点击则清空
  if (selectedDeleteIds.value.size > 0 && selectedDeleteIds.value.size === ownedGroups.value.length) {
    selectedDeleteIds.value = new Set()
  } else {
    selectedDeleteIds.value = new Set(ownedGroups.value.map((g) => g.groupId))
  }
}

async function handleConfirmDelete() {
  await deleteGroupsByIds(selectedDeleteGroups.value)
}

/** 管理弹窗内删除当前聚焦小组（单个删除） */
async function handleDeleteFocusedGroup() {
  if (!focusedGroup.value) return
  const owned = ownedGroups.value.find((g) => g.groupId === focusedGroup.value!.groupId)
  if (!owned) {
    error.value = '仅小组所有者可以删除该小组'
    return
  }
  await deleteGroupsByIds([owned])
}

/** 删除小组公共流程：确认弹窗 → 调用接口 → 刷新列表/统计/可访问小组状态 */
async function deleteGroupsByIds(groups: GroupItem[]) {
  if (groups.length === 0 || isDeleting.value) return

  const names = groups.map((g) => `「${g.groupName}」`).join('、')
  try {
    await ElMessageBox.confirm(
      `确定要删除 ${groups.length} 个小组：${names} 吗？删除后小组成员将失去对该小组文档和问答的访问，此操作不可撤销。`,
      '确认删除小组',
      {
        confirmButtonText: '删除',
        confirmButtonClass: 'el-button--danger',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return // user cancelled
  }

  isDeleting.value = true
  error.value = ''
  feedback.value = ''
  try {
    await deleteGroups(groups.map((g) => g.groupId))
    exitDeleteMode()
    // 刷新小组列表、统计信息与可访问小组状态（currentGroupId 由 applyGroupQueryResult 重算）
    await refreshWorkspace()
    if (
      showManageModal.value &&
      focusedGroup.value &&
      !ownedGroups.value.some((g) => g.groupId === focusedGroup.value!.groupId) &&
      !joinedGroups.value.some((g) => g.groupId === focusedGroup.value!.groupId)
    ) {
      showManageModal.value = false
    }
    feedback.value = `已删除小组：${names}。`
  } catch (err) {
    error.value = extractApiError(err, '删除小组失败')
  } finally {
    isDeleting.value = false
  }
}
</script>

<template>
  <div class="groups-page">
    <!-- Hero -->
    <PageHeaderHero
      eyebrow="协作小组"
      title="我的组"
      :description="isInitialLoading ? '正在加载协作空间数据...' : '管理你的知识库协作空间，创建或加入小组进行文档协同'"
    >
      <template #actions>
        <div class="groups-page__identity">
          <span>{{ currentUserLabel }}</span>
          <small>ID: {{ currentUserIdLabel }}</small>
        </div>
      </template>
    </PageHeaderHero>

    <!-- Feedback -->
    <div v-if="feedback || error" class="groups-feedback">
      <p v-if="feedback" class="feedback feedback--success">{{ feedback }}</p>
      <p v-if="error" class="feedback feedback--error">{{ error }}</p>
    </div>

    <!-- Loading -->
    <div v-if="isInitialLoading" class="groups-loading">
      <div class="spinner"></div>
      <p>正在加载协作空间...</p>
    </div>

    <template v-else>
      <!-- Stats bar -->
      <GroupStatsBar
        :owned-count="ownedGroups.length"
        :joined-count="joinedGroups.length"
        :invitation-count="pendingInvitations.length"
        :request-count="totalRequestCount"
        :active-tab="activeTab"
        @select-tab="handleSelectTab"
      />

      <!-- Layout Header (Tabs + Toolbar) -->
      <div class="groups-layout-header">
        <!-- Tabs -->
        <div class="groups-tabs">
          <button
            class="tab-btn"
            :class="{ 'is-active': activeTab === 'owned' }"
            type="button"
            @click="handleSelectTab('owned')"
          >
            我拥有的组
            <span class="tab-btn__count">{{ ownedGroups.length }}</span>
          </button>
          <button
            class="tab-btn"
            :class="{ 'is-active': activeTab === 'joined' }"
            type="button"
            @click="handleSelectTab('joined')"
          >
            我加入的组
            <span class="tab-btn__count">{{ joinedGroups.length }}</span>
          </button>
          <button
            class="tab-btn"
            :class="{ 'is-active': activeTab === 'invitations' }"
            type="button"
            @click="handleSelectTab('invitations')"
          >
            待处理邀请
            <span v-if="pendingInvitations.length > 0" class="tab-btn__count tab-btn__count--warn">
              {{ pendingInvitations.length }}
            </span>
          </button>
          <button
            class="tab-btn"
            :class="{ 'is-active': activeTab === 'requests' }"
            type="button"
            @click="handleSelectTab('requests')"
          >
            我的申请
            <span class="tab-btn__count">{{ totalRequestCount }}</span>
          </button>
        </div>

        <!-- Toolbar Actions -->
        <div class="groups-toolbar">
          <button
            class="modern-action-btn modern-action-btn--danger"
            :class="{ 'is-active': isDeleteMode }"
            type="button"
            :disabled="ownedGroups.length === 0"
            @click="toggleDeleteMode"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
            </svg>
            {{ isDeleteMode ? '退出删除' : '删除小组' }}
          </button>
          <button class="modern-action-btn modern-action-btn--primary" @click="showCreateModal = true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4" />
            </svg>
            创建小组
          </button>
          <button class="modern-action-btn" @click="showJoinModal = true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
            </svg>
            加入小组
          </button>
        </div>
      </div>

      <!-- 删除选择模式操作栏 -->
      <div v-if="isDeleteMode" class="groups-delete-bar">
        <span
          class="groups-delete-bar__check"
          role="checkbox"
          :aria-checked="ownedGroups.length > 0 && selectedDeleteIds.size === ownedGroups.length"
          @click="selectAllOwnedGroups"
        >
          <!-- 受控复选框：pointer-events 交给容器处理，状态由 selectedDeleteIds 驱动 -->
          <input
            type="checkbox"
            :checked="ownedGroups.length > 0 && selectedDeleteIds.size === ownedGroups.length"
            :tabindex="-1"
            readonly
          />
          全选我拥有的小组
        </span>
        <span class="groups-delete-bar__count">已选择 {{ selectedDeleteIds.size }} 个</span>
        <div class="groups-delete-bar__actions">
          <button class="modern-action-btn" type="button" @click="exitDeleteMode">取消</button>
          <button
            class="modern-action-btn modern-action-btn--danger is-solid"
            type="button"
            :disabled="!canSubmitDelete"
            @click="handleConfirmDelete"
          >
            {{ isDeleting ? '删除中…' : `确认删除（${selectedDeleteIds.size}）` }}
          </button>
        </div>
      </div>

      <!-- Tab content -->
      <div class="groups-tab-content">
        <InvitationsTab
          v-if="activeTab === 'invitations'"
          :items="pendingInvitations"
          :action-ids="actionIds.invitation"
          @accept="handleAcceptInvitation"
          @reject="handleRejectInvitation"
        />
        <OwnedGroupsTab
          v-if="activeTab === 'owned'"
          :items="ownedGroups"
          :selection-mode="isDeleteMode"
          :selected-ids="selectedDeleteIds"
          @manage="(id: number) => openManageModal(id, 'owner')"
          @toggle-select="toggleGroupSelection"
        />
        <JoinedGroupsTab
          v-if="activeTab === 'joined'"
          :items="joinedGroups"
          :leaving-ids="actionIds.leaving"
          @view="(id: number) => openManageModal(id, 'member')"
          @leave="handleLeaveGroup"
        />
        <MyRequestsTab
          v-if="activeTab === 'requests'"
          :items="myJoinRequests"
          :sent-invitations="mySentInvitations"
        />
      </div>
    </template>

    <!-- Modals -->
    <CreateGroupModal
      v-model:visible="showCreateModal"
      :loading="isCreating"
      @submit="handleCreateGroup"
    />
    <JoinGroupModal
      v-model:visible="showJoinModal"
      :loading="isJoining"
      @submit="handleSubmitJoinRequest"
    />
    <GroupManageModal
      v-model:visible="showManageModal"
      :mode="manageMode"
      :group="focusedGroup"
      :members="groupMembers"
      :requests="ownerJoinRequests"
      :is-members-loading="isMembersLoading"
      :is-requests-loading="isRequestsLoading"
      :is-inviting="isInviting"
      :is-deleting="isDeleting"
      :removing-keys="actionIds.removing"
      :request-action-ids="actionIds.joinRequest"
      @remove-member="handleRemoveMember"
      @approve-request="handleApproveRequest"
      @reject-request="handleRejectRequest"
      @invite-member="handleInviteMember"
      @delete-group="handleDeleteFocusedGroup"
      @leave-group="() => { if (focusedGroup) handleLeaveGroup(focusedGroup.groupId) }"
    />
  </div>
</template>

<style scoped>
/* Push the hero further apart from stats for clear visual hierarchy */
.groups-page :deep(.page-header-hero) {
  margin-bottom: 36px;
}

/* Feedback */
.groups-feedback {
  margin-bottom: 8px;
}

.feedback {
  margin: 0 0 8px;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
  line-height: 1.5;
}

.feedback--success {
  background: rgba(16, 185, 129, 0.08);
  color: #10b981;
  border: 1px solid rgba(16, 185, 129, 0.15);
}

.feedback--error {
  background: rgba(239, 68, 68, 0.08);
  color: #ef4444;
  border: 1px solid rgba(239, 68, 68, 0.15);
}

/* Loading */
.groups-loading {
  text-align: center;
  padding: 80px 24px;
}

.groups-loading p {
  margin-top: 16px;
  font-size: 0.9rem;
  color: var(--text-muted);
}

.spinner {
  width: 32px;
  height: 32px;
  margin: 0 auto;
  border: 3px solid var(--surface-muted);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Identity */
.groups-page__identity {
  text-align: right;
  line-height: 1.4;
}

.groups-page__identity span {
  display: block;
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--text-primary);
}

.groups-page__identity small {
  font-size: 0.74rem;
  color: var(--text-muted);
}

/* Layout Header */
.groups-layout-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  border-bottom: 2px solid var(--border-default);
  margin-bottom: 28px;
}

/* Toolbar */
.groups-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

/* Modern Action Buttons */
.modern-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  font-size: 0.9rem;
  font-weight: 600;
  border: 1px solid var(--border-default);
  background: var(--surface-white);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
}

.modern-action-btn:hover {
  color: var(--text-primary);
  border-color: var(--text-muted);
  background: var(--surface-subtle);
}

.modern-action-btn--primary {
  background: var(--brand-primary);
  border-color: var(--brand-primary);
  color: #fff;
}

.modern-action-btn--primary:hover {
  background: var(--brand-primary-dark);
  border-color: var(--brand-primary-dark);
  color: #fff;
}

.modern-action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 危险操作按钮（删除小组） */
.modern-action-btn--danger {
  color: var(--el-color-danger, #ef4444);
  border-color: rgba(239, 68, 68, 0.35);
}

.modern-action-btn--danger:hover:not(:disabled) {
  color: var(--el-color-danger, #ef4444);
  border-color: var(--el-color-danger, #ef4444);
  background: rgba(239, 68, 68, 0.08);
}

.modern-action-btn--danger.is-active {
  background: rgba(239, 68, 68, 0.1);
  border-color: var(--el-color-danger, #ef4444);
}

.modern-action-btn--danger.is-solid {
  background: var(--el-color-danger, #ef4444);
  border-color: var(--el-color-danger, #ef4444);
  color: #fff;
}

.modern-action-btn--danger.is-solid:hover:not(:disabled) {
  background: #dc2626;
  border-color: #dc2626;
  color: #fff;
}

/* 删除选择模式操作栏 */
.groups-delete-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
  padding: 10px 16px;
  border: 1px solid rgba(239, 68, 68, 0.2);
  border-radius: var(--radius-sm);
  background: rgba(239, 68, 68, 0.05);
}

.groups-delete-bar__check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.86rem;
  font-weight: 600;
  color: var(--text-secondary);
  cursor: pointer;
  user-select: none;
}

.groups-delete-bar__check input[type='checkbox'] {
  width: 15px;
  height: 15px;
  margin: 0;
  accent-color: var(--el-color-danger, #ef4444);
  cursor: pointer;
  /* 点击事件统一由容器处理，避免 label 转发导致重复触发 */
  pointer-events: none;
}

.groups-delete-bar__count {
  font-size: 0.84rem;
  font-weight: 600;
  color: var(--text-primary);
}

.groups-delete-bar__actions {
  display: flex;
  gap: 10px;
  margin-left: auto;
}

/* Tabs */
.groups-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: -2px;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 14px 20px;
  border: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.95rem;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s ease;
}

.tab-btn:hover {
  color: var(--text-primary);
}

.tab-btn.is-active {
  color: var(--brand-primary);
  border-bottom-color: var(--brand-primary);
}

.tab-btn__count {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 700;
  background: var(--surface-muted);
  color: var(--text-secondary);
}

.tab-btn__count--warn {
  background: rgba(245, 158, 11, 0.12);
  color: #d97706;
}

.groups-tab-content {
  min-height: 200px;
}
</style>
