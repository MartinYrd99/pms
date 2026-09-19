package com.pms.parking.core;

import lombok.Getter;

/**
 * Thrown when a vehicle already has an unsettled session — still parked, or ended but unpaid.
 * Carries the blocking session's id (nullable only for the rare race caught at the database
 * constraint rather than the pre-check) so the client can be sent straight to it.
 */
@Getter
public class UnsettledSessionException extends IllegalStateException {
    private final Long blockingSessionId;

    public UnsettledSessionException(String messageKey, Long blockingSessionId) {
        super(messageKey);
        this.blockingSessionId = blockingSessionId;
    }
}