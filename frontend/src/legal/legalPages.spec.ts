import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PrivacyPage from '../views/PrivacyPage.vue'
import TermsPage from '../views/TermsPage.vue'
import { CONTACT_EMAIL, PLAID_PRIVACY_URL } from './legal'

const RouterLink = { props: ['to'], template: '<a :href="to"><slot /></a>' }

function render(page: typeof PrivacyPage) {
  return mount(page, { global: { stubs: { RouterLink } } })
}

describe.each([
  ['privacy policy', PrivacyPage],
  ['terms of service', TermsPage],
])('the %s', (_, page) => {
  it('links to the contact email and Plaid’s privacy policy', () => {
    const hrefs = render(page)
      .findAll('a')
      .map((link) => link.attributes('href'))

    expect(hrefs).toContain(`mailto:${CONTACT_EMAIL}`)
    expect(hrefs).toContain(PLAID_PRIVACY_URL)
  })

  it('links to the other page', () => {
    const hrefs = render(page)
      .findAll('a')
      .map((link) => link.attributes('href'))

    expect(hrefs).toEqual(expect.arrayContaining(['/privacy', '/terms']))
  })
})
