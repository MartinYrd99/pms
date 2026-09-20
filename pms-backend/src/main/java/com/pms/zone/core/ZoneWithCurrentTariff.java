package com.pms.zone.core;

import com.pms.zone.core.tariff.Tariff;

public record ZoneWithCurrentTariff(Zone zone, Tariff tariff) {
}