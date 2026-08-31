package com.roundup.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roundup.exception.SelectionConflictException;
import com.roundup.model.Money;
import com.roundup.model.api.RoundUpResult;
import com.roundup.model.starling.Account;
import com.roundup.model.starling.CurrencyAndAmount;
import com.roundup.model.starling.FeedItem;
import com.roundup.model.starling.SavingsGoal;

class RoundUpOrchestrationTests {

    private static final UUID ACCOUNT_UID = UUID.fromString("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CATEGORY_UID = UUID.fromString("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb");
    private static final UUID GOAL_UID = UUID.fromString("cccccccc-cccc-4ccc-cccc-cccccccccccc");
    private static final UUID TRANSFER_UID = UUID.fromString("dddddddd-dddd-4ddd-dddd-dddddddddddd");
    private static final LocalDate START_DATE = LocalDate.parse("2026-08-24");
    private static final Instant PERIOD_START = Instant.parse("2026-08-24T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-08-30T23:59:59.999999999Z");

    private final StarlingApiService starlingApiService = mock(StarlingApiService.class);
    private final RoundUpService service = new RoundUpService(starlingApiService);

    @Test
    void transfersCalculationIntoExistingGoalInOrder() {
        when(starlingApiService.getAccounts()).thenReturn(List.of(primaryAccount()));
        when(starlingApiService.getFeedItems(ACCOUNT_UID, CATEGORY_UID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(feedItem(435), feedItem(520), feedItem(87)));
        when(starlingApiService.getSavingsGoals(ACCOUNT_UID))
                .thenReturn(List.of(new SavingsGoal(GOAL_UID, "Weekly Round-ups", "ACTIVE")));

        RoundUpResult result = service.roundup(START_DATE, TRANSFER_UID);

        assertThat(result.periodStart()).isEqualTo(PERIOD_START);
        assertThat(result.periodEnd()).isEqualTo(PERIOD_END);
        assertThat(result.total()).isEqualTo(new Money("GBP", 158));
        assertThat(result.eligibleTransactionCount()).isEqualTo(3);
        assertThat(result.transferUid()).isEqualTo(TRANSFER_UID);
        InOrder calls = inOrder(starlingApiService);
        calls.verify(starlingApiService).getAccounts();
        calls.verify(starlingApiService)
                .getFeedItems(ACCOUNT_UID, CATEGORY_UID, PERIOD_START, PERIOD_END);
        calls.verify(starlingApiService).getSavingsGoals(ACCOUNT_UID);
        calls.verify(starlingApiService)
                .addMoney(
                        ACCOUNT_UID,
                        GOAL_UID,
                        TRANSFER_UID,
                        new Money("GBP", 158),
                        "round-up:" + START_DATE);
    }

    @Test
    void createsGoalWhenNoExactNameMatchExists() {
        when(starlingApiService.getAccounts()).thenReturn(List.of(primaryAccount()));
        when(starlingApiService.getFeedItems(ACCOUNT_UID, CATEGORY_UID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(feedItem(101)));
        when(starlingApiService.getSavingsGoals(ACCOUNT_UID)).thenReturn(List.of());
        when(starlingApiService.createSavingsGoal(ACCOUNT_UID, "Weekly Round-ups"))
                .thenReturn(GOAL_UID);

        service.roundup(START_DATE, TRANSFER_UID);

        verify(starlingApiService)
                .addMoney(
                        ACCOUNT_UID,
                        GOAL_UID,
                        TRANSFER_UID,
                        new Money("GBP", 99),
                        "round-up:" + START_DATE);
    }

    @Test
    void zeroTotalSkipsAllSavingsGoalCalls() {
        when(starlingApiService.getAccounts()).thenReturn(List.of(primaryAccount()));
        when(starlingApiService.getFeedItems(ACCOUNT_UID, CATEGORY_UID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(feedItem(500)));

        RoundUpResult result = service.roundup(START_DATE, TRANSFER_UID);

        assertThat(result.periodStart()).isEqualTo(PERIOD_START);
        assertThat(result.periodEnd()).isEqualTo(PERIOD_END);
        assertThat(result.transferUid()).isNull();
        verify(starlingApiService, never()).getSavingsGoals(ACCOUNT_UID);
        verify(starlingApiService, never()).createSavingsGoal(any(), any());
        verify(starlingApiService, never()).addMoney(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAmbiguousPrimaryAccountsBeforeReadingFeed() {
        when(starlingApiService.getAccounts())
                .thenReturn(List.of(primaryAccount(), primaryAccount()));

        assertThatThrownBy(() -> service.roundup(START_DATE, TRANSFER_UID))
                .isInstanceOf(SelectionConflictException.class)
                .hasMessage("Expected exactly one GBP primary account but found 2");
        verify(starlingApiService, never()).getFeedItems(any(), any(), any(), any());
    }

    @Test
    void skipsNonActiveGoalsAndSelectsTheActiveMatch() {
        UUID archivedGoalUid = UUID.fromString("eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee");
        when(starlingApiService.getAccounts()).thenReturn(List.of(primaryAccount()));
        when(starlingApiService.getFeedItems(ACCOUNT_UID, CATEGORY_UID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(feedItem(101)));
        when(starlingApiService.getSavingsGoals(ACCOUNT_UID))
                .thenReturn(
                        List.of(
                                new SavingsGoal(archivedGoalUid, "Weekly Round-ups", "ARCHIVED"),
                                new SavingsGoal(GOAL_UID, "Weekly Round-ups", "ACTIVE")));

        service.roundup(START_DATE, TRANSFER_UID);

        verify(starlingApiService)
                .addMoney(
                        ACCOUNT_UID,
                        GOAL_UID,
                        TRANSFER_UID,
                        new Money("GBP", 99),
                        "round-up:" + START_DATE);
        verify(starlingApiService, never()).createSavingsGoal(any(), any());
    }

    @Test
    void selectsFirstOfMultipleActiveDuplicateGoals() {
        UUID firstGoalUid = UUID.fromString("eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee");
        when(starlingApiService.getAccounts()).thenReturn(List.of(primaryAccount()));
        when(starlingApiService.getFeedItems(ACCOUNT_UID, CATEGORY_UID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(feedItem(101)));
        when(starlingApiService.getSavingsGoals(ACCOUNT_UID))
                .thenReturn(
                        List.of(
                                new SavingsGoal(firstGoalUid, "Weekly Round-ups", "ACTIVE"),
                                new SavingsGoal(GOAL_UID, "Weekly Round-ups", "ACTIVE")));

        service.roundup(START_DATE, TRANSFER_UID);

        verify(starlingApiService)
                .addMoney(
                        ACCOUNT_UID,
                        firstGoalUid,
                        TRANSFER_UID,
                        new Money("GBP", 99),
                        "round-up:" + START_DATE);
    }

    @Test
    void createsGoalWhenOnlyNonActiveOrUnusableMatchesExist() {
        when(starlingApiService.getAccounts()).thenReturn(List.of(primaryAccount()));
        when(starlingApiService.getFeedItems(ACCOUNT_UID, CATEGORY_UID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(feedItem(101)));
        when(starlingApiService.getSavingsGoals(ACCOUNT_UID))
                .thenReturn(
                        List.of(
                                new SavingsGoal(
                                        UUID.fromString("eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee"),
                                        "Weekly Round-ups",
                                        "ARCHIVED"),
                                new SavingsGoal(null, "Weekly Round-ups", "ACTIVE")));
        when(starlingApiService.createSavingsGoal(ACCOUNT_UID, "Weekly Round-ups"))
                .thenReturn(GOAL_UID);

        service.roundup(START_DATE, TRANSFER_UID);

        verify(starlingApiService)
                .addMoney(
                        ACCOUNT_UID,
                        GOAL_UID,
                        TRANSFER_UID,
                        new Money("GBP", 99),
                        "round-up:" + START_DATE);
    }

    private Account primaryAccount() {
        return new Account(ACCOUNT_UID, "PRIMARY", CATEGORY_UID, "GBP");
    }

    private FeedItem feedItem(long minorUnits) {
        return new FeedItem(
                new CurrencyAndAmount("GBP", minorUnits),
                null,
                "OUT",
                "SETTLED",
                "MASTER_CARD",
                null,
                Instant.parse("2026-08-28T12:00:00Z"));
    }
}
