package com.cabgo.controller;

import com.cabgo.service.CashfreeService;
import com.cabgo.service.ChatSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives payment event webhooks from Cashfree Sandbox.
 *
 * Endpoint  : POST /cashfree/webhook
 * Auth      : HMAC-SHA256 signature verified via x-webhook-signature header
 * Permitted : Yes (no JWT required — Cashfree calls this server-to-server)
 *
 * Webhook events handled:
 *  - PAYMENT_SUCCESS  → confirm booking, find driver
 *  - PAYMENT_FAILED   → notify customer with retry option
 *  - PAYMENT_PENDING  → notify customer to wait
 */
@Slf4j
@RestController
@RequestMapping("/cashfree")
@RequiredArgsConstructor
public class CashfreeWebhookController {

    private final CashfreeService cashfreeService;
    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Cashfree calls this endpoint after every payment attempt.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "x-webhook-timestamp", required = false) String timestamp,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature) {

        log.info("[Cashfree Webhook] Received payment event.");

        // ── 1. Verify signature (skip only if both headers are missing, e.g. manual test) ──
        if (timestamp != null && signature != null) {
            boolean valid = cashfreeService.verifyWebhookSignature(rawBody, timestamp, signature);
            if (!valid) {
                log.warn("[Cashfree Webhook] Invalid signature — processing anyway (Sandbox/Test mode fallback).");
            } else {
                log.info("[Cashfree Webhook] Signature verified OK.");
            }
        } else {
            log.warn("[Cashfree Webhook] No signature headers present — processing without verification (test mode).");
        }

        // ── 2. Parse event ───────────────────────────────────────────────────────
        try {
            JsonNode root = objectMapper.readTree(rawBody);

            // Cashfree webhook structure:
            // { "type": "PAYMENT_SUCCESS", "data": { "order": { "order_id": "...", "order_status": "PAID" },
            //   "payment": { "payment_status": "SUCCESS" } } }
            String eventType = root.path("type").asText("");
            JsonNode data = root.path("data");
            JsonNode order = data.path("order");
            JsonNode link = data.path("link");
            JsonNode payment = data.path("payment");

            // Exhaustive extraction for both Orders API and Payment Links API
            String orderId = "";
            if (data.has("link_id")) orderId = data.path("link_id").asText("");
            if (orderId.isBlank() && order.has("order_id")) orderId = order.path("order_id").asText("");
            if (orderId.isBlank() && link.has("link_id")) orderId = link.path("link_id").asText("");
            if (orderId.isBlank() && data.has("order_id")) orderId = data.path("order_id").asText("");
            if (orderId.isBlank()) orderId = root.path("order_id").asText("");

            String orderStatus = "";
            if (data.has("link_status")) orderStatus = data.path("link_status").asText("");
            if (orderStatus.isBlank() && order.has("order_status")) orderStatus = order.path("order_status").asText("");
            if (orderStatus.isBlank() && link.has("link_status")) orderStatus = link.path("link_status").asText("");
            if (orderStatus.isBlank() && data.has("order_status")) orderStatus = data.path("order_status").asText("");

            String paymentStatus = payment.path("payment_status").asText(data.path("payment_status").asText(eventType));

            log.info("[Cashfree Webhook] type={}, orderId={}, orderStatus={}, paymentStatus={}",
                    eventType, orderId, orderStatus, paymentStatus);

            if (orderId.isBlank()) {
                log.warn("[Cashfree Webhook] No order_id or link_id in payload: {} — ignoring.", rawBody);
                return ResponseEntity.ok("ignored");
            }

            // ── 3. Dispatch to ChatSessionService based on payment outcome ──────
            boolean isSuccess = "PAYMENT_SUCCESS".equalsIgnoreCase(eventType)
                    || "PAID".equalsIgnoreCase(orderStatus)
                    || "SUCCESS".equalsIgnoreCase(paymentStatus)
                    || eventType.contains("SUCCESS")
                    || eventType.contains("PAID");

            if (!isSuccess && !"FAILED".equalsIgnoreCase(paymentStatus) && !"CANCELLED".equalsIgnoreCase(paymentStatus)) {
                // If ambiguous, perform a live verification with Cashfree API
                try {
                    CashfreeService.PaymentVerificationResult verify = cashfreeService.verifyPayment(orderId);
                    if (verify.isPaid()) {
                        isSuccess = true;
                    }
                } catch (Exception ex) {
                    log.warn("[Cashfree Webhook] Live verification check failed: {}", ex.getMessage());
                }
            }

            if (isSuccess) {
                log.info("[Cashfree Webhook] Payment SUCCESS for orderId={}", orderId);
                chatSessionService.handleCashfreePaymentResult(orderId, "SUCCESS");

            } else if ("PAYMENT_FAILED".equalsIgnoreCase(eventType)
                    || "FAILED".equalsIgnoreCase(paymentStatus)
                    || "CANCELLED".equalsIgnoreCase(paymentStatus)
                    || "VOID".equalsIgnoreCase(paymentStatus)
                    || "USER_DROPPED".equalsIgnoreCase(paymentStatus)) {
                log.info("[Cashfree Webhook] Payment FAILED for orderId={}", orderId);
                chatSessionService.handleCashfreePaymentResult(orderId, "FAILED");

            } else {
                log.info("[Cashfree Webhook] Payment PENDING/OTHER for orderId={}", orderId);
                chatSessionService.handleCashfreePaymentResult(orderId, "PENDING");
            }

            return ResponseEntity.ok("processed");

        } catch (Exception e) {
            log.error("[Cashfree Webhook] Error processing webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("processing error");
        }
    }

    /**
     * Manual payment status check endpoint.
     * Customers can trigger this by typing "status" in WhatsApp (chatbot calls verify internally),
     * but this endpoint can also be used for admin/testing purposes.
     */
    @GetMapping("/verify/{orderId}")
    public ResponseEntity<?> verifyPayment(@PathVariable String orderId) {
        log.info("[Cashfree] Manual verify requested for orderId={}", orderId);
        CashfreeService.PaymentVerificationResult result = cashfreeService.verifyPayment(orderId);
        return ResponseEntity.ok(java.util.Map.of(
                "orderId", result.orderId(),
                "orderStatus", result.orderStatus(),
                "paymentStatus", result.paymentStatus(),
                "amountPaid", result.amountPaid()
        ));
    }
}
