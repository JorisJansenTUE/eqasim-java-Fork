package org.eqasim.switzerland.ch_cmdp.routing;

import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

public class LocarnoRoadTravelDisutilityFactory
        implements TravelDisutilityFactory {

    private final TravelDisutilityFactory delegate;
    private final double destinationPenaltyPerMeter;
    private final double cyclestreetPenaltyPerMeter;

    public LocarnoRoadTravelDisutilityFactory(
            TravelDisutilityFactory delegate,
            double destinationPenaltyPerMeter,
            double cyclestreetPenaltyPerMeter) {

        this.delegate = delegate;
        this.destinationPenaltyPerMeter = destinationPenaltyPerMeter;
        this.cyclestreetPenaltyPerMeter = cyclestreetPenaltyPerMeter;
    }

    @Override
    public TravelDisutility createTravelDisutility(TravelTime travelTime) {
        return new LocarnoRoadTravelDisutility(
            delegate.createTravelDisutility(travelTime),
            destinationPenaltyPerMeter,
            cyclestreetPenaltyPerMeter
        );
    }
}