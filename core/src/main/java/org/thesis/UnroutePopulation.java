package org.thesis;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;

import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.algorithms.TripsToLegsAlgorithm;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

public class UnroutePopulation {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println(
                "Usage: UnroutePopulation <input_population> <output_population>"
            );
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];

        Scenario scenario =
            ScenarioUtils.createScenario(ConfigUtils.createConfig());

        System.out.println("Reading population: " + inputPath);

        new PopulationReader(scenario).readFile(inputPath);

        Population population = scenario.getPopulation();

        System.out.println(
            "Persons: " + population.getPersons().size()
        );

        TripsToLegsAlgorithm algorithm =
            new TripsToLegsAlgorithm(
                TripStructureUtils.getRoutingModeIdentifier()
            );

        long plans = 0;

        for (Person person : population.getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                algorithm.run(plan);
                plans++;
            }
        }

        System.out.println(
            "Converted " + plans + " plans to unrouted trips."
        );

        System.out.println("Writing population: " + outputPath);

        new PopulationWriter(population).write(outputPath);

        System.out.println("Done.");
    }
}