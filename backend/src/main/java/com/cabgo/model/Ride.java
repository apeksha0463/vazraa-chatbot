package com.cabgo.model;

import com.cabgo.enums.PaymentMethod;
import com.cabgo.enums.RideStatus;
import com.cabgo.enums.VehicleCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "rides")
public class Ride {
    @Id
    private String id;
    
    private String customerId;
    private String customerName;
    private String driverId;
    private String driverName;
    private String cityId;
    
    private String pickupLocation;
    private Double pickupLatitude;
    private Double pickupLongitude;
    
    private String dropLocation;
    private Double dropLatitude;
    private Double dropLongitude;
    
    private VehicleCategory vehicleCategory;
    private RideStatus status;
    
    private Double estimatedFare;
    private Double actualFare;
    private Double fare; // Final fare
    
    private Double distance; // in km
    private Double duration; // in minutes
    
    private PaymentMethod paymentMethod; // CASH, WALLET, UPI, CARD
    private String paymentStatus; // PENDING, PAID, FAILED

    // ── Cashfree payment reference ────────────────────────────────────────────
    private String cashfreeOrderId;     // Cashfree order ID linked to this ride
    private String cashfreePaymentLink; // Hosted payment URL sent to customer
    
    private String otp; // For ride start verification
    
    private String cancellationReason;
    private String cancelledBy;
    private LocalDateTime cancelledAt;
    
    private LocalDateTime bookingTime;
    private LocalDateTime requestedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime startTime;
    private LocalDateTime startedAt;
    private LocalDateTime endTime;
    private LocalDateTime completedAt;
    
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private String customerWhatsappPhone; // for WhatsApp notifications
    private String driverPhone;           // driver's phone for WhatsApp
    private String driverVehicleNumber;   // convenience field
}
