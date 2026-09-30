# Jev judges recurring payees, not single transactions

ADR 0002 had Jev judge each transaction on its own and grouped the answers by merchant. That missed the user's rent: Plaid labels the payments to "Sterling Group" home improvement, and one such charge looks like a one-off repair (Jev: 0.2). What gives rent away is the same amount a month apart, which no single transaction shows. So Jev now judges each payee once, from its recent charges together, and in the same request picks the plan line its bills belong under. The judgment is stored per payee and asked again only when what Jev would be shown changes (a new charge, new plan lines, Plaid starting or stopping to detect it).

Measured in `evals/recurring_payees.py`: well-known subscriptions and bills are recognized from one charge (0.85–0.97), Sterling Group from two (0.90), frequent shopping stays low (≤ 0.17), and every bill lands on its line, rent included.

## Considered Options

- **Per-transaction judgments with the payee's other charges added to each request.** Worked (rent 0.79–0.89), but asked about a payee once per charge and left the per-merchant grouping, the plan line and the Plaid category table as separate pieces.
- **Dropping Plaid's category from the question.** Didn't work: rent only reached 0.45, and correctly labelled rent fell from 0.94 to 0.42.
- **Keeping the category → line table and asking Jev only for categories it misses.** Rejected: two sources of lines, and Jev placed every eval case correctly on its own.

## Consequences

- A payee with a single charge and an uninformative name (first month's rent) still isn't suggested; the user can place it in plan setup.
- Payments to one payee with a subscription among one-off purchases (Uber One among rides) are judged together; candidates take their amount from the payee's repeating charges.
- The per-transaction judgment columns on `transactions` are unused and can be dropped in a later migration.
