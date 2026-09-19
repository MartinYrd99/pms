package com.pms.parking.core;

import lombok.Getter;

/**
 * Thrown when an end attempt loses the guarded update: the session was already ended by an
 * earlier request. Carries the session as it now stands in the database so the caller reads the
 * final amount out of the error instead of retrying blindly.
 */
@Getter
public class SessionAlreadyEndedException extends IllegalStateException {
    private final StartedSession endedSession;

    public SessionAlreadyEndedException(String messageKey, StartedSession endedSession) {
        super(messageKey);

        this.endedSession = endedSession;
    }
}