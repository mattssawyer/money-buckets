# Jev's recurring guesses are candidates the user confirms, kept apart from Plaid's streams

Plaid only detects a recurring stream after seeing about three charges, so a user with a month or two of history gets none, and the spending plan has nothing to fill in. We ask Jev whether each transaction looks like a recurring bill, subscription or paycheck, and group its answers by merchant into recurring candidates. A candidate is only a guess: it counts, in the plan's autofill and on Home, once the user confirms it. A rejected merchant is never suggested again. When Plaid detects the same merchant, its recurring stream takes the candidate's place.

## Considered Options

- **Merging Jev's guesses into the recurring streams.** Rejected. A guessed Netflix and Plaid's Netflix would count twice, and nothing would show which payments were only guesses.
- **Trusting Jev above a probability threshold without asking.** Rejected. A wrong guess inflates the plan with money the user doesn't owe, and one transaction can't say whether a merchant charges regularly. The user answers yes or no instead.
- **Asking the user about each transaction.** Rejected. They'd be asked about the same subscription every month; one answer per merchant covers its future charges.

## Consequences

- There are two sources of recurring payments, and Home shows them separately until a candidate is confirmed.
- The user's answers are stored per merchant and kind, and have to survive later Jev judgments and Plaid syncs.
