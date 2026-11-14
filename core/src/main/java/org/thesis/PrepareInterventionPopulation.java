package org.thesis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripRouterFactoryBuilderWithDefaults;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.utils.timing.TimeTracker;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Prepare a MATSim population for a road-space intervention scenario.
 *
 * <p>In one command:
 * <ol>
 *   <li>Load the original simulation config with the intervention network and
 *       updated transit schedule.</li>
 *   <li>Reroute complete PT trips where a passenger's stored boarding/alighting
 *       link no longer matches the stop's link in the updated schedule. MATSim's
 *       TripRouter/SwissRailRaptor regenerates the PT legs, access/egress legs,
 *       and pt interaction activities together.</li>
 *   <li>Run MATSim's built-in PopulationUtils.checkRouteModeAndReset for routes
 *       incompatible with changed network link modes.</li>
 *   <li>Write one prepared population XML.</li>
 * </ol>
 *
 * Usage:
 *   org.thesis.PrepareInterventionPopulation \
 *       BASE_CONFIG MODIFIED_NETWORK MODIFIED_SCHEDULE INPUT_POP OUTPUT_POP
 *
 * All input files may be .xml or .xml.gz. Supply absolute paths, especially
 * for the modified network/schedule/population; other paths in BASE_CONFIG
 * remain relative to the original config location.
 */
public final class PrepareInterventionPopulation {
    private PrepareInterventionPopulation() {}

