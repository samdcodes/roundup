package com.roundup.model.starling;

import java.util.UUID;

public record TransferResponse(UUID transferUid, boolean success) {
}
