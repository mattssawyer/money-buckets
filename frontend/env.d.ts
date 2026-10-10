/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_PRIMEUI_LICENSE_KEY?: string
  readonly VITE_API_BASE_URL: string
  readonly VITE_WORKOS_CLIENT_ID: string
  /** "true" when sign-up is turned off in WorkOS, so new accounts need an invitation. */
  readonly VITE_INVITE_ONLY?: string
}

interface Window {
  Plaid: {
    create(config: {
      token: string
      onSuccess(publicToken: string, metadata: unknown): void
      onExit(error: unknown, metadata: unknown): void
    }): {
      open(): void
      destroy(): void
    }
  }
}