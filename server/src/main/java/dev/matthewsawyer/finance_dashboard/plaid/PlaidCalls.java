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
        Response<T> response;
        try {
            response = call.execute();
        } catch (IOException e) {
            throw new PlaidRequestException("Plaid " + action + " failed", e);
        }
        if (!response.isSuccessful() || response.body() == null) {
            JsonObject error = errorBody(response);
            log.warn("Plaid {} failed with HTTP {}: {}", action, response.code(), describe(error));
            throw new PlaidRequestException("Plaid " + action + " failed",
                    error == null ? null : nullableField(error, "error_code"));
        }
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

    /** Plaid's error type, code and message; never tokens or secrets. */
    private static String describe(JsonObject error) {
        if (error == null) {
            return "no readable error body";
        }
        return String.join(" / ", field(error, "error_type"), field(error, "error_code"),
                field(error, "error_message"));
    }

    private static String nullableField(JsonObject error, String name) {
        return error.has(name) && !error.get(name).isJsonNull() ? error.get(name).getAsString() : null;
    }

    private static String field(JsonObject error, String name) {
        String value = nullableField(error, name);
        return value == null ? "-" : value;
    }

    private PlaidCalls() {
    }
}
