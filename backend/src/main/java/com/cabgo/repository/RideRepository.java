package com.cabgo.repository;

import com.cabgo.enums.RideStatus;
import com.cabgo.enums.VehicleCategory;
import com.cabgo.model.Ride;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RideRepository extends MongoRepository<Ride, String> {
    Page<Ride> findByCustomerId(String customerId, Pageable pageable);
    List<Ride> findByCustomerIdAndStatus(String customerId, RideStatus status);
    Optional<Ride> findFirstByCustomerIdAndStatusInOrderByCreatedAtDesc(String customerId, List<RideStatus> statuses);
    Page<Ride> findByDriverId(String driverId, Pageable pageable);

    @Query("{ '$or': [ { 'id': { '$regex': ?0, '$options': 'i' } }, { 'customerName': { '$regex': ?0, '$options': 'i' } }, { 'driverName': { '$regex': ?0, '$options': 'i' } } ] }")
    Page<Ride> searchRides(String search, Pageable pageable);

    Page<Ride> findByStatus(RideStatus status, Pageable pageable);
    
    List<Ride> findByStatusAndVehicleCategory(RideStatus status, VehicleCategory vehicleCategory);
    
    List<Ride> findByDriverIdAndStatusIn(String driverId, List<RideStatus> statuses);
    
    long countByStatus(RideStatus status);
    
    @Query("{ createdAt: { $gte: ?0, $lte: ?1 } }")
    List<Ride> findByDateRange(LocalDateTime from, LocalDateTime to);

    List<Ride> findByCustomerWhatsappPhone(String whatsappPhone);

    Optional<Ride> findFirstByCustomerWhatsappPhoneAndStatusInOrderByBookingTimeDesc(String whatsappPhone, List<RideStatus> statuses);

    Optional<Ride> findByCashfreeOrderId(String cashfreeOrderId);
}
