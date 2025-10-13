package com.firefly.examples.tcc.ecommerce.model;

import java.time.Instant;
import java.util.List;

/**
 * Represents an inventory reservation.
 */
public class InventoryReservation {
    
    private String reservationId;
    private List<OrderItem> items;
    private ReservationStatus status;
    private Instant createdAt;
    private Instant expiresAt;
    
    public InventoryReservation() {
    }
    
    public InventoryReservation(String reservationId, List<OrderItem> items, ReservationStatus status) {
        this.reservationId = reservationId;
        this.items = items;
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
    
    public List<OrderItem> getItems() {
        return items;
    }
    
    public void setItems(List<OrderItem> items) {
        this.items = items;
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

