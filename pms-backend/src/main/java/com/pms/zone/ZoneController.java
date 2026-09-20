package com.pms.zone;

import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneService;
import com.pms.zone.core.ZoneWithCurrentTariff;
import com.pms.zone.core.tariff.Tariff;
import com.pms.zone.response.ZoneResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/zones")
@RequiredArgsConstructor
public class ZoneController {
    private final ZoneService zoneService;

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<ZoneResponse> listActive() {
        return zoneService.listActiveZones().stream().map(this::toResponse).toList();
    }

    private ZoneResponse toResponse(ZoneWithCurrentTariff entry) {
        Zone zone = entry.zone();
        Tariff tariff = entry.tariff();

        return new ZoneResponse(
                zone.getId(), zone.getName(), zone.getCity(), tariff.getHourlyRate(), tariff.getCurrency(), tariff.getRuleType()
        );
    }
}