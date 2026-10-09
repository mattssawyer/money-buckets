import type { Plugin } from 'vite'

/** Stands in for the API's origin in Docker builds; docker/frontend-env.sh fills it in. */
const API_ORIGIN_PLACEHOLDER = '__VITE_API_ORIGIN__'

/** Where the API is, as a CSP source. A relative URL is the app's own origin, already allowed. */
function apiSource(apiBaseUrl: string): string | undefined {
  if (apiBaseUrl.includes('__VITE_API_BASE_URL__')) return API_ORIGIN_PLACEHOLDER
  return /^https?:\/\//.test(apiBaseUrl) ? new URL(apiBaseUrl).origin : undefined
}

/**
 * The built app's Content-Security-Policy. The WorkOS refresh token is kept in localStorage, so
 * this is what stops injected scripts from reading it: only the app's own bundles and Plaid Link
 * run, Trusted Types keeps markup out of the DOM except through Vue (see src/trustedTypes.ts),
 * and the page can only talk to the API, WorkOS and Plaid.
 */
export function contentSecurityPolicy(apiBaseUrl: string): string {
  const connect = [
    "'self'",
    apiSource(apiBaseUrl),
    'https://api.workos.com',
    'https://production.plaid.com',
    'https://sandbox.plaid.com',
  ]
  return [
    "default-src 'none'",
    "script-src 'self' https://cdn.plaid.com/link/v2/stable/link-initialize.js",
    // PrimeVue adds its theme as <style> elements.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src ${connect.filter(Boolean).join(' ')}`,
    'frame-src https://cdn.plaid.com/',
    "manifest-src 'self'",
    "base-uri 'none'",
    "form-action 'self'",
    "object-src 'none'",
    "require-trusted-types-for 'script'",
    'trusted-types vue default',
  ].join('; ')
}

const CHARSET = '<meta charset="UTF-8" />'

/**
 * Puts the policy in built pages only; the dev server needs inline scripts for hot reload. It goes
 * straight after the charset, since a <meta> policy only covers what comes after it.
 */
export function contentSecurityPolicyPlugin(apiBaseUrl: string): Plugin {
  return {
    name: 'content-security-policy',
    apply: 'build',
    transformIndexHtml(html) {
      if (!html.includes(CHARSET))
        throw new Error(`index.html needs ${CHARSET} for the CSP to follow`)
      const policy = contentSecurityPolicy(apiBaseUrl).replace(/'/g, '&#39;')
      return html.replace(
        CHARSET,
        `${CHARSET}\n    <meta http-equiv="Content-Security-Policy" content="${policy}" />`,
      )
    },
  }
}
