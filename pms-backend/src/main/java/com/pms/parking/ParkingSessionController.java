package com.pms.parking;

import com.pms.common.response.PageResponse;
import com.pms.parking.core.ParkingSessionService;
import com.pms.parking.core.StartedSession;
import com.pms.parking.request.StartParkingSessionRequest;
import com.pms.parking.response.ParkingSessionResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parking-sessions")
@Slf4j
@RequiredArgsConstructor
public class ParkingSessionController {
    private final ParkingSessionService parkingSessionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ParkingSessionResponse start(@AuthenticationPrincipal Long userId, @Valid @RequestBody StartParkingSessionRequest request) {
        log.info("POST /api/v1/parking-sessions for user {} (vehicle {}, zone {})", userId, request.vehicleId(), request.zoneId());

        StartedSession started = parkingSessionService.start(userId, request.vehicleId(), request.zoneId());

        return ParkingSessionResponse.from(started);
    }

    @GetMapping("/active")
    @ResponseStatus(HttpStatus.OK)
    public List<ParkingSessionResponse> active(@AuthenticationPrincipal Long userId) {
        log.info("GET /api/v1/parking-sessions/active for user {}", userId);

        return parkingSessionService.listActive(userId).stream().map(ParkingSessionResponse::from).toList();
    }

    @PostMapping("/{id}/end")
    @ResponseStatus(HttpStatus.OK)
    public ParkingSessionResponse end(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        log.info("POST /api/v1/parking-sessions/{}/end for user {}", id, userId);

        StartedSession ended = parkingSessionService.end(userId, id);

        return ParkingSessionResponse.from(ended);
    }

    @GetMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public ParkingSessionResponse get(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        log.info("GET /api/v1/parking-sessions/{} for user {}", id, userId);

        StartedSession session = parkingSessionService.get(userId, id);

        return ParkingSessionResponse.from(session);
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public PageResponse<ParkingSessionResponse> history(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("GET /api/v1/parking-sessions for user {} (page={}, size={})", userId, page, size);

        Page<StartedSession> sessions = parkingSessionService.history(userId, page, size);

        return PageResponse.from(sessions.map(ParkingSessionResponse::from));
    }
}
