package com.cabgo.config;

import com.cabgo.enums.DriverStatus;
import com.cabgo.enums.VehicleCategory;
import com.cabgo.enums.VerificationStatus;
import com.cabgo.model.Driver;
import com.cabgo.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Seeds dummy drivers into MongoDB on every startup if no drivers exist.
 * All drivers are placed around Bangalore and set ONLINE + APPROVED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DummyDriverSeeder implements CommandLineRunner {

    private final DriverRepository driverRepository;

    @Override
    public void run(String... args) {
        long count = driverRepository.count();
        if (count > 0) {
            // Drivers already exist — reset them all back to ONLINE + available
            // so that dummy drivers don't get permanently stuck as BUSY after a completed ride.
            List<Driver> all = driverRepository.findAll();
            all.forEach(d -> {
                d.setStatus(DriverStatus.ONLINE);
                d.setAvailableForRide(true);
            });
            driverRepository.saveAll(all);
            log.info("[DummyDriverSeeder] {} drivers reset to ONLINE + available.", all.size());
            return;
        }

        log.info("[DummyDriverSeeder] No drivers found — seeding dummy drivers...");

        List<Driver> drivers = List.of(
            buildDriver("Rajesh Kumar",      "9845012345", "rajesh@vazraa.com",  "KA01AB1234", "Maruti Swift",     VehicleCategory.SEDAN,  "White",  12.9716,  77.5946), // MG Road
            buildDriver("Suresh Nair",       "9741023456", "suresh@vazraa.com",  "KA02CD5678", "Honda City",       VehicleCategory.SEDAN,  "Silver", 12.9352,  77.6245), // Koramangala
            buildDriver("Anand Reddy",       "9632034567", "anand@vazraa.com",   "KA03EF9012", "Toyota Innova",    VehicleCategory.SUV,    "White",  13.0358,  77.5970), // Hebbal
            buildDriver("Priya Sharma",      "9538045678", "priya@vazraa.com",   "KA04GH3456", "Maruti Swift Dzire", VehicleCategory.SEDAN, "Grey",   12.9141,  77.6393), // BTM Layout
            buildDriver("Mohammed Ali",      "9449056789", "mali@vazraa.com",    "KA05IJ7890", "Hyundai Verna",    VehicleCategory.SEDAN,  "Black",  12.9800,  77.6408), // Indiranagar
            buildDriver("Kiran Gowda",       "9360067890", "kiran@vazraa.com",   "KA06KL2345", "Mahindra XUV300",  VehicleCategory.SUV,    "Red",    12.8845,  77.5761), // Bannerghatta
            buildDriver("Deepa Venkatesh",   "9271078901", "deepa@vazraa.com",   "KA07MN6789", "Maruti Ertiga",    VehicleCategory.SUV,    "White",  13.0012,  77.5762), // Yeshwanthpur
            buildDriver("Santosh Pillai",    "9182089012", "santosh@vazraa.com", "KA08OP0123", "Toyota Etios",     VehicleCategory.SEDAN,  "Blue",   12.9592,  77.6974)  // Whitefield
        );

        driverRepository.saveAll(drivers);
        log.info("[DummyDriverSeeder] ✅ Seeded {} dummy drivers successfully.", drivers.size());
    }

    private Driver buildDriver(String name, String phone, String email,
                               String vehicleNumber, String vehicleModel,
                               VehicleCategory category, String color,
                               double lat, double lng) {
        return Driver.builder()
            .name(name)
            .phone(phone)
            .email(email)
            .password("dummy123")
            .vehicleNumber(vehicleNumber)
            .vehicleModel(vehicleModel)
            .vehicleCategory(category)
            .vehicleColor(color)
            .latitude(lat)
            .longitude(lng)
            .status(DriverStatus.ONLINE)
            .verificationStatus(VerificationStatus.APPROVED)
            .availableForRide(true)
            .rating(4.5)
            .totalRides(50)
            .totalEarnings(15000.0)
            .whatsappPhone("91" + phone)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }
}
