package com.cabgo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends WhatsApp messages via the AiSensy / bgsinfotech campaign-message API.
 *
 * Endpoint : POST https://messages.bgsinfotech.com/messages/whatsapp
 * Auth     : apiKey field inside the JSON body (not a header)
 * Payload  : { apiKey, campaignName, destination, userName, templateParams, source,
 *              media, buttons, carouselCards, location }
 *
 * NOTE: sendButtons() intentionally degrades to plain text because AiSensy
 * interactive buttons require pre-approved WhatsApp templates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppService {

    @Value("${aisensy.message-api-url}")
    private String apiUrl;

    @Value("${aisensy.project-api-key}")
    private String apiKey;

    @Value("${aisensy.campaign-name}")
    private String campaignName;

    @Value("${aisensy.user-name:Vazra mobility}")
    private String userName;

    @Value("${aisensy.whatsapp-number:919035999800}")
    private String whatsappNumber;

    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final com.cabgo.repository.WhatsAppSessionsRepository whatsappSessionsRepository;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Send a plain text message.
     */
    public void sendText(String toPhone, String message) {
        String dest = normalizeDestination(toPhone);
        Map<String, Object> payload = buildPayload(dest, message);
        post(payload);
        broadcastOutgoing(toPhone, message);
    }

    /**
     * Send a message with button choices.
     * AiSensy campaign API does not support ad-hoc interactive buttons, so this
     * falls back to a plain-text numbered list.
     */
    public void sendButtons(String toPhone, String bodyText, List<Map<String, String>> buttons) {
        StringBuilder sb = new StringBuilder(bodyText);
        sb.append("\n");
        int i = 1;
        for (Map<String, String> b : buttons) {
            sb.append("\n").append(i++).append("️⃣ ").append(b.get("title"));
        }
        sendText(toPhone, sb.toString());
    }

    /**
     * Ask the user to share their location — plain text prompt since AiSensy
     * location_request_message is a Meta-specific interactive type.
     */
    public void sendLocationRequest(String toPhone, String text) {
        sendText(toPhone, text + "\n\n📌 Please share your location using the WhatsApp attachment button.");
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /**
     * Build the AiSensy campaign-message payload.
     * The message body is sanitized to remove newlines, tabs, and excessive whitespace
     * so that Meta/AiSensy template parameter validation passes.
     */
    private Map<String, Object> buildPayload(String destination, String messageBody) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("apiKey",         apiKey);
        payload.put("campaignName",   campaignName);
        payload.put("destination",    destination);
        payload.put("userName",       userName);
        payload.put("templateParams", List.of(sanitizeForAiSensy(messageBody)));
        payload.put("source",         "api");
        payload.put("media",          Map.of());
        payload.put("buttons",        List.of());
        payload.put("carouselCards",  List.of());
        payload.put("location",       Map.of());
        return payload;
    }

    /**
     * Sanitizes dynamic message text for AiSensy template parameters:
     * - Replaces newlines (\r, \n) with clean bullet separators (" • ") for structured readability
     * - Replaces tabs with spaces
     * - Collapses multiple spaces / redundant separators
     * - Trims the final string
     * Meta/AiSensy rejects template parameters containing newlines (\n), tabs, or >4 spaces.
     */
    public String sanitizeForAiSensy(String message) {
        if (message == null) return "";
        return message
                .replaceAll("[\\r\\n]+", " • ")
                .replaceAll("[\\t]+", " ")
                .replaceAll(" {2,}", " ")
                .trim();
    }

    /**
     * Normalise phone to 91XXXXXXXXXX (no +, no spaces).
     */
    private String normalizeDestination(String phone) {
        if (phone == null) return "";
        String clean = phone.replaceAll("[^0-9]", "");
        if (clean.length() == 10) return "91" + clean;
        return clean;
    }

    private void broadcastOutgoing(String toPhone, String textBody) {
        try {
            Map<String, Object> wsMsg = Map.of(
                    "direction", "outgoing",
                    "to",        toPhone,
                    "type",      "text",
                    "textBody",  textBody,
                    "timestamp", System.currentTimeMillis());
            messagingTemplate.convertAndSend("/topic/admin/whatsapp", wsMsg);
        } catch (Exception e) {
            log.error("Failed to broadcast outgoing WhatsApp message", e);
        }

        // Archive to WhatsAppSessions collection
        try {
            com.cabgo.model.WhatsAppSessions logEntry = com.cabgo.model.WhatsAppSessions.builder()
                    .whatsappPhone(toPhone)
                    .direction("OUTGOING")
                    .messageType("text")
                    .content(textBody)
                    .timestamp(java.time.LocalDateTime.now())
                    .build();
            whatsappSessionsRepository.save(logEntry);
        } catch (Exception logEx) {
            log.error("Failed to archive outgoing WhatsApp message", logEx);
        }
    }

    private void post(Map<String, Object> payload) {
        String dest = (String) payload.get("destination");
        log.info("[WhatsApp Outbound] Preparing to send message via AiSensy API...");
        log.info("[WhatsApp Outbound] Endpoint URL: {}", apiUrl);
        log.info("[WhatsApp Outbound] Target Destination (Normalized): {}", dest);
        log.info("[WhatsApp Outbound] Campaign: {}", payload.get("campaignName"));
        log.info("[WhatsApp Outbound] Template parameters: {}", payload.get("templateParams"));

        try {
            String json = objectMapper.writeValueAsString(payload);
            log.info("[WhatsApp Outbound] Outgoing JSON Payload: {}", json);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            log.info("[WhatsApp Outbound] Executing HTTP POST request...");
            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();
                String bodyText = response.body() != null ? response.body().string() : "no body";
                
                log.info("[WhatsApp Outbound] HTTP Response Status Code: {}", code);
                log.info("[WhatsApp Outbound] HTTP Response Body: {}", bodyText);

                if (!response.isSuccessful()) {
                    log.error("[WhatsApp Outbound] AiSensy Campaign Message API returned error code {}: {}", code, bodyText);
                    throw new RuntimeException("AiSensy message delivery failed with HTTP " + code + ": " + bodyText);
                } else {
                    log.info("[WhatsApp Outbound] Message successfully delivered to AiSensy Campaign Manager for {}", dest);
                }
            }
        } catch (Exception e) {
            log.error("[WhatsApp Outbound] CRITICAL EXCEPTION occurred while sending message via AiSensy API", e);
            // Ensure the exception is not silently swallowed: throw a customized exception or log it fully
            throw new RuntimeException("Failed to complete AiSensy API dispatch", e);
        }
    }
}
