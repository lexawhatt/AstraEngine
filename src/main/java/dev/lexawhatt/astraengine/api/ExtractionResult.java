package dev.lexawhatt.astraengine.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of a resource request. Only APPLIED represents a new change. REPLAY
 * reports the original amount for reconciliation and must not credit a consumer again.
 */
public record ExtractionResult(UUID operationId, long requested, long extracted, long revision, Status status) {
    public ExtractionResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(status, "status");
        if (requested <= 0 || extracted < 0 || extracted > requested || revision < 0) {
            throw new IllegalArgumentException("Invalid extraction result");
        }
    }

    /** Rejections never consume resource or allocate a new receipt. */
    public enum Status {
        APPLIED,
        REPLAY,
        CONFLICT,
        RECEIPT_LIMIT
    }
}
