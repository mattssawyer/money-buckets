package dev.matthewsawyer.finance_dashboard.sorting;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Asks TypeSafe's System One model questions about one state, in a single request so they run
 * in parallel. See <a href="https://docs.typesafe.ai/api">the API reference</a>.
 */
@Component
class TypeSafeClient {

    private static final String MODEL = "jev-latest";

    // Rate limited and overloaded; both clear up after a short wait.
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 529);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

    private final RestClient restClient;
    private final boolean configured;

    TypeSafeClient(
            @Value("${typesafe.api-key:}") String apiKey,
            @Value("${typesafe.base-url:https://api.typesafe.ai}") String baseUrl
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.configured = !apiKey.isBlank();
    }

    /** False when no API key is set, as in local setups that haven't added one yet. */
    boolean isConfigured() {
        return configured;
    }

    /**
     * Returns an answer for every question, keyed like {@code questions}. Retries rate limiting
     * and overload with backoff; any other failure, or a missing answer, is thrown.
     */
    Answers ask(Object state, Map<String, Map<String, Object>> questions) {
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "state", state,
                "questions", questions);

        Duration backoff = FIRST_BACKOFF;
        for (int attempt = 1; ; attempt++) {
            try {
                SystemOneResponse response = restClient.post()
                        .uri("/v1/systemone")
                        .body(body)
                        .retrieve()
                        .body(SystemOneResponse.class);
                if (response == null || response.answers() == null
                        || !response.answers().keySet().containsAll(questions.keySet())) {
                    throw new IllegalStateException("TypeSafe didn't answer every question");
                }
                return new Answers(response.answers());
            } catch (RestClientResponseException e) {
                if (attempt >= MAX_ATTEMPTS || !RETRYABLE_STATUSES.contains(e.getStatusCode().value())) {
                    throw e;
                }
            }
            sleep(backoff);
            backoff = backoff.multipliedBy(2);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry TypeSafe", e);
        }
    }

    /** One question's answer: {@code choice} for a Choice, {@code noul} (0–1, how likely yes) for a Noul. */
    record Answer(
            @JsonProperty("choice") String choice,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("noul") Double noul
    ) {
        static Answer choice(String choice) {
            return new Answer(choice, 1.0, null);
        }

        static Answer noul(double probability) {
            return new Answer(null, null, probability);
        }
    }

    record Answers(Map<String, Answer> byQuestion) {

        /** The chosen option of a Choice question. */
        String choice(String questionId) {
            return byQuestion.get(questionId).choice();
        }

        /** How likely the answer to a Noul question is yes, from 0 to 1. */
        double noul(String questionId) {
            return byQuestion.get(questionId).noul();
        }
    }

    private record SystemOneResponse(@JsonProperty("answers") Map<String, Answer> answers) {
    }
}
