package com.firefly.examples.tcc.ecommerce;

import com.firefly.examples.tcc.ecommerce.model.*;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.engine.TccEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Main service for processing e-commerce orders using TCC pattern.
 */
@Service
public class OrderService {
    
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    
    @Autowired
    private TccEngine tccEngine;
    
    /**
     * Process an order using the TCC pattern.
     * 
     * This method coordinates payment, inventory, and shipping reservations.
     * If all reservations succeed, they are all confirmed.
     * If any reservation fails, all successful reservations are canceled.
     * 
     * @param order The order request
     * @return Order result with status and details
     */
    public Mono<OrderResult> processOrder(OrderRequest order) {
        log.info("Processing order: orderId={}, customerId={}, totalAmount={}", 
            order.getOrderId(), order.getCustomerId(), order.getTotalAmount());
        
        // Build inputs for all participants
        // All participants receive the same OrderRequest object
        TccInputs inputs = TccInputs.builder()
            .forParticipant("payment", order)
            .forParticipant("inventory", order)
            .forParticipant("shipping", order)
            .build();
        
        // Execute TCC transaction
        return tccEngine.execute("OrderProcessing", inputs)
            .map(result -> mapToOrderResult(order, result))
            .doOnSuccess(result -> 
                log.info("Order processing completed: orderId={}, status={}", 
                    order.getOrderId(), result.getStatus()))
            .doOnError(e -> 
                log.error("Order processing failed: orderId={}", order.getOrderId(), e));
    }
    
    /**
     * Maps TCC result to order result.
     */
    private OrderResult mapToOrderResult(OrderRequest order, TccResult result) {
        if (result.isSuccess()) {
            // All participants confirmed - extract reservation IDs
            PaymentReservation payment = result.getTryResult("payment");
            InventoryReservation inventory = result.getTryResult("inventory");
            ShippingReservation shipping = result.getTryResult("shipping");
            
            return OrderResult.builder()
                .orderId(order.getOrderId())
                .status(OrderStatus.CONFIRMED)
                .paymentReservationId(payment.getReservationId())
                .inventoryReservationId(inventory.getReservationId())
                .shippingReservationId(shipping.getReservationId())
                .message("Order confirmed successfully")
                .build();
        } else {
            // Transaction failed - determine which participant failed
            String failedParticipant = result.getFailedParticipantId();
            Throwable error = result.getError();
            
            String failureReason = error != null 
                ? error.getMessage() 
                : "Unknown error";
            
            return OrderResult.builder()
                .orderId(order.getOrderId())
                .status(OrderStatus.FAILED)
                .failedStep(failedParticipant)
                .failureReason(failureReason)
                .message(String.format("Order failed at %s: %s", failedParticipant, failureReason))
                .build();
        }
    }
}

