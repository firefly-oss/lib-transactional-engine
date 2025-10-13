package com.firefly.examples.tcc.ecommerce.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a payment reservation.
 */
public class PaymentReservation {
    
    private String reservationId;
    private String customerId;
    private BigDecimal amount;
    private ReservationStatus status;
    private Instant createdAt;
    private Instant expiresAt;
    
    public PaymentReservation() {
    }
    
    public PaymentReservation(String reservationId, String customerId, BigDecimal amount, 
                             ReservationStatus status) {
        this.reservationId = reservationId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.createdAt = Instant.now();
        this.expiresAt = Instant.now().plusSeconds(300); // 5 minutes
    }
    
    public String getReservationId() {
        return reservationId;
    }
    
    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }
    
    public String getCustomerId() {
        return customerId;
    }
    
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
    
    public ReservationStatus getStatus() {
        return status;
    }
    
    public void setStatus(ReservationStatus status) {
        this.status = status;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}

