import { describe, expect, it } from 'vitest'
import { contentSecurityPolicy } from '../../contentSecurityPolicy'

function directive(policy: string, name: string): string | undefined {
  return policy.split('; ').find((part) => part.startsWith(`${name} `))
}

describe('content security policy', () => {
  it('runs only the app’s own scripts and Plaid Link, never inline ones', () => {
    const scripts = directive(contentSecurityPolicy('/api'), 'script-src')

    expect(scripts).toBe(
      "script-src 'self' https://cdn.plaid.com/link/v2/stable/link-initialize.js",
    )
  })

  it('keeps HTML strings out of the DOM except through Vue', () => {
    const policy = contentSecurityPolicy('/api')

    expect(directive(policy, 'require-trusted-types-for')).toBe(
      "require-trusted-types-for 'script'",
    )
    expect(directive(policy, 'trusted-types')).toBe('trusted-types vue default')
  })

  it('lets the page reach an API on another origin', () => {
    const connect = directive(contentSecurityPolicy('https://api.example.com/api'), 'connect-src')

    expect(connect).toContain(' https://api.example.com ')
    expect(connect).toContain('https://api.workos.com')
  })

  it('needs nothing extra for an API on the app’s own origin', () => {
    expect(directive(contentSecurityPolicy('/api'), 'connect-src')).toBe(
      "connect-src 'self' https://api.workos.com https://production.plaid.com https://sandbox.plaid.com",
    )
  })

  it('leaves the API origin for Docker to fill in when the build has a placeholder', () => {
    const connect = directive(contentSecurityPolicy('__VITE_API_BASE_URL__'), 'connect-src')

    expect(connect).toContain('__VITE_API_ORIGIN__')
  })
})
