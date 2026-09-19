package com.pms.payment.core;

/**
 * The result of a pay request: tells the caller whether a new payment was just
 * inserted (201) or an already-live payment was handed back unchanged (200).
 */
public record PaymentOutcome(Payment payment, boolean created) {
}