package dev.matthewsawyer.finance_dashboard.model;

/** Why a Plaid item needs the user to sign in to their bank again through Link update mode. */
public enum Reconnect {
    /** The bank no longer accepts the item's login, so nothing syncs until the user signs in again. */
    LOGIN_REQUIRED,
    /** The bank's consent runs out soon; signing in again before then keeps the item syncing. */
    EXPIRING
}
