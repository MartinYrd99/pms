package com.pms.parking;

import com.pms.parking.core.ParkingSessionService;
import com.pms.parking.core.StartedSession;
import com.pms.parking.request.StartParkingSessionRequest;
import com.pms.parking.response.ParkingSessionResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parking-sessions")
@RequiredArgsConstructor
public class ParkingSessionController {
    private final ParkingSessionService parkingSessionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ParkingSessionResponse start(@AuthenticationPrincipal Long userId, @Valid @RequestBody StartParkingSessionRequest request) {
        StartedSession started = parkingSessionService.start(userId, request.vehicleId(), request.zoneId());

        return ParkingSessionResponse.from(started);
    }

    @GetMapping("/active")
    @ResponseStatus(HttpStatus.OK)
    public List<ParkingSessionResponse> active(@AuthenticationPrincipal Long userId) {
        return parkingSessionService.listActive(userId).stream().map(ParkingSessionResponse::from).toList();
    }

    @PostMapping("/{id}/end")
    @ResponseStatus(HttpStatus.OK)
    public ParkingSessionResponse end(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        StartedSession ended = parkingSessionService.end(userId, id);

        return ParkingSessionResponse.from(ended);
    }
}