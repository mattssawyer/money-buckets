package dev.matthewsawyer.finance_dashboard.plaid;

/** Plaid could not be reached or rejected a request. */
public class PlaidRequestException extends RuntimeException {

    /** The bank no longer accepts the item's login; the user must sign in again through Link. */
    public static final String ITEM_LOGIN_REQUIRED = "ITEM_LOGIN_REQUIRED";

    /** Plaid's error_code, such as ITEM_NOT_FOUND; null when Plaid sent none or wasn't reached. */
    private final String errorCode;
    private int httpStatus;

    public PlaidRequestException(String message) {
        this(message, (String) null);
    }

    public PlaidRequestException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public PlaidRequestException(String message, String errorCode, int httpStatus) {
        this(message, errorCode);
        this.httpStatus = httpStatus;
    }

    public PlaidRequestException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** Only transient failures get rapid retries; product/permission errors wait for recovery. */
    public boolean isRetryable() {
        return httpStatus == 429 || httpStatus >= 500 || errorCode == null || errorCode.contains("RATE_LIMIT")
                || java.util.Set.of("PRODUCT_NOT_READY", "INTERNAL_SERVER_ERROR", "INSTITUTION_DOWN",
                "INSTITUTION_NOT_RESPONDING", "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION").contains(errorCode);
    }

    public boolean isLoginRequired() {
        return ITEM_LOGIN_REQUIRED.equals(errorCode);
    }
}
