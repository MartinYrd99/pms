package com.pms.auth.response;

public record LoginResponse(String accessToken, String refreshToken) {
}