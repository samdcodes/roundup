package com.roundup.model.starling;

import java.util.List;

public record SavingsGoalsResponse(List<SavingsGoal> savingsGoalList) {

    public SavingsGoalsResponse {
        savingsGoalList = savingsGoalList == null ? List.of() : List.copyOf(savingsGoalList);
    }
}
