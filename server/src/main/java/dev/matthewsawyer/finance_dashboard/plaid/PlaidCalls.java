package dev.matthewsawyer.finance_dashboard.plaid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

final class PlaidCalls {

    private static final Logger log = LoggerFactory.getLogger(PlaidCalls.class);

    /**
     * Runs a Plaid request and returns its body. {@code action} names the request in errors,
     * e.g. "transactions sync".
     */
    static <T> T execute(Call<T> call, String action) {
        long started = System.nanoTime();
        Response<T> response;
        try {
            response = call.execute();
        } catch (IOException e) {
            log.info("Plaid request: endpoint={} trigger={} outcome=io_error duration_ms={}", action,
                    org.slf4j.MDC.get("plaid_trigger"), (System.nanoTime() - started) / 1_000_000);
            throw new PlaidRequestException("Plaid " + action + " failed", e);
        }
        if (!response.isSuccessful() || response.body() == null) {
            JsonObject error = errorBody(response);
            log.warn("Plaid request: endpoint={} trigger={} status={} error={} request_id={} duration_ms={}",
                    action, org.slf4j.MDC.get("plaid_trigger"), response.code(),
                    error == null ? null : nullableField(error, "error_code"),
                    error == null ? null : nullableField(error, "request_id"),
                    (System.nanoTime() - started) / 1_000_000);
            throw new PlaidRequestException("Plaid " + action + " failed",
                    error == null ? null : nullableField(error, "error_code"), response.code());
        }
        log.info("Plaid request: endpoint={} trigger={} status={} duration_ms={}", action,
                org.slf4j.MDC.get("plaid_trigger"), response.code(), (System.nanoTime() - started) / 1_000_000);
        return response.body();
    }

    /** Plaid's error body, or null when there's none or it isn't JSON. */
    private static JsonObject errorBody(Response<?> response) {
        try (ResponseBody body = response.errorBody()) {
            return body == null ? null : JsonParser.parseString(body.string()).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String nullableField(JsonObject error, String name) {
        return error.has(name) && !error.get(name).isJsonNull() ? error.get(name).getAsString() : null;
    }

    private PlaidCalls() {
    }
}
