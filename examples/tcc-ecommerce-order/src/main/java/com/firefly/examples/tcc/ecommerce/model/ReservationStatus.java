package com.firefly.examples.tcc.ecommerce.model;

/**
 * Status of a reservation.
 */
public enum ReservationStatus {
    /**
     * Reservation has been created and resources are locked.
     */
    RESERVED,
    
    /**
     * Reservation has been confirmed and resources are committed.
     */
    CONFIRMED,
    
    /**
     * Reservation has been canceled and resources are released.
     */
    CANCELED,
    
    /**
     * Reservation has expired.
     */
    EXPIRED
}

