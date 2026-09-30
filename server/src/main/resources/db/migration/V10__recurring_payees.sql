-- Jev's judgment of each payee the user pays (a bill) or is paid by (a paycheck): how likely
-- it's regular, how often, and which spending plan line its payments belong under. Judged from
-- all of the payee's charges at once, and again whenever what Jev would see changes, which
-- judged_state records. Replaces the per-transaction judgments, whose columns are dropped once
-- nothing reads them.
CREATE TABLE recurring_payees (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    kind VARCHAR(16) NOT NULL,
    merchant_key VARCHAR(512) NOT NULL,
    probability NUMERIC(5, 4),
    usual_frequency VARCHAR(32),
    plan_bucket VARCHAR(32),
    plan_line VARCHAR(255),
    judged_state VARCHAR(64) NOT NULL,
    judged_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT recurring_payees_unique UNIQUE (user_id, kind, merchant_key)
);
