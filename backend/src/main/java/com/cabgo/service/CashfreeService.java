package com.cabgo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side Cashfree Payments integration.
 *
 * All API calls use the Cashfree Orders API (2023-08-01).
 * Secret key is injected from environment — never hardcoded, never logged.
 *
 * Sandbox base URL : https://sandbox.cashfree.com/pg
 * Production base URL: https://api.cashfree.com/pg
 */
@Slf4j
@Service
public class CashfreeService {

    @Value("${cashfree.app-id}")
    private String appId;

    // Intentionally NOT logged anywhere in this class
    @Value("${cashfree.secret-key}")
    private String secretKey;

    @Value("${cashfree.base-url:https://sandbox.cashfree.com/pg}")
    private String baseUrl;

    @Value("${cashfree.environment:TEST}")
    private String environment;

    private static final String API_VERSION = "2023-08-01";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── DTOs ──────────────────────────────────────────────────────────────────

    public record CashfreeOrderResult(
            String cashfreeOrderId,
            String paymentSessionId,
            String paymentLink,
            String status,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null && paymentLink != null;
        }
    }

    public record PaymentVerificationResult(
            String orderId,
            String orderStatus,       // ACTIVE, PAID, EXPIRED
            String paymentStatus,     // SUCCESS, FAILED, PENDING, NOT_ATTEMPTED, FLAGGED, CANCELLED, VOID, USER_DROPPED
            double amountPaid,
            String errorMessage
    ) {
        /** Returns true only when Cashfree confirms successful payment. */
        public boolean isPaid() {
            return "PAID".equalsIgnoreCase(orderStatus);
        }

        public boolean isFailed() {
            return "FAILED".equalsIgnoreCase(paymentStatus)
                    || "CANCELLED".equalsIgnoreCase(paymentStatus)
                    || "VOID".equalsIgnoreCase(paymentStatus)
                    || "USER_DROPPED".equalsIgnoreCase(paymentStatus);
        }
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates a Cashfree payment order server-side.
     *
     * @param internalOrderId  Unique ID for this order (e.g. "VZRA-RIDE-XXXXXX")
     * @param amountInRupees   Fare amount in INR (e.g. 179.0)
     * @param customerPhone    Customer WhatsApp phone in 91XXXXXXXXXX format
     * @param customerName     Customer display name
     * @param customerEmail    Customer email
     * @return CashfreeOrderResult with payment link, or error info
     */
    public CashfreeOrderResult createOrder(String internalOrderId,
                                           double amountInRupees,
                                           String customerPhone,
                                           String customerName,
                                           String customerEmail) {
        try {
            log.info("[Cashfree] Creating order: orderId={}, amount={}", internalOrderId, amountInRupees);

            Map<String, Object> customerDetails = new HashMap<>();
            customerDetails.put("customer_id", "VAZRAA_" + sanitizePhone(customerPhone));
            customerDetails.put("customer_phone", sanitizePhone(customerPhone));
            customerDetails.put("customer_name", customerName != null ? customerName : "Vazraa Customer");
            customerDetails.put("customer_email", customerEmail != null ? customerEmail : "noreply@vazraa.com");

            Map<String, Object> orderMeta = new HashMap<>();
            orderMeta.put("return_url", "https://vazraamobility.com/payment/success?order_id={order_id}");

            Map<String, Object> body = new HashMap<>();
            body.put("order_id", internalOrderId);
            body.put("order_amount", Math.round(amountInRupees * 100.0) / 100.0);
            body.put("order_currency", "INR");
            body.put("customer_details", customerDetails);
            body.put("order_meta", orderMeta);
            body.put("order_note", "Vazraa ride booking payment");

            String json = objectMapper.writeValueAsString(body);
            log.info("[Cashfree] Sending order creation request for orderId={}", internalOrderId);

            Request request = new Request.Builder()
                    .url(baseUrl + "/orders")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-version", API_VERSION)
                    .addHeader("x-client-id", appId)
                    .addHeader("x-client-secret", secretKey) // sent over HTTPS only, never logged
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "{}";
                int statusCode = response.code();

                if (!response.isSuccessful()) {
                    log.error("[Cashfree] Order creation failed. HTTP {}", statusCode);
                    return new CashfreeOrderResult(null, null, null, "ERROR",
                            "Cashfree order creation failed with HTTP " + statusCode);
                }

                JsonNode node = objectMapper.readTree(responseBody);
                String cfOrderId   = node.path("order_id").asText(internalOrderId);
                String sessionId   = node.path("payment_session_id").asText("");
                String paymentLink = node.path("order_link").asText("");

                // Build payment link from session ID if order_link is not returned
                if (paymentLink == null || paymentLink.isBlank()) {
                    // Cashfree Sandbox hosted checkout URL
                    paymentLink = "https://sandbox.cashfree.com/checkout/#" + sessionId;
                }

                log.info("[Cashfree] Order created successfully: cfOrderId={}", cfOrderId);
                return new CashfreeOrderResult(cfOrderId, sessionId, paymentLink, "CREATED", null);
            }

        } catch (Exception e) {
            log.error("[Cashfree] Exception while creating order: {}", e.getMessage());
            return new CashfreeOrderResult(null, null, null, "ERROR",
                    "Exception creating Cashfree order: " + e.getMessage());
        }
    }

    /**
     * Verifies payment status by fetching the Cashfree order.
     *
     * @param cashfreeOrderId  The order ID used in createOrder
     * @return PaymentVerificationResult with current payment status
     */
    public PaymentVerificationResult verifyPayment(String cashfreeOrderId) {
        try {
            log.info("[Cashfree] Verifying payment for orderId={}", cashfreeOrderId);

            Request request = new Request.Builder()
                    .url(baseUrl + "/orders/" + cashfreeOrderId)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-version", API_VERSION)
                    .addHeader("x-client-id", appId)
                    .addHeader("x-client-secret", secretKey) // never logged
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "{}";
                int statusCode = response.code();

                if (!response.isSuccessful()) {
                    log.error("[Cashfree] Payment verification failed. HTTP {}", statusCode);
                    return new PaymentVerificationResult(cashfreeOrderId, "UNKNOWN", "UNKNOWN", 0.0,
                            "Verification HTTP error " + statusCode);
                }

                JsonNode node = objectMapper.readTree(responseBody);
                String orderStatus = node.path("order_status").asText("UNKNOWN");
                double orderAmount = node.path("order_amount").asDouble(0.0);

                // Derive paymentStatus from order_status or payments array
                String paymentStatus = "NOT_ATTEMPTED";
                JsonNode payments = node.path("payments");
                if (payments.isArray() && !payments.isEmpty()) {
                    paymentStatus = payments.get(0).path("payment_status").asText("NOT_ATTEMPTED");
                } else if ("PAID".equalsIgnoreCase(orderStatus)) {
                    paymentStatus = "SUCCESS";
                } else if ("ACTIVE".equalsIgnoreCase(orderStatus)) {
                    paymentStatus = "PENDING";
                }

                log.info("[Cashfree] Verification result: orderId={}, orderStatus={}, paymentStatus={}",
                        cashfreeOrderId, orderStatus, paymentStatus);

                return new PaymentVerificationResult(
                        cashfreeOrderId, orderStatus, paymentStatus, orderAmount, null);
            }

        } catch (Exception e) {
            log.error("[Cashfree] Exception while verifying payment: {}", e.getMessage());
            return new PaymentVerificationResult(cashfreeOrderId, "UNKNOWN", "UNKNOWN", 0.0,
                    "Exception: " + e.getMessage());
        }
    }

    /**
     * Verifies the Cashfree webhook HMAC-SHA256 signature.
     * Cashfree signs: timestamp + rawBody using the secret key.
     *
     * @param rawBody    Raw POST body string from webhook
     * @param timestamp  Value of "x-webhook-timestamp" header
     * @param signature  Value of "x-webhook-signature" header
     * @return true if signature is valid
     */
    public boolean verifyWebhookSignature(String rawBody, String timestamp, String signature) {
        try {
            String message = timestamp + rawBody;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec =
                    new javax.crypto.spec.SecretKeySpec(
                            secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "HmacSHA256");
            mac.init(keySpec);
            byte[] hashBytes = mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String computed = java.util.Base64.getEncoder().encodeToString(hashBytes);
            boolean valid = computed.equals(signature);
            if (!valid) {
                log.warn("[Cashfree] Webhook signature mismatch. Possible tampered request.");
            }
            return valid;
        } catch (Exception e) {
            log.error("[Cashfree] Webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generates a unique Cashfree order ID tied to a ride.
     * Format: VZRA-{rideId-last6}-{8-char-UUID}
     * Max 50 chars, alphanumeric + hyphens only.
     */
    public static String generateOrderId(String rideId) {
        String suffix = (rideId != null && rideId.length() >= 6)
                ? rideId.substring(rideId.length() - 6).toUpperCase()
                : UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "VZRA-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Normalises phone to 10-digit format required by Cashfree customer_phone */
    private String sanitizePhone(String phone) {
        if (phone == null) return "9999999999";
        String clean = phone.replaceAll("[^0-9]", "");
        if (clean.startsWith("91") && clean.length() == 12) return clean.substring(2);
        if (clean.length() == 10) return clean;
        return clean.length() > 10 ? clean.substring(clean.length() - 10) : clean;
    }
}
