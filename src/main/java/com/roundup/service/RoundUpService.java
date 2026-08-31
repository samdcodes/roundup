package com.roundup.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.roundup.exception.SelectionConflictException;
import com.roundup.model.Money;
import com.roundup.model.RoundUpCalculation;
import com.roundup.model.TransactionPeriod;
import com.roundup.model.api.RoundUpResult;
import com.roundup.model.starling.Account;
import com.roundup.model.starling.FeedItem;
import com.roundup.model.starling.SavingsGoal;

@Service
public class RoundUpService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoundUpService.class);
    private static final String SAVINGS_GOAL_NAME = "Weekly Round-ups";
    private static final String ACTIVE_GOAL_STATE = "ACTIVE";
    private static final String TRANSFER_REFERENCE_PREFIX = "round-up:";

    private final StarlingApiService starlingApiService;

    public RoundUpService(StarlingApiService starlingApiService) {
        this.starlingApiService = starlingApiService;
    }

    public RoundUpResult roundup(LocalDate startDate, UUID transferUid) {
        TransactionPeriod transactionPeriod = new TransactionPeriod(startDate);
        LOGGER.info(
                "Starting round-up: transferUid={}, periodStart={}, periodEnd={}",
                transferUid,
                transactionPeriod.startInclusive(),
                transactionPeriod.endInclusive()
        );

        // get accounts
        List<Account> accounts = starlingApiService.getAccounts();
        LOGGER.info("Retrieved accounts: count={}", accounts.size());

        // Choose account for roundups
        Account account = selectAccount(accounts);
        LOGGER.info(
                "Selected account: accountUid={}, defaultCategoryUid={}",
                account.accountUid(),
                account.defaultCategory()
        );

        // Get the feed items for the chosen account
        List<FeedItem> feedItems = starlingApiService.getFeedItems(
                account.accountUid(),
                account.defaultCategory(),
                transactionPeriod.startInclusive(),
                transactionPeriod.endInclusive()
        );
        LOGGER.info(
                "Retrieved feed items: accountUid={}, count={}, periodStart={}, periodEnd={}",
                account.accountUid(),
                feedItems.size(),
                transactionPeriod.startInclusive(),
                transactionPeriod.endInclusive()
        );

        // Calculate round-up
        RoundUpCalculation calculation = calculate(feedItems, transactionPeriod);
        LOGGER.info(
                "Calculated round-up: transferUid={}, eligibleTransactionCount={}, totalMinorUnits={}",
                transferUid,
                calculation.eligibleTransactionCount(),
                calculation.total().minorUnits());

        // if nothing to round up, don't attempt to create goal or make transfer
        if (calculation.total().minorUnits() == 0) {
            LOGGER.info("Skipping transfer for zero round-up total: transferUid={}", transferUid);
            return new RoundUpResult(
                    transactionPeriod.startInclusive(),
                    transactionPeriod.endInclusive(),
                    calculation.eligibleTransactionCount(),
                    calculation.total(),
                    null);
        }

        // Get or create a savings goal
        UUID savingsGoalUid = findOrCreateSavingsGoal(account.accountUid());
        // transfer money
        transferRoundUp(transferUid, startDate, account, savingsGoalUid, calculation.total());

        return new RoundUpResult(
                transactionPeriod.startInclusive(),
                transactionPeriod.endInclusive(),
                calculation.eligibleTransactionCount(),
                calculation.total(),
                transferUid);
    }

    private void transferRoundUp(
            UUID transferUid,
            LocalDate startDate,
            Account account,
            UUID savingsGoalUid,
            Money amount) {
        starlingApiService.addMoney(
                account.accountUid(),
                savingsGoalUid,
                transferUid,
                amount,
                transferReference(startDate));
        LOGGER.info(
                "Transferred round-up: transferUid={}, accountUid={}, savingsGoalUid={}, totalMinorUnits={}",
                transferUid,
                account.accountUid(),
                savingsGoalUid,
                amount.minorUnits());
    }

    public RoundUpCalculation calculate(
            List<FeedItem> feedItems, TransactionPeriod transactionPeriod) {
        long totalMinorUnits = 0;
        int eligibleTransactionCount = 0;

        for (FeedItem feedItem : feedItems == null ? List.<FeedItem>of() : feedItems) {
            if (!isEligible(feedItem, transactionPeriod)) {
                continue;
            }
            // using 100 here as GBP, would need to be configurable based on currency to support all currencies
            long contribution = 100 - (feedItem.amount().minorUnits() % 100);
            if (contribution == 100) {
                contribution = 0;
            }
            totalMinorUnits += contribution;
            eligibleTransactionCount = Math.incrementExact(eligibleTransactionCount);
        }
        return new RoundUpCalculation(eligibleTransactionCount, new Money("GBP", totalMinorUnits));
    }

    private boolean isEligible(FeedItem feedItem, TransactionPeriod transactionPeriod) {
        return feedItem != null
                && feedItem.amount() != null
                && "GBP".equals(feedItem.amount().currency())
                && "OUT".equals(feedItem.direction())
                && !"INTERNAL_TRANSFER".equals(feedItem.source())
                && !isRoundUpTransfer(feedItem)
                && transactionPeriod != null
                && transactionPeriod.contains(feedItem.transactionTime());
    }

    private boolean isRoundUpTransfer(FeedItem feedItem) {
        return feedItem.reference() != null
                && feedItem.reference().startsWith(TRANSFER_REFERENCE_PREFIX);
    }

    // The reference marks the transfer as a roundup so it can easily be identified and excluded from future calculations.
    private String transferReference(LocalDate startDate) {
        return TRANSFER_REFERENCE_PREFIX + startDate;
    }

    private Account selectAccount(List<Account> accounts) {
        List<Account> matches =
                accounts == null ? List.of() : accounts.stream()
                        .filter(account -> "PRIMARY".equals(account.accountType()))
                        .filter(account -> "GBP".equals(account.currency()))
                        .toList();

        if (matches.size() != 1) {
            throw new SelectionConflictException(
                    "Expected exactly one GBP primary account but found " + matches.size());
        }
        Account account = matches.getFirst();
        if (account.accountUid() == null || account.defaultCategory() == null) {
            throw new SelectionConflictException("Selected account is missing required identifiers");
        }
        return account;
    }

    private UUID findOrCreateSavingsGoal(UUID accountUid) {
        List<SavingsGoal> savingsGoals = starlingApiService.getSavingsGoals(accountUid);
        LOGGER.info(
                "Retrieved savings goals: accountUid={}, count={}",
                accountUid,
                savingsGoals.size()
        );

        // Reuse the first active goal with the reserved name and a usable identifier.
        // If several active goals share the name I pick the first as a best effort.
        return savingsGoals.stream()
                .filter(goal -> SAVINGS_GOAL_NAME.equals(goal.name()))
                .filter(goal -> ACTIVE_GOAL_STATE.equals(goal.state()))
                .map(SavingsGoal::savingsGoalUid)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> {
                    UUID savingsGoalUid = starlingApiService.createSavingsGoal(accountUid, SAVINGS_GOAL_NAME);
                    LOGGER.info(
                            "Created savings goal: accountUid={}, savingsGoalUid={}",
                            accountUid,
                            savingsGoalUid);
                    return savingsGoalUid;
                });
    }
}
