package com.uprfvx.random.randomizers;

import com.uprfvx.random.Settings;
import com.uprfvx.romio.constants.MoveIDs;
import com.uprfvx.romio.gamedata.Move;
import com.uprfvx.romio.gamedata.MoveCategory;
import com.uprfvx.romio.gamedata.StatusType;
import com.uprfvx.romio.gamedata.Type;
import com.uprfvx.romio.romhandlers.RomHandler;

import java.util.*;
import java.util.stream.Collectors;

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
    // more moves in HIGH to ensure late-game viability.
    // ~30% Low, ~30% Mid, ~25% High, ~12% Extreme, ~3% Nuke
    private static final int TIER_LOW_WEIGHT = 30;
    private static final int TIER_MID_WEIGHT = 30;
    private static final int TIER_HIGH_WEIGHT = 25;
    private static final int TIER_EXTREME_WEIGHT = 12;

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

        // Pass 1: Assign each move an initial tier via weighted random roll
        Map<Move, PowerTier> tierAssignments = new LinkedHashMap<>();
        for (Move mv : eligibleMoves) {
            tierAssignments.put(mv, rollInitialTier());
        }

        // Pass 2: Enforce per-type constraints with cascading prerequisite checks
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

        // Pass 3: Assign concrete power values within each tier
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
     * Enforces per-type power caps based on the CURRENT types of moves.
     * This must be called AFTER move type randomization (if enabled) to ensure
     * the caps are checked against final types, not original types.
     *
     * Walks all damaging moves, groups them by their (possibly randomized) type,
     * and demotes any that exceed the per-type EXTREME/NUKE caps.
     * Also enforces the prerequisite chain: NUKE requires EXTREME, EXTREME requires HIGH.
     */
    public void enforceMovePowerTypeCaps() {
        List<Move> moves = romHandler.getMoves();

        // Group eligible damaging moves by type with their current power
        Map<Type, List<Move>> movesByType = new HashMap<>();
        for (Move mv : moves) {
            if (mv != null && mv.internalId != MoveIDs.struggle && mv.power >= 10 && mv.type != null) {
                movesByType.computeIfAbsent(mv.type, t -> new ArrayList<>()).add(mv);
            }
        }

        for (Map.Entry<Type, List<Move>> entry : movesByType.entrySet()) {
            List<Move> typeMoves = entry.getValue();

            // Count current tier distribution for this type
            int extremeCount = 0, highCount = 0;
            for (Move mv : typeMoves) {
                int effectivePower = (int) (mv.power * mv.hitCount);
                if (effectivePower > EXTREME_MAX) { /* nuke - counted during enforcement loop */ }
                else if (effectivePower > HIGH_MAX) extremeCount++;
                else if (effectivePower > MID_MAX) highCount++;
            }

            // Shuffle to randomize which moves get demoted
            List<Move> shuffled = new ArrayList<>(typeMoves);
            Collections.shuffle(shuffled, random);

            // Enforce NUKE cap first
            int nukesAllowed = MAX_NUKE_PER_TYPE;
            // Prerequisite: NUKE needs EXTREME
            if (extremeCount == 0) nukesAllowed = 0;
            int nukesSeen = 0;
            for (Move mv : shuffled) {
                int ep = (int) (mv.power * mv.hitCount);
                if (ep > EXTREME_MAX) {
                    nukesSeen++;
                    if (nukesSeen > nukesAllowed) {
                        // Demote to EXTREME
                        mv.power = rollPowerInTier(PowerTier.EXTREME);
                        if (mv.hitCount != 1) {
                            mv.power = (int) (Math.round(mv.power / mv.hitCount / 5) * 5);
                            if (mv.power == 0) mv.power = 5;
                        }
                        extremeCount++;
                    }
                }
            }

            // Enforce EXTREME cap
            int extremesAllowed = MAX_EXTREME_PER_TYPE;
            // Prerequisite: EXTREME needs HIGH
            if (highCount == 0) extremesAllowed = 0;
            int extremesSeen = 0;
            for (Move mv : shuffled) {
                int ep = (int) (mv.power * mv.hitCount);
                if (ep > HIGH_MAX && ep <= EXTREME_MAX) {
                    extremesSeen++;
                    if (extremesSeen > extremesAllowed) {
                        // Demote to HIGH
                        mv.power = rollPowerInTier(PowerTier.HIGH);
                        if (mv.hitCount != 1) {
                            mv.power = (int) (Math.round(mv.power / mv.hitCount / 5) * 5);
                            if (mv.power == 0) mv.power = 5;
                        }
                        highCount++;
                    }
                }
            }
        }
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

        // Phase 1: Lock in LOW and MID - these have no prerequisites
        for (Move mv : shuffled) {
            PowerTier tier = tierAssignments.get(mv);
            if (tier == PowerTier.LOW || tier == PowerTier.MID) {
                incrementTypeTierCount(typeTierCounts, mv.type, tier);
            }
        }

        // Phase 2: Resolve HIGH - requires at least one LOW and one MID for this type
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

        // Phase 3: Resolve EXTREME - requires at least one HIGH for this type, capped at MAX_EXTREME_PER_TYPE
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

        // Phase 4: Resolve NUKE - requires at least one EXTREME for this type, capped at MAX_NUKE_PER_TYPE
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
                // Vanilla-faithful accuracy randomization.
                // In the actual games, roughly:
                //   ~70% of moves are 100% accuracy
                //   ~10% are 95%
                //   ~8% are 85-90%
                //   ~7% are 70-80%
                //   ~5% are below 70% (OHKO moves, Zap Cannon, etc.)
                //
                // OHKO moves (original acc <= 30) are kept in their own bracket.
                // Extreme+ power moves (80+ BP) skip generic randomization entirely
                // and are handled by applyPowerAwareAccuracyAdjustment instead.
 
                if (mv.hitratio <= 30) {
                    // OHKO / very low accuracy moves — keep them low
                    // Randomize within 20-50% range
                    mv.hitratio = random.nextInt(7) * 5 + 20;
                } else if (mv.power >= HIGH_MAX && mv.category != MoveCategory.STATUS) {
                    // Extreme+ power damaging moves: start at 100%, let the
                    // power-aware adjustment handle any reductions
                    mv.hitratio = 100;
                } else {
                    // Weighted roll emulating vanilla distribution
                    int roll = random.nextInt(100);
                    if (roll < 70) {
                        mv.hitratio = 100;
                    } else if (roll < 80) {
                        mv.hitratio = 95;
                    } else if (roll < 88) {
                        // 85-90 range
                        mv.hitratio = random.nextBoolean() ? 90 : 85;
                    } else if (roll < 95) {
                        // 70-80 range
                        mv.hitratio = random.nextInt(3) * 5 + 70; // 70, 75, or 80
                    } else {
                        // Below 70 — rare inaccurate moves
                        mv.hitratio = random.nextInt(5) * 5 + 45; // 45, 50, 55, 60, or 65
                    }
                }
 
                // Power-aware accuracy adjustment for Extreme tier and above (80+ BP).
                // Higher power moves have a chance to lose accuracy, simulating the
                // classic risk/reward tradeoff of powerful moves.
                if (mv.power >= HIGH_MAX && mv.category != MoveCategory.STATUS) {
                    applyPowerAwareAccuracyAdjustment(mv);
                }
            }
        }
        changesMade = true;
    }
    
    /**
     * Applies accuracy deductions to high-power moves based on their power and properties.
     *
     * For each 10 BP past 80, roll a chance to deduct 5% accuracy.
     * Base chance per roll: 50%
     *
     * Modifiers:
     *   - Move has a drawback (isChargeMove, isRechargeMove, recoilPercent < 0):
     *     Skip accuracy adjustment entirely - these moves already pay a cost.
     *   - Move has a beneficial secondary effect (inflicts status, flinches, absorbs HP):
     *     Extra roll per deduction step (effectively doubles the chance of each deduction).
     *
     * Accuracy floor: 50% - no move is reduced below this regardless of power
     */
    private void applyPowerAwareAccuracyAdjustment(Move mv) {
        int baseChance = 50;
        int reductionThreshold = 80;
        int reductionStep = 10;
        int accuracyReduction = 5;
        int accuracyFloor = 50;

        // Moves with drawbacks are exempt - they already have a cost
        if (mv.isChargeMove || mv.isRechargeMove || mv.recoilPercent < 0) {
            return;
        }

        int effectivePower = (int) (mv.power * mv.hitCount);
        if (effectivePower <= reductionThreshold) {
            return;
        }

        // Determine if this move has beneficial secondary effects
        boolean hasBeneficialEffect = false;
        if (mv.statusType != null && mv.statusType != StatusType.NONE && mv.statusPercentChance > 0) {
            hasBeneficialEffect = true; // Inflicts a status condition
        }
        if (mv.flinchPercentChance > 0) {
            hasBeneficialEffect = true; // Causes flinching
        }
        if (mv.absorbPercent > 0) {
            hasBeneficialEffect = true; // Drains HP
        }
        if (mv.hasBeneficialStatChange()) {
            hasBeneficialEffect = true; // Raises user stats or lowers target stats
        }

        // For each 10 BP past 80, roll for a -5 accuracy deduction
        int stepsOverThreshold = (effectivePower - reductionThreshold) / reductionStep;
        for (int i = 0; i < stepsOverThreshold; i++) {
            // Base 50% chance to deduct
            boolean deduct = random.nextInt(100) < baseChance;
            if (!deduct && hasBeneficialEffect) {
                // Extra roll for moves with beneficial effects
                deduct = random.nextInt(100) < baseChance;
            }
            if (deduct) {
                mv.hitratio -= accuracyReduction;
            }
        }

        // Floor accuracy
        if (mv.hitratio < accuracyFloor) {
            mv.hitratio = accuracyFloor;
        }
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