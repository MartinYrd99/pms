package com.pms.zone.response;

import com.pms.zone.core.RuleType;
import java.math.BigDecimal;

public record ZoneResponse(Long id, String name, String city, BigDecimal hourlyRate, String currency, RuleType ruleType) {
}
