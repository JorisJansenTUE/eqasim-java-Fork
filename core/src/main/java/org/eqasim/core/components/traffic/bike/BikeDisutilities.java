package org.eqasim.core.components.traffic.bike;

import org.matsim.api.core.v01.network.Link;

public class BikeDisutilities {

    public static String getLinkType(Link link) {
        Object osmHighway = link.getAttributes().getAttribute("osm:way:highway");
        if (osmHighway != null) {
            return String.valueOf(osmHighway);
        }
        return "unknown";
    }

    //Values take from the Dutch New values of Travel time and Comfort. (https://english.kimnet.nl/documents/2024/04/03/new-values-of-travel-time-reliability-and-comfort-in-the-netherlands)
    private static final double REFERENCE_VTT = 0.862;
    private static final double INDEPENDENT_PATH_VTT = 0.862;
    private static final double CYCLESTREET_VTT = 0.956;
    private static final double MIXED_30_VTT = 1.085;
    private static final double MIXED_50_VTT = 1.233;

    public static double infrastructureDisutility(
            Link link,
            double travelTime) {


        String highway = getLinkType(link);

        boolean cyclestreet = Boolean.TRUE.equals(
                link.getAttributes().getAttribute("cyclestreet")
        );

        double vtt;

        if ("cycleway".equals(highway)
                || "bike_suited".equals(highway)) {

            vtt = INDEPENDENT_PATH_VTT;

        } else if (cyclestreet) {

            vtt = CYCLESTREET_VTT;

        } else {

        vtt = link.getFreespeed() <= 30.0 / 3.6
                ? MIXED_30_VTT
                : MIXED_50_VTT;
                }

        return travelTime * (vtt / REFERENCE_VTT - 1.0);
    }

    public static double linkTypeDisutility(Link link, double travelTime) {
        // TODO: these parameters need calibration
        String linkType = getLinkType(link);
        switch (linkType){
            case "motorway","motorway_link", "trunk","trunk_link":
                return 0.6 * travelTime;
            case "primary", "primary_link":
                return 0.1 * travelTime;
            case "secondary", "secondary_link":
                return 0.05 * travelTime;
            default:
                return 0.0;
        }
    }

    public static double gradientDisutility(Link link, double travelTime) {
        double gradientPce = BikeGradientBasedLinkSpeedCalculator.percentageGradient(link);
        if (gradientPce > 10.0) {
            return 0.2 * travelTime;
        } else if (gradientPce > 5.0) {
            return 0.1 * travelTime;
        } else if (gradientPce > 2.0) {
            return 0.02 * travelTime;
        } else {
            return 0.0;
        }

    }

    public static double distanceDisutility(Link link, double travelTime) {
        return link.getLength() * 0.2;
    }


}
