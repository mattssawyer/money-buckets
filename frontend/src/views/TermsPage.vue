<script setup lang="ts">
import { RouterLink } from 'vue-router'
import LegalPage from '../legal/LegalPage.vue'
import { CONTACT_EMAIL, PLAID_PRIVACY_URL, UPDATED } from '../legal/legal'
</script>

<template>
  <LegalPage title="Terms of service" :updated="UPDATED">
    <p>
      These terms cover your use of Money Buckets at moneybuckets.io, run by Matthew Sawyer, an
      individual in Alabama (“we”, “us”). By creating an account or using the app, you agree to
      them. If you don’t agree, don’t use the app. The
      <RouterLink to="/privacy">privacy policy</RouterLink> explains how we handle your data.
    </p>

    <h2>Who can use it</h2>
    <p>
      You must be 18 or older and live in the United States. Money Buckets is invite-only, so you
      also need an invitation. You may only connect bank accounts you’re allowed to access.
    </p>

    <h2>What the app does</h2>
    <p>
      Money Buckets shows your balances, spending and net worth, and helps you plan how your income
      splits between fixed costs, investments, savings and guilt-free spending. It’s free. We may
      change features, or stop running the app, at any time. If we stop, we’ll give you notice where
      we reasonably can.
    </p>

    <h2>Not financial advice</h2>
    <p>
      Money Buckets is a tool for organizing your own information. Nothing in it is financial,
      investment, tax or legal advice. Make your own decisions, and talk to a qualified professional
      when you need advice.
    </p>

    <h2>Your data may be wrong</h2>
    <p>
      Your bank data comes from your bank through Plaid. It can arrive late, be incomplete, or be
      wrong. An AI model sorts your transactions and guesses which payments repeat, and it makes
      mistakes. Check your bank for the real figures before relying on any number in the app.
    </p>

    <h2>Connecting banks through Plaid</h2>
    <p>
      When you connect a bank, you authorize us and Plaid to get your account data from it. Plaid
      handles that connection, and its use of your data is covered by the
      <a :href="PLAID_PRIVACY_URL" target="_blank" rel="noopener">Plaid End User Privacy Policy</a>.
      You can disconnect a bank at any time on the Accounts page.
    </p>

    <h2>Your account</h2>
    <p>
      Keep your sign-in secure. You’re responsible for what happens under your account. Tell us
      right away at <a :href="`mailto:${CONTACT_EMAIL}`">{{ CONTACT_EMAIL }}</a> if you think
      someone else has used it.
    </p>

    <h2>What you can’t do</h2>
    <ul>
      <li>Try to see or change anyone else’s data.</li>
      <li>Attack, overload, or disrupt the app or the services it relies on.</li>
      <li>Use the app for anything illegal.</li>
      <li>Share your account or your invitation with someone else.</li>
    </ul>

    <h2>Ending your use</h2>
    <p>
      You can delete your account at any time in Settings, which deletes your data as the privacy
      policy describes. We may suspend or close an account that breaks these terms, and we’ll tell
      you why unless the law or someone’s safety prevents it.
    </p>

    <h2>The source code</h2>
    <p>
      The app’s source code is public on GitHub. These terms cover only the hosted app at
      moneybuckets.io. A copy someone else runs is theirs, not ours.
    </p>

    <h2>No warranty</h2>
    <p>
      We provide Money Buckets “as is” and “as available”, without warranties of any kind, including
      that it will be accurate, uninterrupted or free of errors.
    </p>

    <h2>Limit on liability</h2>
    <p>
      To the extent the law allows, we aren’t liable for indirect, incidental, special or
      consequential losses, or for lost money, profits or data, arising from your use of the app.
      Since the app is free, our total liability for any claim is limited to $50.
    </p>

    <h2>Governing law</h2>
    <p>
      Alabama law governs these terms, apart from its rules on conflicts of law. Any dispute goes to
      the state or federal courts in Alabama.
    </p>

    <h2>Changes to these terms</h2>
    <p>
      If these terms change, we’ll update the date at the top. For a significant change, we’ll tell
      you by email or in the app before it takes effect. Using the app after that means you accept
      the new terms.
    </p>

    <h2>Contact</h2>
    <p>
      Questions go to Matthew Sawyer at
      <a :href="`mailto:${CONTACT_EMAIL}`">{{ CONTACT_EMAIL }}</a
      >.
    </p>
  </LegalPage>
</template>
