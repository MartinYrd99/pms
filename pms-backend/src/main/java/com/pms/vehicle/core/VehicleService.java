package com.pms.vehicle.core;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class VehicleService {
    private static final String PLATE_TAKEN_CODE = "validation.vehicle.plate-taken";

    private final VehicleRepository vehicleRepository;

    @Transactional(readOnly = true)
    public List<Vehicle> listOwnedBy(Long userId) {
        List<Vehicle> vehicles = vehicleRepository.findByUserId(userId);

        log.info("Listed {} vehicle(s) owned by user {}", vehicles.size(), userId);

        return vehicles;
    }

    /**
     * A plate is unique system-wide, so a duplicate is refused with the same conflict regardless of
     * who owns the existing one. The pre-check only produces a friendly message; the database's
     * unique constraint is the final authority, so a race that slips past the pre-check is still
     * caught here and mapped to the same conflict.
     */
    @Transactional
    public Vehicle register(Long userId, String plate, String brand, String model) {
        log.info("Registering plate '{}' for user {}", plate, userId);

        if (vehicleRepository.existsByPlate(plate)) {
            log.info("Registration refused: plate '{}' is already taken", plate);

            throw new IllegalStateException(PLATE_TAKEN_CODE);
        }

        Vehicle vehicle = new Vehicle()
                .setUserId(userId)
                .setPlate(plate)
                .setBrand(brand)
                .setModel(model);

        try {
            Vehicle saved = vehicleRepository.save(vehicle);

            log.info("Created vehicle {} with plate '{}' for user {}", saved.getId(), plate, userId);

            return saved;
        } catch (DataIntegrityViolationException e) {
            log.info("Registration of plate '{}' lost the race for the unique plate index", plate);

            throw new IllegalStateException(PLATE_TAKEN_CODE);
        }
    }
}