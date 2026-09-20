package com.pms.zone.core.tariff;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TariffRepository extends JpaRepository<Tariff, Long> {
    Optional<Tariff> findByZoneIdAndValidToIsNull(Long zoneId);

    List<Tariff> findByZoneIdInAndValidToIsNull(Collection<Long> zoneIds);
}
