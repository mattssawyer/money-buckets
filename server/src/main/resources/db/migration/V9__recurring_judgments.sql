-- Jev's judgment of whether a transaction repeats on a schedule (a bill or subscription for
-- money out, a paycheck for money in) and how often a payment like it is usually made. Empty
-- until judged; sync clears it along with the bucket when Plaid changes the transaction.
ALTER TABLE transactions ADD COLUMN recurring_probability NUMERIC(5, 4);
ALTER TABLE transactions ADD COLUMN usual_frequency VARCHAR(32);
ALTER TABLE transactions ADD COLUMN recurring_judged_at TIMESTAMP WITH TIME ZONE;

-- The user's answers about recurring candidates: whether they pay (a bill) or are paid by (a
-- paycheck) a merchant regularly. One answer covers all of the merchant's transactions, now and
-- later. Undoing an answer deletes it.
CREATE TABLE recurring_answers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    kind VARCHAR(16) NOT NULL,
    merchant_key VARCHAR(512) NOT NULL,
    confirmed BOOLEAN NOT NULL,
    answered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT recurring_answers_unique UNIQUE (user_id, kind, merchant_key)
);
