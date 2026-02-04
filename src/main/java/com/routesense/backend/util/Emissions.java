package com.routesense.backend.util;

import com.routesense.backend.dto.RouteRequest;

public final class Emissions {

    private Emissions() {}

    private static final double CO2_PER_LITER_DIESEL = 2.68;
    public static final double DIESEL_PRICE_LKR = 277.0;

    public static double calcCo2Kg(double distanceKm, double payloadKg, double trafficFactor, RouteRequest.VehicleType type) {
        double fuelLiters = calcFuelLiters(distanceKm, payloadKg, trafficFactor, type);
        return fuelLiters * CO2_PER_LITER_DIESEL;
    }

    private static VehicleProfile profile(RouteRequest.VehicleType type) {
        if (type == null) type = RouteRequest.VehicleType.MEDIUM;
        return switch (type) {
            case LIGHT -> new VehicleProfile(0.14, 2000.0);
            case MEDIUM -> new VehicleProfile(0.28, 10000.0);
            case HEAVY -> new VehicleProfile(0.38, 20000.0);
        };
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private record VehicleProfile(double baseLitersPerKm, double maxPayloadKg) {}

    public static double calcFuelLiters(
            double distanceKm,
            double payloadKg,
            double trafficFactor,
            RouteRequest.VehicleType type
    ) {
        VehicleProfile p = profile(type);

        double safeTraffic = trafficFactor > 0 ? trafficFactor : 1.0;
        double safePayload = Math.max(0.0, payloadKg);

        double weightFactor = 1.0 + 0.4 * (safePayload / p.maxPayloadKg);
        weightFactor = clamp(weightFactor, 1.0, 1.4);

        return distanceKm * p.baseLitersPerKm * weightFactor * safeTraffic;
    }

}
