/**
 * The Content-Security-Policy makes the browser refuse plain strings as HTML. Vue brings its own
 * policy; this default one covers PrimeVue, which writes CSS into <style> elements with innerHTML.
 * It lets through only strings with no "<", which can't create elements, so markup from anywhere
 * else is still refused.
 */
export function installTrustedTypesPolicy(): void {
  const trustedTypes = (
    window as Window & {
      trustedTypes?: {
        createPolicy(name: string, rules: { createHTML(input: string): string | null }): unknown
      }
    }
  ).trustedTypes
  if (!trustedTypes) return
  trustedTypes.createPolicy('default', {
    createHTML: (input) => (input.includes('<') ? null : input),
  })
}
