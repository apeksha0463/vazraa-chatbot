package com.cabgo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppWebhookService {

    private final ChatSessionService chatSessionService;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final com.cabgo.repository.WhatsAppSessionsRepository whatsappSessionsRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse the incoming AiSensy Project Webhook payload and route to state machine.
     *
     * AiSensy sends payloads in two known formats:
     *
     * Format 1 – Flat (Project Message API):
     *   { "phone_number": "91...", "sender": "USER", "message_type": "TEXT", "message_content": { "text": "hi" } }
     *
     * Format 2 – Topic-based (Project Webhook):
     *   { "topic": "message.sender.user", "data": { "phone_number": "91...", "message": { "type": "TEXT", "message_content": { "text": "hi" } } } }
     *
     * Format 3 – Meta WhatsApp Cloud API (fallback):
     *   { "entry": [ { "changes": [ { "value": { "messages": [ { "from": "91...", "type": "text", "text": { "body": "hi" } } ] } } ] } ] }
     */
    public void processWebhookPayload(String payload) {
        log.info("Received WhatsApp webhook payload: {}", payload);
        try {
            JsonNode root = objectMapper.readTree(payload);

            String from = null;
            String type = null;
            String textBody = null;
            Double locationLat = null;
            Double locationLng = null;
            String locationName = null;
            String interactiveReplyId = null;

            // ── FORMAT 1: AiSensy Flat format (phone_number at root level) ──────
            if (root.has("phone_number") && !root.has("topic")) {
                log.info("Handling AiSensy flat Project Webhook format");

                String senderType = root.path("sender").asText("").trim().toUpperCase();
                if ("AGENT".equals(senderType) || "API".equals(senderType) || "SYSTEM".equals(senderType)) {
                    log.info("Ignoring outgoing/system message from sender type: {}", senderType);
                    return;
                }

                from = root.path("phone_number").asText("").trim();
                String messageType = root.path("message_type").asText("").toUpperCase();
                JsonNode messageContent = root.path("message_content");

                log.info("[Webhook Extractor] Flat format — from: {}, message_type: {}", from, messageType);

                switch (messageType) {
                    case "TEXT", "ROOM_MESSAGE" -> {
                        type = "text";
                        String buttonPayload = messageContent.path("button_payload").asText("").trim();
                        String rawText = messageContent.path("text").asText("").trim();
                        textBody = buttonPayload.isEmpty() ? rawText : buttonPayload;
                        log.info("[Webhook Extractor] Extracted text: {}", textBody);
                    }
                    case "QUICK_REPLY", "QUICK_REPLY_CARD", "BUTTON_REPLY" -> {
                        type = "text";
                        String buttonPayload = messageContent.path("button_payload").asText("").trim();
                        String rawText = messageContent.path("text").asText("").trim();
                        textBody = buttonPayload.isEmpty() ? rawText : buttonPayload;
                        interactiveReplyId = textBody;
                        log.info("[Webhook Extractor] Extracted quick reply: {}", textBody);
                    }
                    case "LIST_REPLY", "LIST_MESSAGE" -> {
                        type = "text";
                        textBody = messageContent.path("text").asText("").trim();
                        interactiveReplyId = textBody;
                        log.info("[Webhook Extractor] Extracted list reply: {}", textBody);
                    }
                    case "LOCATION" -> {
                        type = "location";
                        locationLat = messageContent.path("latitude").asDouble();
                        locationLng = messageContent.path("longitude").asDouble();
                        locationName = messageContent.path("name").asText("");
                        if (locationName.isEmpty()) {
                            locationName = messageContent.path("address").asText("");
                        }
                        log.info("[Webhook Extractor] Extracted location: lat={}, lng={}, name={}", locationLat, locationLng, locationName);
                    }
                    case "IMAGE" -> {
                        type = "image";
                        textBody = messageContent.path("url").asText("");
                        log.info("[Webhook Extractor] Extracted image URL: {}", textBody);
                    }
                    default -> {
                        type = messageType.toLowerCase();
                        textBody = messageContent.path("text").asText("");
                        log.info("[Webhook Extractor] Unhandled message_type: {}, text: {}", messageType, textBody);
                    }
                }

            // ── FORMAT 2: AiSensy Topic-based format ────────────────────────────
            } else if (root.has("topic")) {
                String topic = root.path("topic").asText();
                log.info("Handling AiSensy topic-based webhook: {}", topic);

                if ("message.status.updated".equals(topic)) {
                    log.info("AiSensy message status updated — ignoring");
                    return;
                }

                if ("message.sender.user".equals(topic)) {
                    JsonNode data = root.path("data");
                    if (data.isMissingNode()) {
                        log.warn("AiSensy message.sender.user webhook is missing 'data' node");
                        return;
                    }

                    // Extract phone number — can be at data.phone_number, data.sender.phone_number
                    if (!data.path("phone_number").isMissingNode()) {
                        from = data.path("phone_number").asText("").trim();
                    } else if (!data.path("sender").path("phone_number").isMissingNode()) {
                        from = data.path("sender").path("phone_number").asText("").trim();
                    } else if (!data.path("message").path("phone_number").isMissingNode()) {
                        from = data.path("message").path("phone_number").asText("").trim();
                    } else {
                        from = data.path("phone").asText("").trim();
                    }

                    // Extract message node — AiSensy wraps message details under data.message
                    JsonNode msgNode = data.has("message") ? data.path("message") : data;
                    JsonNode messageContent = msgNode.has("message_content") ? msgNode.path("message_content") : msgNode;

                    // Extract type
                    String rawType = msgNode.path("type").asText("");
                    if (rawType.isEmpty()) rawType = data.path("type").asText("");
                    if (rawType.isEmpty()) rawType = data.path("message_type").asText("");
                    type = rawType.toLowerCase();

                    log.info("[Webhook Extractor] Topic format — from: {}, type: {}", from, type);

                    if ("text".equals(type) || "message".equals(type)) {
                        // "message" is AiSensy's generic text message type
                        type = "text";
                        textBody = messageContent.path("text").asText("");
                        if (textBody.isEmpty()) textBody = msgNode.path("text").path("body").asText("");
                        if (textBody.isEmpty()) textBody = data.path("body").asText("");
                        // Also try message_content directly on the data node
                        if (textBody.isEmpty()) textBody = data.path("message_content").path("text").asText("");
                        log.info("[Webhook Extractor] Extracted text: {}", textBody);
                    } else if ("location".equals(type)) {
                        // Try message_content first, then fall back to msgNode directly
                        locationLat = messageContent.path("latitude").asDouble();
                        locationLng = messageContent.path("longitude").asDouble();
                        // If lat/lng are zero (not found in message_content), try msgNode directly
                        if (locationLat == 0.0 && locationLng == 0.0) {
                            locationLat = msgNode.path("latitude").asDouble();
                            locationLng = msgNode.path("longitude").asDouble();
                        }
                        // Try 'name' first, fallback to 'address'
                        locationName = messageContent.path("name").asText("");
                        if (locationName.isEmpty()) locationName = messageContent.path("address").asText("");
                        if (locationName.isEmpty()) locationName = msgNode.path("name").asText("");
                        if (locationName.isEmpty()) locationName = msgNode.path("address").asText("");
                        log.info("[Webhook Extractor] Topic format location: lat={}, lng={}, name={}", locationLat, locationLng, locationName);
                    } else if ("button_reply".equals(type) || "quick_reply".equals(type)) {
                        type = "text";
                        textBody = messageContent.path("button_payload").asText("");
                        if (textBody.isEmpty()) textBody = messageContent.path("text").asText("");
                        interactiveReplyId = textBody;
                    }
                } else {
                    log.info("Ignoring unhandled AiSensy topic: {}", topic);
                    return;
                }

            // ── FORMAT 3: Meta WhatsApp Cloud API fallback ───────────────────────
            } else {
                log.info("Handling Meta WhatsApp Cloud API format");
                JsonNode entry = root.path("entry").get(0);
                if (entry == null) return;
                JsonNode change = entry.path("changes").get(0);
                if (change == null) return;
                JsonNode value = change.path("value");
                JsonNode messages = value.path("messages");
                if (messages.isMissingNode() || messages.size() == 0) {
                    log.debug("No messages in webhook payload (likely a status update)");
                    return;
                }
                JsonNode message = messages.get(0);
                from = message.path("from").asText();
                type = message.path("type").asText();

                if ("text".equals(type)) {
                    textBody = message.path("text").path("body").asText();
                } else if ("location".equals(type)) {
                    JsonNode loc = message.path("location");
                    locationLat = loc.path("latitude").asDouble();
                    locationLng = loc.path("longitude").asDouble();
                    locationName = loc.path("name").asText();
                } else if ("interactive".equals(type)) {
                    JsonNode interactive = message.path("interactive");
                    String iType = interactive.path("type").asText();
                    JsonNode reply = "button_reply".equals(iType)
                            ? interactive.path("button_reply")
                            : interactive.path("list_reply");
                    interactiveReplyId = reply.path("id").asText();
                    textBody = reply.path("title").asText();
                }
            }

            if (from == null || from.isEmpty() || type == null || type.isEmpty()) {
                log.warn("Webhook payload could not be parsed: from={}, type={}", from, type);
                return;
            }

            // Save to historical log
            try {
                com.cabgo.model.WhatsAppSessions logEntry = com.cabgo.model.WhatsAppSessions.builder()
                        .whatsappPhone(from)
                        .direction("INCOMING")
                        .messageType(type)
                        .content(textBody != null ? textBody : (interactiveReplyId != null ? interactiveReplyId : ""))
                        .timestamp(java.time.LocalDateTime.now())
                        .build();
                whatsappSessionsRepository.save(logEntry);
            } catch (Exception logEx) {
                log.error("Failed to archive incoming WhatsApp message", logEx);
            }

            log.info("Processing message from: {}, type: {}, body: {}", from, type,
                    interactiveReplyId != null ? interactiveReplyId : textBody);

            // Broadcast to WebSocket for live admin monitor
            try {
                java.util.Map<String, Object> wsMsg = java.util.Map.of(
                    "direction", "incoming",
                    "from", from,
                    "type", type,
                    "textBody", textBody != null ? textBody : (interactiveReplyId != null ? interactiveReplyId : ""),
                    "timestamp", System.currentTimeMillis()
                );
                messagingTemplate.convertAndSend("/topic/admin/whatsapp", wsMsg);
            } catch (Exception wsEx) {
                log.error("Failed to broadcast incoming WhatsApp message", wsEx);
            }

            chatSessionService.handleMessage(
                from,
                type,
                textBody,
                locationLat,
                locationLng,
                locationName,
                interactiveReplyId
            );

        } catch (Exception e) {
            log.error("Failed to parse WhatsApp webhook payload", e);
        }
    }
}
