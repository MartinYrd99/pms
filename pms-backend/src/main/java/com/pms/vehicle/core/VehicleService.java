package com.pms.vehicle.core;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class VehicleService {
    private static final String PLATE_TAKEN_CODE = "validation.vehicle.plate-taken";

    private final VehicleRepository vehicleRepository;

    @Transactional(readOnly = true)
    public List<Vehicle> listOwnedBy(Long userId) {
        return vehicleRepository.findByUserId(userId);
    }

    /**
     * A plate is unique system-wide, so a duplicate is refused with the same conflict regardless of
     * who owns the existing one. The pre-check only produces a friendly message; the database's
     * unique constraint is the final authority, so a race that slips past the pre-check is still
     * caught here and mapped to the same conflict.
     */
    public Vehicle register(Long userId, String plate, String brand, String model) {
        if (vehicleRepository.existsByPlate(plate)) {
            throw new IllegalStateException(PLATE_TAKEN_CODE);
        }

        Vehicle vehicle = new Vehicle()
                .setUserId(userId)
                .setPlate(plate)
                .setBrand(brand)
                .setModel(model);

        try {
            return vehicleRepository.save(vehicle);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(PLATE_TAKEN_CODE);
        }
    }
}