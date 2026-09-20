package com.pms.zone.core;

import static java.util.Objects.nonNull;

import com.pms.zone.core.tariff.Tariff;
import com.pms.zone.core.tariff.TariffRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoneService {
    private final ZoneRepository zoneRepository;
    private final TariffRepository tariffRepository;

    /**
     * A zone is only offered while it is active and only once it carries a current tariff.
     * Both lookups are batched so the catalogue costs two
     * queries regardless of how many zones exist.
     */
    public List<ZoneWithCurrentTariff> listActiveZones() {
        List<Zone> activeZones = zoneRepository.findByActiveTrue();

        Map<Long, Tariff> currentTariffsByZoneId = tariffRepository
                .findByZoneIdInAndValidToIsNull(activeZones.stream().map(Zone::getId).toList())
                .stream()
                .collect(Collectors.toMap(Tariff::getZoneId, Function.identity()));

        return activeZones.stream()
                .map(zone -> new ZoneWithCurrentTariff(zone, currentTariffsByZoneId.get(zone.getId())))
                .filter(entry -> nonNull(entry.tariff()))
                .toList();
    }
}