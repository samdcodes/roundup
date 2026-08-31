package com.roundup.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import com.roundup.exception.TransferIdempotencyConflictException;
import com.roundup.model.Money;
import com.roundup.model.api.RoundUpResult;
import com.roundup.service.RoundUpService;

class RoundUpControllerTests {

    private static final UUID TRANSFER_UID =
            UUID.fromString("cccccccc-cccc-4ccc-cccc-cccccccccccc");
    private static final LocalDate START_DATE = LocalDate.parse("2026-08-24");
    private static final Instant PERIOD_START = Instant.parse("2026-08-24T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-08-30T23:59:59.999999999Z");

    private final RoundUpService service = mock(RoundUpService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc =
                MockMvcBuilders.standaloneSetup(new RoundUpController(service))
                        .setControllerAdvice(new RoundUpControllerAdvice())
                        .setValidator(validator)
                        .build();
    }

    @Test
    void passesStartDateAndCallerUuidAndReturnsCalculationSummary() throws Exception {
        when(service.roundup(START_DATE, TRANSFER_UID))
                .thenReturn(
                        new RoundUpResult(
                                PERIOD_START, PERIOD_END, 3, new Money("GBP", 158), TRANSFER_UID));

        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStart").value("2026-08-24T00:00:00Z"))
                .andExpect(jsonPath("$.periodEnd").value("2026-08-30T23:59:59.999999999Z"))
                .andExpect(jsonPath("$.total.currency").value("GBP"))
                .andExpect(jsonPath("$.total.minorUnits").value(158))
                .andExpect(jsonPath("$.transferUid").value(TRANSFER_UID.toString()));

        verify(service).roundup(START_DATE, TRANSFER_UID);
    }

    @Test
    void returnsZeroTotalWithNullTransferUid() throws Exception {
        when(service.roundup(START_DATE, TRANSFER_UID))
                .thenReturn(
                        new RoundUpResult(PERIOD_START, PERIOD_END, 1, new Money("GBP", 0), null));

        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferUid").value(nullValue()));
    }

    @Test
    void rejectsMissingTransferUuid() throws Exception {
        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"startDate\":\"2026-08-24\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsMissingStartDate() throws Exception {
        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"transferUid\":\"" + TRANSFER_UID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsMalformedStartDate() throws Exception {
        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"startDate\":\"24-08-2026\",\"transferUid\":\""
                                                + TRANSFER_UID
                                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void acceptsStartDateThatIsNotMonday() throws Exception {
        LocalDate tuesday = LocalDate.parse("2026-08-25");
        when(service.roundup(tuesday, TRANSFER_UID))
                .thenReturn(
                        new RoundUpResult(
                                Instant.parse("2026-08-25T00:00:00Z"),
                                Instant.parse("2026-08-31T23:59:59.999999999Z"),
                                0,
                                new Money("GBP", 0),
                                null));

        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"startDate\":\"2026-08-25\",\"transferUid\":\""
                                                + TRANSFER_UID
                                                + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStart").value("2026-08-25T00:00:00Z"))
                .andExpect(jsonPath("$.periodEnd").value("2026-08-31T23:59:59.999999999Z"));

        verify(service).roundup(tuesday, TRANSFER_UID);
    }

    @Test
    void rejectsMalformedTransferUuid() throws Exception {
        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"startDate\":\"2026-08-24\",\"transferUid\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void mapsStarlingRejectionToUnprocessableContent() throws Exception {
        assertStarlingFailure(
                new HttpClientErrorException(HttpStatus.BAD_REQUEST),
                422,
                "STARLING_REJECTED",
                "Starling rejected the request");
    }

    @Test
    void mapsTransferIdempotencyMismatchToConflict() throws Exception {
        when(service.roundup(START_DATE, TRANSFER_UID))
                .thenThrow(
                        new TransferIdempotencyConflictException(
                                "transferUid was already used for a different transfer"));

        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_IDEMPOTENCY_MISMATCH"))
                .andExpect(
                        jsonPath("$.message")
                                .value("transferUid was already used for a different transfer"));
    }

    @Test
    void mapsStarlingUnauthorizedToBadGatewayAuthFailure() throws Exception {
        assertStarlingFailure(
                new HttpClientErrorException(HttpStatus.UNAUTHORIZED),
                502,
                "STARLING_AUTH_FAILED",
                "Starling rejected the service credentials");
    }

    @Test
    void mapsStarlingForbiddenToBadGatewayAuthFailure() throws Exception {
        assertStarlingFailure(
                new HttpClientErrorException(HttpStatus.FORBIDDEN),
                502,
                "STARLING_AUTH_FAILED",
                "Starling rejected the service credentials");
    }

    @Test
    void mapsStarlingNotFoundToBadGateway() throws Exception {
        assertStarlingFailure(
                new HttpClientErrorException(HttpStatus.NOT_FOUND),
                502,
                "STARLING_BAD_RESPONSE",
                "Starling returned an invalid response");
    }

    @Test
    void mapsInvalidStarlingResponseToBadGateway() throws Exception {
        assertStarlingFailure(
                new RestClientException("Invalid response"),
                502,
                "STARLING_BAD_RESPONSE",
                "Starling returned an invalid response");
    }

    @Test
    void mapsStarlingOutageToServiceUnavailable() throws Exception {
        assertStarlingFailure(
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE),
                503,
                "STARLING_UNAVAILABLE",
                "Starling is temporarily unavailable");
    }

    private void assertStarlingFailure(
            RestClientException exception, int statusCode, String code, String message)
            throws Exception {
        when(service.roundup(START_DATE, TRANSFER_UID)).thenThrow(exception);

        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody()))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message));
    }

    private String requestBody() {
        return "{\"startDate\":\"" + START_DATE + "\",\"transferUid\":\"" + TRANSFER_UID + "\"}";
    }
}
