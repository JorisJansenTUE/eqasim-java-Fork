package org.thesis;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.MatsimFacilitiesReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class GenerateFeasibilityTestNetworks {

    /*
     * Modes removed when a road loses general car access.
     *
     * The connectivity test itself is based on "car", because that is normally
     * the actual network-routing mode. The additional variants are removed if
     * they happen to be present on the links.
     */
    private static final Set<String> CAR_MODES_TO_REMOVE = Set.of(
            "car",
            "car_passenger",
            "car_loop",
            "car_passenger_loop",
            "truck"
    );

    private static final Set<String> BIKE_MODES_TO_REMOVE = Set.of(
            "bike",
            "bike_loop"
    );

    /*
     * IMPORTANT:
     * These are only candidate PT NETWORK modes.
     *
     * The script prints all modes found in your network. Check those first.
     * If your buses currently run on "car" links, this test will probably not
     * find a PT-only candidate yet, which is useful information in itself.
     */
    private static final Set<String> PT_NETWORK_MODES = Set.of(
            "pt",
            "bus"
    );

    private static final String CAR_CONNECTIVITY_MODE = "car";
    private static final String BIKE_CONNECTIVITY_MODE = "bike";

    /*
     * For test 1 we try to affect several distinct facility/activity references.
     */
    private static final int TARGET_ACCESS_REFERENCES = 3;
    private static final int MAX_LINKS_TEST_1 = 5;


    public static void main(String[] args) throws Exception {

        if (args.length != 4) {
            System.err.println("""
                    Usage:
                      GenerateFeasibilityTestNetworks \
                        <network.xml.gz> \
                        <population.xml.gz or -> \
                        <facilities.xml.gz or -> \
                        <output-directory>

                    Example:
                      GenerateFeasibilityTestNetworks \
                        locarno_network.xml.gz \
                        locarno_population.xml.gz \
                        - \
                        feasibility_tests
                    """);
            System.exit(1);
        }

        String networkFile = args[0];
        String populationFile = "-".equals(args[1]) ? null : args[1];
        String facilitiesFile = "-".equals(args[2]) ? null : args[2];

        Path outputDirectory = Path.of(args[3]);
        Files.createDirectories(outputDirectory);

        // --------------------------------------------------------------
        // Load baseline + optional demand/facility information
        // --------------------------------------------------------------

        Scenario baselineScenario = loadScenario(
                networkFile,
                populationFile,
                facilitiesFile
        );

        Network baseline = baselineScenario.getNetwork();

        System.out.println();
        System.out.println("======================================================");
        System.out.println("BASELINE NETWORK");
        System.out.println("======================================================");
        System.out.println("Nodes : " + baseline.getNodes().size());
        System.out.println("Links : " + baseline.getLinks().size());
        System.out.println("Modes : " + collectAllModes(baseline));

        /*
         * These are the nodes that belong to the original modal networks.
         *
         * We retain this same required node set after modification. That is
         * important: otherwise deleting the last modal connection to a node
         * could make that node simply disappear from the modal graph and make
         * the network falsely appear connected.
         */
        Set<Id<Node>> baselineCarNodes =
                collectModalNodes(baseline, CAR_CONNECTIVITY_MODE);

        Set<Id<Node>> baselineBikeNodes =
                collectModalNodes(baseline, BIKE_CONNECTIVITY_MODE);

        boolean baselineCarConnected =
                isStronglyConnected(
                        baseline,
                        CAR_CONNECTIVITY_MODE,
                        baselineCarNodes
                );

        boolean baselineBikeConnected =
                isStronglyConnected(
                        baseline,
                        BIKE_CONNECTIVITY_MODE,
                        baselineBikeNodes
                );

        System.out.println("Car strongly connected  : " + baselineCarConnected);
        System.out.println("Bike strongly connected : " + baselineBikeConnected);

        if (!baselineCarConnected || !baselineBikeConnected) {
            throw new IllegalStateException("""
                    Baseline modal network is not strongly connected.

                    These experiments assume that both the baseline car and
                    bicycle subnetworks are already connected. Check/clean the
                    baseline first, otherwise 'breaking connectivity' is not
                    well-defined.
                    """);
        }

        /*
         * Link -> facility/activity references.
         *
         * This uses real facilities when a facilities file is provided.
         * It also collects population activity link references, which is useful
         * if your eqasim population contains link IDs directly.
         */
        Map<Id<Link>, Set<String>> accessReferences =
                collectAccessReferences(baselineScenario);

        System.out.println(
                "Links referenced by facilities/activities: "
                        + accessReferences.size()
        );

        List<String> report = new ArrayList<>();

        report.add("Baseline:");
        report.add("  car connected  = " + baselineCarConnected);
        report.add("  bike connected = " + baselineBikeConnected);
        report.add("  modes = " + collectAllModes(baseline));
        report.add("");

        // ==============================================================
        // TEST 1
        // Remove car access from facility-linked roads while preserving
        // global car connectivity.
        // ==============================================================

        {
            Network network = readNetwork(networkFile);

            List<Link> candidates = new ArrayList<>();

            for (Link link : network.getLinks().values()) {
                if (!link.getAllowedModes().contains(CAR_CONNECTIVITY_MODE)) {
                    continue;
                }

                if (!accessReferences.containsKey(link.getId())) {
                    continue;
                }

                Set<String> remaining =
                        subtract(link.getAllowedModes(), CAR_MODES_TO_REMOVE);

                // Do not create a link with zero allowed modes.
                if (remaining.isEmpty()) {
                    continue;
                }

                candidates.add(link);
            }

            // Prefer links associated with several facilities/activities.
            candidates.sort(
                    Comparator.<Link>comparingInt(
                            l -> accessReferences
                                    .getOrDefault(l.getId(), Set.of())
                                    .size()
                    ).reversed()
            );

            List<Modification> selected = new ArrayList<>();
            Set<String> affectedReferences = new LinkedHashSet<>();

            for (Link link : candidates) {

                if (selected.size() >= MAX_LINKS_TEST_1) {
                    break;
                }

                Set<String> oldModes =
                        new LinkedHashSet<>(link.getAllowedModes());

                Set<String> newModes =
                        subtract(oldModes, CAR_MODES_TO_REMOVE);

                link.setAllowedModes(newModes);

                boolean stillConnected =
                        isStronglyConnected(
                                network,
                                CAR_CONNECTIVITY_MODE,
                                baselineCarNodes
                        );

                if (stillConnected) {
                    selected.add(
                            new Modification(
                                    link.getId(),
                                    oldModes,
                                    newModes
                            )
                    );

                    affectedReferences.addAll(
                            accessReferences.getOrDefault(
                                    link.getId(),
                                    Set.of()
                            )
                    );

                    if (affectedReferences.size()
                            >= TARGET_ACCESS_REFERENCES) {
                        break;
                    }

                } else {
                    // Candidate was structurally critical -> undo.
                    link.setAllowedModes(oldModes);
                }
            }

            Path output = outputDirectory.resolve(
                    "test1_facility_links_without_car.xml.gz"
            );

            new NetworkWriter(network).write(output.toString());

            report.add("TEST 1 - Facility/activity link loses car:");
            report.add("  output = " + output);
            report.add("  changed links = " + selected.size());
            report.add("  affected references = " + affectedReferences.size());
            report.add("  car connected = "
                    + isStronglyConnected(
                    network,
                    CAR_CONNECTIVITY_MODE,
                    baselineCarNodes
            ));

            for (Modification modification : selected) {
                report.add("    " + modification);
                report.add(
                        "      references = "
                                + accessReferences.getOrDefault(
                                modification.linkId,
                                Set.of()
                        )
                );
            }

            report.add("");
        }

        // ==============================================================
        // TEST 2
        // Convert one road to PT-only while preserving BOTH car and bike
        // connectivity.
        // ==============================================================

        {
            Network network = readNetwork(networkFile);

            Modification selected = null;

            for (Link link : network.getLinks().values()) {

                Set<String> oldModes =
                        new LinkedHashSet<>(link.getAllowedModes());

                if (!oldModes.contains(CAR_CONNECTIVITY_MODE)) {
                    continue;
                }

                if (!oldModes.contains(BIKE_CONNECTIVITY_MODE)) {
                    continue;
                }

                /*
                 * Keep only PT network modes that ALREADY existed on the link.
                 */
                Set<String> ptModes = intersection(
                        oldModes,
                        PT_NETWORK_MODES
                );

                if (ptModes.isEmpty()) {
                    continue;
                }

                link.setAllowedModes(ptModes);

                boolean carConnected =
                        isStronglyConnected(
                                network,
                                CAR_CONNECTIVITY_MODE,
                                baselineCarNodes
                        );

                boolean bikeConnected =
                        isStronglyConnected(
                                network,
                                BIKE_CONNECTIVITY_MODE,
                                baselineBikeNodes
                        );

                if (carConnected && bikeConnected) {
                    selected = new Modification(
                            link.getId(),
                            oldModes,
                            ptModes
                    );
                    break;
                }

                link.setAllowedModes(oldModes);
            }

            report.add("TEST 2 - PT-only road:");

            if (selected == null) {
                report.add(
                        "  NO suitable link found."
                );
                report.add(
                        "  This may mean road links do not currently contain "
                                + "a PT-specific network mode."
                );
            } else {

                Path output = outputDirectory.resolve(
                        "test2_pt_only.xml.gz"
                );

                new NetworkWriter(network).write(output.toString());

                report.add("  output = " + output);
                report.add("  " + selected);
                report.add("  car connected = "
                        + isStronglyConnected(
                        network,
                        CAR_CONNECTIVITY_MODE,
                        baselineCarNodes
                ));
                report.add("  bike connected = "
                        + isStronglyConnected(
                        network,
                        BIKE_CONNECTIVITY_MODE,
                        baselineBikeNodes
                ));
            }

            report.add("");
        }

        // ==============================================================
        // TEST 3
        // Find one link where removing bike breaks bike connectivity.
        // ==============================================================

        {
            Network network = readNetwork(networkFile);

            Modification breaking =
                    findSingleBreakingLink(
                            network,
                            BIKE_CONNECTIVITY_MODE,
                            BIKE_MODES_TO_REMOVE,
                            baselineBikeNodes
                    );

            report.add("TEST 3 - Break BIKE connectivity:");

            if (breaking == null) {
                report.add(
                        "  No single-link bike cut found."
                );
                report.add(
                        "  The bike graph may be at least 2-edge-connected."
                );
            } else {

                apply(network, breaking);

                Path output = outputDirectory.resolve(
                        "test3_break_bike_connectivity.xml.gz"
                );

                new NetworkWriter(network).write(output.toString());

                report.add("  output = " + output);
                report.add("  " + breaking);
                report.add("  bike connected = "
                        + isStronglyConnected(
                        network,
                        BIKE_CONNECTIVITY_MODE,
                        baselineBikeNodes
                ));
                report.add("  car connected = "
                        + isStronglyConnected(
                        network,
                        CAR_CONNECTIVITY_MODE,
                        baselineCarNodes
                ));
            }

            report.add("");
        }

        // ==============================================================
        // TEST 4
        // Find one link where removing car breaks car connectivity.
        // ==============================================================

        {
            Network network = readNetwork(networkFile);

            Modification breaking =
                    findSingleBreakingLink(
                            network,
                            CAR_CONNECTIVITY_MODE,
                            CAR_MODES_TO_REMOVE,
                            baselineCarNodes
                    );

            report.add("TEST 4 - Break CAR connectivity:");

            if (breaking == null) {
                report.add(
                        "  No single-link car cut found."
                );
                report.add(
                        "  The car graph may be at least 2-edge-connected."
                );
            } else {

                apply(network, breaking);

                Path output = outputDirectory.resolve(
                        "test4_break_car_connectivity.xml.gz"
                );

                new NetworkWriter(network).write(output.toString());

                report.add("  output = " + output);
                report.add("  " + breaking);
                report.add("  car connected = "
                        + isStronglyConnected(
                        network,
                        CAR_CONNECTIVITY_MODE,
                        baselineCarNodes
                ));
                report.add("  bike connected = "
                        + isStronglyConnected(
                        network,
                        BIKE_CONNECTIVITY_MODE,
                        baselineBikeNodes
                ));
            }

            report.add("");
        }

        // --------------------------------------------------------------
        // Write report
        // --------------------------------------------------------------

        Path reportFile = outputDirectory.resolve("feasibility_report.txt");

        Files.write(reportFile, report);

        System.out.println();
        System.out.println("======================================================");
        System.out.println("RESULTS");
        System.out.println("======================================================");

        for (String line : report) {
            System.out.println(line);
        }

        System.out.println();
        System.out.println("Report written to:");
        System.out.println(reportFile);
    }


    // ==================================================================
    // Find a single modal-critical link
    // ==================================================================

    private static Modification findSingleBreakingLink(
            Network network,
            String connectivityMode,
            Set<String> modesToRemove,
            Set<Id<Node>> requiredNodes
    ) {

        List<Link> candidates = new ArrayList<>();

        for (Link link : network.getLinks().values()) {

            if (!link.getAllowedModes().contains(connectivityMode)) {
                continue;
            }

            Set<String> remaining =
                    subtract(link.getAllowedModes(), modesToRemove);

            /*
             * For this experiment we avoid mode-less links.
             *
             * In your network most car/bike roads should retain the opposite
             * mode anyway.
             */
            if (remaining.isEmpty()) {
                continue;
            }

            candidates.add(link);
        }

        /*
         * Search low-degree locations first, because they are more likely
         * to contain a critical edge.
         */
        candidates.sort(
                Comparator.comparingInt(
                        l -> modalOutDegree(
                                l.getFromNode(),
                                connectivityMode
                        ) + modalInDegree(
                                l.getToNode(),
                                connectivityMode
                        )
                )
        );

        int checked = 0;

        for (Link link : candidates) {

            checked++;

            Set<String> oldModes =
                    new LinkedHashSet<>(link.getAllowedModes());

            Set<String> newModes =
                    subtract(oldModes, modesToRemove);

            link.setAllowedModes(newModes);

            boolean connected =
                    isStronglyConnected(
                            network,
                            connectivityMode,
                            requiredNodes
                    );

            link.setAllowedModes(oldModes);

            if (!connected) {
                System.out.println(
                        "Found " + connectivityMode
                                + " critical link after checking "
                                + checked + " candidates: "
                                + link.getId()
                );

                return new Modification(
                        link.getId(),
                        oldModes,
                        newModes
                );
            }
        }

        return null;
    }


    // ==================================================================
    // Connectivity
    // ==================================================================

    private static boolean isStronglyConnected(
            Network network,
            String mode,
            Set<Id<Node>> requiredNodes
    ) {

        if (requiredNodes.isEmpty()) {
            return true;
        }

        Id<Node> start = requiredNodes.iterator().next();

        Set<Id<Node>> forward =
                reachableNodes(network, start, mode, false);

        if (!forward.containsAll(requiredNodes)) {
            return false;
        }

        Set<Id<Node>> backward =
                reachableNodes(network, start, mode, true);

        return backward.containsAll(requiredNodes);
    }


    private static Set<Id<Node>> reachableNodes(
            Network network,
            Id<Node> start,
            String mode,
            boolean reverse
    ) {

        Set<Id<Node>> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();

        Node startNode = network.getNodes().get(start);

        if (startNode == null) {
            return visited;
        }

        visited.add(start);
        queue.add(startNode);

        while (!queue.isEmpty()) {

            Node node = queue.poll();

            Collection<? extends Link> links = reverse
                    ? node.getInLinks().values()
                    : node.getOutLinks().values();

            for (Link link : links) {

                if (!link.getAllowedModes().contains(mode)) {
                    continue;
                }

                Node next = reverse
                        ? link.getFromNode()
                        : link.getToNode();

                if (visited.add(next.getId())) {
                    queue.add(next);
                }
            }
        }

        return visited;
    }


    private static Set<Id<Node>> collectModalNodes(
            Network network,
            String mode
    ) {

        Set<Id<Node>> nodes = new HashSet<>();

        for (Link link : network.getLinks().values()) {
            if (link.getAllowedModes().contains(mode)) {
                nodes.add(link.getFromNode().getId());
                nodes.add(link.getToNode().getId());
            }
        }

        return nodes;
    }


    private static int modalOutDegree(
            Node node,
            String mode
    ) {

        int count = 0;

        for (Link link : node.getOutLinks().values()) {
            if (link.getAllowedModes().contains(mode)) {
                count++;
            }
        }

        return count;
    }


    private static int modalInDegree(
            Node node,
            String mode
    ) {

        int count = 0;

        for (Link link : node.getInLinks().values()) {
            if (link.getAllowedModes().contains(mode)) {
                count++;
            }
        }

        return count;
    }


    // ==================================================================
    // Facilities / population access references
    // ==================================================================

    private static Map<Id<Link>, Set<String>> collectAccessReferences(
            Scenario scenario
    ) {

        Map<Id<Link>, Set<String>> references = new HashMap<>();

        // Explicit MATSim facilities
        for (ActivityFacility facility
                : scenario.getActivityFacilities()
                .getFacilities()
                .values()) {

            if (facility.getLinkId() != null) {
                references
                        .computeIfAbsent(
                                facility.getLinkId(),
                                k -> new LinkedHashSet<>()
                        )
                        .add("facility:" + facility.getId());
            }
        }

        // Activity locations stored directly in population plans
        for (Person person
                : scenario.getPopulation().getPersons().values()) {

            Plan plan = person.getSelectedPlan();

            if (plan == null) {
                continue;
            }

            int activityIndex = 0;

            for (PlanElement element : plan.getPlanElements()) {

                if (!(element instanceof Activity activity)) {
                    continue;
                }

                if (activity.getLinkId() != null) {

                    String label;

                    if (activity.getFacilityId() != null) {
                        label = "facility:" + activity.getFacilityId();
                    } else {
                        label =
                                "activity:"
                                        + person.getId()
                                        + ":"
                                        + activity.getType()
                                        + ":"
                                        + activityIndex;
                    }

                    references
                            .computeIfAbsent(
                                    activity.getLinkId(),
                                    k -> new LinkedHashSet<>()
                            )
                            .add(label);
                }

                activityIndex++;
            }
        }

        return references;
    }


    // ==================================================================
    // IO
    // ==================================================================

    private static Scenario loadScenario(
            String networkFile,
            String populationFile,
            String facilitiesFile
    ) {

        Scenario scenario =
                ScenarioUtils.createScenario(
                        ConfigUtils.createConfig()
                );

        new MatsimNetworkReader(
                scenario.getNetwork()
        ).readFile(networkFile);

        if (populationFile != null) {
            new PopulationReader(
                    scenario
            ).readFile(populationFile);
        }

        if (facilitiesFile != null) {
            new MatsimFacilitiesReader(
                    scenario
            ).readFile(facilitiesFile);
        }

        return scenario;
    }


    private static Network readNetwork(String networkFile) {

        Scenario scenario =
                ScenarioUtils.createScenario(
                        ConfigUtils.createConfig()
                );

        new MatsimNetworkReader(
                scenario.getNetwork()
        ).readFile(networkFile);

        return scenario.getNetwork();
    }


    // ==================================================================
    // Mode helpers
    // ==================================================================

    private static Set<String> collectAllModes(Network network) {

        Set<String> modes = new TreeSet<>();

        for (Link link : network.getLinks().values()) {
            modes.addAll(link.getAllowedModes());
        }

        return modes;
    }


    private static Set<String> subtract(
            Set<String> source,
            Set<String> remove
    ) {

        Set<String> result =
                new LinkedHashSet<>(source);

        result.removeAll(remove);

        return result;
    }


    private static Set<String> intersection(
            Set<String> a,
            Set<String> b
    ) {

        Set<String> result =
                new LinkedHashSet<>(a);

        result.retainAll(b);

        return result;
    }


    private static void apply(
            Network network,
            Modification modification
    ) {

        Link link =
                network.getLinks().get(
                        modification.linkId
                );

        if (link == null) {
            throw new IllegalArgumentException(
                    "Link not found: "
                            + modification.linkId
            );
        }

        link.setAllowedModes(
                new LinkedHashSet<>(
                        modification.newModes
                )
        );
    }


    // ==================================================================
    // Small value object
    // ==================================================================

    private static class Modification {

        private final Id<Link> linkId;
        private final Set<String> oldModes;
        private final Set<String> newModes;

        private Modification(
                Id<Link> linkId,
                Set<String> oldModes,
                Set<String> newModes
        ) {
            this.linkId = linkId;
            this.oldModes = new LinkedHashSet<>(oldModes);
            this.newModes = new LinkedHashSet<>(newModes);
        }

        @Override
        public String toString() {
            return "link=" + linkId
                    + " modes "
                    + oldModes
                    + " -> "
                    + newModes;
        }
    }
}