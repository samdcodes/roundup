package com.roundup.model.starling;

import java.util.List;

public record AccountsResponse(List<Account> accounts) {

    public AccountsResponse {
        // ensure immutability
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }
}
