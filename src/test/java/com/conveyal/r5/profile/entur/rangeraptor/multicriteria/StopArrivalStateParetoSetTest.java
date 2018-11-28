package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.TestLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransferStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class StopArrivalStateParetoSetTest {
    private static final TransitCalculator CALCULATOR = new TransitCalculator(Collections.emptyList(), 60);
    // 08:35 in seconds
    private static final int A_TIME = ((8 * 60) + 35) * 60;
    private static final int ANY = 3;
    private static final int ROUND_1 = 1;
    private static final int ROUND_2 = 2;
    private static final int ROUND_3 = 3;
    private static final TripScheduleInfo ANY_TRIP = null;

    // In this test each stop is used to identify the pareto vector - it is just one
    // ParetoSet "subject" with multiple "stops" in it. The stop have no effect on
    // the Pareto functionality.
    private static final int STOP_1 = 1;
    private static final int STOP_2 = 2;
    private static final int STOP_3 = 3;
    private static final int STOP_4 = 4;
    private static final int STOP_5 = 5;
    private static final int STOP_6 = 6;

    private AbstractStopArrival<TripScheduleInfo> A_STATE = newMcAccessStopState(999, 10);

    private Stop<TripScheduleInfo> subject = new Stop<>(0);

    @Test
    public void addOneElementToSet() {
        subject.add(newMcAccessStopState(STOP_1, 10));
        assertStopsInSet(STOP_1);
    }

    @Test
    public void testTimeDominance() {
        subject.add(newMcAccessStopState(STOP_1, 10));
        subject.add(newMcAccessStopState(STOP_2, 9));
        subject.add(newMcAccessStopState(STOP_3, 9));
        subject.add(newMcAccessStopState(STOP_4, 11));
        assertStopsInSet(STOP_2);
    }

    @Test
    public void testRoundDominance() {
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_1, 10));
        subject.add(newMcTransferStopState(A_STATE, ROUND_2, STOP_2, 10));
        assertStopsInSet(STOP_1);
    }

    @Test
    public void testRoundAndTimeDominance() {
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_1, 10));
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_2, 8));

        assertStopsInSet(STOP_2);

        subject.add(newMcTransferStopState(A_STATE, ROUND_2, STOP_3, 8));

        assertStopsInSet(STOP_2);

        subject.add(newMcTransferStopState(A_STATE, ROUND_2, STOP_4, 7));

        assertStopsInSet(STOP_2, STOP_4);

        subject.add(newMcTransferStopState(A_STATE, ROUND_3, STOP_5, 6));

        assertStopsInSet(STOP_2, STOP_4, STOP_5);

        subject.add(newMcTransferStopState(A_STATE, ROUND_3, STOP_6, 6));

        assertStopsInSet(STOP_2, STOP_4, STOP_5);
    }

    /**
     * During the same round transfers should not dominate transits, but this is handled
     * by the worker state (2-phase transfer calculation), not by the pareto-set. Using
     * the pareto-set for this would cause unnecessary exploration in the following round.
     */
    @Test
    public void testTransitAndTransferDoesNotAffectDominance() {
        subject.add(newMcAccessStopState(STOP_1, 20));
        subject.add(newMcTransitStopState(A_STATE, ROUND_1, STOP_2, 10));
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_4, 8));
        assertStopsInSet(STOP_1, STOP_4);
    }

    private void assertStopsInSet(int ... expStopIndexes) {
        int[] result = subject.stream().mapToInt(AbstractStopArrival::stop).sorted().toArray();
        Assert.assertEquals("Stop indexes", Arrays.toString(expStopIndexes), Arrays.toString(result));
    }

    private static AccessStopArrival<TripScheduleInfo> newMcAccessStopState(int stop, int accessDurationInSeconds) {
        return new AccessStopArrival<>(stop, A_TIME, accessDurationInSeconds, ANY, CALCULATOR);
    }

    private static TransitStopArrival<TripScheduleInfo> newMcTransitStopState(AbstractStopArrival<TripScheduleInfo> prev, int round, int stop, int arrivalTime) {
        return new TransitStopArrival<>(prev, round, stop, arrivalTime, ANY, ANY_TRIP);
    }

    private static TransferStopArrival<TripScheduleInfo> newMcTransferStopState(AbstractStopArrival<TripScheduleInfo> prev, int round, int stop, int arrivalTime) {
        return new TransferStopArrival<>(prev, round, new TestLeg(stop, ANY), arrivalTime);
    }
}