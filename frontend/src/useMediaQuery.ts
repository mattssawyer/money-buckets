import { getCurrentScope, onScopeDispose, readonly, ref } from 'vue'

/** The width at and below which pages switch to their phone layout. */
export const PHONE_QUERY = '(max-width: 640px)'

/** Whether a media query matches, kept current as the window changes. */
export function useMediaQuery(query: string) {
  const media = typeof window.matchMedia === 'function' ? window.matchMedia(query) : undefined
  const matches = ref(media?.matches ?? false)
  const update = (event: MediaQueryListEvent) => (matches.value = event.matches)
  media?.addEventListener?.('change', update)
  if (getCurrentScope()) onScopeDispose(() => media?.removeEventListener?.('change', update))
  return readonly(matches)
}
