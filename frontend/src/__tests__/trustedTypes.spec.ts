import { afterEach, describe, expect, it, vi } from 'vitest'
import { installTrustedTypesPolicy } from '../trustedTypes'

type Rules = { createHTML(input: string): string | null }

function installedRules(): Rules {
  const createPolicy = vi.fn<(name: string, rules: Rules) => unknown>()
  vi.stubGlobal('trustedTypes', { createPolicy })
  installTrustedTypesPolicy()
  expect(createPolicy).toHaveBeenCalledWith('default', expect.anything())
  return createPolicy.mock.calls[0]![1]
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('trusted types policy', () => {
  it('lets CSS through', () => {
    const css =
      '@media screen and (max-width: 640px) { .p-dialog > .p-dialog-content { width: 90vw } }'

    expect(installedRules().createHTML(css)).toBe(css)
  })

  it('refuses anything that could create an element', () => {
    expect(installedRules().createHTML('<img src=x onerror=alert(1)>')).toBeNull()
  })
})
