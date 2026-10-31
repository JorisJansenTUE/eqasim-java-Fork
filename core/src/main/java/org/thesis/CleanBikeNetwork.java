package org.thesis;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.io.NetworkWriter;

import java.util.Set;

public class CleanBikeNetwork {

    public static void main(String[] args) {

        if (args.length != 2) {
            System.err.println(
                "Usage: java CleanBikeNetwork " +
                "<input_network.xml.gz> <output_network.xml.gz>"
            );
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];

        System.out.println("Reading network:");
        System.out.println("  " + inputPath);

        Network network = NetworkUtils.readNetwork(inputPath);

        long linksBefore = network.getLinks().size();

        long bikeLinksBefore = network.getLinks().values().stream()
            .filter(link ->
                link.getAllowedModes().contains(TransportMode.bike)
            )
            .count();

        System.out.println();
        System.out.println("Before cleaning:");
        System.out.println("  Total links: " + linksBefore);
        System.out.println("  Bike links:  " + bikeLinksBefore);

        // --------------------------------------------------------
        // Clean ONLY the bicycle subnetwork
        // --------------------------------------------------------

        MultimodalNetworkCleaner cleaner =
            new MultimodalNetworkCleaner(network);

        cleaner.run(Set.of(TransportMode.bike));

        // --------------------------------------------------------
        // Statistics
        // --------------------------------------------------------

        long linksAfter = network.getLinks().size();

        long bikeLinksAfter = network.getLinks().values().stream()
            .filter(link ->
                link.getAllowedModes().contains(TransportMode.bike)
            )
            .count();

        System.out.println();
        System.out.println("After cleaning:");
        System.out.println("  Total links: " + linksAfter);
        System.out.println("  Bike links:  " + bikeLinksAfter);

        System.out.println();
        System.out.println(
            "Links modified by cleaner: "
            + cleaner.getModifiedLinkIds().size()
        );

        System.out.println(
            "Links removed entirely:    "
            + cleaner.getRemovedLinkIds().size()
        );

        // --------------------------------------------------------
        // Write result
        // --------------------------------------------------------

        System.out.println();
        System.out.println("Writing cleaned network:");
        System.out.println("  " + outputPath);

        new NetworkWriter(network).write(outputPath);

        System.out.println();
        System.out.println("========================================");
        System.out.println("Bike network cleaning completed");
        System.out.println("========================================");
    }
}