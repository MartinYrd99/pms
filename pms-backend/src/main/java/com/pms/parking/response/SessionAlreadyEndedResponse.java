package com.pms.parking.response;

public record SessionAlreadyEndedResponse(String code, String message, ParkingSessionResponse session) {
}