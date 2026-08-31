package com.cabgo.service;

import com.cabgo.enums.DriverStatus;
import com.cabgo.enums.VerificationStatus;
import com.cabgo.model.Driver;
import com.cabgo.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverMatchingService {

    private final DriverRepository driverRepository;
    private final GoogleMapsService mapsService;

    @Value("${cab.matching.radius-km:10.0}")
    private double radiusKm;

    /**
     * Find the nearest available ONLINE + APPROVED driver within radiusKm.
     */
    public Optional<Driver> findNearestDriver(double pickupLat, double pickupLng) {
        return findNearestDriver(pickupLat, pickupLng, List.of());
    }

    public Optional<Driver> findNearestDriver(double pickupLat, double pickupLng, List<String> excludeDriverIds) {
        List<Driver> onlineDrivers = driverRepository
            .findByStatusAndVerificationStatus(DriverStatus.ONLINE, VerificationStatus.APPROVED);

        return onlineDrivers.stream()
            .filter(d -> d.getLatitude() != null && d.getLongitude() != null)
            .filter(d -> d.getAvailableForRide() == null || Boolean.TRUE.equals(d.getAvailableForRide()))
            .filter(d -> excludeDriverIds == null || !excludeDriverIds.contains(d.getId()))
            .map(d -> {
                double dist = mapsService.haversineKm(pickupLat, pickupLng, d.getLatitude(), d.getLongitude());
                return new DriverDistance(d, dist);
            })
            .filter(dd -> dd.distanceKm() <= radiusKm)
            .min(Comparator.comparingDouble(DriverDistance::distanceKm))
            .map(DriverDistance::driver);
    }

    public record DriverDistance(Driver driver, double distanceKm) {}
}
