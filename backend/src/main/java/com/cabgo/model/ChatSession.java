package com.cabgo.model;

import com.cabgo.enums.ConversationState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "chat_sessions")
public class ChatSession {

    @Id
    private String id;

    @Indexed(unique = true)
    private String whatsappPhone; // e.g. "919876543210" (country code + number)

    private String customerId;   // linked Customer._id (null until first booking)
    private String customerName;

    @Builder.Default
    private ConversationState state = ConversationState.IDLE;

    // ── Temporary booking data collected during conversation ──────────────────
    private String tempPickupAddress;
    private Double tempPickupLat;
    private Double tempPickupLng;

    private String tempDropAddress;
    private Double tempDropLat;
    private Double tempDropLng;

    private Double tempFare;
    private Double tempDistance;
    private Double tempDuration;

    @Builder.Default
    private String tempVehicleCategory = "SEDAN";

    // ── Temporary driver registration data ────────────────────────────────────
    private String tempDriverName;
    private String tempDriverEmail;
    private String tempDriverCity;
    private String tempDriverVehicleCategory;
    private String tempDriverVehicleModel;
    private String tempDriverVehicleNumber;
    private String tempDriverVehicleColor;
    private String tempDriverAadhaar;
    private String tempDriverLicense;
    private String tempDriverSelfie;
    private String tempDriverAadhaarImage;
    private String tempDriverLicenseImage;
    private String tempDriverRcImage;
    private String tempDriverVehicleImage;

    // ── Active ride tracking ──────────────────────────────────────────────────
    private String activeRideId;

    // ── Cashfree payment tracking ─────────────────────────────────────────────
    private String pendingCashfreeOrderId;  // Cashfree order ID while awaiting payment
    private String pendingRideId;           // Ride created but not yet confirmed (payment pending)
    private String pendingPaymentLink;      // Cashfree payment link sent to customer

    @Builder.Default
    private List<String> rejectedDriverIds = new ArrayList<>();

    // ── Timestamps ────────────────────────────────────────────────────────────
    private LocalDateTime lastMessageAt;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
