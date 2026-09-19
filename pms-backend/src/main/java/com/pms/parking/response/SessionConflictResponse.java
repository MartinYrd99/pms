package com.pms.parking.response;

public record SessionConflictResponse(String code, String message, Long blockingSessionId) {
}