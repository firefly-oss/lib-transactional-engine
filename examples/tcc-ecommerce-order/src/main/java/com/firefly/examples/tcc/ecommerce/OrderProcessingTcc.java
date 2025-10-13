package com.firefly.examples.tcc.ecommerce;

import com.firefly.examples.tcc.ecommerce.model.*;
import com.firefly.examples.tcc.ecommerce.service.InventoryService;
import com.firefly.examples.tcc.ecommerce.service.PaymentService;
import com.firefly.examples.tcc.ecommerce.service.ShippingService;
import com.firefly.transactional.tcc.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/**
 * TCC transaction coordinator for e-commerce order processing.
 * 
 * This transaction coordinates three participants:
 * 1. Payment - Reserve and capture payment
 * 2. Inventory - Reserve and commit inventory
 * 3. Shipping - Reserve and schedule shipping
 * 
 * If all participants succeed in the TRY phase, they are all confirmed.
 * If any participant fails in the TRY phase, all successful participants are canceled.
 */
@Tcc(name = "OrderProcessing", timeoutMs = 30000)
public class OrderProcessingTcc {

    /**
     * Payment participant - handles payment reservation and capture.
     * 
     * Try: Reserve payment amount from customer account
     * Confirm: Capture the reserved payment
     * Cancel: Release the reserved payment
     */
    @TccParticipant(id = "payment", order = 1, timeoutMs = 10000)
    public static class PaymentParticipant {
        
        private static final Logger log = LoggerFactory.getLogger(PaymentParticipant.class);
        
        @Autowired
        private PaymentService paymentService;
        
        /**
         * Try phase: Reserve payment amount.
         * 
         * This method checks if the customer has sufficient funds and creates
         * a payment reservation without actually debiting the account.
         */
        @TryMethod(timeoutMs = 5000)
        public Mono<PaymentReservation> reservePayment(OrderRequest order) {
            log.info("Attempting to reserve payment for order: orderId={}, customerId={}, amount={}", 
                order.getOrderId(), order.getCustomerId(), order.getTotalAmount());
            
            return paymentService.reserveAmount(order.getCustomerId(), order.getTotalAmount())
                .doOnSuccess(reservation -> 
                    log.info("Payment reserved successfully: reservationId={}, amount={}", 
                        reservation.getReservationId(), reservation.getAmount()))
                .doOnError(e -> 
                    log.error("Payment reservation failed for order: orderId={}", order.getOrderId(), e));
        }
        
        /**
         * Confirm phase: Capture the reserved payment.
         * 
         * This method actually debits the customer account and marks the
         * reservation as captured.
         */
        @ConfirmMethod(timeoutMs = 3000)
        public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
            log.info("Confirming payment: reservationId={}", reservation.getReservationId());
            
            return paymentService.capturePayment(reservation.getReservationId())
                .doOnSuccess(v -> 
                    log.info("Payment captured successfully: reservationId={}", reservation.getReservationId()))
                .doOnError(e -> 
                    log.error("Payment capture failed: reservationId={}", reservation.getReservationId(), e));
        }
        
        /**
         * Cancel phase: Release the reserved payment.
         * 
         * This method releases the payment reservation, making the funds
         * available again to the customer.
         */
        @CancelMethod(timeoutMs = 3000)
        public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
            log.info("Canceling payment: reservationId={}", reservation.getReservationId());
            
