package com.pms.payment.core.provider;

import com.pms.payment.core.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class SimulatorPaymentProvider implements PaymentProvider {
    private final boolean forcedFailure;

    SimulatorPaymentProvider(@Value("${pms.payment.simulator.fail:false}") boolean forcedFailure) {
        this.forcedFailure = forcedFailure;
    }

    @Override
    public boolean charge(Payment payment) {
        log.debug("Simulating charge for payment {} (forcedFailure={})", payment.getId(), forcedFailure);

        return !forcedFailure;
    }
}
