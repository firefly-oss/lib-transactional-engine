package com.firefly.examples.tcc.ecommerce.model;

/**
 * Status of an order.
 */
public enum OrderStatus {
    /**
     * Order has been confirmed and all reservations are committed.
     */
    CONFIRMED,
    
    /**
     * Order processing failed and all reservations were canceled.
     */
    FAILED,
    
    /**
     * Order is being processed.
     */
    PROCESSING
}

