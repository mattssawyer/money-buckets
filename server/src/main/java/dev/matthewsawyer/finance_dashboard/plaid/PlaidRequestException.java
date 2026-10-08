package dev.matthewsawyer.finance_dashboard.plaid;

/** Plaid could not be reached or rejected a request. */
public class PlaidRequestException extends RuntimeException {

    /** Plaid's error_code, such as ITEM_NOT_FOUND; null when Plaid sent none or wasn't reached. */
    private final String errorCode;

    public PlaidRequestException(String message) {
        this(message, (String) null);
    }

    public PlaidRequestException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public PlaidRequestException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
