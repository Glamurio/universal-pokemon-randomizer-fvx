package com.uprfvx.random.randomizers;

import com.uprfvx.random.Settings;
import com.uprfvx.romio.constants.MoveIDs;
import com.uprfvx.romio.gamedata.Move;
import com.uprfvx.romio.gamedata.MoveCategory;
import com.uprfvx.romio.gamedata.Type;
import com.uprfvx.romio.romhandlers.RomHandler;

import java.util.*;

public class MoveDataRandomizer extends Randomizer {

    // Power tier boundaries (inclusive on both ends)
    private static final int LOW_MIN = 30;
    private static final int LOW_MAX = 40;
    private static final int MID_MAX = 60;
    private static final int HIGH_MAX = 80;
    private static final int EXTREME_MAX = 120;
    private static final int NUKE_MAX = 150;

    // Per-type caps for the top tiers
    private static final int MAX_EXTREME_PER_TYPE = 3;
    private static final int MAX_NUKE_PER_TYPE = 1;

    // Weighted tier distribution (out of 100)
    // ~45% Low, ~35% Mid, ~15% High, ~4% Extreme, ~1% Nuke
    private static final int TIER_LOW_WEIGHT = 45;
    private static final int TIER_MID_WEIGHT = 35;
    private static final int TIER_HIGH_WEIGHT = 15;
    private static final int TIER_EXTREME_WEIGHT = 4;
    // Nuke gets the remaining 1%

    private enum PowerTier {
        LOW, MID, HIGH, EXTREME, NUKE
    }

    public MoveDataRandomizer(RomHandler romHandler, Settings settings, Random random) {
        super(romHandler, settings, random);
    }

    // Makes sure to not touch move ID 165 (Struggle)
    // There are other exclusions where necessary to stop things glitching.

    public void randomizeMovePowers() {
        List<Move> moves = romHandler.getMoves();

        // Collect all damaging moves eligible for randomization, grouped by type
        List<Move> eligibleMoves = new ArrayList<>();
        for (Move mv : moves) {
            if (mv != null && mv.internalId != MoveIDs.struggle && mv.power >= 10) {
                eligibleMoves.add(mv);
            }
        }

        // Assign each move an initial tier via weighted random roll
        Map<Move, PowerTier> tierAssignments = new LinkedHashMap<>();
        for (Move mv : eligibleMoves) {
            tierAssignments.put(mv, rollInitialTier());
        }

        // Enforce per-type constraints with cascading prerequisite checks
        //   - HIGH requires that this type already has at least one LOW and one MID move
        //   - EXTREME requires that this type already has at least one HIGH move
        //   - NUKE requires that this type already has at least one EXTREME move
        //   - EXTREME is capped at MAX_EXTREME_PER_TYPE per type
        //   - NUKE is capped at MAX_NUKE_PER_TYPE per type
        //
        // We process moves in a random order to avoid bias toward early move IDs getting
        // the higher tiers. We also make multiple passes: first assign LOW/MID freely,
        // then resolve HIGH (which needs LOW+MID to exist), then EXTREME (needs HIGH),
        // then NUKE (needs EXTREME).
        enforceTierConstraints(tierAssignments);

        // Assign concrete power values within each tier
        for (Map.Entry<Move, PowerTier> entry : tierAssignments.entrySet()) {
            Move mv = entry.getKey();
            PowerTier tier = entry.getValue();
            mv.power = rollPowerInTier(tier);

            // Multi-hit moves: divide by average hit count, round to nearest 5
            if (mv.hitCount != 1) {
                mv.power = (int) (Math.round(mv.power / mv.hitCount / 5) * 5);
                if (mv.power == 0) {
                    mv.power = 5;
                }
            }
        }

        changesMade = true;
    }

    /**
     * Rolls an initial tier for a move based on weighted distribution.
     */
    private PowerTier rollInitialTier() {
        int roll = random.nextInt(100);
        if (roll < TIER_LOW_WEIGHT) {
            return PowerTier.LOW;
        } else if (roll < TIER_LOW_WEIGHT + TIER_MID_WEIGHT) {
            return PowerTier.MID;
        } else if (roll < TIER_LOW_WEIGHT + TIER_MID_WEIGHT + TIER_HIGH_WEIGHT) {
            return PowerTier.HIGH;
        } else if (roll < TIER_LOW_WEIGHT + TIER_MID_WEIGHT + TIER_HIGH_WEIGHT + TIER_EXTREME_WEIGHT) {
            return PowerTier.EXTREME;
        } else {
            return PowerTier.NUKE;
        }
    }

