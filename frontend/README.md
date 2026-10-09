# Money Buckets (frontend)

The Money Buckets frontend uses Vue 3, TypeScript, Vite, Tailwind CSS, and PrimeVue 5.
WorkOS AuthKit handles sign-in.

## UI components

PrimeVue is the primary component library. Import only the components needed by
a page, for example:

```vue
<script setup lang="ts">
import Button from 'primevue/button'
</script>

<template>
  <Button label="Continue" />
</template>
```

`src/main.ts` registers the PrimeVue plugin. `src/theme.ts` defines the shared
warm graphite preset based on Aura from `@primeuix/themes`. Customize shared
colors and component design tokens there, and use scoped CSS for page layout.
Dark mode is currently disabled.

shadcn-vue may be used for additional components or customization when needed;
it is not currently installed.

## PrimeUI license

Add the license key to `.env.local`, which is ignored by Git:

```dotenv
VITE_PRIMEUI_LICENSE_KEY=your_primeui_license_key
```

The PrimeVue plugin reads this variable in `src/main.ts`. Restart the dev server
after changing it. For deployment, set it before building and rebuild the
frontend. The value is included in the browser bundle for client-side license
validation.

See the [project README](../README.md) for backend and authentication setup.

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Run Unit Tests with [Vitest](https://vitest.dev/)

```sh
npm run test:unit
```

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```
