package dev.matthewsawyer.finance_dashboard.plaid;

/**
 * Where Plaid Link opens. iOS Link needs a redirect URI so OAuth banks can hand the user back to
 * the app; the web keeps the user in the same tab and needs none.
 */
public enum LinkPlatform {
    WEB,
    IOS
}
