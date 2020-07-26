package com.conveyal.r5.analyst.fare.faresv2;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.faresv2.FareLegRuleInfo;
import com.conveyal.r5.transit.faresv2.FareTransferRuleInfo;
import com.conveyal.r5.transit.faresv2.FareTransferRuleInfo.FareTransferType;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static com.conveyal.r5.analyst.fare.faresv2.IndexUtils.getMatching;

/**
 * A fare calculator for feeds compliant with the GTFS Fares V2 standard (https://bit.ly/gtfs-fares)
 *
 * @author mattwigway
 */
public class FaresV2InRoutingFareCalculator extends InRoutingFareCalculator {
    private static final Logger LOG = LoggerFactory.getLogger(FaresV2InRoutingFareCalculator.class);

    private transient LoadingCache<FareTransferRuleKey, Integer> fareTransferRuleCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .build(new CacheLoader<>() {
                @Override
                public Integer load(FareTransferRuleKey fareTransferRuleKey) {
                    return searchFareTransferRule(fareTransferRuleKey);
                }
            });

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList boardTimes = new TIntArrayList();
        TIntList alightTimes = new TIntArrayList();

        McRaptorSuboptimalPathProfileRouter.McRaptorState stateForTraversal = state;
        while (stateForTraversal != null) {
            if (stateForTraversal.pattern == -1) {
                stateForTraversal = stateForTraversal.back;
                continue; // on the street, not on transit
            }
            patterns.add(stateForTraversal.pattern);
            alightStops.add(stateForTraversal.stop);
            boardStops.add(transitLayer.tripPatterns.get(stateForTraversal.pattern).stops[stateForTraversal.boardStopPosition]);
            boardTimes.add(stateForTraversal.boardTime);
            alightTimes.add(stateForTraversal.time);

            stateForTraversal = stateForTraversal.back;
        }

        patterns.reverse();
        boardStops.reverse();
        alightStops.reverse();
        boardTimes.reverse();
        alightTimes.reverse();

        int prevFareLegRuleIdx = -1;
        int cumulativeFare = 0;

        RoaringBitmap asRouteFareNetworks = null;
        int asRouteBoardStop = -1;
        for (int i = 0; i < patterns.size(); i++) {
            int pattern = patterns.get(i);
            int boardStop = boardStops.get(i);
            int alightStop = alightStops.get(i);
            int boardTime = boardTimes.get(i);
            int alightTime = alightTimes.get(i);

            // CHECK FOR AS_ROUTE FARE NETWORK
            // NB this is applied greedily, if it is cheaper to buy separate tickets that will not be found
            RoaringBitmap fareNetworks = getFareNetworksForPattern(pattern);
            asRouteFareNetworks = getAsRouteFareNetworksForPattern(pattern);
            if (asRouteFareNetworks.getCardinality() > 0) {
                asRouteBoardStop = boardStop;
                for (int j = i + 1; j < patterns.size(); j++) {
                    RoaringBitmap nextAsRouteFareNetworks = getAsRouteFareNetworksForPattern(patterns.get(j));
                    // can't modify asRouteFareNetworks in-place as it may have already been set as fareNetworks below
                    asRouteFareNetworks = RoaringBitmap.and(asRouteFareNetworks, nextAsRouteFareNetworks);

                    if (asRouteFareNetworks.getCardinality() > 0) {
                        // extend ride
                        alightStop = alightStops.get(j);
                        alightTime = alightTimes.get(j);
                        // these are the fare networks actually in use, other fare leg rules should not match
                        fareNetworks = asRouteFareNetworks;
                        i = j; // don't process this ride again
                    } else {
                        break;
                    }
                }
            } else {
                // reset as-route board stop if this leg is not a part of any as-route fare networks
                asRouteBoardStop = -1;
            }

            // FIND THE FARE LEG RULE
            int fareLegRuleIdx = getFareLegRuleForLeg(boardStop, alightStop, fareNetworks);
            FareLegRuleInfo fareLegRule = transitLayer.fareLegRules.get(fareLegRuleIdx);

            // CHECK IF THERE ARE ANY TRANSFER DISCOUNTS
            if (prevFareLegRuleIdx != -1) {
                int transferRuleIdx = getFareTransferRule(prevFareLegRuleIdx, fareLegRuleIdx);
                if (transferRuleIdx == -1) {
                    // pay full fare, no transfer found
                    cumulativeFare += fareLegRule.amount;
                } else {
                    FareTransferRuleInfo transferRule = transitLayer.fareTransferRules.get(transferRuleIdx);
                    if (FareTransferType.TOTAL_COST_PLUS_AMOUNT.equals(transferRule.fare_transfer_type)) {
                        if (transferRule.amount > 0) {
                            LOG.warn("Negatively discounted transfer");
                        }
                        int fareIncrement = fareLegRule.amount + transferRule.amount;
                        if (fareIncrement < 0)
                            LOG.warn("Fare increment is negative!");
                        cumulativeFare += fareIncrement;
                    } else if (FareTransferType.FIRST_LEG_PLUS_AMOUNT.equals(transferRule.fare_transfer_type)) {
                        cumulativeFare += transferRule.amount;
                    } else {
                        throw new UnsupportedOperationException("Only total cost plus amount and first leg plus amount transfer rules are supported.");
                    }
                }
            } else {
                // pay full fare
                cumulativeFare += fareLegRule.amount;
            }

            prevFareLegRuleIdx = fareLegRuleIdx;
        }

