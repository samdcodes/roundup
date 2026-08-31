package com.roundup.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.roundup.model.starling.Account;
import com.roundup.model.starling.AccountsResponse;
import com.roundup.model.starling.CurrencyAndAmount;
import com.roundup.model.starling.FeedItem;
import com.roundup.model.starling.FeedItemsResponse;
import com.roundup.model.starling.SavingsGoal;
import com.roundup.model.starling.SavingsGoalsResponse;

class ModelImmutabilityTests {

    @Test
    void accountsResponseDefensivelyCopiesItsList() {
        List<Account> source = new ArrayList<>();
        source.add(new Account(UUID.randomUUID(), "PRIMARY", UUID.randomUUID(), "GBP"));

        AccountsResponse response = new AccountsResponse(source);
        source.clear();

        assertThat(response.accounts()).hasSize(1);
        assertThatThrownBy(() -> response.accounts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void feedItemsResponseDefensivelyCopiesItsList() {
        List<FeedItem> source = new ArrayList<>();
        source.add(
                new FeedItem(
                        new CurrencyAndAmount("GBP", 101),
                        null,
                        "OUT",
                        "SETTLED",
                        "MASTER_CARD",
                        null,
                        Instant.EPOCH));

        FeedItemsResponse response = new FeedItemsResponse(source);
        source.clear();

        assertThat(response.feedItems()).hasSize(1);
        assertThatThrownBy(() -> response.feedItems().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void savingsGoalsResponseDefensivelyCopiesItsList() {
        List<SavingsGoal> source = new ArrayList<>();
        source.add(new SavingsGoal(UUID.randomUUID(), "Weekly Round-ups", "ACTIVE"));

        SavingsGoalsResponse response = new SavingsGoalsResponse(source);
        source.clear();

        assertThat(response.savingsGoalList()).hasSize(1);
        assertThatThrownBy(() -> response.savingsGoalList().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullCollectionsBecomeImmutableEmptyLists() {
        assertThat(new AccountsResponse(null).accounts()).isEmpty();
        assertThat(new FeedItemsResponse(null).feedItems()).isEmpty();
        assertThat(new SavingsGoalsResponse(null).savingsGoalList()).isEmpty();
    }
}
