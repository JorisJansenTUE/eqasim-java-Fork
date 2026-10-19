package org.eqasim.switzerland.ch_cmdp.routing;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.vehicles.Vehicle;

public class LocarnoRoadTravelDisutility implements TravelDisutility {

    private final TravelDisutility delegate;
    private final double destinationPenaltyPerMeter;
    private final double cyclestreetPenaltyPerMeter;

    public LocarnoRoadTravelDisutility(
            TravelDisutility delegate,
            double destinationPenaltyPerMeter,
            double cyclestreetPenaltyPerMeter) {

        this.delegate = delegate;
        this.destinationPenaltyPerMeter = destinationPenaltyPerMeter;
        this.cyclestreetPenaltyPerMeter = cyclestreetPenaltyPerMeter;
    }

    @Override
    public double getLinkTravelDisutility(
            Link link,
            double time,
            Person person,
            Vehicle vehicle) {

        double disutility = delegate.getLinkTravelDisutility(
            link, time, person, vehicle
        );

        if (Boolean.TRUE.equals(
                link.getAttributes().getAttribute("destination_road"))) {
            disutility += destinationPenaltyPerMeter * link.getLength();
        }

        if (Boolean.TRUE.equals(
                link.getAttributes().getAttribute("cyclestreet"))) {
            disutility += cyclestreetPenaltyPerMeter * link.getLength();
        }

        return disutility;
    }

    @Override
    public double getLinkMinimumTravelDisutility(Link link) {
        return delegate.getLinkMinimumTravelDisutility(link);
    }
}