    /**
     * Enforces the cascading prerequisite and per-type cap rules.
     * Moves that don't meet their tier's prerequisites are demoted to the highest tier
     * whose prerequisites are satisfied. Moves that exceed per-type caps are also demoted.
     *
     * Processing order: LOW and MID are always valid, so we lock those in first.
     * Then we resolve HIGH, EXTREME, and NUKE in ascending order, since each depends
     * on the tier below it existing for that type.
     */
    private void enforceTierConstraints(Map<Move, PowerTier> tierAssignments) {
        // Build per-type tier counts (only counting confirmed/locked-in assignments)
        Map<Type, EnumMap<PowerTier, Integer>> typeTierCounts = new HashMap<>();

        // Shuffle the moves to avoid move-ID ordering bias
        List<Move> shuffled = new ArrayList<>(tierAssignments.keySet());
        Collections.shuffle(shuffled, random);

        // Lock in LOW and MID - these have no prerequisites
        for (Move mv : shuffled) {
            PowerTier tier = tierAssignments.get(mv);
            if (tier == PowerTier.LOW || tier == PowerTier.MID) {
                incrementTypeTierCount(typeTierCounts, mv.type, tier);
            }
        }

        // Resolve HIGH - requires at least one LOW and one MID for this type
        for (Move mv : shuffled) {
            PowerTier tier = tierAssignments.get(mv);
            if (tier == PowerTier.HIGH) {
                if (typeHasTier(typeTierCounts, mv.type, PowerTier.LOW)
                        && typeHasTier(typeTierCounts, mv.type, PowerTier.MID)) {
                    incrementTypeTierCount(typeTierCounts, mv.type, PowerTier.HIGH);
                } else {
                    // Demote to MID
                    tierAssignments.put(mv, PowerTier.MID);
                    incrementTypeTierCount(typeTierCounts, mv.type, PowerTier.MID);
                }
            }
        }

        // Resolve EXTREME - requires at least one HIGH for this type, capped at MAX_EXTREME_PER_TYPE
        for (Move mv : shuffled) {
            PowerTier tier = tierAssignments.get(mv);
            if (tier == PowerTier.EXTREME) {
                if (typeHasTier(typeTierCounts, mv.type, PowerTier.HIGH)
                        && getTypeTierCount(typeTierCounts, mv.type, PowerTier.EXTREME) < MAX_EXTREME_PER_TYPE) {
                    incrementTypeTierCount(typeTierCounts, mv.type, PowerTier.EXTREME);
                } else {
                    // Demote to HIGH if prerequisites are met, otherwise MID
                    if (typeHasTier(typeTierCounts, mv.type, PowerTier.LOW)
                            && typeHasTier(typeTierCounts, mv.type, PowerTier.MID)) {
                        tierAssignments.put(mv, PowerTier.HIGH);
                        incrementTypeTierCount(typeTierCounts, mv.type, PowerTier.HIGH);
                    } else {
                        tierAssignments.put(mv, PowerTier.MID);
                        incrementTypeTierCount(typeTierCounts, mv.type, PowerTier.MID);
                    }
                }
            }
        }

        // Resolve NUKE - requires at least one EXTREME for this type, capped at MAX_NUKE_PER_TYPE
        for (Move mv : shuffled) {
            PowerTier tier = tierAssignments.get(mv);
            if (tier == PowerTier.NUKE) {
                if (typeHasTier(typeTierCounts, mv.type, PowerTier.EXTREME)
                        && getTypeTierCount(typeTierCounts, mv.type, PowerTier.NUKE) < MAX_NUKE_PER_TYPE) {
                    incrementTypeTierCount(typeTierCounts, mv.type, PowerTier.NUKE);
                } else {
                    // Demote down the chain
                    PowerTier demotedTier = findHighestAllowedTier(typeTierCounts, mv.type, PowerTier.EXTREME);
                    tierAssignments.put(mv, demotedTier);
                    incrementTypeTierCount(typeTierCounts, mv.type, demotedTier);
                }
            }
        }
    }

    /**
     * Finds the highest tier this type can accept based on current prerequisites.
     * Used when demoting a move that failed its tier check.
     */
    private PowerTier findHighestAllowedTier(Map<Type, EnumMap<PowerTier, Integer>> typeTierCounts,
                                              Type type, PowerTier maxTier) {
        // Try each tier from maxTier downward
        if (maxTier.ordinal() >= PowerTier.EXTREME.ordinal()
                && typeHasTier(typeTierCounts, type, PowerTier.HIGH)
                && getTypeTierCount(typeTierCounts, type, PowerTier.EXTREME) < MAX_EXTREME_PER_TYPE) {
            return PowerTier.EXTREME;
        }
        if (maxTier.ordinal() >= PowerTier.HIGH.ordinal()
                && typeHasTier(typeTierCounts, type, PowerTier.LOW)
                && typeHasTier(typeTierCounts, type, PowerTier.MID)) {
            return PowerTier.HIGH;
        }
        if (maxTier.ordinal() >= PowerTier.MID.ordinal()) {
            return PowerTier.MID;
        }
        return PowerTier.LOW;
    }

