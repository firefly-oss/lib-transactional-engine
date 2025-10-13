package com.firefly.examples.tcc.ecommerce.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Represents a customer order request.
 */
public class OrderRequest {
    
    private String orderId;
    private String customerId;
    private List<OrderItem> items;
    private BigDecimal totalAmount;
    private String shippingAddress;
    
    public OrderRequest() {
    }
    
    public OrderRequest(String orderId, String customerId, List<OrderItem> items, 
                       BigDecimal totalAmount, String shippingAddress) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.items = items;
        this.totalAmount = totalAmount;
        this.shippingAddress = shippingAddress;
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
    
    public String getCustomerId() {
        return customerId;
    }
    
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    
    public List<OrderItem> getItems() {
        return items;
    }
    
    public void setItems(List<OrderItem> items) {
        this.items = items;
    }
    
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
    
    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
    
    public String getShippingAddress() {
        return shippingAddress;
    }
    
    public void setShippingAddress(String shippingAddress) {
        this.shippingAddress = shippingAddress;
    }
    
    public static class Builder {
        private String orderId;
        private String customerId;
        private List<OrderItem> items;
        private BigDecimal totalAmount;
        private String shippingAddress;
        
        public Builder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }
        
        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }
        
        public Builder items(List<OrderItem> items) {
            this.items = items;
            return this;
        }
        
        public Builder totalAmount(BigDecimal totalAmount) {
            this.totalAmount = totalAmount;
            return this;
        }
        
        public Builder shippingAddress(String shippingAddress) {
            this.shippingAddress = shippingAddress;
            return this;
        }
        
        public OrderRequest build() {
            return new OrderRequest(orderId, customerId, items, totalAmount, shippingAddress);
        }
    }
}

