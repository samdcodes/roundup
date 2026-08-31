package com.roundup.model.starling;

import java.util.UUID;

public record Account(UUID accountUid, String accountType, UUID defaultCategory, String currency) {
}
