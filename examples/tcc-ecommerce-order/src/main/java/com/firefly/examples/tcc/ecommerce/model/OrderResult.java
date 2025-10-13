package com.firefly.examples.tcc.ecommerce.model;

/**
 * Result of order processing.
 */
public class OrderResult {
    
    private String orderId;
    private OrderStatus status;
    private String paymentReservationId;
    private String inventoryReservationId;
    private String shippingReservationId;
    private String failedStep;
    private String failureReason;
    private String message;
    
    public OrderResult() {
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public String getOrderId() {
        return orderId;
    }
    
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
    
    public OrderStatus getStatus() {
        return status;
    }
    
    public void setStatus(OrderStatus status) {
        this.status = status;
    }
    
    public String getPaymentReservationId() {
        return paymentReservationId;
    }
    
    public void setPaymentReservationId(String paymentReservationId) {
        this.paymentReservationId = paymentReservationId;
    }
    
    public String getInventoryReservationId() {
        return inventoryReservationId;
    }
    
    public void setInventoryReservationId(String inventoryReservationId) {
        this.inventoryReservationId = inventoryReservationId;
    }
    
    public String getShippingReservationId() {
        return shippingReservationId;
    }
    
    public void setShippingReservationId(String shippingReservationId) {
        this.shippingReservationId = shippingReservationId;
    }
    
    public String getFailedStep() {
        return failedStep;
    }
    
    public void setFailedStep(String failedStep) {
        this.failedStep = failedStep;
    }
    
    public String getFailureReason() {
        return failureReason;
    }
    
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public static class Builder {
        private String orderId;
        private OrderStatus status;
        private String paymentReservationId;
        private String inventoryReservationId;
        private String shippingReservationId;
        private String failedStep;
        private String failureReason;
        private String message;
        
        public Builder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }
        
        public Builder status(OrderStatus status) {
            this.status = status;
            return this;
        }
        
        public Builder paymentReservationId(String paymentReservationId) {
            this.paymentReservationId = paymentReservationId;
            return this;
        }
        
        public Builder inventoryReservationId(String inventoryReservationId) {
            this.inventoryReservationId = inventoryReservationId;
            return this;
        }
        
        public Builder shippingReservationId(String shippingReservationId) {
            this.shippingReservationId = shippingReservationId;
            return this;
        }
        
        public Builder failedStep(String failedStep) {
            this.failedStep = failedStep;
            return this;
        }
        
        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }
        
        public Builder message(String message) {
            this.message = message;
            return this;
        }
        
        public OrderResult build() {
            OrderResult result = new OrderResult();
            result.orderId = this.orderId;
            result.status = this.status;
            result.paymentReservationId = this.paymentReservationId;
            result.inventoryReservationId = this.inventoryReservationId;
            result.shippingReservationId = this.shippingReservationId;
            result.failedStep = this.failedStep;
            result.failureReason = this.failureReason;
            result.message = this.message;
            return result;
        }
    }
}

