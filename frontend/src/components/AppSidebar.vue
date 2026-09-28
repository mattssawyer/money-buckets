<script setup lang="ts">
import { House, TrendingUp, Wallet } from '@lucide/vue'
import { RouterLink } from 'vue-router'

const logoUrl = `${import.meta.env.BASE_URL}logo.svg`

const items = [
  { name: 'Home', to: '/', icon: House },
  { name: 'Spending Plan', to: '/spending-plan', icon: Wallet },
  { name: 'Investments', to: '/investments', icon: TrendingUp },
]
</script>

<template>
  <nav class="rail" aria-label="Main navigation">
    <RouterLink to="/" class="rail-brand" aria-label="Money Buckets home">
      <img :src="logoUrl" alt="" width="36" height="36" />
    </RouterLink>

    <ul class="rail-items">
      <li v-for="item in items" :key="item.name">
        <RouterLink :to="item.to" class="rail-item" exact-active-class="rail-item-active">
          <span class="rail-indicator" aria-hidden="true" />
          <span class="rail-icon">
            <component :is="item.icon" :size="18" :stroke-width="1.75" aria-hidden="true" />
          </span>
          <span class="rail-label">{{ item.name }}</span>
        </RouterLink>
      </li>
    </ul>
  </nav>
</template>

<style scoped>
.rail {
  position: sticky;
  top: 0;
  display: flex;
  flex: none;
  flex-direction: column;
  width: var(--app-rail-width);
  height: 100svh;
  padding: 1rem 0;
}

.rail-brand {
  display: grid;
  width: 2.25rem;
  height: 2.25rem;
  margin: 0 auto 0.75rem;
  place-items: center;
  border-radius: var(--app-radius-control);
}

.rail-brand img {
  display: block;
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 0.5625rem;
}

.rail-items {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.rail-item {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: auto auto;
  row-gap: 0.25rem;
  padding: 0 0 0.25rem;
  color: var(--app-text-secondary);
  text-decoration: none;
  border-radius: var(--app-radius-chip);
}

.rail-indicator {
  position: absolute;
  top: 0.5rem;
  left: 0;
  width: 0.25rem;
  height: 1rem;
  background: transparent;
  border-radius: 0 2px 2px 0;
}

.rail-icon {
  display: grid;
  width: 2rem;
  height: 2rem;
  margin: 0 auto;
  place-items: center;
  border-radius: var(--app-radius-chip);
  transition: background-color 120ms ease;
}

.rail-icon :deep(svg) {
  width: 1.125rem;
  height: 1.125rem;
}

.rail-item:hover .rail-icon {
  background: var(--app-inset);
}

.rail-item-active {
  color: var(--app-text);
}

.rail-item-active .rail-indicator {
  background: var(--app-text);
}

.rail-item-active .rail-icon {
  background: var(--app-surface);
  box-shadow: var(--app-shadow-xs);
}

.rail-label {
  min-width: 0;
  padding: 0 0.25rem;
  font-size: 0.6875rem;
  font-weight: 500;
  line-height: 1.2;
  text-align: center;
  white-space: nowrap;
}

.rail-item:focus-visible {
  outline-offset: -2px;
}

@media (max-width: 900px) {
  .rail {
    position: fixed;
    inset: auto 0 0 0;
    z-index: 10;
    flex-direction: row;
    justify-content: space-around;
    width: auto;
    height: calc(var(--app-tabbar-height) + env(safe-area-inset-bottom));
    padding: 0 0 env(safe-area-inset-bottom);
    background: rgb(247 247 247 / 86%);
    border-top: 1px solid var(--app-divider);
    backdrop-filter: saturate(180%) blur(20px);
    -webkit-backdrop-filter: saturate(180%) blur(20px);
  }

  .rail-brand {
    display: none;
  }

  .rail-items {
    display: flex;
    flex: 1;
    justify-content: space-around;
    gap: 0;
  }

  .rail-item {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: 0.25rem auto auto;
    row-gap: 0.25rem;
    min-width: 4rem;
    padding: 0 0 0.5rem;
  }

  .rail-indicator {
    position: static;
    grid-row: 1;
    grid-column: 1;
    width: 1rem;
    height: 0.25rem;
    margin: 0 auto;
    border-radius: 0 0 2px 2px;
  }

  .rail-icon {
    grid-row: 2;
    grid-column: 1;
  }

  .rail-label {
    grid-column: 1;
    padding: 0;
  }
}
</style>
