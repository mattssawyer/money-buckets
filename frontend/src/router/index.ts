import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '../views/HomePage.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: HomePage },
    {
      path: '/spending-plan',
      name: 'spending-plan',
      component: () => import('../views/SpendingPlanPage.vue'),
    },
    {
      path: '/accounts',
      name: 'accounts',
      component: () => import('../views/AccountsPage.vue'),
    },
    {
      path: '/investments',
      name: 'investments',
      component: () => import('../views/InvestmentsPage.vue'),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('../views/SettingsPage.vue'),
    },
  ],
})

export default router
