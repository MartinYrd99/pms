package com.pms.payment.core;

public interface PaymentProvider {
    /**
     * Attempts to charge a payment. Returns {@code true} on success, {@code false} on a normal
     * decline; throwing signals an unexpected provider error and must leave nothing half-written.
     */
    boolean charge(Payment payment);
}
