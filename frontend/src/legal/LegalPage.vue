<script setup lang="ts">
import { RouterLink } from 'vue-router'

defineProps<{
  title: string
  /** When the text last changed, e.g. "October 8, 2026". */
  updated: string
}>()

const logoUrl = `${import.meta.env.BASE_URL}logo.svg`
</script>

<template>
  <div class="legal-page">
    <header class="legal-header">
      <RouterLink to="/" class="legal-brand">
        <img :src="logoUrl" alt="" width="28" height="28" />
        <span>Money Buckets</span>
      </RouterLink>
    </header>

    <main class="legal-main" aria-labelledby="legal-title">
      <h1 id="legal-title">{{ title }}</h1>
      <p class="legal-updated">Last updated {{ updated }}</p>
      <div class="legal-body">
        <slot />
      </div>
    </main>

    <footer class="legal-footer">
      <RouterLink to="/privacy">Privacy policy</RouterLink>
      <RouterLink to="/terms">Terms of service</RouterLink>
    </footer>
  </div>
</template>

<style scoped>
.legal-page {
  width: min(100%, 42rem);
  margin: 0 auto;
  padding: 1.5rem 1.25rem 3rem;
}

.legal-brand {
  display: inline-flex;
  align-items: center;
  gap: 0.625rem;
  color: var(--app-text);
  font-weight: 550;
  letter-spacing: -0.035em;
  text-decoration: none;
}

.legal-brand img {
  display: block;
  border-radius: 0.4375rem;
}

.legal-main {
  padding-top: 3rem;
}

h1 {
  font-size: clamp(1.75rem, 5vw, 2.25rem);
  font-weight: 550;
  line-height: 1.15;
  letter-spacing: -0.04em;
}

.legal-updated {
  margin-top: 0.5rem;
  color: var(--app-text-secondary);
  font-size: 0.875rem;
}

.legal-body {
  padding-top: 1.5rem;
  line-height: 1.7;
}

.legal-body :deep(h2) {
  margin: 2.25rem 0 0.5rem;
  font-size: 1.125rem;
  font-weight: 550;
  line-height: 1.35;
  letter-spacing: -0.02em;
}

.legal-body :deep(p),
.legal-body :deep(ul) {
  margin: 0 0 0.875rem;
}

.legal-body :deep(ul) {
  padding-left: 1.25rem;
}

.legal-body :deep(li) {
  margin-bottom: 0.375rem;
}

.legal-body :deep(a) {
  color: var(--app-text);
  text-decoration: underline;
  text-underline-offset: 0.15em;
}

.legal-footer {
  display: flex;
  gap: 1.25rem;
  margin-top: 3rem;
  padding-top: 1.25rem;
  border-top: 1px solid var(--app-divider);
  font-size: 0.875rem;
}

.legal-footer a {
  color: var(--app-text-secondary);
}

.legal-footer a:hover {
  color: var(--app-text);
}
</style>
