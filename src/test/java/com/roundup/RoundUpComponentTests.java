package com.roundup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roundup.model.Money;
import com.roundup.model.starling.Account;
import com.roundup.model.starling.CurrencyAndAmount;
import com.roundup.model.starling.FeedItem;
import com.roundup.model.starling.SavingsGoal;
import com.roundup.service.StarlingApiService;

/**
 * End-to-end component test: boots the full application context and drives a real request through
 * the controller, bean validation, service orchestration and calculation, and the exception
 * advice, with the external Starling integration replaced by a Mockito mock ({@code @MockitoBean}).
 */
@SpringBootTest(
        properties = {"starling.access-token=test-token", "starling.user-agent=round-up-tests"})
@AutoConfigureMockMvc
class RoundUpComponentTests {

    private static final UUID ACCOUNT_UID = UUID.fromString("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CATEGORY_UID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb");
    private static final UUID GOAL_UID = UUID.fromString("11111111-1111-4111-1111-111111111111");
    private static final UUID TRANSFER_UID =
            UUID.fromString("cccccccc-cccc-4ccc-cccc-cccccccccccc");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private StarlingApiService starlingApiService;

    @Test
    void performsRoundUpAcrossTheFullyWiredStack() throws Exception {
        when(starlingApiService.getAccounts())
                .thenReturn(List.of(new Account(ACCOUNT_UID, "PRIMARY", CATEGORY_UID, "GBP")));
        when(starlingApiService.getFeedItems(eq(ACCOUNT_UID), eq(CATEGORY_UID), any(), any()))
                .thenReturn(List.of(feedItem(435), feedItem(520), feedItem(87)));
        when(starlingApiService.getSavingsGoals(ACCOUNT_UID))
                .thenReturn(List.of(new SavingsGoal(GOAL_UID, "Weekly Round-ups", "ACTIVE")));

        mockMvc.perform(
                        post("/api/v1/round-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"startDate\":\"2026-08-24\",\"transferUid\":"
                                                + "\"cccccccc-cccc-4ccc-cccc-cccccccccccc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStart").value("2026-08-24T00:00:00Z"))
                .andExpect(jsonPath("$.periodEnd").value("2026-08-30T23:59:59.999999999Z"))
                .andExpect(jsonPath("$.eligibleTransactionCount").value(3))
                .andExpect(jsonPath("$.total.currency").value("GBP"))
                .andExpect(jsonPath("$.total.minorUnits").value(158))
                .andExpect(
                        jsonPath("$.transferUid").value("cccccccc-cccc-4ccc-cccc-cccccccccccc"));

        verify(starlingApiService)
                .addMoney(
                        ACCOUNT_UID,
                        GOAL_UID,
                        TRANSFER_UID,
                        new Money("GBP", 158),
                        "round-up:2026-08-24");
    }

    private static FeedItem feedItem(long minorUnits) {
        return new FeedItem(
                new CurrencyAndAmount("GBP", minorUnits),
                null,
                "OUT",
                "SETTLED",
                "MASTER_CARD",
                null,
                Instant.parse("2026-08-26T12:00:00Z"));
    }
}
