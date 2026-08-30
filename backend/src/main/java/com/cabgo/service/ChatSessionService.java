package com.cabgo.service;

import com.cabgo.dto.FareRequest;
import com.cabgo.dto.FareResponse;
import com.cabgo.enums.*;
import com.cabgo.model.*;
import com.cabgo.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final CustomerRepository customerRepository;
    private final DriverRepository driverRepository;
    private final RideRepository rideRepository;
    private final DriverMatchingService driverMatchingService;
    private final WhatsAppService whatsAppService;
    private final GoogleMapsService mapsService;
    private final GeminiService geminiService;
    private final FareService fareService;
    private final CashfreeService cashfreeService;
    private final com.cabgo.repository.SOSAlertRepository sosAlertRepository;
    private final com.cabgo.repository.PopularDestinationRepository popularDestinationRepository;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    /**
     * Normalizes the incoming phone number to 91XXXXXXXXXX format.
     */
    public String normalizePhone(String phone) {
        if (phone == null) return "";
        String clean = phone.replaceAll("[^0-9]", "");
        if (clean.length() == 10) {
            return "91" + clean;
        }
        return clean;
    }

    /**
     * Returns true only when lat/lng are valid non-zero coordinates.
     * Jackson deserialises missing JSON number fields as 0.0, so we must
     * guard against treating (0, 0) — the middle of the Atlantic Ocean — as a
     * legitimate pickup/drop coordinate.
     */
    private boolean isValidCoordinate(Double lat, Double lng) {
        return lat != null && lng != null
                && (Math.abs(lat) > 0.001 || Math.abs(lng) > 0.001); // at least 0.001° from null-island
    }

    private GoogleMapsService.GeocodingResult parseLocationInput(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String cleanText = text.trim();

        // 1. Check if it's a coordinate string like "12.9716,77.5946"
        java.util.regex.Pattern coordPattern = java.util.regex.Pattern.compile("^\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*$");
        java.util.regex.Matcher coordMatcher = coordPattern.matcher(cleanText);
        if (coordMatcher.matches()) {
            try {
                double lat = Double.parseDouble(coordMatcher.group(1));
                double lng = Double.parseDouble(coordMatcher.group(2));
                if (isValidCoordinate(lat, lng)) {
                    return new GoogleMapsService.GeocodingResult(lat, lng, "Coordinates: " + lat + ", " + lng);
                }
            } catch (Exception e) {
                log.error("Failed to parse coordinates: {}", cleanText, e);
            }
        }

        // 2. Check if it is a Google Maps Link
        if (cleanText.toLowerCase().contains("google.com/maps") || 
            cleanText.toLowerCase().contains("goo.gl/maps") || 
            cleanText.toLowerCase().contains("maps.app.goo.gl")) {
            
            GoogleMapsService.GeocodingResult urlGeo = resolveGoogleMapsLink(cleanText);
            if (urlGeo != null) {
                return urlGeo;
            }
        }

        // 3. Geocoding via MapsService
        try {
            GoogleMapsService.GeocodingResult geo = mapsService.geocode(cleanText);
            if (geo != null) {
                return geo;
            }
        } catch (Exception e) {
            log.warn("Google Maps Geocoding service failed/unreachable: {}", e.getMessage());
        }

        // 4. Local fallback: search seeded destinations and cities
        return lookupLocalDestination(cleanText);
    }

    /**
     * Entry point — called for every incoming WhatsApp message.
     */
    public void handleMessage(String fromPhone, String messageType, String textBody,
                               Double locationLat, Double locationLng, String locationName,
                               String interactiveReplyId) {
        // First check if the sender is a registered Driver
        String normalizedPhone = normalizePhone(fromPhone);
        Optional<Driver> driverOpt = driverRepository.findByWhatsappPhone(fromPhone)
            .or(() -> driverRepository.findByPhone(normalizedPhone));

        if (driverOpt.isPresent()) {
            handleDriverMessage(fromPhone, textBody, interactiveReplyId);
            return;
        }

        // Handle Customer Message
        ChatSession session = chatSessionRepository.findByWhatsappPhone(fromPhone)
            .orElseGet(() -> {
                ChatSession s = ChatSession.builder()
                    .whatsappPhone(fromPhone)
                    .state(ConversationState.IDLE)
                    .build();
                return chatSessionRepository.save(s);
            });

        session.setLastMessageAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        // Normalize input
        String text = textBody != null ? textBody.trim().toLowerCase() : "";
        String replyId = interactiveReplyId != null ? interactiveReplyId : "";

        // Global commands
        if (text.equals("hi") || text.equals("hello") || text.equals("start") || text.equals("menu")) {
            sendMainMenu(session);
            chatSessionRepository.save(session);
            return;
        }

        if (text.equals("sos")) {
            handleSOSAlert(session, false, fromPhone);
            return;
        }

        if (text.equals("track")) {
            handleTrackRide(session);
            return;
        }

        if (text.equals("fare") || text.contains("fare estimate")) {
            startFareEstimation(session);
            chatSessionRepository.save(session);
            return;
        }

        if (text.equals("cancel") && session.getState() != ConversationState.AWAITING_CANCEL_CONFIRM) {
            if (session.getActiveRideId() != null) {
                session.setState(ConversationState.AWAITING_CANCEL_CONFIRM);
                whatsAppService.sendText(fromPhone, "⚠️ *Cancel Ride Request*\n\nAre you sure you want to cancel your current booking?\n\n1️⃣ Yes, Cancel\n2️⃣ No, Keep Ride");
            } else {
                whatsAppService.sendText(fromPhone, "You don't have any active booking to cancel. Type *menu* to start.");
            }
            chatSessionRepository.save(session);
            return;
        }

        switch (session.getState()) {
            case IDLE, MAIN_MENU -> handleMainMenu(session, text, replyId);
            case AWAITING_PICKUP -> handlePickup(session, messageType, text, locationLat, locationLng, locationName);
            case AWAITING_DROP -> handleDrop(session, messageType, text, locationLat, locationLng, locationName);
            case AWAITING_VEHICLE_SELECTION -> handleVehicleSelection(session, text, replyId);
            case AWAITING_CONFIRMATION -> handleConfirmation(session, text, replyId);
            case AWAITING_PAYMENT -> handleAwaitingPayment(session, text);
            case RIDE_ACTIVE -> handleRideActive(session, text, replyId);
            case AWAITING_CANCEL_CONFIRM -> handleCancelConfirm(session, text, replyId);
            case AWAITING_PAYMENT_METHOD -> handlePaymentMethod(session, text, replyId);
            case AWAITING_RATING -> handleRating(session, text);
            
            // Driver registration steps
            case DRIVER_REG_NAME -> handleDriverRegName(session, text);
            case DRIVER_REG_EMAIL -> handleDriverRegEmail(session, text);
            case DRIVER_REG_CITY -> handleDriverRegCity(session, text);
            case DRIVER_REG_VEHICLE_CATEGORY -> handleDriverRegVehicleCategory(session, text, replyId);
            case DRIVER_REG_VEHICLE_MODEL -> handleDriverRegVehicleModel(session, text);
            case DRIVER_REG_VEHICLE_NUMBER -> handleDriverRegVehicleNumber(session, text);
            case DRIVER_REG_VEHICLE_COLOR -> handleDriverRegVehicleColor(session, text);
            case DRIVER_REG_AADHAAR -> handleDriverRegAadhaar(session, text);
            case DRIVER_REG_LICENSE -> handleDriverRegLicense(session, text);
            case DRIVER_REG_DOC_SELFIE -> handleDriverDocSelfie(session, messageType, text);
            case DRIVER_REG_DOC_AADHAAR -> handleDriverDocAadhaar(session, messageType, text);
            case DRIVER_REG_DOC_LICENSE -> handleDriverDocLicense(session, messageType, text);
            case DRIVER_REG_DOC_RC -> handleDriverDocRc(session, messageType, text);
            case DRIVER_REG_DOC_VEHICLE -> handleDriverDocVehicle(session, messageType, text);
            
            // Fare estimation steps
            case FARE_EST_PICKUP -> handleFareEstPickup(session, messageType, text, locationLat, locationLng, locationName);
            case FARE_EST_DROP -> handleFareEstDrop(session, messageType, text, locationLat, locationLng, locationName);
            case FARE_EST_VEHICLE -> handleFareEstVehicle(session, text, replyId);

            // Support — user is in support mode, redirect them back to main menu
            case SUPPORT -> {
                whatsAppService.sendText(fromPhone,
                    "\uD83C\uDD98 *Vazraa Support*\n\nFor urgent help call: *+91-9035999800*\n" +
                    "Email: support@vazraamobility.com\n\nType *menu* to return to main options.");
                session.setState(ConversationState.MAIN_MENU);
            }
            
            default -> {
                // Unknown state — reset to MAIN_MENU with clear instructions (no AI fallback)
                log.info("Unknown state {} for phone {}, resetting to MAIN_MENU", session.getState(), fromPhone);
                session.setState(ConversationState.MAIN_MENU);
                whatsAppService.sendText(fromPhone,
                    "Sorry, something went wrong. Let's start over.\n\n" +
                    "What would you like to do?\n\n" +
                    "1\uFE0F\u20E3 Book a Ride\n" +
                    "2\uFE0F\u20E3 View Ride History\n" +
                    "3\uFE0F\u20E3 Cancel Ride\n" +
                    "4\uFE0F\u20E3 Support / Help\n" +
                    "5\uFE0F\u20E3 Register as Driver\n\n" +
                    "Reply with a number (1-5) to continue.");
            }
        }
        chatSessionRepository.save(session);
    }

    // =================== DRIVER INCOMING MESSAGE ===================
    /**
     * Handle messages sent BY A DRIVER (from their WhatsApp).
     */
    public void handleDriverMessage(String driverPhone, String text, String replyId) {
        String input = text != null ? text.trim().toLowerCase() : "";
        String cleanReply = replyId != null ? replyId.trim().toUpperCase() : "";

        if (input.equalsIgnoreCase("sos")) {
            handleSOSAlert(null, true, driverPhone);
            return;
        }

        if (input.startsWith("start ")) {
            String otp = input.substring(6).trim();
            driverStartRide(driverPhone, otp);
            return;
        }

        if (input.equals("1") || input.equals("accept") || cleanReply.equals("ACCEPT_RIDE")) {
            driverAcceptRide(driverPhone);
        } else if (input.equals("2") || input.equals("reject") || cleanReply.equals("REJECT_RIDE")) {
            driverRejectRide(driverPhone);
        } else if (input.equals("start") || cleanReply.equals("START_RIDE")) {
            driverStartRide(driverPhone, "");
        } else if (input.contains("complete") || input.contains("finish") || cleanReply.equals("COMPLETE_RIDE")) {
            driverCompleteRide(driverPhone);
        } else if (input.replaceAll("[^0-9]", "").length() == 4) {
            String otp = input.replaceAll("[^0-9]", "");
            driverStartRide(driverPhone, otp);
        } else {
            whatsAppService.sendText(driverPhone, 
                "📱 *Vazraa Driver Console*\n\n" +
                "Available commands:\n" +
                "• *1* or *accept* - Accept incoming request\n" +
                "• *2* or *reject* - Reject request\n" +
                "• *start <OTP>* - Start trip with passenger boarding OTP\n" +
                "• *complete* - Complete current trip\n" +
                "• *sos* - Trigger emergency SOS alert");
        }
    }

    // =================== STATE HANDLERS ===================

    private void sendMainMenu(ChatSession session) {
        session.setState(ConversationState.MAIN_MENU);
        String welcomeMsg = "Hi! Welcome to Vazraa Mobility 👋\n\n" +
            "I can help you book a ride.\n\n" +
            "What would you like to do?\n\n" +
            "1️⃣ Book a Ride\n" +
            "2️⃣ View Ride History\n" +
            "3️⃣ Cancel Ride\n" +
            "4️⃣ Support / Help\n" +
            "5️⃣ Register as Driver\n\n" +
            "Reply with a number (1-5) to continue.";
        whatsAppService.sendText(session.getWhatsappPhone(), welcomeMsg);
    }

    private void handleMainMenu(ChatSession session, String text, String replyId) {
        String input = replyId.isEmpty() ? text : replyId.toLowerCase();
        switch (input) {
            case "1", "book", "book_ride", "book a ride" -> startBooking(session);
            case "2", "my_rides", "rides", "my rides" -> showMyRides(session);
            case "3", "cancel", "cancel_ride", "cancel ride" -> {
                if (session.getActiveRideId() != null) {
                    session.setState(ConversationState.AWAITING_CANCEL_CONFIRM);
                    whatsAppService.sendText(session.getWhatsappPhone(), "⚠️ *Cancel Ride Request*\n\nAre you sure you want to cancel your current booking?\n\n1️⃣ Yes, Cancel\n2️⃣ No, Keep Ride");
                } else {
                    whatsAppService.sendText(session.getWhatsappPhone(), "You don't have any active booking to cancel. Type *menu* to start.");
                }
            }
            case "4", "support", "help" -> {
                session.setState(ConversationState.SUPPORT);
                whatsAppService.sendText(session.getWhatsappPhone(),
                    "🆘 *Vazraa Support*\n\nFor urgent help call: *+91-1800-VAZRAA*\nEmail: support@vazraamobility.com\n\nType *menu* to go back.");
            }
            case "5", "register", "driver" -> startDriverRegistration(session);
            default -> {
                // Unrecognised input — show numbered menu clearly (no AI fallback)
                log.info("Unrecognised main menu input '{}' for phone {}", input, session.getWhatsappPhone());
                whatsAppService.sendText(session.getWhatsappPhone(),
                    "Sorry, I didn't understand that.\n\n" +
                    "Please reply with:\n\n" +
                    "1\uFE0F\u20E3 Book a Ride\n" +
                    "2\uFE0F\u20E3 View Ride History\n" +
                    "3\uFE0F\u20E3 Cancel Ride\n" +
                    "4\uFE0F\u20E3 Support / Help\n" +
                    "5\uFE0F\u20E3 Register as Driver\n\n" +
                    "Example: Type 1 to book a ride.");
            }
        }
    }

    private void startBooking(ChatSession session) {
        log.info("[ChatState] Transitioning user {} to AWAITING_PICKUP", session.getWhatsappPhone());
        session.setState(ConversationState.AWAITING_PICKUP);
        session.setTempPickupAddress(null);
        session.setTempPickupLat(null);
        session.setTempPickupLng(null);
        session.setTempDropAddress(null);
        session.setTempDropLat(null);
        session.setTempDropLng(null);
        session.setTempFare(null);
        session.setTempDistance(null);
        session.setTempDuration(null);
        session.setRejectedDriverIds(new ArrayList<>());

        whatsAppService.sendText(session.getWhatsappPhone(),
            "📍 *Please share your pickup location.*\n\n" +
            "You can either:\n" +
            "• Type your address or landmark\n" +
            "• Tap 📎 → *Location* → Send your current location\n\n" +
            "_Example: Koramangala, Bangalore_");
    }

    private void handlePickup(ChatSession session, String messageType, String text,
                               Double lat, Double lng, String locationName) {
        log.info("[ChatState] Processing pickup location input for user: {}, messageType: {}, lat: {}, lng: {}",
                session.getWhatsappPhone(), messageType, lat, lng);

        if ("location".equals(messageType) && isValidCoordinate(lat, lng)) {
            // Customer shared their WhatsApp location — reverse-geocode for a readable address
            String address;
            if (locationName != null && !locationName.isBlank()
                    && !locationName.matches("-?\\d+\\.\\d+,\\s*-?\\d+\\.\\d+")) {
                // AiSensy already provided a name (saved place name from WhatsApp)
                address = locationName;
                log.info("[ChatState] Using WhatsApp location name as pickup address: {}", address);
            } else {
                // No name or only raw coordinates — call Google Maps Reverse Geocoding
                log.info("[ChatState] No location name in payload — reverse geocoding lat={}, lng={}", lat, lng);
                GoogleMapsService.GeocodingResult rev = mapsService.reverseGeocode(lat, lng);
                address = rev.formattedAddress();
                log.info("[ChatState] Reverse geocoded pickup address: {}", address);
            }
            session.setTempPickupLat(lat);
            session.setTempPickupLng(lng);
            session.setTempPickupAddress(address);

        } else if (text != null && !text.isBlank()) {
            GoogleMapsService.GeocodingResult geo = parseLocationInput(text);
            if (geo != null) {
                session.setTempPickupLat(geo.lat());
                session.setTempPickupLng(geo.lng());
                session.setTempPickupAddress(geo.formattedAddress());
            } else {
                log.warn("Geocoding returned null for pickup '{}', using text input with default coordinates", text);
                session.setTempPickupLat(12.9716);
                session.setTempPickupLng(77.5946);
                session.setTempPickupAddress(text);
            }

        } else if ("location".equals(messageType) && !isValidCoordinate(lat, lng)) {
            whatsAppService.sendText(session.getWhatsappPhone(),
                "📍 Got your location pin, but could not read the coordinates.\n\nPlease try again by pinning your location or typing the address.");
            return;

        } else {
            whatsAppService.sendText(session.getWhatsappPhone(),
                "📍 *Please share your pickup location.*\n\n" +
                "You can either:\n" +
                "• Type your address or landmark\n" +
                "• Tap 📎 → *Location* → Send your current location");
            return;
        }

        log.info("[ChatState] Transitioning user {} to AWAITING_DROP. Pickup set: {}",
                session.getWhatsappPhone(), session.getTempPickupAddress());
        session.setState(ConversationState.AWAITING_DROP);
        whatsAppService.sendText(session.getWhatsappPhone(),
            "✅ *Pickup confirmed:* " + session.getTempPickupAddress() + "\n\n" +
            "📍 *Now share your drop location.*\n\n" +
            "You can either:\n" +
            "• Type your destination address or landmark\n" +
            "• Tap 📎 → *Location* → Send your destination\n\n" +
            "_Example: Indiranagar, Bangalore_");
    }

    private void handleDrop(ChatSession session, String messageType, String text,
                             Double lat, Double lng, String locationName) {
        log.info("[ChatState] Processing drop location input for user: {}, messageType: {}, lat: {}, lng: {}",
                session.getWhatsappPhone(), messageType, lat, lng);

        if ("location".equals(messageType) && isValidCoordinate(lat, lng)) {
            // Customer shared their WhatsApp location — reverse-geocode for a readable address
            String address;
            if (locationName != null && !locationName.isBlank()
                    && !locationName.matches("-?\\d+\\.\\d+,\\s*-?\\d+\\.\\d+")) {
                // AiSensy already provided a saved place name from WhatsApp
                address = locationName;
                log.info("[ChatState] Using WhatsApp location name as drop address: {}", address);
            } else {
                // No name — call Google Maps Reverse Geocoding
                log.info("[ChatState] No location name in payload — reverse geocoding lat={}, lng={}", lat, lng);
                GoogleMapsService.GeocodingResult rev = mapsService.reverseGeocode(lat, lng);
                address = rev.formattedAddress();
                log.info("[ChatState] Reverse geocoded drop address: {}", address);
            }
            session.setTempDropLat(lat);
            session.setTempDropLng(lng);
            session.setTempDropAddress(address);

        } else if (text != null && !text.isBlank()) {
            GoogleMapsService.GeocodingResult geo = parseLocationInput(text);
            if (geo != null) {
                session.setTempDropLat(geo.lat());
                session.setTempDropLng(geo.lng());
                session.setTempDropAddress(geo.formattedAddress());
            } else {
                log.warn("Geocoding returned null for drop '{}', using text input with default coordinates", text);
                session.setTempDropLat(12.9352);
                session.setTempDropLng(77.6245);
                session.setTempDropAddress(text);
            }

        } else if ("location".equals(messageType) && !isValidCoordinate(lat, lng)) {
            whatsAppService.sendText(session.getWhatsappPhone(),
                "📍 Got your location pin, but could not read the coordinates.\n\nPlease try again by pinning your location or typing the address.");
            return;

        } else {
            whatsAppService.sendText(session.getWhatsappPhone(),
                "📍 *Please share your drop location.*\n\n" +
                "You can either:\n" +
                "• Type your destination address or landmark\n" +
                "• Tap 📎 → *Location* → Send your destination");
            return;
        }

        whatsAppService.sendText(session.getWhatsappPhone(), "Calculating fare... ⏳");

        // Calculate distance/duration/fare using the intelligent FareService (SEDAN as initial estimate)
        FareRequest fareReq = FareRequest.builder()
            .pickupLat(session.getTempPickupLat())
            .pickupLng(session.getTempPickupLng())
            .dropLat(session.getTempDropLat())
            .dropLng(session.getTempDropLng())
            .city("Bengaluru")
            .vehicleType("SEDAN")
            .build();
        FareResponse fareResp;
        try {
            fareResp = fareService.estimateFare(fareReq);
        } catch (Exception ex) {
            log.warn("FareService estimation failed, falling back to distance-only: {}", ex.getMessage());
            GoogleMapsService.DistanceResult fallback = mapsService.getDistance(
                session.getTempPickupLat(), session.getTempPickupLng(),
                session.getTempDropLat(), session.getTempDropLng());
            fareResp = FareResponse.builder()
                .distanceKm(fallback.distanceKm())
                .durationMinutes(fallback.durationMinutes())
                .estimatedFare((long) (50.0 + fallback.distanceKm() * 12.0 + fallback.durationMinutes() * 1.0))
                .build();
        }
        session.setTempDistance(fareResp.getDistanceKm());
        session.setTempDuration((double) fareResp.getDurationMinutes());
        session.setTempFare((double) fareResp.getEstimatedFare());

        // Compute fares for all vehicle types dynamically using FareService
        double miniFare = getCategoryFare(session, "MINI", 10.0);
        double hatchbackFare = getCategoryFare(session, "HATCHBACK", 11.0);
        double sedanFare = fareResp.getEstimatedFare();
        double suvFare = getCategoryFare(session, "SUV", 15.0);
        double luxuryFare = getCategoryFare(session, "LUXURY", 22.0);

        log.info("[ChatState] Fares calculated -> MINI={}, HATCHBACK={}, SEDAN={}, SUV={}, LUXURY={}", 
            miniFare, hatchbackFare, sedanFare, suvFare, luxuryFare);

        session.setState(ConversationState.AWAITING_VEHICLE_SELECTION);

        String fareMsg = String.format(
            "🚖 *Vazra Fare Estimation*\n\n" +
            "📍 Pickup: %s\n" +
            "🎯 Drop: %s\n" +
            "📏 Distance: %.1f km\n" +
            "⏱ ETA: %.0f mins\n\n" +
            "🚗 *Vehicle Options & Fares:*\n" +
            "1️⃣ Mini (4 people): ₹%.0f\n" +
            "2️⃣ Hatchback (4 people): ₹%.0f\n" +
            "3️⃣ Sedan (4 people): ₹%.0f\n" +
            "4️⃣ SUV (6 people): ₹%.0f\n" +
            "5️⃣ Luxury (4 people): ₹%.0f\n\n" +
            "Please reply with the option number (1-5) to select and book your cab.",
            session.getTempPickupAddress(),
            session.getTempDropAddress(),
            session.getTempDistance() != null ? session.getTempDistance() : 0.0,
            session.getTempDuration() != null ? session.getTempDuration() : 0.0,
            miniFare,
            hatchbackFare,
            sedanFare,
            suvFare,
            luxuryFare
        );
        whatsAppService.sendText(session.getWhatsappPhone(), fareMsg);
    }

    private double getCategoryFare(ChatSession session, String category, double ratePerKm) {
        try {
            FareRequest req = FareRequest.builder()
                .pickupLat(session.getTempPickupLat())
                .pickupLng(session.getTempPickupLng())
                .dropLat(session.getTempDropLat())
                .dropLng(session.getTempDropLng())
                .city("Bengaluru")
                .vehicleType(category)
                .build();
            return fareService.estimateFare(req).getEstimatedFare();
        } catch (Exception e) {
            double dist = session.getTempDistance() != null ? session.getTempDistance() : 0.0;
            return 50.0 + dist * ratePerKm;
        }
    }

    // ─── Vehicle Selection Step (car-only, with people count) ─────────────────
    private void handleVehicleSelection(ChatSession session, String text, String replyId) {
        String input = replyId.isEmpty() ? text.trim() : replyId.trim();
        VehicleCategory chosen;
        String chosenLabel;
        switch (input) {
            case "1", "mini"      -> { chosen = VehicleCategory.MINI;      chosenLabel = "Mini (4 people)"; }
            case "2", "hatchback" -> { chosen = VehicleCategory.HATCHBACK; chosenLabel = "Hatchback (4 people)"; }
            case "3", "sedan"     -> { chosen = VehicleCategory.SEDAN;     chosenLabel = "Sedan (4 people)"; }
            case "4", "suv"       -> { chosen = VehicleCategory.SUV;       chosenLabel = "SUV (6 people)"; }
            case "5", "luxury"    -> { chosen = VehicleCategory.LUXURY;    chosenLabel = "Luxury (4 people)"; }
            default -> {
                whatsAppService.sendText(session.getWhatsappPhone(),
                    "❌ Invalid choice. Please reply 1, 2, 3, 4, or 5 to select your ride type.");
                return;
            }
        }
        session.setTempVehicleCategory(chosen.name());

        // Recalculate fare using FareService with the selected vehicle category
        double fare;
        try {
            FareRequest fareReq = FareRequest.builder()
                .pickupLat(session.getTempPickupLat() != null ? session.getTempPickupLat() : 0.0)
                .pickupLng(session.getTempPickupLng() != null ? session.getTempPickupLng() : 0.0)
                .dropLat(session.getTempDropLat() != null ? session.getTempDropLat() : 0.0)
                .dropLng(session.getTempDropLng() != null ? session.getTempDropLng() : 0.0)
                .city("Bengaluru")
                .vehicleType(chosen.name())
                .build();
            FareResponse resp = fareService.estimateFare(fareReq);
            fare = resp.getEstimatedFare();
            // Update session with recalculated distance/duration
            session.setTempDistance(resp.getDistanceKm());
            session.setTempDuration((double) resp.getDurationMinutes());
            session.setTempFare(fare);
        } catch (Exception ex) {
            log.warn("FareService recalc failed for vehicle {}: {}", chosen.name(), ex.getMessage());
            fare = session.getTempFare() != null ? session.getTempFare() : 0.0;
        }

        session.setState(ConversationState.AWAITING_CONFIRMATION);

        String fareMsg = String.format(
            "🚖 *Ride Summary*\n\n" +
            "📍 Pickup: %s\n" +
            "🎯 Drop: %s\n" +
            "🚗 Ride Type: *%s*\n\n" +
            "📏 Distance: %.1f km\n" +
            "⏱ Duration: %.0f mins\n\n" +
            "💰 *Estimated Fare: ₹%.0f*\n\n" +
            "Confirm booking?\n1️⃣ Confirm ✅\n2️⃣ Cancel ❌",
            session.getTempPickupAddress(), session.getTempDropAddress(), chosenLabel,
            session.getTempDistance() != null ? session.getTempDistance() : 0.0,
            session.getTempDuration() != null ? session.getTempDuration() : 0.0,
            fare
        );
        whatsAppService.sendText(session.getWhatsappPhone(), fareMsg);
    }

    private void handleConfirmation(ChatSession session, String text, String replyId) {
        String input = replyId.isEmpty() ? text : replyId.toLowerCase();
        if (input.equals("1") || input.equals("confirm") || input.contains("yes")) {
            initiatePaymentForRide(session);
        } else if (input.equals("2") || input.equals("cancel") || input.contains("no")) {
            session.setState(ConversationState.MAIN_MENU);
            whatsAppService.sendText(session.getWhatsappPhone(), "Booking cancelled. Type *menu* to start again.");
        } else {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please reply 1 to Confirm or 2 to Cancel.");
        }
    }

    /**
     * Creates a ride (in PENDING_PAYMENT status), creates a Cashfree Sandbox order,
     * sends the payment link to the customer via WhatsApp, and waits for payment.
     */
    private void initiatePaymentForRide(ChatSession session) {
        String phone = session.getWhatsappPhone();
        log.info("[Cashfree] Initiating payment for session phone={}", phone);

        // Look up or auto-register customer
        String phoneSuffix = normalizePhone(phone);
        Customer customer = customerRepository.findByPhone(phoneSuffix)
            .orElseGet(() -> {
                Customer newCust = Customer.builder()
                    .name("WhatsApp User")
                    .phone(phoneSuffix)
                    .email("wa_" + phone + "@vazraa.com")
                    .password("")
                    .status(CustomerStatus.ACTIVE)
                    .build();
                return customerRepository.save(newCust);
            });
        session.setCustomerId(customer.getId());
        session.setCustomerName(customer.getName());

        double fare = session.getTempFare() != null ? session.getTempFare() : 0.0;

        // Create the ride in PENDING_PAYMENT state — no driver matching yet
        Ride ride = Ride.builder()
            .customerId(customer.getId())
            .customerName(customer.getName())
            .customerWhatsappPhone(phone)
            .pickupLocation(session.getTempPickupAddress())
            .pickupLatitude(session.getTempPickupLat())
            .pickupLongitude(session.getTempPickupLng())
            .dropLocation(session.getTempDropAddress())
            .dropLatitude(session.getTempDropLat())
            .dropLongitude(session.getTempDropLng())
            .vehicleCategory(session.getTempVehicleCategory() != null
                    ? VehicleCategory.valueOf(session.getTempVehicleCategory())
                    : VehicleCategory.SEDAN)
            .status(RideStatus.PENDING_PAYMENT)
            .estimatedFare(fare)
            .fare(fare)
            .distance(session.getTempDistance())
            .duration(session.getTempDuration())
            .paymentMethod(PaymentMethod.UPI)
            .paymentStatus("PENDING")
            .otp(String.format("%04d", new Random().nextInt(10000)))
            .bookingTime(LocalDateTime.now())
            .requestedAt(LocalDateTime.now())
            .build();
        ride = rideRepository.save(ride);

        // Generate unique Cashfree order ID
        String cashfreeOrderId = CashfreeService.generateOrderId(ride.getId());
        ride.setCashfreeOrderId(cashfreeOrderId);
        rideRepository.save(ride);

        // Store pending state on session
        session.setPendingRideId(ride.getId());
        session.setPendingCashfreeOrderId(cashfreeOrderId);

        // Create Cashfree Sandbox order
        whatsAppService.sendText(phone, "⏳ Creating your payment link...");
        CashfreeService.CashfreeOrderResult orderResult = cashfreeService.createOrder(
                cashfreeOrderId,
                fare,
                phone,
                customer.getName() != null ? customer.getName() : "Vazraa Customer",
                customer.getEmail() != null ? customer.getEmail() : "noreply@vazraa.com"
        );

        if (!orderResult.isSuccess()) {
            log.error("[Cashfree] Order creation failed: {}", orderResult.errorMessage());
            // Fall back to cash booking if Cashfree fails
            whatsAppService.sendText(phone,
                "⚠️ Payment gateway is temporarily unavailable.\n\n" +
                "Your booking has been confirmed as a *Cash payment* booking.\n\n" +
                "💵 Please pay ₹" + String.format("%.0f", fare) + " to the driver in cash.");
            ride.setPaymentMethod(PaymentMethod.CASH);
            ride.setStatus(RideStatus.SEARCHING);
            rideRepository.save(ride);
            session.setActiveRideId(ride.getId());
            session.setPendingRideId(null);
            session.setPendingCashfreeOrderId(null);
            session.setState(ConversationState.RIDE_ACTIVE);
            chatSessionRepository.save(session);
            findAndAssignNextDriver(session, ride);
            return;
        }

        // Store payment link on ride
        String paymentLink = orderResult.paymentLink();
        ride.setCashfreePaymentLink(paymentLink);
        rideRepository.save(ride);
        session.setPendingPaymentLink(paymentLink);

        session.setState(ConversationState.AWAITING_PAYMENT);
        chatSessionRepository.save(session);

        String paymentMsg = String.format(
            "💳 *Your ride fare is ₹%.0f.*\n\n" +
            "Please complete the payment using the link below:\n\n" +
            "%s\n\n" +
            "Once payment is successful, your booking will be confirmed.\n\n" +
            "📌 *Booking ID:* #%s\n\n" +
            "_Type *status* to check payment status or *cancel* to cancel the booking._",
            fare,
            paymentLink,
            ride.getId().substring(Math.max(0, ride.getId().length() - 6)).toUpperCase()
        );
        whatsAppService.sendText(phone, paymentMsg);
        log.info("[Cashfree] Payment link sent to {} for orderId={}", phone, cashfreeOrderId);
    }

    /**
     * Handles customer messages while in AWAITING_PAYMENT state.
     * Supports: "status" (verify payment), "cancel" (cancel booking), "retry" (resend link).
     */
    private void handleAwaitingPayment(ChatSession session, String text) {
        String phone = session.getWhatsappPhone();
        String input = text != null ? text.trim().toLowerCase() : "";

        if (input.equals("cancel")) {
            // Cancel the pending ride
            if (session.getPendingRideId() != null) {
                rideRepository.findById(session.getPendingRideId()).ifPresent(ride -> {
                    ride.setStatus(RideStatus.CANCELLED);
                    ride.setCancellationReason("Cancelled by customer during payment");
                    ride.setCancelledBy("CUSTOMER");
                    ride.setCancelledAt(LocalDateTime.now());
                    rideRepository.save(ride);
                });
            }
            session.setPendingRideId(null);
            session.setPendingCashfreeOrderId(null);
            session.setPendingPaymentLink(null);
            session.setState(ConversationState.MAIN_MENU);
            whatsAppService.sendText(phone, "❌ Booking cancelled. Type *menu* to start again.");

        } else if (input.equals("status") || input.equals("check")) {
            // Manually verify payment status with Cashfree
            String orderId = session.getPendingCashfreeOrderId();
            if (orderId == null) {
                whatsAppService.sendText(phone, "No pending payment found. Type *menu* to restart.");
                return;
            }
            whatsAppService.sendText(phone, "🔄 Checking your payment status...");
            CashfreeService.PaymentVerificationResult result = cashfreeService.verifyPayment(orderId);
            handleCashfreePaymentResult(orderId, result.isPaid() ? "SUCCESS" : (result.isFailed() ? "FAILED" : "PENDING"));

        } else if (input.equals("retry") || input.equals("resend") || input.contains("link")) {
            // Resend the payment link
            String link = session.getPendingPaymentLink();
            double fare = session.getTempFare() != null ? session.getTempFare() : 0.0;
            if (link != null) {
                whatsAppService.sendText(phone,
                    String.format("💳 *Resending your payment link:*\n\n" +
                    "Fare: ₹%.0f\n\n%s\n\n" +
                    "_Type *status* to verify or *cancel* to cancel._", fare, link));
            } else {
                whatsAppService.sendText(phone, "Payment link not available. Type *cancel* to restart.");
            }
        } else {
            // Default — remind them what to do
            String link = session.getPendingPaymentLink();
            double fare = session.getTempFare() != null ? session.getTempFare() : 0.0;
            whatsAppService.sendText(phone,
                String.format("⏳ *Payment Pending*\n\n" +
                "Your fare is ₹%.0f. Please complete the payment:\n\n" +
                "%s\n\n" +
                "Commands:\n" +
                "• *status* — check payment status\n" +
                "• *retry* — resend payment link\n" +
                "• *cancel* — cancel booking",
                fare, link != null ? link : "[Link expired — type cancel to restart]"));
        }
        chatSessionRepository.save(session);
    }

    /**
     * Called by CashfreeWebhookController when Cashfree sends a payment notification,
     * or manually triggered when the customer types "status" during AWAITING_PAYMENT.
     *
     * @param cashfreeOrderId  The Cashfree order ID
     * @param paymentStatus    "SUCCESS", "FAILED", or "PENDING"
     */
    public void handleCashfreePaymentResult(String cashfreeOrderId, String paymentStatus) {
        log.info("[Cashfree] handleCashfreePaymentResult: orderId={}, status={}", cashfreeOrderId, paymentStatus);

        Optional<ChatSession> sessionOpt = chatSessionRepository.findByPendingCashfreeOrderId(cashfreeOrderId);
        if (sessionOpt.isEmpty()) {
            log.warn("[Cashfree] No session found for orderId={}", cashfreeOrderId);
            return;
        }
        ChatSession session = sessionOpt.get();
        String phone = session.getWhatsappPhone();

        switch (paymentStatus.toUpperCase()) {
            case "SUCCESS" -> {
                // Mark ride as payment received and start driver matching
                String pendingRideId = session.getPendingRideId();
                if (pendingRideId == null) {
                    log.error("[Cashfree] SUCCESS but no pendingRideId on session for phone={}", phone);
                    whatsAppService.sendText(phone, "✅ Payment received! However, we had trouble locating your booking. Please type *menu* and book again.");
                    return;
                }

                Optional<Ride> rideOpt = rideRepository.findById(pendingRideId);
                if (rideOpt.isEmpty()) {
                    log.error("[Cashfree] Ride not found for pendingRideId={}", pendingRideId);
                    whatsAppService.sendText(phone, "✅ Payment received! However, we had trouble locating your booking. Please contact support.");
                    return;
                }

                Ride ride = rideOpt.get();
                ride.setStatus(RideStatus.SEARCHING);
                ride.setPaymentStatus("PAID");
                rideRepository.save(ride);

                // Clear payment pending state on session, set active ride
                session.setActiveRideId(ride.getId());
                session.setPendingRideId(null);
                session.setPendingCashfreeOrderId(null);
                session.setPendingPaymentLink(null);
                session.setState(ConversationState.RIDE_ACTIVE);
                chatSessionRepository.save(session);

                whatsAppService.sendText(phone,
                    "✅ *Payment Received!*\n\n" +
                    "💰 ₹" + String.format("%.0f", ride.getFare()) + " paid successfully.\n\n" +
                    "🔍 Searching for nearby drivers...\n\n" +
                    "Ride ID: #" + ride.getId().substring(Math.max(0, ride.getId().length() - 6)).toUpperCase() + "\n" +
                    "OTP: *" + ride.getOtp() + "*\n\n" +
                    "You'll be notified when a driver accepts.");

                findAndAssignNextDriver(session, ride);
            }

            case "FAILED" -> {
                // Payment failed — allow retry
                Optional<Ride> rideOpt = session.getPendingRideId() != null
                        ? rideRepository.findById(session.getPendingRideId())
                        : Optional.empty();

                double fare = session.getTempFare() != null ? session.getTempFare() : 0.0;
                String link = session.getPendingPaymentLink();

                // Mark ride payment failed (but keep ride for retry)
                rideOpt.ifPresent(ride -> {
                    ride.setPaymentStatus("FAILED");
                    rideRepository.save(ride);
                });

                // Keep session in AWAITING_PAYMENT so customer can retry
                chatSessionRepository.save(session);

                whatsAppService.sendText(phone,
                    String.format("❌ *Payment Failed*\n\n" +
                    "Your payment of ₹%.0f could not be processed.\n\n" +
                    "Please try again:\n%s\n\n" +
                    "Or type:\n" +
                    "• *retry* — resend payment link\n" +
                    "• *cancel* — cancel booking",
                    fare, link != null ? link : "[Link expired — type cancel and rebook]"));
            }

            case "PENDING" -> {
                chatSessionRepository.save(session);
                whatsAppService.sendText(phone,
                    "⏳ *Payment Processing*\n\n" +
                    "Your payment is still being processed by the bank.\n\n" +
                    "Please wait a few minutes. Type *status* to check again.");
            }

            default -> {
                log.warn("[Cashfree] Unknown paymentStatus={} for orderId={}", paymentStatus, cashfreeOrderId);
                chatSessionRepository.save(session);
            }
        }
    }

    // NOTE: createRideAndFindDriver is no longer called directly from handleConfirmation.
    // Payment now goes through initiatePaymentForRide → handleCashfreePaymentResult → findAndAssignNextDriver.
    // This method is kept for any legacy/cash fallback usage.

    private void findAndAssignNextDriver(ChatSession session, Ride ride) {
        Optional<Driver> nearestDriver = driverMatchingService.findNearestDriver(
            session.getTempPickupLat(), session.getTempPickupLng(), session.getRejectedDriverIds());

        if (nearestDriver.isPresent()) {
            Driver driver = nearestDriver.get();
            String driverPhone = driver.getWhatsappPhone() != null ? driver.getWhatsappPhone() : driver.getPhone();
            // Normalize driver phone
            if (driverPhone != null && driverPhone.startsWith("0")) driverPhone = "91" + driverPhone.substring(1);
            else if (driverPhone != null && !driverPhone.startsWith("91")) driverPhone = "91" + driverPhone;

            // Update ride with driver info temporarily (pending acceptance)
            ride.setDriverId(driver.getId());
            ride.setDriverName(driver.getName());
            ride.setDriverPhone(driverPhone);
            ride.setDriverVehicleNumber(driver.getVehicleNumber());
            rideRepository.save(ride);

            // Notify driver via WhatsApp
            String driverMsg = String.format(
                "🚖 *New Ride Request!*\n\n" +
                "📍 Pickup: %s\n" +
                "🎯 Drop: %s\n" +
                "💰 Fare: ₹%.0f\n" +
                "📏 Distance: %.1f km\n\n" +
                "Reply:\n1️⃣ Accept\n2️⃣ Reject",
                session.getTempPickupAddress(), session.getTempDropAddress(),
                session.getTempFare(), session.getTempDistance()
            );
            whatsAppService.sendText(driverPhone, driverMsg);
        } else {
            // No driver found
            ride.setStatus(RideStatus.CANCELLED);
            ride.setCancellationReason("No drivers available");
            rideRepository.save(ride);

            session.setState(ConversationState.MAIN_MENU);
            session.setActiveRideId(null);
            chatSessionRepository.save(session);

            whatsAppService.sendText(session.getWhatsappPhone(),
                "❌ Sorry, no drivers are available in your area at the moment. Your request has been cancelled. Type *menu* to restart.");
        }
    }

    private void handleRideActive(ChatSession session, String text, String replyId) {
        whatsAppService.sendText(session.getWhatsappPhone(), 
            "🚖 Your booking is currently active.\n\n• Type *track* to get updates.\n• Type *cancel* to cancel booking.");
    }

    private void handleTrackRide(ChatSession session) {
        if (session.getActiveRideId() == null) {
            whatsAppService.sendText(session.getWhatsappPhone(), "You don't have any active ride. Type *menu* to start.");
            return;
        }

        Optional<Ride> rideOpt = rideRepository.findById(session.getActiveRideId());
        if (rideOpt.isEmpty()) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Ride details not found.");
            return;
        }

        Ride ride = rideOpt.get();
        if (ride.getStatus() == RideStatus.COMPLETED || ride.getStatus() == RideStatus.CANCELLED) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Your last ride is finished. Type *menu* to book a new one.");
            return;
        }

        String statusMsg = "🚖 *Ride Status:* " + ride.getStatus() + "\n";
        if (ride.getDriverName() != null) {
            statusMsg += "👨‍✈️ Driver: " + ride.getDriverName() + "\n" +
                "🚗 Vehicle: " + ride.getDriverVehicleNumber() + "\n" +
                "📌 OTP: " + ride.getOtp() + "\n";
        } else {
            statusMsg += "🔍 Searching for driver...\n";
        }
        
        statusMsg += "\n📍 Pickup: " + ride.getPickupLocation() + "\n" +
            "🎯 Drop: " + ride.getDropLocation() + "\n" +
            "💰 Fare: ₹" + ride.getFare();
        
        whatsAppService.sendText(session.getWhatsappPhone(), statusMsg);
    }

    private void handleCancelConfirm(ChatSession session, String text, String replyId) {
        String input = replyId.isEmpty() ? text : replyId.toLowerCase();
        if (input.equals("1") || input.contains("yes") || input.contains("cancel")) {
            cancelActiveRide(session);
        } else {
            session.setState(ConversationState.RIDE_ACTIVE);
            whatsAppService.sendText(session.getWhatsappPhone(), "Ride kept active. We will continue your booking.");
        }
    }

    private void cancelActiveRide(ChatSession session) {
        if (session.getActiveRideId() == null) {
            session.setState(ConversationState.MAIN_MENU);
            whatsAppService.sendText(session.getWhatsappPhone(), "No active ride to cancel. Returning to menu.");
            return;
        }

        Optional<Ride> rideOpt = rideRepository.findById(session.getActiveRideId());
        if (rideOpt.isPresent()) {
            Ride ride = rideOpt.get();
            if (ride.getStatus() == RideStatus.ONGOING) {
                whatsAppService.sendText(session.getWhatsappPhone(), "❌ Cannot cancel a ride that has already started.");
                session.setState(ConversationState.RIDE_ACTIVE);
                return;
            }

            ride.setStatus(RideStatus.CANCELLED);
            ride.setCancelledBy("CUSTOMER");
            ride.setCancelledAt(LocalDateTime.now());
            ride.setCancellationReason("Cancelled via WhatsApp");
            rideRepository.save(ride);

            // If driver was assigned, notify driver and free driver
            if (ride.getDriverId() != null) {
                Optional<Driver> driverOpt = driverRepository.findById(ride.getDriverId());
                if (driverOpt.isPresent()) {
                    Driver driver = driverOpt.get();
                    driver.setAvailableForRide(true);
                    driver.setStatus(DriverStatus.ONLINE);
                    driverRepository.save(driver);

                    String driverPhone = driver.getWhatsappPhone() != null ? driver.getWhatsappPhone() : driver.getPhone();
                    whatsAppService.sendText(driverPhone, "⚠️ Ride request #" + ride.getId().substring(ride.getId().length() - 6).toUpperCase() + " has been cancelled by the customer.");
                }
            }
        }

        session.setState(ConversationState.MAIN_MENU);
        session.setActiveRideId(null);
        whatsAppService.sendText(session.getWhatsappPhone(), "❌ Your booking has been cancelled successfully. Type *menu* to book again.");
    }

    private void handlePaymentMethod(ChatSession session, String text, String replyId) {
        String input = replyId.isEmpty() ? text.trim().toLowerCase() : replyId.toLowerCase();
        if (input.equals("1") || input.contains("online") || input.contains("upi")) {
            whatsAppService.sendText(session.getWhatsappPhone(),
                "💳 Online Payment selected.\n\n" +
                "Please pay via UPI or scan the QR code provided by your driver.\n\n" +
                "Type menu to return to main menu.");
            session.setState(ConversationState.MAIN_MENU);
        } else if (input.equals("2") || input.contains("cash")) {
            whatsAppService.sendText(session.getWhatsappPhone(),
                "💵 Cash Payment selected.\n\n" +
                "Please pay the driver directly in cash.\n\n" +
                "Type menu to return to main menu.");
            session.setState(ConversationState.MAIN_MENU);
        } else {
            whatsAppService.sendText(session.getWhatsappPhone(),
                "💳 Please choose a payment method.\n\n" +
                "1️⃣ Online Payment (UPI / QR Code)\n" +
                "2️⃣ Cash\n\n" +
                "Reply with 1 or 2.");
        }
    }

    private void handleRating(ChatSession session, String text) {
        try {
            int rating = Integer.parseInt(text.trim());
            if (rating < 1 || rating > 5) {
                whatsAppService.sendText(session.getWhatsappPhone(), "Please enter a valid rating between 1 and 5 stars.");
                return;
            }

            if (session.getActiveRideId() != null) {
                Optional<Ride> rideOpt = rideRepository.findById(session.getActiveRideId());
                if (rideOpt.isPresent()) {
                    Ride ride = rideOpt.get();
                    if (ride.getDriverId() != null) {
                        Optional<Driver> driverOpt = driverRepository.findById(ride.getDriverId());
                        if (driverOpt.isPresent()) {
                            Driver driver = driverOpt.get();
                            double currentRating = driver.getRating() != null ? driver.getRating() : 0.0;
                            int totalRides = driver.getTotalRides() != null ? driver.getTotalRides() : 0;
                            double newRating;
                            if (totalRides <= 1) {
                                newRating = (double) rating;
                            } else {
                                newRating = ((currentRating * (totalRides - 1)) + rating) / totalRides;
                            }
                            newRating = Math.round(newRating * 10.0) / 10.0;
                            driver.setRating(newRating);
                            driverRepository.save(driver);
                        }
                    }
                }
            }

            whatsAppService.sendText(session.getWhatsappPhone(), "⭐ Thank you for rating your trip! Your feedback is highly appreciated.\n\nType *menu* to return to the main menu.");
            session.setState(ConversationState.MAIN_MENU);
            session.setActiveRideId(null);
        } catch (NumberFormatException e) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please reply with a single number from 1 to 5 to rate your driver.");
        }
    }

    private void showMyRides(ChatSession session) {
        List<Ride> rides = rideRepository.findByCustomerWhatsappPhone(session.getWhatsappPhone());
        if (rides == null || rides.isEmpty()) {
            if (session.getCustomerId() != null) {
                rides = rideRepository.findByCustomerIdAndStatus(session.getCustomerId(), RideStatus.COMPLETED);
            }
        }

        if (rides == null || rides.isEmpty()) {
            whatsAppService.sendText(session.getWhatsappPhone(), "You do not have any ride history with Vazraa yet. Type *menu* to start booking!");
            return;
        }

        // Sort by booking time descending and take top 3
        rides.sort((r1, r2) -> {
            LocalDateTime t1 = r1.getBookingTime() != null ? r1.getBookingTime() : LocalDateTime.MIN;
            LocalDateTime t2 = r2.getBookingTime() != null ? r2.getBookingTime() : LocalDateTime.MIN;
            return t2.compareTo(t1);
        });

        StringBuilder history = new StringBuilder("📜 *Your Ride History (Last 3 trips):*\n\n");
        int count = 0;
        for (Ride r : rides) {
            if (count++ >= 3) break;
            history.append(String.format(
                "🚖 *Ride #%s*\n" +
                "📅 Date: %s\n" +
                "📍 From: %s\n" +
                "🎯 To: %s\n" +
                "💰 Fare: ₹%.0f\n" +
                "⚡ Status: %s\n\n",
                r.getId().substring(r.getId().length() - 6).toUpperCase(),
                r.getBookingTime() != null ? r.getBookingTime().toString().substring(0, 10) : "N/A",
                r.getPickupLocation(),
                r.getDropLocation(),
                r.getFare(),
                r.getStatus()
            ));
        }

        history.append("Type *menu* to return to main menu.");
        whatsAppService.sendText(session.getWhatsappPhone(), history.toString());
    }

    // =================== DRIVER STATE TRANSITIONS ===================

    public void driverAcceptRide(String driverPhone) {
        Optional<Driver> driverOpt = driverRepository.findByWhatsappPhone(driverPhone)
            .or(() -> driverRepository.findByPhone(normalizePhone(driverPhone)));
        if (driverOpt.isEmpty()) {
            whatsAppService.sendText(driverPhone, "❌ Driver profile not registered.");
            return;
        }
        Driver driver = driverOpt.get();

        Optional<Ride> rideOpt = rideRepository.findByDriverIdAndStatusIn(driver.getId(), List.of(RideStatus.SEARCHING))
            .stream().findFirst();

        if (rideOpt.isPresent()) {
            Ride ride = rideOpt.get();
            ride.setStatus(RideStatus.ACCEPTED);
            ride.setAcceptedAt(LocalDateTime.now());
            rideRepository.save(ride);

            // Update driver status
            driver.setAvailableForRide(false);
            driver.setStatus(DriverStatus.BUSY);
            driverRepository.save(driver);

            // Notify driver
            whatsAppService.sendText(driverPhone,
                "✅ *Ride Request Accepted!*\n\n" +
                "📌 OTP: *" + ride.getOtp() + "*\n\n" +
                "📍 Pickup: " + ride.getPickupLocation() + "\n" +
                "🎯 Drop: " + ride.getDropLocation() + "\n\n" +
                "Please head to the pickup location. When customer boards and provides the OTP, type *start* to begin the trip.");

            // Notify customer
            Optional<ChatSession> sessionOpt = chatSessionRepository.findByWhatsappPhone(ride.getCustomerWhatsappPhone());
            if (sessionOpt.isPresent()) {
                ChatSession session = sessionOpt.get();
                session.setState(ConversationState.RIDE_ACTIVE);
                chatSessionRepository.save(session);

                String custMsg = String.format(
                    "🎉 *Driver Assigned!*\n\n" +
                    "👨‍✈️ Driver: *%s*\n" +
                    "📱 Phone: %s\n" +
                    "🚗 Vehicle: *%s* (%s)\n" +
                    "📌 OTP: *%s*\n\n" +
                    "Your driver is arriving at your pickup location. Please share the OTP *%s* with the driver to start the trip.",
                    driver.getName(), driver.getPhone(),
                    driver.getVehicleNumber(), driver.getVehicleModel(),
                    ride.getOtp(), ride.getOtp()
                );
                whatsAppService.sendText(session.getWhatsappPhone(), custMsg);
            }
        } else {
            whatsAppService.sendText(driverPhone, "❌ No active ride request found or already accepted/cancelled.");
        }
    }

    public void driverRejectRide(String driverPhone) {
        Optional<Driver> driverOpt = driverRepository.findByWhatsappPhone(driverPhone)
            .or(() -> driverRepository.findByPhone(normalizePhone(driverPhone)));
        if (driverOpt.isEmpty()) return;
        Driver driver = driverOpt.get();

        Optional<Ride> rideOpt = rideRepository.findByDriverIdAndStatusIn(driver.getId(), List.of(RideStatus.SEARCHING))
            .stream().findFirst();

        if (rideOpt.isPresent()) {
            Ride ride = rideOpt.get();
            Optional<ChatSession> sessionOpt = chatSessionRepository.findByWhatsappPhone(ride.getCustomerWhatsappPhone());
            if (sessionOpt.isPresent()) {
                ChatSession session = sessionOpt.get();
                if (session.getRejectedDriverIds() == null) {
                    session.setRejectedDriverIds(new ArrayList<>());
                }
                session.getRejectedDriverIds().add(driver.getId());
                chatSessionRepository.save(session);

                whatsAppService.sendText(driverPhone, "Declined. You will not receive notifications for this trip.");

                // Match next driver
                findAndAssignNextDriver(session, ride);
            }
        } else {
            whatsAppService.sendText(driverPhone, "No pending requests to reject.");
        }
    }

    public void driverStartRide(String driverPhone, String otpCode) {
        Optional<Driver> driverOpt = driverRepository.findByWhatsappPhone(driverPhone)
            .or(() -> driverRepository.findByPhone(normalizePhone(driverPhone)));
        if (driverOpt.isEmpty()) return;
        Driver driver = driverOpt.get();

        Optional<Ride> rideOpt = rideRepository.findByDriverIdAndStatusIn(driver.getId(), List.of(RideStatus.ACCEPTED, RideStatus.DRIVER_ARRIVING, RideStatus.DRIVER_ASSIGNED))
            .stream().findFirst();

        if (rideOpt.isPresent()) {
            Ride ride = rideOpt.get();
            if (otpCode == null || otpCode.trim().isEmpty()) {
                whatsAppService.sendText(driverPhone, "🔑 *Boarding OTP Required*\n\nPlease provide the passenger boarding OTP. Reply with *start <OTP>* (e.g. *start 1234*) or just send the 4-digit OTP.");
                return;
            }

            if (otpCode.trim().equals(ride.getOtp())) {
                ride.setStatus(RideStatus.ONGOING);
                ride.setStartedAt(LocalDateTime.now());
                ride.setStartTime(LocalDateTime.now());
                rideRepository.save(ride);

                whatsAppService.sendText(driverPhone, "🚖 *Trip Started!* Drive safely. Reply *complete* once you reach the destination.");
                whatsAppService.sendText(ride.getCustomerWhatsappPhone(), "🚖 *Your ride has started!* Have a safe and pleasant journey.");
                
                // Broadcast ride status updates via WebSocket
                try {
                    messagingTemplate.convertAndSend("/topic/ride/" + ride.getId() + "/status", ride);
                } catch (Exception wsEx) {
                    log.error("WebSocket broadcast error", wsEx);
                }
            } else {
                whatsAppService.sendText(driverPhone, "❌ Invalid OTP code. Please verify with the passenger and try again using *start <OTP>*.");
            }
        } else {
            whatsAppService.sendText(driverPhone, "❌ No pending ride found to start.");
        }
    }

    public void driverCompleteRide(String driverPhone) {
        Optional<Driver> driverOpt = driverRepository.findByWhatsappPhone(driverPhone)
            .or(() -> driverRepository.findByPhone(normalizePhone(driverPhone)));
        if (driverOpt.isEmpty()) return;
        Driver driver = driverOpt.get();

        Optional<Ride> rideOpt = rideRepository.findByDriverIdAndStatusIn(driver.getId(), List.of(RideStatus.ONGOING))
            .stream().findFirst();

        if (rideOpt.isPresent()) {
            Ride ride = rideOpt.get();
            ride.setStatus(RideStatus.COMPLETED);
            ride.setCompletedAt(LocalDateTime.now());
            ride.setEndTime(LocalDateTime.now());
            ride.setActualFare(ride.getFare());
            ride.setPaymentStatus("PAID");
            rideRepository.save(ride);

            // Reset driver status
            driver.setAvailableForRide(true);
            driver.setStatus(DriverStatus.ONLINE);
            driver.setTotalRides((driver.getTotalRides() != null ? driver.getTotalRides() : 0) + 1);
            driver.setTotalEarnings((driver.getTotalEarnings() != null ? driver.getTotalEarnings() : 0.0) + ride.getFare());
            driverRepository.save(driver);

            whatsAppService.sendText(driverPhone,
                "🏁 *Ride Completed successfully!*\n\n" +
                "💰 Fare: ₹" + ride.getFare() + "\n" +
                "Earnings: ₹" + ride.getFare() + "\n\n" +
                "You are now back online and available for new requests.");

            // Update customer
            Optional<ChatSession> sessionOpt = chatSessionRepository.findByWhatsappPhone(ride.getCustomerWhatsappPhone());
            if (sessionOpt.isPresent()) {
                ChatSession session = sessionOpt.get();
                session.setState(ConversationState.AWAITING_RATING);
                chatSessionRepository.save(session);

                whatsAppService.sendText(session.getWhatsappPhone(),
                    "🏁 You have arrived!\n\n" +
                    "💰 Total Fare: ₹" + ride.getFare() + "\n\n" +
                    "Please pay the driver via Cash or UPI.\n\n" +
                    "⭐ How was your ride? Please rate from 1 to 5 stars (reply with a number).");
            }
        } else {
            whatsAppService.sendText(driverPhone, "❌ No ongoing trip found to complete.");
        }
    }

    // =================== DRIVER REGISTRATION FLOW ===================
    private void startDriverRegistration(ChatSession session) {
        session.setState(ConversationState.DRIVER_REG_NAME);
        whatsAppService.sendText(session.getWhatsappPhone(),
            "🚕 *Become a Vazraa Partner!*\n\n" +
            "Let's get you registered as a driver on our platform.\n" +
            "Please type your *Full Name*:");
    }

    private void handleDriverRegName(ChatSession session, String text) {
        if (text.isEmpty()) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please enter a valid name.");
            return;
        }
        session.setTempDriverName(text);
        session.setState(ConversationState.DRIVER_REG_EMAIL);
        whatsAppService.sendText(session.getWhatsappPhone(), "Great! Now please enter your *Email Address*:");
    }

    private void handleDriverRegEmail(ChatSession session, String text) {
        if (!text.contains("@") || !text.contains(".")) {
            whatsAppService.sendText(session.getWhatsappPhone(), "❌ Invalid email format. Please enter a valid email address:");
            return;
        }
        session.setTempDriverEmail(text);
        session.setState(ConversationState.DRIVER_REG_CITY);
        whatsAppService.sendText(session.getWhatsappPhone(), "What *City* will you be operating in? (e.g. Bangalore)");
    }

    private void handleDriverRegCity(ChatSession session, String text) {
        if (text.isEmpty()) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please enter a valid city name.");
            return;
        }
        session.setTempDriverCity(text);
        session.setState(ConversationState.DRIVER_REG_VEHICLE_CATEGORY);
        String catMsg = "Choose your *Vehicle Category*:\n" +
            "1️⃣ MINI\n" +
            "2️⃣ HATCHBACK\n" +
            "3️⃣ SEDAN\n" +
            "4️⃣ SUV\n" +
            "5️⃣ LUXURY\n\n" +
            "Reply with 1-5 or type the category.";
        whatsAppService.sendText(session.getWhatsappPhone(), catMsg);
    }

    private void handleDriverRegVehicleCategory(ChatSession session, String text, String replyId) {
        String input = replyId.isEmpty() ? text.trim() : replyId.trim();
        String cat;
        switch (input) {
            case "1", "mini" -> cat = "MINI";
            case "2", "hatchback" -> cat = "HATCHBACK";
            case "3", "sedan" -> cat = "SEDAN";
            case "4", "suv" -> cat = "SUV";
            case "5", "luxury" -> cat = "LUXURY";
            default -> {
                whatsAppService.sendText(session.getWhatsappPhone(), "❌ Invalid selection. Please reply with 1, 2, 3, 4, or 5.");
                return;
            }
        }
        session.setTempDriverVehicleCategory(cat);
        session.setState(ConversationState.DRIVER_REG_VEHICLE_MODEL);
        whatsAppService.sendText(session.getWhatsappPhone(), "Enter your *Vehicle Model* (e.g. Toyota Etios):");
    }

    private void handleDriverRegVehicleModel(ChatSession session, String text) {
        if (text.isEmpty()) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please enter a valid vehicle model.");
            return;
        }
        session.setTempDriverVehicleModel(text);
        session.setState(ConversationState.DRIVER_REG_VEHICLE_NUMBER);
        whatsAppService.sendText(session.getWhatsappPhone(), "Enter your *Vehicle License Plate Number* (e.g. KA-01-MJ-1234):");
    }

    private void handleDriverRegVehicleNumber(ChatSession session, String text) {
        if (text.isEmpty()) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please enter a valid vehicle number.");
            return;
        }
        session.setTempDriverVehicleNumber(text.toUpperCase());
        session.setState(ConversationState.DRIVER_REG_VEHICLE_COLOR);
        whatsAppService.sendText(session.getWhatsappPhone(), "Enter your *Vehicle Color* (e.g. White):");
    }

    private void handleDriverRegVehicleColor(ChatSession session, String text) {
        if (text.isEmpty()) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please enter a valid vehicle color.");
            return;
        }
        session.setTempDriverVehicleColor(text);
        session.setState(ConversationState.DRIVER_REG_AADHAAR);
        whatsAppService.sendText(session.getWhatsappPhone(), "Enter your 12-digit *Aadhaar Number*:");
    }

    private void handleDriverRegAadhaar(ChatSession session, String text) {
        String clean = text.replaceAll("[^0-9]", "");
        if (clean.length() != 12) {
            whatsAppService.sendText(session.getWhatsappPhone(), "❌ Invalid Aadhaar number. Must be exactly 12 digits. Please try again:");
            return;
        }
        session.setTempDriverAadhaar(clean);
        session.setState(ConversationState.DRIVER_REG_LICENSE);
        whatsAppService.sendText(session.getWhatsappPhone(), "Enter your *Driving License Number*:");
    }

    private void handleDriverRegLicense(ChatSession session, String text) {
        if (text.isEmpty()) {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please enter a valid license number.");
            return;
        }
        session.setTempDriverLicense(text.toUpperCase());
        session.setState(ConversationState.DRIVER_REG_DOC_SELFIE);
        whatsAppService.sendText(session.getWhatsappPhone(), "Now, please upload your documents as photos.\n\n📸 Please upload a *Selfie / Profile Picture* (send as an image attachment):");
    }

    private void handleDriverDocSelfie(ChatSession session, String messageType, String text) {
        if (!"image".equals(messageType) && !text.startsWith("http")) {
            whatsAppService.sendText(session.getWhatsappPhone(), "❌ Please send an image attachment (photo).");
            return;
        }
        session.setTempDriverSelfie(text);
        session.setState(ConversationState.DRIVER_REG_DOC_AADHAAR);
        whatsAppService.sendText(session.getWhatsappPhone(), "✅ Selfie received!\n\n📸 Please upload your *Aadhaar Card Front* photo:");
    }

    private void handleDriverDocAadhaar(ChatSession session, String messageType, String text) {
        if (!"image".equals(messageType) && !text.startsWith("http")) {
            whatsAppService.sendText(session.getWhatsappPhone(), "❌ Please send an image attachment (photo).");
            return;
        }
        session.setTempDriverAadhaarImage(text);
        session.setState(ConversationState.DRIVER_REG_DOC_LICENSE);
        whatsAppService.sendText(session.getWhatsappPhone(), "✅ Aadhaar front received!\n\n📸 Please upload your *Driving License Front* photo:");
    }

    private void handleDriverDocLicense(ChatSession session, String messageType, String text) {
        if (!"image".equals(messageType) && !text.startsWith("http")) {
            whatsAppService.sendText(session.getWhatsappPhone(), "❌ Please send an image attachment (photo).");
            return;
        }
        session.setTempDriverLicenseImage(text);
        session.setState(ConversationState.DRIVER_REG_DOC_RC);
        whatsAppService.sendText(session.getWhatsappPhone(), "✅ License front received!\n\n📸 Please upload your *Vehicle RC (Registration Certificate)* photo:");
    }

    private void handleDriverDocRc(ChatSession session, String messageType, String text) {
        if (!"image".equals(messageType) && !text.startsWith("http")) {
            whatsAppService.sendText(session.getWhatsappPhone(), "❌ Please send an image attachment (photo).");
            return;
        }
        session.setTempDriverRcImage(text);
        session.setState(ConversationState.DRIVER_REG_DOC_VEHICLE);
        whatsAppService.sendText(session.getWhatsappPhone(), "✅ RC received!\n\n📸 Please upload your *Vehicle Front* photo:");
    }

    private void handleDriverDocVehicle(ChatSession session, String messageType, String text) {
        if (!"image".equals(messageType) && !text.startsWith("http")) {
            whatsAppService.sendText(session.getWhatsappPhone(), "❌ Please send an image attachment (photo).");
            return;
        }
        session.setTempDriverVehicleImage(text);

        // Save Driver in database
        String phoneSuffix = normalizePhone(session.getWhatsappPhone());
        Driver driver = Driver.builder()
                .name(session.getTempDriverName())
                .email(session.getTempDriverEmail())
                .phone(phoneSuffix)
                .whatsappPhone(session.getWhatsappPhone())
                .cityId(session.getTempDriverCity())
                .vehicleCategory(VehicleCategory.valueOf(session.getTempDriverVehicleCategory()))
                .vehicleModel(session.getTempDriverVehicleModel())
                .vehicleNumber(session.getTempDriverVehicleNumber())
                .vehicleColor(session.getTempDriverVehicleColor())
                .aadhaarNumber(session.getTempDriverAadhaar())
                .licenseNumber(session.getTempDriverLicense())
                .profileImage(session.getTempDriverSelfie())
                .aadhaarImage(session.getTempDriverAadhaarImage())
                .licenseImage(session.getTempDriverLicenseImage())
                .rcImage(session.getTempDriverRcImage())
                .vehicleImage(session.getTempDriverVehicleImage())
                .verificationStatus(VerificationStatus.PENDING)
                .status(DriverStatus.OFFLINE)
                .availableForRide(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        driverRepository.save(driver);

        session.setState(ConversationState.MAIN_MENU);
        whatsAppService.sendText(session.getWhatsappPhone(),
            "🎉 *Registration Completed!*\n\n" +
            "Your application has been submitted successfully for verification.\n" +
            "We will review your documents and notify you via WhatsApp once approved. Thank you!");
    }

    // =================== FARE ESTIMATION FLOW ===================
    private void startFareEstimation(ChatSession session) {
        session.setState(ConversationState.FARE_EST_PICKUP);
        whatsAppService.sendText(session.getWhatsappPhone(),
            "💰 *Get Fare Estimate*\n\n" +
            "Please share/type your *Pickup Location*:");
    }

    private void handleFareEstPickup(ChatSession session, String messageType, String text, Double lat, Double lng, String locationName) {
        if ("location".equals(messageType) && lat != null && lng != null) {
            String address = locationName != null && !locationName.isEmpty() ? locationName : String.format("%.4f, %.4f", lat, lng);
            GoogleMapsService.GeocodingResult geo = mapsService.geocode(address);
            if (geo != null) {
                session.setTempPickupLat(geo.lat());
                session.setTempPickupLng(geo.lng());
                session.setTempPickupAddress(geo.formattedAddress());
            } else {
                session.setTempPickupLat(lat);
                session.setTempPickupLng(lng);
                session.setTempPickupAddress(address);
            }
        } else if (!text.isEmpty()) {
            GoogleMapsService.GeocodingResult geo = mapsService.geocode(text);
            if (geo != null) {
                session.setTempPickupLat(geo.lat());
                session.setTempPickupLng(geo.lng());
                session.setTempPickupAddress(geo.formattedAddress());
            } else {
                whatsAppService.sendText(session.getWhatsappPhone(), "❌ Couldn't find location. Try again:");
                return;
            }
        } else {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please send pickup location.");
            return;
        }

        session.setState(ConversationState.FARE_EST_DROP);
        whatsAppService.sendText(session.getWhatsappPhone(),
            "📍 *Pickup Address:* " + session.getTempPickupAddress() + "\n\n" +
            "🎯 Now share/type your *Drop Location*:");
    }

    private void handleFareEstDrop(ChatSession session, String messageType, String text, Double lat, Double lng, String locationName) {
        if ("location".equals(messageType) && lat != null && lng != null) {
            String address = locationName != null && !locationName.isEmpty() ? locationName : String.format("%.4f, %.4f", lat, lng);
            GoogleMapsService.GeocodingResult geo = mapsService.geocode(address);
            if (geo != null) {
                session.setTempDropLat(geo.lat());
                session.setTempDropLng(geo.lng());
                session.setTempDropAddress(geo.formattedAddress());
            } else {
                session.setTempDropLat(lat);
                session.setTempDropLng(lng);
                session.setTempDropAddress(address);
            }
        } else if (!text.isEmpty()) {
            GoogleMapsService.GeocodingResult geo = mapsService.geocode(text);
            if (geo != null) {
                session.setTempDropLat(geo.lat());
                session.setTempDropLng(geo.lng());
                session.setTempDropAddress(geo.formattedAddress());
            } else {
                whatsAppService.sendText(session.getWhatsappPhone(), "❌ Couldn't find location. Try again:");
                return;
            }
        } else {
            whatsAppService.sendText(session.getWhatsappPhone(), "Please send drop location.");
            return;
        }

        // Calculate distance & fare using FareService (SEDAN as initial estimate)
        FareRequest initReq = FareRequest.builder()
            .pickupLat(session.getTempPickupLat())
            .pickupLng(session.getTempPickupLng())
            .dropLat(session.getTempDropLat())
            .dropLng(session.getTempDropLng())
            .city("Bengaluru")
            .vehicleType("SEDAN")
            .build();
        try {
            FareResponse initResp = fareService.estimateFare(initReq);
            session.setTempDistance(initResp.getDistanceKm());
            session.setTempDuration((double) initResp.getDurationMinutes());
            session.setTempFare((double) initResp.getEstimatedFare());
        } catch (Exception ex) {
            log.warn("FareService init estimation failed: {}", ex.getMessage());
            GoogleMapsService.DistanceResult fallback = mapsService.getDistance(
                session.getTempPickupLat(), session.getTempPickupLng(),
                session.getTempDropLat(), session.getTempDropLng());
            session.setTempDistance(fallback.distanceKm());
            session.setTempDuration((double) fallback.durationMinutes());
            session.setTempFare(50.0 + fallback.distanceKm() * 12.0);
        }

        session.setState(ConversationState.FARE_EST_VEHICLE);
        whatsAppService.sendText(session.getWhatsappPhone(),
            "🚗 *Select Vehicle Type for Estimate:*\n" +
            "1️⃣ MINI\n" +
            "2️⃣ HATCHBACK\n" +
            "3️⃣ SEDAN\n" +
            "4️⃣ SUV\n" +
            "5️⃣ LUXURY\n\n" +
            "Reply with 1-5.");
    }

    private void handleFareEstVehicle(ChatSession session, String text, String replyId) {
        String input = replyId.isEmpty() ? text.trim() : replyId.trim();
        VehicleCategory chosen;
        String label;
        switch (input) {
            case "1", "mini"      -> { chosen = VehicleCategory.MINI;      label = "Mini"; }
            case "2", "hatchback" -> { chosen = VehicleCategory.HATCHBACK; label = "Hatchback"; }
            case "3", "sedan"     -> { chosen = VehicleCategory.SEDAN;     label = "Sedan"; }
            case "4", "suv"       -> { chosen = VehicleCategory.SUV;       label = "SUV"; }
            case "5", "luxury"    -> { chosen = VehicleCategory.LUXURY;    label = "Luxury"; }
            default -> {
                whatsAppService.sendText(session.getWhatsappPhone(), "❌ Invalid selection. Please reply 1-5.");
                return;
            }
        }

        // Use FareService for dynamic, city-aware, traffic-aware estimation
        double fare;
        double distance = session.getTempDistance() != null ? session.getTempDistance() : 0.0;
        double duration = session.getTempDuration() != null ? session.getTempDuration() : 0.0;
        String trafficInfo = "";
        try {
            FareRequest fareReq = FareRequest.builder()
                .pickupLat(session.getTempPickupLat() != null ? session.getTempPickupLat() : 0.0)
                .pickupLng(session.getTempPickupLng() != null ? session.getTempPickupLng() : 0.0)
                .dropLat(session.getTempDropLat() != null ? session.getTempDropLat() : 0.0)
                .dropLng(session.getTempDropLng() != null ? session.getTempDropLng() : 0.0)
                .city("Bengaluru")
                .vehicleType(chosen.name())
                .build();
            FareResponse fareResp = fareService.estimateFare(fareReq);
            fare = fareResp.getEstimatedFare();
            distance = fareResp.getDistanceKm();
            duration = fareResp.getDurationMinutes();
            if (fareResp.isPeakHour()) trafficInfo = "\n⚡ _Peak hour surcharge applied_";
            else if (fareResp.getTrafficMultiplier() > 1.0) trafficInfo = "\n🚦 _Traffic surcharge applied_";
        } catch (Exception ex) {
            log.warn("FareService estimation failed in fare-est vehicle: {}", ex.getMessage());
            // Fallback: use previously stored estimates
            fare = session.getTempFare() != null ? session.getTempFare() : 0.0;
        }

        String resultMsg = String.format(
            "💰 *Fare Estimate — %s*\n\n" +
            "📍 Pickup: %s\n" +
            "🎯 Drop: %s\n\n" +
            "📏 Distance: %.1f km\n" +
            "⏱ Duration: %.0f mins%s\n\n" +
            "💵 *Estimated Fare: ₹%.0f*\n\n" +
            "_Fare includes live traffic, peak hours & city pricing._\n" +
            "Type *menu* to book or explore more options.",
            label,
            session.getTempPickupAddress(), session.getTempDropAddress(),
            distance, duration, trafficInfo, fare
        );

        session.setState(ConversationState.MAIN_MENU);
        whatsAppService.sendText(session.getWhatsappPhone(), resultMsg);
    }

    // =================== SOS ALERT & EMERGENCY ===================
    public void handleSOSAlert(ChatSession session, boolean isDriver, String phone) {
        String customerId = null;
        String driverId = null;
        String rideId = null;
        Double lat = null;
        Double lng = null;

        if (isDriver) {
            Optional<Driver> driverOpt = driverRepository.findByWhatsappPhone(phone)
                .or(() -> driverRepository.findByPhone(normalizePhone(phone)));
            if (driverOpt.isPresent()) {
                Driver d = driverOpt.get();
                driverId = d.getId();
                lat = d.getLatitude();
                lng = d.getLongitude();
                
                Optional<Ride> ride = rideRepository.findByDriverIdAndStatusIn(d.getId(), List.of(RideStatus.ACCEPTED, RideStatus.DRIVER_ARRIVING, RideStatus.ONGOING))
                    .stream().findFirst();
                if (ride.isPresent()) {
                    rideId = ride.get().getId();
                    customerId = ride.get().getCustomerId();
                }
            }
        } else {
            customerId = session.getCustomerId();
            rideId = session.getActiveRideId();
            lat = session.getTempPickupLat();
            lng = session.getTempPickupLng();
            
            if (rideId != null) {
                Optional<Ride> ride = rideRepository.findById(rideId);
                if (ride.isPresent()) {
                    driverId = ride.get().getDriverId();
                    if (ride.get().getStatus() == RideStatus.ONGOING && driverId != null) {
                        Optional<Driver> d = driverRepository.findById(driverId);
                        if (d.isPresent()) {
                            lat = d.get().getLatitude();
                            lng = d.get().getLongitude();
                        }
                    }
                }
            }
        }

        // Create alert
        SOSAlert alert = SOSAlert.builder()
                .customerId(customerId)
                .driverId(driverId)
                .rideId(rideId)
                .latitude(lat)
                .longitude(lng)
                .message(isDriver ? "Driver triggered SOS via WhatsApp" : "Customer triggered SOS via WhatsApp")
                .status("ACTIVE")
                .timestamp(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        
        sosAlertRepository.save(alert);

        // Notify user
        whatsAppService.sendText(phone, "⚠️ *SOS EMERGENCY TRIGGERED!*\n\nWe have logged your emergency, notified our operations team, and dispatched help immediately. Your location coordinates are being sent to authorities. Stay safe!");

        // Broadcast SOS alert via WebSocket to admin
        try {
            messagingTemplate.convertAndSend("/topic/admin/sos", alert);
        } catch (Exception e) {
            log.error("Failed to broadcast SOS alert via WebSocket", e);
        }

        // Notify other party if active ride exists
        if (rideId != null) {
            if (isDriver) {
                Optional<Ride> rOpt = rideRepository.findById(rideId);
                if (rOpt.isPresent() && rOpt.get().getCustomerWhatsappPhone() != null) {
                    whatsAppService.sendText(rOpt.get().getCustomerWhatsappPhone(), "⚠️ *Emergency Notice*\n\nYour driver has triggered an SOS alert. Emergency services have been contacted. Please remain calm and check your surroundings.");
                }
            } else if (driverId != null) {
                Optional<Driver> d = driverRepository.findById(driverId);
                if (d.isPresent()) {
                    String dPhone = d.get().getWhatsappPhone() != null ? d.get().getWhatsappPhone() : d.get().getPhone();
                    whatsAppService.sendText(dPhone, "⚠️ *Emergency Notice*\n\nYour passenger has triggered an SOS alert. Please stop safely and check on the passenger. Help has been dispatched.");
                }
            }
        }
    }

    // =================== LOCATION HELPERS ===================

    /**
     * Tries to extract lat/lng from a Google Maps share URL.
     * Handles formats like:
     *   https://maps.app.goo.gl/...
     *   https://www.google.com/maps?q=12.9716,77.5946
     *   https://www.google.com/maps/place/.../@12.9716,77.5946,...
     */
    private GoogleMapsService.GeocodingResult resolveGoogleMapsLink(String url) {
        try {
            // Try to extract @lat,lng or q=lat,lng directly from the URL string
            java.util.regex.Pattern latLngPattern = java.util.regex.Pattern.compile(
                "[@?&](-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"
            );
            java.util.regex.Matcher m = latLngPattern.matcher(url);
            if (m.find()) {
                double lat = Double.parseDouble(m.group(1));
                double lng = Double.parseDouble(m.group(2));
                if (isValidCoordinate(lat, lng)) {
                    // Optionally reverse-geocode for a human-readable address
                    String address = String.format("%.5f, %.5f", lat, lng);
                    try {
                        GoogleMapsService.GeocodingResult rev = mapsService.geocode(address);
                        if (rev != null) return rev;
                    } catch (Exception ignored) {}
                    return new GoogleMapsService.GeocodingResult(lat, lng, address);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Google Maps link: {}", url, e);
        }
        return null;
    }

    /**
     * Fallback: look up a plain-text address in seeded Destination documents.
     * Returns a GeocodingResult if a matching city or destination is found,
     * otherwise returns null so the caller can surface an error to the user.
     */
    private GoogleMapsService.GeocodingResult lookupLocalDestination(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String query = text.trim().toLowerCase();

        try {
            // Search popular destinations collection
            List<com.cabgo.model.PopularDestination> destinations =
                    popularDestinationRepository.findAll();
            for (com.cabgo.model.PopularDestination dest : destinations) {
                if (dest.getName() != null && dest.getName().toLowerCase().contains(query)) {
                    if (isValidCoordinate(dest.getLatitude(), dest.getLongitude())) {
                        return new GoogleMapsService.GeocodingResult(
                            dest.getLatitude(), dest.getLongitude(), dest.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Destination lookup failed: {}", e.getMessage());
        }

        // Karnataka city coordinate seeds
        java.util.Map<String, double[]> citySeed = new java.util.LinkedHashMap<>();
        citySeed.put("bengaluru",  new double[]{12.9716, 77.5946});
        citySeed.put("bangalore",  new double[]{12.9716, 77.5946});
        citySeed.put("mysuru",     new double[]{12.2958, 76.6394});
        citySeed.put("mysore",     new double[]{12.2958, 76.6394});
        citySeed.put("mangaluru",  new double[]{12.9141, 74.8560});
        citySeed.put("mangalore",  new double[]{12.9141, 74.8560});
        citySeed.put("hubballi",   new double[]{15.3647, 75.1240});
        citySeed.put("hubli",      new double[]{15.3647, 75.1240});
        citySeed.put("dharwad",    new double[]{15.4589, 75.0078});
        citySeed.put("belagavi",   new double[]{15.8497, 74.4977});
        citySeed.put("belgaum",    new double[]{15.8497, 74.4977});
        citySeed.put("kalaburagi", new double[]{17.3297, 76.8343});
        citySeed.put("gulbarga",   new double[]{17.3297, 76.8343});
        citySeed.put("shivamogga", new double[]{13.9299, 75.5681});
        citySeed.put("shimoga",    new double[]{13.9299, 75.5681});
        citySeed.put("tumakuru",   new double[]{13.3379, 77.1173});
        citySeed.put("tumkur",     new double[]{13.3379, 77.1173});
        citySeed.put("davanagere", new double[]{14.4644, 75.9218});
        citySeed.put("udupi",      new double[]{13.3409, 74.7421});
        citySeed.put("hassan",     new double[]{13.0035, 76.1004});
        citySeed.put("bellary",    new double[]{15.1394, 76.9214});
        citySeed.put("ballari",    new double[]{15.1394, 76.9214});
        citySeed.put("bidar",      new double[]{17.9104, 77.5199});
        citySeed.put("raichur",    new double[]{16.2120, 77.3439});
        citySeed.put("koppal",     new double[]{15.3508, 76.1549});
        citySeed.put("gadag",      new double[]{15.4166, 75.6278});
        citySeed.put("bagalkot",   new double[]{16.1691, 75.6616});
        citySeed.put("vijayapura", new double[]{16.8302, 75.7100});
        citySeed.put("bijapur",    new double[]{16.8302, 75.7100});
        citySeed.put("yadgir",     new double[]{16.7713, 77.1375});
        citySeed.put("chikkamagaluru", new double[]{13.3153, 75.7754});
        citySeed.put("chikmagalur",    new double[]{13.3153, 75.7754});
        citySeed.put("kodagu",     new double[]{12.3375, 75.8069});
        citySeed.put("coorg",      new double[]{12.3375, 75.8069});
        citySeed.put("mandya",     new double[]{12.5218, 76.8951});
        citySeed.put("chamrajnagar", new double[]{11.9234, 76.9432});
        citySeed.put("kolar",      new double[]{13.1360, 78.1294});
        citySeed.put("chikkaballapur", new double[]{13.4355, 77.7315});

        for (java.util.Map.Entry<String, double[]> entry : citySeed.entrySet()) {
            if (query.contains(entry.getKey())) {
                double[] coords = entry.getValue();
                return new GoogleMapsService.GeocodingResult(coords[0], coords[1],
                    Character.toUpperCase(entry.getKey().charAt(0)) + entry.getKey().substring(1));
            }
        }

        return null; // no match — caller will ask user to retype
    }
}
