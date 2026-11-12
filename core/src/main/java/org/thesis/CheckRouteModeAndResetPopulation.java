package org.thesis;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.population.PopulationUtils;

/**
 * Usage: CheckRouteModeAndResetPopulation NETWORK INPUT_POP OUTPUT_POP
 * Uses the same MATSim API call tested previously in JShell.
 */
public final class CheckRouteModeAndResetPopulation {
    private CheckRouteModeAndResetPopulation() {}

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "Expected NETWORK INPUT_POP OUTPUT_POP");
        }
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(args[0]);
        new PopulationReader(scenario).readFile(args[1]);
        System.out.println("Loaded persons: " + scenario.getPopulation().getPersons().size());
        PopulationUtils.checkRouteModeAndReset(
            scenario.getPopulation(), scenario.getNetwork());
        new PopulationWriter(scenario.getPopulation(), scenario.getNetwork()).write(args[2]);
        System.out.println("Route-checked population written: " + args[2]);
    }
}