            return paymentService.releasePayment(reservation.getReservationId())
                .doOnSuccess(v -> 
                    log.info("Payment released successfully: reservationId={}", reservation.getReservationId()))
                .doOnError(e -> 
                    log.error("Payment release failed: reservationId={}", reservation.getReservationId(), e));
        }
    }

    /**
     * Inventory participant - handles inventory reservation and commitment.
     * 
     * Try: Reserve inventory items
     * Confirm: Commit the inventory reservation (mark as sold)
     * Cancel: Release the reserved inventory
     */
    @TccParticipant(id = "inventory", order = 2, timeoutMs = 10000)
    public static class InventoryParticipant {
        
        private static final Logger log = LoggerFactory.getLogger(InventoryParticipant.class);
        
        @Autowired
        private InventoryService inventoryService;
        
        /**
         * Try phase: Reserve inventory items.
         * 
         * This method checks if the requested items are available and creates
         * an inventory reservation without actually removing the items from stock.
         */
        @TryMethod(timeoutMs = 5000)
        public Mono<InventoryReservation> reserveInventory(OrderRequest order) {
            log.info("Attempting to reserve inventory for order: orderId={}, items={}", 
                order.getOrderId(), order.getItems().size());
            
            return inventoryService.reserveItems(order.getItems())
                .doOnSuccess(reservation -> 
                    log.info("Inventory reserved successfully: reservationId={}, items={}", 
                        reservation.getReservationId(), reservation.getItems().size()))
                .doOnError(e -> 
                    log.error("Inventory reservation failed for order: orderId={}", order.getOrderId(), e));
        }
        
        /**
         * Confirm phase: Commit the inventory reservation.
         * 
         * This method actually removes the items from available stock and
         * marks them as sold.
         */
        @ConfirmMethod(timeoutMs = 3000)
        public Mono<Void> confirmInventory(@FromTry InventoryReservation reservation) {
            log.info("Confirming inventory: reservationId={}", reservation.getReservationId());
            
            return inventoryService.commitReservation(reservation.getReservationId())
                .doOnSuccess(v -> 
                    log.info("Inventory committed successfully: reservationId={}", reservation.getReservationId()))
                .doOnError(e -> 
                    log.error("Inventory commit failed: reservationId={}", reservation.getReservationId(), e));
        }
        
        /**
         * Cancel phase: Release the reserved inventory.
         * 
         * This method releases the inventory reservation, making the items
         * available again for other orders.
         */
        @CancelMethod(timeoutMs = 3000)
        public Mono<Void> cancelInventory(@FromTry InventoryReservation reservation) {
            log.info("Canceling inventory: reservationId={}", reservation.getReservationId());
            
            return inventoryService.releaseReservation(reservation.getReservationId())
                .doOnSuccess(v -> 
                    log.info("Inventory released successfully: reservationId={}", reservation.getReservationId()))
                .doOnError(e -> 
                    log.error("Inventory release failed: reservationId={}", reservation.getReservationId(), e));
        }
    }

    /**
     * Shipping participant - handles shipping capacity reservation and scheduling.
     * 
     * Try: Reserve shipping capacity
     * Confirm: Schedule the shipment
     * Cancel: Release the reserved capacity
     */
    @TccParticipant(id = "shipping", order = 3, timeoutMs = 10000)
    public static class ShippingParticipant {
        
        private static final Logger log = LoggerFactory.getLogger(ShippingParticipant.class);
        
        @Autowired
        private ShippingService shippingService;
        
        /**
         * Try phase: Reserve shipping capacity.
         * 
         * This method checks if shipping capacity is available for the
         * destination and creates a shipping reservation.
         */
        @TryMethod(timeoutMs = 5000)
        public Mono<ShippingReservation> reserveShipping(OrderRequest order) {
            log.info("Attempting to reserve shipping for order: orderId={}, destination={}", 
                order.getOrderId(), order.getShippingAddress());
            
            return shippingService.reserveCapacity(order.getShippingAddress(), order.getItems())
                .doOnSuccess(reservation -> 
                    log.info("Shipping reserved successfully: reservationId={}, destination={}", 
                        reservation.getReservationId(), reservation.getDestination()))
                .doOnError(e -> 
                    log.error("Shipping reservation failed for order: orderId={}", order.getOrderId(), e));
        }
        
        /**
         * Confirm phase: Schedule the shipment.
         * 
         * This method actually schedules the shipment and assigns it to
         * a carrier.
         */
        @ConfirmMethod(timeoutMs = 3000)
        public Mono<Void> confirmShipping(@FromTry ShippingReservation reservation) {
            log.info("Confirming shipping: reservationId={}", reservation.getReservationId());
            
            return shippingService.scheduleShipment(reservation.getReservationId())
                .doOnSuccess(v -> 
                    log.info("Shipping scheduled successfully: reservationId={}", reservation.getReservationId()))
                .doOnError(e -> 
                    log.error("Shipping scheduling failed: reservationId={}", reservation.getReservationId(), e));
        }
        
        /**
         * Cancel phase: Release the reserved shipping capacity.
         * 
         * This method releases the shipping reservation, making the capacity
         * available again for other orders.
         */
        @CancelMethod(timeoutMs = 3000)
        public Mono<Void> cancelShipping(@FromTry ShippingReservation reservation) {
            log.info("Canceling shipping: reservationId={}", reservation.getReservationId());
            
            return shippingService.releaseCapacity(reservation.getReservationId())
                .doOnSuccess(v -> 
                    log.info("Shipping released successfully: reservationId={}", reservation.getReservationId()))
                .doOnError(e -> 
                    log.error("Shipping release failed: reservationId={}", reservation.getReservationId(), e));
        }
    }
}

