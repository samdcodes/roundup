package com.roundup.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.roundup.configuration.StarlingRetryProperties;
import com.roundup.exception.TransferIdempotencyConflictException;
import com.roundup.model.Money;
import com.roundup.model.starling.Account;
import com.roundup.model.starling.AccountsResponse;
import com.roundup.model.starling.CreateSavingsGoalRequest;
import com.roundup.model.starling.CreateSavingsGoalResponse;
import com.roundup.model.starling.CurrencyAndAmount;
import com.roundup.model.starling.FeedItem;
import com.roundup.model.starling.FeedItemsResponse;
import com.roundup.model.starling.SavingsGoal;
import com.roundup.model.starling.SavingsGoalsResponse;
import com.roundup.model.starling.TopUpRequest;
import com.roundup.model.starling.TransferResponse;

@Service
public class StarlingApiService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StarlingApiService.class);

    private static final String FEED_ITEMS_PATH = "/api/v2/feed/account/{accountUid}/category/{categoryUid}/transactions-between";
    private static final String SG_ADD_MONEY_PATH = "/api/v2/account/{accountUid}/savings-goals/{savingsGoalUid}/add-money/{transferUid}";
    private static final String SG_PATH = "/api/v2/account/{accountUid}/savings-goals";
    private final RestClient restClient;
    private final StarlingRetryProperties retryProperties;

    public StarlingApiService(RestClient restClient, StarlingRetryProperties retryProperties) {
        this.restClient = restClient;
        this.retryProperties = retryProperties;
    }

    public List<Account> getAccounts() {
        AccountsResponse response = executeWithRetry(
                "getAccounts",
                () -> restClient
                        .get()
                        .uri("/api/v2/accounts")
                        .retrieve()
                        .body(AccountsResponse.class)
        );
        return response == null || response.accounts() == null ? List.of() : response.accounts();
    }

    public List<FeedItem> getFeedItems(UUID accountUid, UUID categoryUid, Instant start, Instant end) {
        FeedItemsResponse response = executeWithRetry(
                "getFeedItems",
                () -> restClient
                        .get()
                        .uri(uriBuilder ->
                                uriBuilder
                        .path(FEED_ITEMS_PATH)
                        .queryParam("minTransactionTimestamp", start)
                        .queryParam("maxTransactionTimestamp", end)
                        .build(accountUid, categoryUid))
                        .retrieve()
                        .body(FeedItemsResponse.class)
        );
        return response == null || response.feedItems() == null ? List.of() : response.feedItems();
    }

    public List<SavingsGoal> getSavingsGoals(UUID accountUid) {
        SavingsGoalsResponse response = executeWithRetry(
                "getSavingsGoals",
                () -> restClient
                        .get()
                        .uri(SG_PATH, accountUid)
                        .retrieve()
                        .body(SavingsGoalsResponse.class)
        );
        return response == null || response.savingsGoalList() == null
                ? List.of()
                : response.savingsGoalList();
    }

    public UUID createSavingsGoal(UUID accountUid, String name) {
        CreateSavingsGoalResponse response = executeOnce(
                "createSavingsGoal",
                () -> restClient
                        .put()
                        .uri(
                                SG_PATH,
                                accountUid
                        )
                        .body(new CreateSavingsGoalRequest(name, "GBP"))
                        .retrieve()
                        .body(CreateSavingsGoalResponse.class)
        );

        if (response == null || !response.success() || response.savingsGoalUid() == null) {
            LOGGER.warn("Starling response invalid: operation=createSavingsGoal");
            throw new RestClientException("Starling returned an invalid savings goal response");
        }
        return response.savingsGoalUid();
    }

    public void addMoney(
            UUID accountUid,
            UUID savingsGoalUid,
            UUID transferUid,
            Money amount,
            String reference
    ) {
        TransferResponse response;
        try {
            response = executeWithRetry(
                    "addMoney",
                    () -> restClient.put()
                            .uri(
                                    SG_ADD_MONEY_PATH,
                                    accountUid,
                                    savingsGoalUid,
                                    transferUid
                            )
                            .body(new TopUpRequest(
                                    new CurrencyAndAmount(amount.currency(), amount.minorUnits()),
                                    reference))
                            .retrieve()
                            .body(TransferResponse.class)
            );
        } catch (RestClientResponseException exception) {
            if (isIdempotencyMismatch(exception)) {
                LOGGER.warn(
                        "Transfer idempotency mismatch: operation=addMoney, transferUid={}",
                        transferUid);
                throw new TransferIdempotencyConflictException(
                        "transferUid was already used for a different transfer; "
                                + "use a new transferUid for a new round-up");
            }
            throw exception;
        }
        if (response == null || !response.success() || !transferUid.equals(response.transferUid())) {
            LOGGER.warn("Starling response invalid: operation=addMoney, transferUid={}", transferUid);
            throw new RestClientException("Starling returned an invalid transfer response");
        }
    }

    // Starling rejects an add-money call with HTTP 400 and an IDEMPOTENCY_MISMATCH error when the
    // transferUid was already used for a transfer with different content. This is a caller error.
    private boolean isIdempotencyMismatch(RestClientResponseException exception) {
        return exception.getStatusCode().value() == 400
                && exception.getResponseBodyAsString().contains("IDEMPOTENCY_MISMATCH");
    }

    private <T> T executeWithRetry(String operation, Supplier<T> request) {
        return execute(operation, retryProperties.maxAttempts(), request);
    }

    private <T> T executeOnce(String operation, Supplier<T> request) {
        return execute(operation, 1, request);
    }

    private <T> T execute(String operation, int maxAttempts, Supplier<T> request) {
        int attempt = 1;
        while (true) {
            try {
                return request.get();
            } catch (RestClientResponseException exception) {
                boolean retrying = isTransient(exception.getStatusCode()) && attempt < maxAttempts;
                logResponseFailure(operation, exception, attempt, maxAttempts, retrying);
                if (!retrying) {
                    throw exception;
                }
            } catch (ResourceAccessException exception) {
                boolean retrying = attempt < maxAttempts;
                logTransportFailure(operation, attempt, maxAttempts, retrying);
                if (!retrying) {
                    throw exception;
                }
            } catch (RestClientException exception) {
                LOGGER.warn("Starling request failed: operation={}, failure=client", operation);
                throw exception;
            }
            pauseBeforeRetry(attempt);
            attempt++;
        }
    }

    private void logResponseFailure(
            String operation,
            RestClientResponseException exception,
            int attempt,
            int maxAttempts,
            boolean retrying) {
        LOGGER.warn(
                "Starling request failed: operation={}, status={}, reason={}, attempt={}/{}, retrying={}, retryAfter={}, requestId={}, body={}",
                operation,
                exception.getStatusCode().value(),
                exception.getStatusText(),
                attempt,
                maxAttempts,
                retrying,
                responseHeader(exception, "Retry-After"),
                responseRequestId(exception),
                responseBody(exception));
    }

    private String responseHeader(RestClientResponseException exception, String name) {
        String value = exception.getResponseHeaders() == null
                ? null
                : exception.getResponseHeaders().getFirst(name);
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private String responseRequestId(RestClientResponseException exception) {
        return responseHeader(exception, "X-Request-ID");
    }

    // Starling returns a JSON error body describing why a request was rejected. It carries no
    // secrets (the access token is a request header), so it is safe to log.
    private String responseBody(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        return body.isBlank() ? "<none>" : body.strip();
    }

    private void logTransportFailure(
            String operation, int attempt, int maxAttempts, boolean retrying) {
        LOGGER.warn(
                "Starling request failed: operation={}, failure=transport, attempt={}/{}, retrying={}",
                operation,
                attempt,
                maxAttempts,
                retrying);
    }

    private boolean isTransient(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    // Blocks the calling request thread for a short, bounded, linearly increasing backoff.
    private void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryProperties.retryDelay().multipliedBy(attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RestClientException("Starling retry interrupted", exception);
        }
    }
}
