-- Sign-in moved from Clerk to WorkOS, so the column holds whichever provider's user ID signs in.
ALTER TABLE users RENAME COLUMN clerk_user_id TO auth_user_id;
ALTER INDEX users_clerk_user_id_unique RENAME TO users_auth_user_id_unique;
