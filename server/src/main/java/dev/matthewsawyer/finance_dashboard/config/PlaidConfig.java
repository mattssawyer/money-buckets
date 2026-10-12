package dev.matthewsawyer.finance_dashboard.config;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

@Configuration
public class PlaidConfig {

    @Bean
    public PlaidApi plaidApi(
            @Value("${PLAID_CLIENT_ID}") String clientId,
            @Value("${PLAID_ENV:sandbox}") String environment,
            // Falls back to the sandbox secret so existing local .env files keep working.
            @Value("${PLAID_SECRET:${PLAID_SANDBOX_SECRET}}") String secret
    ) {
        HashMap<String, String> keys = new HashMap<>();
        keys.put("clientId", clientId);
        keys.put("secret", secret);

        ApiClient apiClient = new ApiClient(keys);
        apiClient.setTimeout(30);
        apiClient.getOkBuilder().callTimeout(java.time.Duration.ofSeconds(45));
        apiClient.setPlaidAdapter(baseUrl(environment));
        return apiClient.createService(PlaidApi.class);
    }

    private static String baseUrl(String environment) {
        return switch (environment) {
            case "sandbox" -> ApiClient.Sandbox;
            case "production" -> ApiClient.Production;
            default -> throw new IllegalStateException(
                    "PLAID_ENV must be sandbox or production, not " + environment);
        };
    }
}
