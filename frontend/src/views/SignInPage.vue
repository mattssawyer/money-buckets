<script setup lang="ts">
import { SignInButton, SignUpButton, useAuth } from '@clerk/vue'
import Button from 'primevue/button'

const { isLoaded } = useAuth()
const logoUrl = `${import.meta.env.BASE_URL}logo.svg`
</script>

<template>
  <main class="sign-in-page" aria-labelledby="sign-in-title">
    <div class="sign-in-layout">
      <div class="sign-in-brand" aria-label="Money Buckets">
        <img :src="logoUrl" alt="" class="sign-in-logo" width="36" height="36" />
        <span>Money Buckets</span>
      </div>

      <section class="panel sign-in-card">
        <h1 id="sign-in-title">Your finances, together.</h1>
        <p class="sign-in-subtitle">Sign in or create an account to get started</p>
        <div class="sign-in-actions" :aria-busy="!isLoaded">
          <SignInButton>
            <Button label="Sign in" size="large" fluid :disabled="!isLoaded" :loading="!isLoaded" />
          </SignInButton>
          <p class="sign-in-secondary">New to Money Buckets?</p>
          <SignUpButton>
            <Button
              label="Create an account"
              severity="secondary"
              variant="outlined"
              size="large"
              fluid
              :disabled="!isLoaded"
            />
          </SignUpButton>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.sign-in-page {
  display: grid;
  min-height: 100vh;
  min-height: 100svh;
  place-items: center;
  padding: 2rem 1rem;
}

.sign-in-layout {
  width: min(100%, 26rem);
}

.sign-in-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  margin-bottom: 1.75rem;
  font-size: 1.0625rem;
}

.sign-in-logo {
  display: block;
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 0.5625rem;
}

.sign-in-brand span {
  font-weight: 550;
  letter-spacing: -0.035em;
}

.sign-in-card {
  padding: clamp(1.5rem, 5vw, 2.25rem);
}

h1 {
  font-size: clamp(1.5rem, 5vw, 1.75rem);
  font-weight: 550;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.sign-in-subtitle {
  margin-top: 0.5rem;
  color: var(--app-text-secondary);
  line-height: 1.5;
}

.sign-in-actions {
  display: grid;
  gap: 0.75rem;
  padding-top: 1.5rem;
}

.sign-in-secondary {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 0.75rem;
  margin: 0.25rem 0 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  text-align: center;
}

.sign-in-secondary::before,
.sign-in-secondary::after {
  content: '';
  height: 1px;
  background: var(--app-divider);
}
</style>
