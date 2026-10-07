<script setup lang="ts">
import { UserButton, useClerk, useUser } from '@clerk/vue'
import { isAxiosError } from 'axios'
import { computed, ref } from 'vue'
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

      <section class="panel settings-section" aria-labelledby="sign-in-heading">
        <div class="section-text">
          <h2 id="sign-in-heading">Sign-in</h2>
          <p v-if="email">{{ email }}</p>
        </div>
        <Button label="Manage sign-in" severity="secondary" @click="clerk?.openUserProfile()" />
      </section>

      <section class="panel settings-section" aria-labelledby="delete-heading">
        <div class="section-text">
          <h2 id="delete-heading">Delete your account</h2>
          <p>
            Disconnects your banks and permanently deletes everything Money Buckets has stored about
            you, including your sign-in.
          </p>
        </div>
        <Button
          label="Delete account…"
          severity="danger"
          aria-haspopup="dialog"
          @click="openDialog"
        />
      </section>
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

.settings-section {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1.5rem;
  max-width: 48rem;
  padding: 1.25rem 1.5rem;
}

.section-text {
  min-width: 0;
}

h2 {
  font-size: 1rem;
  font-weight: 550;
  line-height: 1.4;
}

.section-text p {
  margin-top: 0.25rem;
  color: var(--app-text-secondary);
  line-height: 1.6;
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

@media (max-width: 640px) {
  .settings-section {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
