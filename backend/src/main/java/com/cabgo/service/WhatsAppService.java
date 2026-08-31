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
 * Sends WhatsApp messages via two AiSensy endpoints:
 *
 * PRIMARY — Live-Chat (Project) API — free-form text, preserves newlines/formatting.
 *   Used for all bot replies inside the 24-hour customer-service window.
 *   Endpoint : POST https://apis.aisensy.com/project-apis/v1/project/{projectId}/messages
 *   Auth     : Header  X-AiSensy-Project-API-Pwd: <project-api-key>
 *
 * FALLBACK — Campaign API — pre-approved WhatsApp templates (newlines stripped from params).
 *   Used only when the Live-Chat call fails (user outside 24-hour window).
 *   Endpoint : POST https://backend.aisensy.com/campaign/t1/api/v2
 *   Auth     : apiKey field inside the JSON body
 *
 * NOTE: sendButtons() intentionally degrades to plain text because AiSensy
 * interactive buttons require pre-approved WhatsApp templates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppService {

    // ── Live-Chat (Project) API config ─────────────────────────────────────────
    @Value("${aisensy.project-api-url:https://apis.aisensy.com/project-apis/v1/project}")
    private String projectApiBaseUrl;

    @Value("${aisensy.project-id:}")
    private String projectId;

    @Value("${aisensy.project-api-key}")
    private String projectApiKey;

    // ── Campaign API config (template fallback) ────────────────────────────────
    @Value("${aisensy.message-api-url}")
    private String campaignApiUrl;

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
     * Send a plain-text message with full formatting (newlines, emojis, etc.).
     *
     * Tries the Live-Chat API first (free-form, no template needed).
     * Falls back to Campaign API (template) if the Live-Chat call fails,
     * e.g. when the 24-hour session window has expired.
     */
    public void sendText(String toPhone, String message) {
        String dest = normalizeDestination(toPhone);

        boolean liveChatSent = trySendViaLiveChat(dest, message);
        if (!liveChatSent) {
            log.warn("[WhatsApp] Live-Chat API failed or not configured — falling back to Campaign API for {}", dest);
            sendViaCampaignApi(dest, message);
        }
        broadcastOutgoing(toPhone, message);
    }

    /**
     * Send a message with button choices.
     * AiSensy does not support ad-hoc interactive buttons without a pre-approved template,
     * so this falls back to a plain-text numbered list.
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

    // ── Live-Chat (Project) API ────────────────────────────────────────────────

    /**
     * Attempts to send a free-form text message via the AiSensy Project API.
     * This preserves newlines, emojis, and all formatting exactly as written.
     *
     * @return true if the message was sent successfully, false otherwise.
     */
    private boolean trySendViaLiveChat(String destination, String messageBody) {
        if (projectId == null || projectId.isBlank()) {
            log.warn("[WhatsApp LiveChat] aisensy.project-id is not configured — skipping Live-Chat API.");
            return false;
        }

        try {
            String url = projectApiBaseUrl + "/" + projectId + "/messages";

            Map<String, Object> payload = new HashMap<>();
            payload.put("to",             destination);
            payload.put("type",           "text");
            payload.put("recipient_type", "individual");
            payload.put("text",           Map.of("body", messageBody));

            String json = objectMapper.writeValueAsString(payload);

            log.info("[WhatsApp LiveChat] Sending to {} | URL: {}", destination, url);
            log.info("[WhatsApp LiveChat] Payload: {}", json);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type",               "application/json")
                    .addHeader("X-AiSensy-Project-API-Pwd",  projectApiKey)
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "no body";
                log.info("[WhatsApp LiveChat] Response {} : {}", code, body);

                if (response.isSuccessful()) {
                    log.info("[WhatsApp LiveChat] ✅ Message delivered to {} via Live-Chat API", destination);
                    return true;
                } else {
                    log.warn("[WhatsApp LiveChat] ❌ Live-Chat API returned {} for {}: {}", code, destination, body);
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("[WhatsApp LiveChat] Exception while calling Live-Chat API for {}", destination, e);
            return false;
        }
    }

    // ── Campaign (Template) API fallback ──────────────────────────────────────

    /**
     * Sends a message via the AiSensy Campaign API using a pre-approved template.
     * The message body is sanitized (newlines stripped) as required by Meta.
     * Used only as a fallback when the 24-hour session window is not open.
     */
    private void sendViaCampaignApi(String destination, String messageBody) {
        Map<String, Object> payload = buildCampaignPayload(destination, messageBody);

        log.info("[WhatsApp Campaign] Sending to {} | Campaign: {}", destination, campaignName);

        try {
            String json = objectMapper.writeValueAsString(payload);
            log.info("[WhatsApp Campaign] Payload: {}", json);

            Request request = new Request.Builder()
                    .url(campaignApiUrl)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();
                String bodyText = response.body() != null ? response.body().string() : "no body";

                log.info("[WhatsApp Campaign] Response {} : {}", code, bodyText);

                if (!response.isSuccessful()) {
                    log.error("[WhatsApp Campaign] ❌ Campaign API error {} for {}: {}", code, destination, bodyText);
                    throw new RuntimeException("AiSensy Campaign API failed with HTTP " + code + ": " + bodyText);
                } else {
                    log.info("[WhatsApp Campaign] ✅ Message delivered to {} via Campaign API", destination);
                }
            }
        } catch (Exception e) {
            log.error("[WhatsApp Campaign] CRITICAL exception sending to {}", destination, e);
            throw new RuntimeException("Failed to dispatch via AiSensy Campaign API", e);
        }
    }

    /**
     * Builds the AiSensy Campaign API payload.
     * Message body is sanitized — newlines replaced with " • " since Meta
     * rejects template parameters containing newline characters.
     */
    private Map<String, Object> buildCampaignPayload(String destination, String messageBody) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("apiKey",         projectApiKey);
        payload.put("campaignName",   campaignName);
        payload.put("destination",    destination);
        payload.put("userName",       userName);
        payload.put("templateParams", List.of(sanitizeForTemplate(messageBody)));
        payload.put("source",         "api");
        payload.put("media",          Map.of());
        payload.put("buttons",        List.of());
        payload.put("carouselCards",  List.of());
        payload.put("location",       Map.of());
        return payload;
    }

    /**
     * Sanitizes text for use inside a WhatsApp template parameter {{1}}.
     * Meta forbids newlines, tabs, or more than 4 consecutive spaces inside
     * template parameters. Newlines are replaced with " • " for readability.
     */
    public String sanitizeForTemplate(String message) {
        if (message == null) return "";
        return message
                .replaceAll("[\\r\\n]+", " • ")
                .replaceAll("[\\t]+",    " ")
                .replaceAll(" {2,}",     " ")
                .trim();
    }

    /**
     * Kept for backward compatibility with any existing callers (e.g. tests, diagnostics).
     * Delegates to sanitizeForTemplate.
     */
    public String sanitizeForAiSensy(String message) {
        return sanitizeForTemplate(message);
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    /**
     * Normalise phone to 91XXXXXXXXXX (no +, no spaces, no dashes).
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
}
