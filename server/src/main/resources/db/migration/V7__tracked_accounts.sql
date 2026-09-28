-- Which accounts spending is counted from, and how much of a shared account's spending is the
-- user's own.
ALTER TABLE accounts ADD COLUMN tracks_spending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE accounts ADD COLUMN share_percent INTEGER NOT NULL DEFAULT 100
    CONSTRAINT accounts_share_percent_range CHECK (share_percent BETWEEN 1 AND 100);

-- Everyday accounts and credit cards start tracked. Savings and money market accounts don't,
-- since money moved into them is saved rather than spent.
UPDATE accounts SET tracks_spending = TRUE
WHERE (type = 'depository' AND subtype IN ('checking', 'cash management'))
   OR (type = 'credit' AND subtype = 'credit card');
