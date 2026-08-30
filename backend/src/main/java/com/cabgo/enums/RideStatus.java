package com.cabgo.enums;

public enum RideStatus {
    PENDING_PAYMENT,     // Created, waiting for Cashfree payment
    REQUESTED,
    SEARCHING,
    DRIVER_ASSIGNED,
    DRIVER_ARRIVING,
    ARRIVED,
    ACCEPTED,
    RIDE_STARTED,
    ONGOING,
    COMPLETED,
    CANCELLED
}