    public static void main(String[] args) {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                "Usage: PrepareInterventionPopulation "
                + "BASE_CONFIG MODIFIED_NETWORK MODIFIED_SCHEDULE INPUT_POP OUTPUT_POP");
        }

        Path configPath = Path.of(args[0]).toAbsolutePath();
        Path networkPath = Path.of(args[1]).toAbsolutePath();
        Path schedulePath = Path.of(args[2]).toAbsolutePath();
        Path populationPath = Path.of(args[3]).toAbsolutePath();
        Path outputPath = Path.of(args[4]).toAbsolutePath();

        if (outputPath.equals(populationPath)) {
            throw new IllegalArgumentException("Output must differ from input population");
        }
        for (Path input : List.of(configPath, networkPath, schedulePath, populationPath)) {
            if (!Files.isRegularFile(input)) {
                throw new IllegalArgumentException("Missing input file: " + input);
            }
        }

        // Reuse the MATSim configuration, PT routing algorithm, and time rules.
        // Absolute overrides do not change the base config's resource context.
        Config config = ConfigUtils.loadConfig(configPath.toString());
        config.network().setInputFile(networkPath.toString());
        config.plans().setInputFile(populationPath.toString());
        config.transit().setTransitScheduleFile(schedulePath.toString());
        if (!config.transit().isUseTransit()) {
            throw new IllegalArgumentException("The base config has transit.useTransit=false");
        }

        Scenario scenario = ScenarioUtils.loadScenario(config);
        System.out.printf("Loaded %d persons, %d network links, %d transit stops%n",
            scenario.getPopulation().getPersons().size(),
            scenario.getNetwork().getLinks().size(),
            scenario.getTransitSchedule().getFacilities().size());

        // Build a standard MATSim TripRouter for this modified scenario.
        // SwissRailRaptor is installed by the normal MATSim transit modules
        // when transit.routingAlgorithmType=SwissRailRaptor.
        TripRouter tripRouter =
            TripRouterFactoryBuilderWithDefaults
                .createDefaultTripRouterFactoryImpl(scenario).get();
        if (tripRouter.getRoutingModule(TransportMode.pt) == null) {
            throw new IllegalStateException("MATSim PT routing module is unavailable");
        }

        int reroutedTrips = 0;
        int reroutedPlans = 0;
        int inspectedPtLegs = 0;
        TransitSchedule schedule = scenario.getTransitSchedule();
        TimeInterpretation timeInterpretation = TimeInterpretation.create(config);

        for (Person person : scenario.getPopulation().getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                boolean modifiedPlan = false;
                TimeTracker clock = new TimeTracker(timeInterpretation);

                // MATSim returns trip snapshots, so replacing the elements
                // between main activities does not invalidate this iteration.
                for (Trip trip : TripStructureUtils.getTrips(plan)) {
                    clock.addActivity(trip.getOriginActivity());

                    boolean mustReroute = false;
                    for (Leg leg : trip.getLegsOnly()) {
                        if (!TransportMode.pt.equals(leg.getMode())) {
                            continue;
                        }
                        inspectedPtLegs++;
                        if (leg.getRoute() == null) {
                            mustReroute = true;
                        } else if (leg.getRoute() instanceof TransitPassengerRoute ptRoute
                                && hasMovedStop(ptRoute, schedule)) {
                            mustReroute = true;
                        }
                    }

                    if (!mustReroute) {
                        clock.addElements(trip.getTripElements());
                        continue;
                    }

                    if (clock.getTime().isUndefined()) {
                        throw new IllegalStateException(
                            "Undefined departure time for PT trip of person " + person.getId());
                    }

                    // Rebuild the WHOLE PT trip; don't replace individual PT
                    // leg endpoints or stage activities by hand.
                    List<? extends PlanElement> newTrip = tripRouter.calcRoute(
                        TransportMode.pt,
                        FacilitiesUtils.toFacility(
                            trip.getOriginActivity(), scenario.getActivityFacilities()),
                        FacilitiesUtils.toFacility(
                            trip.getDestinationActivity(), scenario.getActivityFacilities()),
                        clock.getTime().seconds(),
                        person,
                        trip.getTripAttributes());

                    // Do not silently turn an existing PT trip into walk when
                    // PT routing fails: that would change demand during prep.
                    if (newTrip == null || newTrip.stream().noneMatch(element ->
                            element instanceof Leg leg
                                && leg.getRoute() instanceof TransitPassengerRoute)) {
                        throw new IllegalStateException(
                            "PT reroute did not produce a transit passenger leg for person "
                            + person.getId() + "; inspect changed stop access and PT availability");
                    }

                    TripRouter.insertTrip(
                        plan, trip.getOriginActivity(), newTrip, trip.getDestinationActivity());
                    clock.addElements(newTrip);
                    reroutedTrips++;
                    modifiedPlan = true;
                }

                if (modifiedPlan) {
                    reroutedPlans++;
                }
            }
        }

        // Fail early if a passenger route still refers to an outdated stop link.
        int remainingMismatches = 0;
        for (Person person : scenario.getPopulation().getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                for (Leg leg : TripStructureUtils.getLegs(plan)) {
                    if (leg.getRoute() instanceof TransitPassengerRoute route) {
                        if (hasMovedStop(route, schedule)) {
                            if (remainingMismatches < 5) {
                                System.err.printf("Still mismatched: person=%s access=%s egress=%s%n",
                                    person.getId(), route.getAccessStopId(), route.getEgressStopId());
                            }
                            remainingMismatches++;
                        }
                    }
                }
            }
        }
        if (remainingMismatches > 0) {
            throw new IllegalStateException(
                remainingMismatches + " PT passenger legs still have outdated stop links");
        }

        // Keep the behavior of the user's existing Java reset helper.
        PopulationUtils.checkRouteModeAndReset(
            scenario.getPopulation(), scenario.getNetwork());

        new PopulationWriter(scenario.getPopulation(), scenario.getNetwork())
            .write(outputPath.toString());

        System.out.printf("PT legs inspected: %d; PT trips rerouted: %d; plans changed: %d%n",
            inspectedPtLegs, reroutedTrips, reroutedPlans);
        System.out.println("Population written: " + outputPath);
    }

    private static boolean hasMovedStop(
            TransitPassengerRoute route, TransitSchedule schedule) {
        TransitStopFacility access = schedule.getFacilities().get(route.getAccessStopId());
        TransitStopFacility egress = schedule.getFacilities().get(route.getEgressStopId());

        // Referencing a missing stop is an inconsistency too: try to route the
        // complete trip again, rather than retaining its obsolete transit leg.
        return access == null || egress == null
            || !Objects.equals(access.getLinkId(), route.getStartLinkId())
            || !Objects.equals(egress.getLinkId(), route.getEndLinkId());
    }
}
