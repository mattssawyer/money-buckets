-- Whether an account counts toward net worth. Every account does until the user leaves it out.
ALTER TABLE accounts ADD COLUMN counts_in_net_worth BOOLEAN NOT NULL DEFAULT TRUE;
