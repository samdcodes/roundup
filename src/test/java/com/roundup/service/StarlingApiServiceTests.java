package com.roundup.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.ExpectedCount.times;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.roundup.configuration.StarlingRetryProperties;
import com.roundup.exception.TransferIdempotencyConflictException;
import com.roundup.model.Money;
import com.roundup.model.starling.Account;

@ExtendWith(OutputCaptureExtension.class)
class StarlingApiServiceTests {

    private static final UUID ACCOUNT_UID = UUID.fromString("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CATEGORY_UID = UUID.fromString("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb");
    private static final UUID GOAL_UID = UUID.fromString("cccccccc-cccc-4ccc-cccc-cccccccccccc");
    private static final UUID TRANSFER_UID = UUID.fromString("dddddddd-dddd-4ddd-dddd-dddddddddddd");

    @Test
    void getsAccountsWithRequiredHeaders() {
        TestClient testClient = testClient(1);
        testClient
                .server()
                .expect(requestTo("https://example.test/api/v2/accounts"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(header(HttpHeaders.USER_AGENT, "round-up-tests"))
                .andRespond(
                        withSuccess(
                                """
                        {"accounts":[{
                          "accountUid":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
                          "accountType":"PRIMARY",
                          "defaultCategory":"bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb",
                          "currency":"GBP"
                        }]}
                        """,
                                MediaType.APPLICATION_JSON));

        List<Account> accounts = testClient.client().getAccounts();

        assertThat(accounts)
                .containsExactly(new Account(ACCOUNT_UID, "PRIMARY", CATEGORY_UID, "GBP"));
        testClient.server().verify();
    }

    @Test
    void sendsFeedBoundariesAsQueryParameters() {
        TestClient testClient = testClient(1);
        testClient
                .server()
                .expect(
                        requestTo(
                                "https://example.test/api/v2/feed/account/"
                                        + ACCOUNT_UID
                                        + "/category/"
                                        + CATEGORY_UID
                                        + "/transactions-between?minTransactionTimestamp=2026-08-23T23:00:00Z"
                                        + "&maxTransactionTimestamp=2026-08-30T23:00:00Z"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"feedItems\":[]}", MediaType.APPLICATION_JSON));

        assertThat(
                        testClient
                                .client()
                                .getFeedItems(
                                        ACCOUNT_UID,
                                        CATEGORY_UID,
                                        Instant.parse("2026-08-23T23:00:00Z"),
                                        Instant.parse("2026-08-30T23:00:00Z")))
                .isEmpty();
        testClient.server().verify();
    }

    @Test
    void retriesTransferWithSameUuidAndBody(CapturedOutput output) {
        TestClient testClient = testClient(2);
        String url =
                "https://example.test/api/v2/account/"
                        + ACCOUNT_UID
                        + "/savings-goals/"
                        + GOAL_UID
                        + "/add-money/"
                        + TRANSFER_UID;
        testClient
                .server()
                .expect(requestTo(url))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"amount":{"currency":"GBP","minorUnits":158},"reference":"round-up:dddddddd-dddd-4ddd-dddd-dddddddddddd"}
                        """))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        testClient
                .server()
                .expect(requestTo(url))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"amount":{"currency":"GBP","minorUnits":158},"reference":"round-up:dddddddd-dddd-4ddd-dddd-dddddddddddd"}
                        """))
                .andRespond(
                        withSuccess(
                                """
                        {"transferUid":"dddddddd-dddd-4ddd-dddd-dddddddddddd","success":true}
                        """,
                                MediaType.APPLICATION_JSON));

        testClient
                .client()
                .addMoney(
                        ACCOUNT_UID,
                        GOAL_UID,
                        TRANSFER_UID,
                        new Money("GBP", 158),
                        "round-up:" + TRANSFER_UID);

        assertThat(output)
                .contains(
                        "operation=addMoney, status=503, reason=Service Unavailable, attempt=1/2, retrying=true");
        testClient.server().verify();
    }

    @Test
    void doesNotRetryGoalCreation(CapturedOutput output) {
        TestClient testClient = testClient(3);
        testClient
                .server()
                .expect(
                        times(1),
                        requestTo(
                                "https://example.test/api/v2/account/"
                                        + ACCOUNT_UID
                                        + "/savings-goals"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"name\":\"Weekly Round-ups\",\"currency\":\"GBP\"}"))
                .andRespond(
                        withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                                .header(HttpHeaders.RETRY_AFTER, "30")
                                .header("X-Request-ID", "starling-request-123")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("""
                                        {"errors":[{"message":"Service temporarily unavailable"}]}
                                        """));

        assertThatThrownBy(
                        () ->
                                testClient
                                        .client()
                                        .createSavingsGoal(ACCOUNT_UID, "Weekly Round-ups"))
                .isInstanceOfSatisfying(
                        RestClientResponseException.class,
                        exception -> {
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(exception.getMessage()).doesNotContain("test-token");
                        });
        assertThat(output)
                .contains(
                        "operation=createSavingsGoal, status=503, reason=Service Unavailable, attempt=1/1, retrying=false, retryAfter=30, requestId=starling-request-123")
                .contains("Service temporarily unavailable");
        testClient.server().verify();
    }

    @Test
    void addMoneyMapsIdempotencyMismatchToConflict() {
        TestClient testClient = testClient(3);
        String url =
                "https://example.test/api/v2/account/"
                        + ACCOUNT_UID
                        + "/savings-goals/"
                        + GOAL_UID
                        + "/add-money/"
                        + TRANSFER_UID;
        testClient
                .server()
                .expect(times(1), requestTo(url))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("""
                                        {"errors":[{"message":"IDEMPOTENCY_MISMATCH"}],"success":false}
                                        """));

        assertThatThrownBy(
                        () ->
                                testClient
                                        .client()
                                        .addMoney(
                                                ACCOUNT_UID,
                                                GOAL_UID,
                                                TRANSFER_UID,
                                                new Money("GBP", 158),
                                                "round-up:" + TRANSFER_UID))
                .isInstanceOf(TransferIdempotencyConflictException.class);
        testClient.server().verify();
    }

    private TestClient testClient(int maxAttempts) {
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("https://example.test")
                        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                        .defaultHeader(HttpHeaders.USER_AGENT, "round-up-tests");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        StarlingRetryProperties retryProperties =
                new StarlingRetryProperties(maxAttempts, Duration.ZERO);
        return new TestClient(new StarlingApiService(builder.build(), retryProperties), server);
    }

    private record TestClient(StarlingApiService client, MockRestServiceServer server) {}
}
