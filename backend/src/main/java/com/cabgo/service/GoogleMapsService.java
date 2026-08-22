package com.cabgo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleMapsService {

    @Value("${google.maps.api-key:}")
    private String apiKey;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record DistanceResult(double distanceKm, int durationMinutes, String distanceText, String durationText) {}
    public record GeocodingResult(double lat, double lng, String formattedAddress) {}
    public record RouteResult(double distanceKm, int durationMinutes, String distanceText, String durationText, java.util.List<double[]> routePoints) {}
    public record RouteEstimation(double distanceKm, int normalDurationMinutes, int liveDurationMinutes, String distanceText, String normalDurationText, String liveDurationText) {}

    /**
     * Get route estimation including traffic info using Google Directions API.
     */
    public RouteEstimation getRouteEstimation(double originLat, double originLng, double destLat, double destLng) {
        String url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=" + originLat + "," + originLng +
            "&destination=" + destLat + "," + destLng +
            "&departure_time=now" +
            "&key=" + apiKey;

        try {
            if (apiKey != null && !apiKey.isEmpty() && !apiKey.contains("YOUR_GOOGLE_MAPS_API_KEY")) {
                JsonNode root = get(url);
                String status = root.path("status").asText();
                if ("OK".equals(status)) {
                    JsonNode route = root.path("routes").get(0);
                    JsonNode leg = route.path("legs").get(0);
                    double distanceKm = leg.path("distance").path("value").asDouble() / 1000.0;
                    int normalDurationMinutes = (int) Math.ceil(leg.path("duration").path("value").asDouble() / 60.0);
                    int liveDurationMinutes = normalDurationMinutes;
                    if (leg.has("duration_in_traffic")) {
                        liveDurationMinutes = (int) Math.ceil(leg.path("duration_in_traffic").path("value").asDouble() / 60.0);
                    }
                    String distanceText = leg.path("distance").path("text").asText();
                    String normalDurationText = leg.path("duration").path("text").asText();
                    String liveDurationText = leg.has("duration_in_traffic") ? leg.path("duration_in_traffic").path("text").asText() : normalDurationText;
                    
                    return new RouteEstimation(distanceKm, normalDurationMinutes, liveDurationMinutes, distanceText, normalDurationText, liveDurationText);
                }
            }
        } catch (Exception e) {
            log.error("Google Maps Directions Route Estimation error", e);
        }
        // Fallback: simple estimation with simulated traffic variation
        double dist = haversineKm(originLat, originLng, destLat, destLng);
        int normalDuration = (int) Math.ceil(dist * 2.2); // ~2.2 mins per km
        if (normalDuration < 1) normalDuration = 1;
        // Traffic simulation: standard variation
        double simulationFactor = 1.0;
        int hour = java.time.LocalTime.now().getHour();
        if ((hour >= 7 && hour < 10) || (hour >= 17 && hour < 21)) {
            simulationFactor = 1.35; // Simulated heavy traffic during peak hours
        } else {
            simulationFactor = 1.0 + (Math.random() * 0.15);
        }
        int liveDuration = (int) Math.ceil(normalDuration * simulationFactor);
        
        return new RouteEstimation(
            dist, 
            normalDuration, 
            liveDuration, 
            String.format("%.1f km", dist), 
            normalDuration + " mins", 
            liveDuration + " mins"
        );
    }


    /**
     * Get route points, distance, and duration between two lat/lng points using Google Directions API.
     */
    public RouteResult getRoute(double originLat, double originLng, double destLat, double destLng) {
        String url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=" + originLat + "," + originLng +
            "&destination=" + destLat + "," + destLng +
            "&key=" + apiKey;

        try {
            JsonNode root = get(url);
            String status = root.path("status").asText();
            if ("OK".equals(status)) {
                JsonNode route = root.path("routes").get(0);
                JsonNode leg = route.path("legs").get(0);
                double distanceKm = leg.path("distance").path("value").asDouble() / 1000.0;
                int durationMinutes = (int) Math.ceil(leg.path("duration").path("value").asDouble() / 60.0);
                String distanceText = leg.path("distance").path("text").asText();
                String durationText = leg.path("duration").path("text").asText();
                
                String encodedPolyline = route.path("overview_polyline").path("points").asText();
                java.util.List<double[]> routePoints = decodePolyline(encodedPolyline);
                
                return new RouteResult(distanceKm, durationMinutes, distanceText, durationText, routePoints);
            }
        } catch (Exception e) {
            log.error("Google Maps Directions error", e);
        }
        // Fallback: simple interpolation
        double dist = haversineKm(originLat, originLng, destLat, destLng);
        int dur = (int)(dist * 3); // Approx 3 mins per km
        java.util.List<double[]> routePoints = new java.util.ArrayList<>();
        routePoints.add(new double[]{originLat, originLng});
        // Add a few interpolated steps for simulation
        int steps = 5;
        for (int i = 1; i < steps; i++) {
            double ratio = (double) i / steps;
            double lat = originLat + (destLat - originLat) * ratio;
            double lng = originLng + (destLng - originLng) * ratio;
            routePoints.add(new double[]{lat, lng});
        }
        routePoints.add(new double[]{destLat, destLng});
        return new RouteResult(dist, dur, String.format("%.1f km", dist), dur + " mins", routePoints);
    }

    /**
     * Decode Google Maps encoded polyline.
     */
    public static java.util.List<double[]> decodePolyline(String encoded) {
        java.util.List<double[]> poly = new java.util.ArrayList<>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;
        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;
            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;
            poly.add(new double[] { lat / 1E5, lng / 1E5 });
        }
        return poly;
    }


    /**
     * Get distance and duration between two lat/lng points.
     * Falls back to Haversine immediately when API key is not configured.
     */
    public DistanceResult getDistance(double originLat, double originLng, double destLat, double destLng) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("YOUR_GOOGLE_MAPS")) {
            String url = "https://maps.googleapis.com/maps/api/distancematrix/json" +
                "?origins=" + originLat + "," + originLng +
                "&destinations=" + destLat + "," + destLng +
                "&units=metric" +
                "&key=" + apiKey;
            try {
                JsonNode root = get(url);
                JsonNode element = root.path("rows").get(0).path("elements").get(0);
                String status = element.path("status").asText();
                if ("OK".equals(status)) {
                    double distanceKm = element.path("distance").path("value").asDouble() / 1000.0;
                    int durationMinutes = (int) Math.ceil(element.path("duration").path("value").asDouble() / 60.0);
                    String distanceText = element.path("distance").path("text").asText();
                    String durationText = element.path("duration").path("text").asText();
                    return new DistanceResult(distanceKm, durationMinutes, distanceText, durationText);
                }
            } catch (Exception e) {
                log.error("Google Maps distance error", e);
            }
        }
        // Fallback: Haversine estimation
        double dist = haversineKm(originLat, originLng, destLat, destLng);
        int dur = (int)(dist * 3); // Approx 3 mins per km
        return new DistanceResult(dist, dur, String.format("%.1f km", dist), dur + " mins");
    }

    /**
     * Geocode an address string to lat/lng.
     * Returns null immediately when API key is not configured so callers
     * fall through to the seeded local-destination lookup.
     */
    public GeocodingResult geocode(String address) {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("YOUR_GOOGLE_MAPS")) {
            log.debug("GoogleMapsService.geocode(): API key not configured — skipping HTTP call");
            return null;
        }
        try {
            String encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
            String url = "https://maps.googleapis.com/maps/api/geocode/json?address=" + encoded + "&key=" + apiKey;
            JsonNode root = get(url);
            if ("OK".equals(root.path("status").asText())) {
                JsonNode result = root.path("results").get(0);
                double lat = result.path("geometry").path("location").path("lat").asDouble();
                double lng = result.path("geometry").path("location").path("lng").asDouble();
                String formatted = result.path("formatted_address").asText();
                return new GeocodingResult(lat, lng, formatted);
            }
        } catch (Exception e) {
            log.error("Geocoding error for address: {}", address, e);
        }
        return null;
    }

    public double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth's radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
            + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    private JsonNode get(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            return objectMapper.readTree(response.body().string());
        }
    }
}
