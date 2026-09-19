package com.pms.vehicle.request;

import jakarta.validation.constraints.NotBlank;

public record VehicleRequest(
        @NotBlank String plate,
        @NotBlank String brand,
        @NotBlank String model) {
}