        FaresV2TransferAllowance allowance;
        // asRouteFareNetworks contains the as route fare networks that the last leg was a part of. If multiple rides
        // have been spliced together, these will be the as-route fare networks that can be used to splice those rides,
        // even if there are additional as_route fare networks that apply to later legs of the splice; we apply as_route
        // fare networks greedily.
        if (asRouteFareNetworks != null && asRouteFareNetworks.getCardinality() > 0) {
            if (asRouteBoardStop == -1)
                throw new IllegalStateException("as route board stop not set even though there are as route fare networks.");
            // NB it is important the second argument here be sorted. This is guaranteed by RoaringBitmap.toArray()
            allowance = new FaresV2TransferAllowance(prevFareLegRuleIdx, asRouteFareNetworks.toArray(), asRouteBoardStop, transitLayer);
        } else {
            allowance = new FaresV2TransferAllowance(prevFareLegRuleIdx, null, -1, transitLayer);
        }

        return new FareBounds(cumulativeFare, allowance);
    }

    /** Get the as_route fare networks for a pattern (used to merge with later rides) */
    private RoaringBitmap getAsRouteFareNetworksForPattern (int patIdx) {
        RoaringBitmap fareNetworks = new RoaringBitmap();
        // protective copy
        fareNetworks.or(getFareNetworksForPattern(patIdx));
        fareNetworks.and(transitLayer.fareNetworkAsRoute);
        return fareNetworks;
    }

    private RoaringBitmap getFareNetworksForPattern (int patIdx) {
        int routeIdx = transitLayer.tripPatterns.get(patIdx).routeIndex;
        return transitLayer.fareNetworksForRoute.get(routeIdx);
    }

    /**
     * Get the fare leg rule for a leg. If there is more than one, which one is returned is undefined and a warning is logged.
     * TODO handle multiple fare leg rules
     */
    private int getFareLegRuleForLeg (int boardStop, int alightStop, RoaringBitmap fareNetworks) {
        // find leg rules that match the fare network
        // getMatching returns a new RoaringBitmap so okay to modify
        RoaringBitmap fareNetworkMatch = getMatching(transitLayer.fareLegRulesForFareNetworkId, fareNetworks);
        fareNetworkMatch.and(transitLayer.fareLegRulesForFromStopId.get(boardStop));
        fareNetworkMatch.and(transitLayer.fareLegRulesForToStopId.get(alightStop));

        // boardAreaMatch now contains only rules that match _all_ criteria

        if (fareNetworkMatch.getCardinality() == 0) {
            String fromStopId = transitLayer.stopIdForIndex.get(boardStop);
            String toStopId = transitLayer.stopIdForIndex.get(alightStop);
            throw new IllegalStateException("no fare leg rule found for leg from " + fromStopId + " to " + toStopId + "!");
        } else if (fareNetworkMatch.getCardinality() == 1) {
            return fareNetworkMatch.iterator().next();
        } else {
            // figure out what matches, first finding the lowest order
            int lowestOrder = Integer.MAX_VALUE;
            TIntList rulesWithLowestOrder = new TIntArrayList();
            for (PeekableIntIterator it = fareNetworkMatch.getIntIterator(); it.hasNext();) {
                int ruleIdx = it.next();
                int order = transitLayer.fareLegRules.get(ruleIdx).order;
                if (order < lowestOrder) {
                    lowestOrder = order;
                    rulesWithLowestOrder.clear();
                    rulesWithLowestOrder.add(ruleIdx);
                } else if (order == lowestOrder) {
                    rulesWithLowestOrder.add(ruleIdx);
                }
            }

            if (rulesWithLowestOrder.size() > 1)
                LOG.warn("Found multiple matching fare_leg_rules with same order, results may be unstable or not find the lowest fare path!");

            return rulesWithLowestOrder.get(0);
        }
    }

    /**
     * get a fare transfer rule, if one exists, between fromLegRule and toLegRule
     *
     * This uses an LRU cache, because often we will be searching for the same fromLegRule and toLegRule repeatedly
     * (e.g. transfers from a Toronto bus to many other possible Toronto buses you could transfer to.)
     */
    public int getFareTransferRule (int fromLegRule, int toLegRule) {
        try {
            return fareTransferRuleCache.get(new FareTransferRuleKey(fromLegRule, toLegRule));
        } catch (ExecutionException e) {
            // should not happen. if it does, catch and re-throw.
            throw new RuntimeException(e);
        }
    }

    private int searchFareTransferRule (FareTransferRuleKey key) {
        int fromLegRule = key.fromLegGroupId;
        int toLegRule = key.toLegGroupId;
        RoaringBitmap fromLegMatch;
        if (transitLayer.fareTransferRulesForFromLegGroupId.containsKey(fromLegRule))
            // this is OR'ed with rules for fare_id_blank at build time
            fromLegMatch = transitLayer.fareTransferRulesForFromLegGroupId.get(fromLegRule);
        else if (transitLayer.fareTransferRulesForFromLegGroupId.containsKey(TransitLayer.FARE_ID_BLANK))
            // no explicit match, use implicit matches
            fromLegMatch = transitLayer.fareTransferRulesForFromLegGroupId.get(TransitLayer.FARE_ID_BLANK);
        else
            return -1;

        RoaringBitmap toLegMatch;
        if (transitLayer.fareTransferRulesForToLegGroupId.containsKey(toLegRule))
            // this is OR'ed with rules for fare_id_blank at build time
            toLegMatch = transitLayer.fareTransferRulesForToLegGroupId.get(toLegRule);
        else if (transitLayer.fareTransferRulesForToLegGroupId.containsKey(TransitLayer.FARE_ID_BLANK))
            // no explicit match, use implicit matches
            toLegMatch = transitLayer.fareTransferRulesForToLegGroupId.get(TransitLayer.FARE_ID_BLANK);
        else
            return -1;

        // use static and to create a new RoaringBitmap, don't destruct transitlayer values.
        RoaringBitmap bothMatch = RoaringBitmap.and(fromLegMatch, toLegMatch);

        if (bothMatch.getCardinality() == 0) return -1; // no discounted transfer
        else if (bothMatch.getCardinality() == 1) return bothMatch.iterator().next();
        else {
            int lowestOrder = Integer.MAX_VALUE;
            TIntList rulesWithLowestOrder = new TIntArrayList();
            for (PeekableIntIterator it = bothMatch.getIntIterator(); it.hasNext();) {
                int ruleIdx = it.next();
                int order = transitLayer.fareTransferRules.get(ruleIdx).order;
                if (order < lowestOrder) {
                    lowestOrder = order;
                    rulesWithLowestOrder.clear();
                    rulesWithLowestOrder.add(ruleIdx);
                } else if (order == lowestOrder) {
                    rulesWithLowestOrder.add(ruleIdx);
                }
            }

            if (rulesWithLowestOrder.size() > 1)
                LOG.warn("Found multiple matching fare_leg_rules with same order, results may be unstable or not find the lowest fare path!");

            return rulesWithLowestOrder.get(0);
        }
    }

    /** Used as a key into the LRU cache for fare transfer rules */
    private static class FareTransferRuleKey {
        int fromLegGroupId;
        int toLegGroupId;

        public FareTransferRuleKey (int fromLegGroupId, int toLegGroupId) {
            this.fromLegGroupId = fromLegGroupId;
            this.toLegGroupId = toLegGroupId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FareTransferRuleKey that = (FareTransferRuleKey) o;
            return fromLegGroupId == that.fromLegGroupId &&
                    toLegGroupId == that.toLegGroupId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(fromLegGroupId, toLegGroupId);
        }
    }

    @Override
    public String getType() {
        return "fares-v2";
    }
}
