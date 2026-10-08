<script setup lang="ts">
import { UserButton, useClerk, useUser } from '@clerk/vue'
import { isAxiosError } from 'axios'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import AppSidebar from '../components/AppSidebar.vue'
import { deleteCurrentUser } from '../api/UserService'

const CONFIRMATION = 'delete'

const clerk = useClerk()
const { user } = useUser()

const email = computed(() => user.value?.primaryEmailAddress?.emailAddress ?? '')
const dialogVisible = ref(false)
const typed = ref('')
const deleting = ref(false)
const deleteError = ref('')

const confirmed = computed(() => typed.value.trim().toLowerCase() === CONFIRMATION)

function openDialog() {
  typed.value = ''
  deleteError.value = ''
  dialogVisible.value = true
}

async function deleteAccount() {
  if (!confirmed.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await deleteCurrentUser()
  } catch (error) {
    deleteError.value =
      isAxiosError(error) && error.response?.status === 503
        ? 'Deleting accounts isn’t set up on this server.'
        : 'We couldn’t finish deleting your account. Try again.'
    deleting.value = false
    return
  }
  // The sign-in is already gone; this only clears it from the browser.
  try {
    await clerk.value?.signOut()
  } catch {
    window.location.assign('/')
  }
}
</script>

<template>
  <div class="app-shell">
    <AppSidebar />
    <main class="settings-page" aria-labelledby="settings-heading">
      <header class="page-heading">
        <h1 id="settings-heading">Settings</h1>
        <UserButton />
      </header>

      <section class="panel settings-card" aria-labelledby="account-heading">
        <h2 id="account-heading" class="card-label">Your account</h2>
        <div class="setting-row">
          <div class="setting-text">
            <span class="setting-name">Email</span>
            <span class="setting-detail">{{ email || 'No email on file' }}</span>
          </div>
          <Button
            label="Manage"
            size="small"
            severity="secondary"
            outlined
            @click="clerk?.openUserProfile()"
          />
        </div>
        <div class="setting-row">
          <div class="setting-text">
            <span class="setting-name">Delete account</span>
            <span class="setting-detail">
              Disconnects your banks and permanently deletes your data.
            </span>
          </div>
          <Button
            label="Delete…"
            size="small"
            severity="danger"
            outlined
            aria-haspopup="dialog"
            @click="openDialog"
          />
        </div>
      </section>

      <nav class="legal-links" aria-label="Legal">
        <RouterLink to="/privacy">Privacy policy</RouterLink>
        <RouterLink to="/terms">Terms of service</RouterLink>
      </nav>
    </main>

    <Dialog
      v-model:visible="dialogVisible"
      modal
      header="Delete your account?"
      :closable="!deleting"
      :draggable="false"
      :style="{ width: 'min(28rem, calc(100vw - 2rem))' }"
    >
      <form class="delete-form" @submit.prevent="deleteAccount">
        <p>
          Your banks will be disconnected, and your accounts, transactions, spending plan and
          sign-in will be deleted right away. This can’t be undone.
        </p>
        <label for="delete-confirmation">Type “{{ CONFIRMATION }}” to confirm</label>
        <InputText
          id="delete-confirmation"
          v-model="typed"
          autocomplete="off"
          :disabled="deleting"
          autofocus
        />
        <p v-if="deleteError" class="delete-error" role="alert">{{ deleteError }}</p>
        <div class="delete-actions">
          <Button
            label="Cancel"
            severity="secondary"
            :disabled="deleting"
            @click="dialogVisible = false"
          />
          <Button
            type="submit"
            label="Delete everything"
            severity="danger"
            :loading="deleting"
            :disabled="!confirmed || deleting"
          />
        </div>
      </form>
    </Dialog>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  align-items: stretch;
  height: 100svh;
}

.settings-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1.25rem;
  min-width: 0;
  min-height: 0;
  padding: 1.25rem 1.5rem 1.5rem 0;
  overflow: auto;
}

.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

h1 {
  font-size: 1.5rem;
  font-weight: 550;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.page-heading :deep(.cl-userButtonTrigger),
.page-heading :deep(.cl-avatarBox) {
  width: 2rem;
  height: 2rem;
}

.settings-card {
  width: min(100%, 40rem);
  padding: 1rem 1.25rem 0.25rem;
}

.card-label {
  padding-bottom: 0.5rem;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
  letter-spacing: -0.005em;
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.875rem 0;
  border-top: 1px solid var(--app-divider);
}

.setting-text {
  display: grid;
  gap: 0.125rem;
  min-width: 0;
  font-size: 0.875rem;
}

.setting-name {
  font-weight: 500;
}

.setting-detail {
  overflow: hidden;
  color: var(--app-text-secondary);
  text-overflow: ellipsis;
}

.setting-row :deep(.p-button) {
  flex: none;
  white-space: nowrap;
}

.legal-links {
  display: flex;
  gap: 1.25rem;
  padding: 0 0.25rem;
  font-size: 0.8125rem;
}

.legal-links a {
  color: var(--app-text-secondary);
}

.legal-links a:hover {
  color: var(--app-text);
}

.delete-form {
  display: grid;
  gap: 0.75rem;
  line-height: 1.6;
}

.delete-form label {
  margin-top: 0.25rem;
  font-weight: 500;
}

.delete-error {
  color: var(--app-danger);
}

.delete-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

@media (max-width: 900px) {
  .settings-page {
    padding: 1.25rem 1.25rem calc(var(--app-tabbar-height) + env(safe-area-inset-bottom) + 1.5rem);
  }

  h1 {
    font-size: 1.375rem;
  }
}
</style>
