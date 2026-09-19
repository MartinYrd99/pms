package com.pms.vehicle;

import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleService;
import com.pms.vehicle.request.VehicleRequest;
import com.pms.vehicle.response.VehicleResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
public class VehicleController {
    private final VehicleService vehicleService;

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<VehicleResponse> listOwn(@AuthenticationPrincipal Long userId) {
        return vehicleService.listOwnedBy(userId).stream().map(this::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse register(@AuthenticationPrincipal Long userId, @Valid @RequestBody VehicleRequest request) {
        Vehicle vehicle = vehicleService.register(userId, request.plate(), request.brand(), request.model());

        return toResponse(vehicle);
    }

    private VehicleResponse toResponse(Vehicle vehicle) {
        return new VehicleResponse(vehicle.getId(), vehicle.getPlate(), vehicle.getBrand(), vehicle.getModel());
    }
}
