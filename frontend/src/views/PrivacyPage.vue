<script setup lang="ts">
import { RouterLink } from 'vue-router'
import LegalPage from '../legal/LegalPage.vue'
import { CONTACT_EMAIL, PLAID_PRIVACY_URL, UPDATED } from '../legal/legal'
</script>

<template>
  <LegalPage title="Privacy policy" :updated="UPDATED">
    <p>
      Money Buckets, at moneybuckets.io, is a free, invite-only budgeting app run by Matthew Sawyer,
      an individual in Alabama. This policy says what the app collects, why, who else handles it,
      and how to delete it. “We” and “us” mean Matthew Sawyer.
    </p>

    <h2>What we collect</h2>
    <ul>
      <li>
        <strong>Your sign-in.</strong> Clerk runs sign-in for us. We get your email address, your
        name if you give one, and an ID for your sign-in. Clerk handles your password; we never see
        it.
      </li>
      <li>
        <strong>Your bank data.</strong> When you connect a bank through Plaid, we get the names and
        types of your accounts, their last four digits, their balances, and up to two years of
        transactions: date, amount, description, merchant and category. We also get the recurring
        payments Plaid detects, and the balances of investment accounts you connect. We never
        receive your bank username or password, or full account and routing numbers.
      </li>
      <li>
        <strong>What you enter.</strong> Your spending plan, changes you make to how a payment is
        sorted, your answers about recurring payments, transactions you exclude, and how each
        account counts toward spending and net worth.
      </li>
      <li>
        <strong>Balance history.</strong> We save each account’s balance once a day so the app can
        chart your net worth over time.
      </li>
      <li>
        <strong>Technical data.</strong> Our hosting provider keeps standard server logs, which can
        include your IP address and the pages your browser requests. Clerk sets cookies to keep you
        signed in. Your browser’s local storage remembers display choices, such as which account you
        last picked. The app has no analytics or advertising trackers.
      </li>
    </ul>

    <h2>How we use it</h2>
    <p>
      Only to run the app for you: to show your balances, spending and net worth, to sort your
      transactions into the parts of your spending plan, and to spot payments that repeat. We don’t
      sell your data, show you ads, or use it for marketing.
    </p>

    <h2>Who else handles it</h2>
    <p>These companies process data on our behalf, only to provide the app:</p>
    <ul>
      <li>
        <strong>Plaid</strong> connects your banks and sends us your bank data. Plaid’s own use of
        your data is covered by the
        <a :href="PLAID_PRIVACY_URL" target="_blank" rel="noopener">Plaid End User Privacy Policy</a
        >.
      </li>
      <li><strong>Clerk</strong> runs sign-in and stores your sign-in details.</li>
      <li><strong>Railway</strong> hosts the app and its database.</li>
      <li>
        <strong>TypeSafe</strong> runs the AI model that sorts transactions and spots recurring
        payments. It receives transaction descriptions, merchant names, amounts, dates and
        categories, along with the names of your spending plan’s lines. It doesn’t receive your
        name, email address or account numbers.
      </li>
    </ul>
    <p>
      We’ll also share data if the law requires it, such as a valid court order. Otherwise we share
      it with no one.
    </p>

    <h2>How we protect it</h2>
    <p>
      Everything between your browser and the app travels over HTTPS. The database sits on encrypted
      storage, and the keys that let us reach your banks through Plaid are encrypted again on their
      own. Only Matthew Sawyer can access the production systems. No system is perfectly secure. If
      someone gets at your data without permission, we’ll tell you by email.
    </p>

    <h2>How long we keep it, and deleting it</h2>
    <p>We keep your data while you have an account.</p>
    <ul>
      <li>
        <strong>Removing a bank</strong> disconnects it at Plaid and deletes its transactions and
        recurring payments. Its account names and balance history stay so your net worth history
        still adds up.
      </li>
      <li>
        <strong>Deleting your account</strong> in Settings disconnects all your banks at Plaid,
        deletes everything we store about you, and deletes your sign-in, all right away. Copies in
        server logs or hosting backups disappear as those expire.
      </li>
    </ul>
    <p>
      You can also email <a :href="`mailto:${CONTACT_EMAIL}`">{{ CONTACT_EMAIL }}</a> to ask for a
      copy of your data, to correct it, or to have it deleted.
    </p>

    <h2>Children</h2>
    <p>Money Buckets is for adults 18 and over. We don’t knowingly collect data from children.</p>

    <h2>Changes to this policy</h2>
    <p>
      If this policy changes, we’ll update the date at the top. For a change that affects how we use
      your data, we’ll tell you by email or in the app before it takes effect.
    </p>

    <h2>Contact</h2>
    <p>
      Questions go to Matthew Sawyer at
      <a :href="`mailto:${CONTACT_EMAIL}`">{{ CONTACT_EMAIL }}</a
      >. See also the <RouterLink to="/terms">terms of service</RouterLink>.
    </p>
  </LegalPage>
</template>
