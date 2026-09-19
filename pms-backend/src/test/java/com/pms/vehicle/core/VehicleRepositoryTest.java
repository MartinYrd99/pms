package com.pms.vehicle.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class VehicleRepositoryTest extends AbstractPostgresIT {
    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void findByUserIdReturnsOnlyRowsOwnedByThatUser() {
        User owner = createUser("owner");
        User otherUser = createUser("other");

        Vehicle ownedVehicle = vehicleRepository.save(new Vehicle()
                .setUserId(owner.getId())
                .setPlate(uniquePlate())
                .setBrand("Toyota")
                .setModel("Corolla"));

        vehicleRepository.save(new Vehicle()
                .setUserId(otherUser.getId())
                .setPlate(uniquePlate())
                .setBrand("Honda")
                .setModel("Civic"));

        List<Vehicle> found = vehicleRepository.findByUserId(owner.getId());

        assertThat(found).extracting(Vehicle::getId).containsExactly(ownedVehicle.getId());
    }

    private User createUser(String usernamePrefix) {
        User user = new User()
                .setUsername(usernamePrefix + "-" + UUID.randomUUID())
                .setPasswordHash(passwordEncoder.encode("irrelevant-password-1"));

        return userRepository.save(user);
    }

    private String uniquePlate() {
        return "PLATE-" + UUID.randomUUID();
    }
}