    /**
     * Rolls a concrete power value within the given tier, in steps of 5.
     */
    private int rollPowerInTier(PowerTier tier) {
        int min, max;
        switch (tier) {
            case LOW:
                min = LOW_MIN;
                max = LOW_MAX;
                break;
            case MID:
                min = LOW_MAX;
                max = MID_MAX;
                break;
            case HIGH:
                min = MID_MAX;
                max = HIGH_MAX;
                break;
            case EXTREME:
                min = HIGH_MAX;
                max = EXTREME_MAX;
                break;
            case NUKE:
                min = EXTREME_MAX;
                max = NUKE_MAX;
                break;
            default:
                min = LOW_MIN;
                max = LOW_MAX;
                break;
        }
        // Number of possible values in steps of 5: (max - min) / 5 + 1
        int steps = (max - min) / 5 + 1;
        return random.nextInt(steps) * 5 + min;
    }

    private void incrementTypeTierCount(Map<Type, EnumMap<PowerTier, Integer>> typeTierCounts,
                                         Type type, PowerTier tier) {
        if (type == null) return;
        typeTierCounts.computeIfAbsent(type, t -> new EnumMap<>(PowerTier.class))
                .merge(tier, 1, Integer::sum);
    }

    private int getTypeTierCount(Map<Type, EnumMap<PowerTier, Integer>> typeTierCounts,
                                  Type type, PowerTier tier) {
        if (type == null) return 0;
        return typeTierCounts.getOrDefault(type, new EnumMap<>(PowerTier.class))
                .getOrDefault(tier, 0);
    }

    private boolean typeHasTier(Map<Type, EnumMap<PowerTier, Integer>> typeTierCounts,
                                 Type type, PowerTier tier) {
        return getTypeTierCount(typeTierCounts, type, tier) > 0;
    }

    public void randomizeMovePPs() {
        List<Move> moves = romHandler.getMoves();
        for (Move mv : moves) {
            if (mv != null && mv.internalId != MoveIDs.struggle) {
                if (random.nextInt(3) != 2) {
                    // "average" PP: 15-25
                    mv.pp = random.nextInt(3) * 5 + 15;
                } else {
                    // "extreme" PP: 5-40
                    mv.pp = random.nextInt(8) * 5 + 5;
                }
            }
        }
        changesMade = true;
    }

    public void randomizeMoveAccuracies() {
        List<Move> moves = romHandler.getMoves();
        for (Move mv : moves) {
            if (mv != null && mv.internalId != MoveIDs.struggle && mv.hitratio >= 5) {
                // "Sane" accuracy randomization
                // Broken into three tiers based on original accuracy
                // Designed to limit the chances of 100% accurate OHKO moves and
                // keep a decent base of 100% accurate regular moves.

                if (mv.hitratio <= 50) {
                    // lowest tier (acc <= 50)
                    // new accuracy = rand(20...50) inclusive
                    // with a 10% chance to increase by 50%
                    mv.hitratio = random.nextInt(7) * 5 + 20;
                    if (random.nextInt(10) == 0) {
                        mv.hitratio = (mv.hitratio * 3 / 2) / 5 * 5;
                    }
                } else if (mv.hitratio < 90) {
                    // middle tier (50 < acc < 90)
                    // count down from 100% to 20% in 5% increments with 20%
                    // chance to "stop" and use the current accuracy at each
                    // increment
                    // gives decent-but-not-100% accuracy most of the time
                    mv.hitratio = 100;
                    while (mv.hitratio > 20) {
                        if (random.nextInt(10) < 2) {
                            break;
                        }
                        mv.hitratio -= 5;
                    }
                } else {
                    // highest tier (90 <= acc <= 100)
                    // count down from 100% to 20% in 5% increments with 40%
                    // chance to "stop" and use the current accuracy at each
                    // increment
                    // gives high accuracy most of the time
                    mv.hitratio = 100;
                    while (mv.hitratio > 20) {
                        if (random.nextInt(10) < 4) {
                            break;
                        }
                        mv.hitratio -= 5;
                    }
                }
            }
        }
        changesMade = true;
    }

    public void randomizeMoveTypes() {
        List<Move> moves = romHandler.getMoves();
        for (Move mv : moves) {
            if (mv != null && mv.internalId != MoveIDs.struggle && mv.type != null) {
                mv.type = romHandler.getTypeService().randomType(random);
            }
        }
        changesMade = true;
    }

    public void randomizeMoveCategory() {
        if (!romHandler.hasPhysicalSpecialSplit()) {
            return;
        }
        List<Move> moves = romHandler.getMoves();
        for (Move mv : moves) {
            if (mv != null && mv.internalId != MoveIDs.struggle && mv.category != MoveCategory.STATUS) {
                if (random.nextInt(2) == 0) {
                    mv.category = (mv.category == MoveCategory.PHYSICAL) ? MoveCategory.SPECIAL : MoveCategory.PHYSICAL;
                }
            }
        }
        changesMade = true;
    }

}