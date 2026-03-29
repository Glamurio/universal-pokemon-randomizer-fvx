package com.uprfvx.random.randomizers;

import com.uprfvx.random.Settings;
import com.uprfvx.romio.constants.GlobalConstants;
import com.uprfvx.romio.constants.MoveIDs;
import com.uprfvx.romio.gamedata.*;
import com.uprfvx.romio.romhandlers.RomHandler;

import java.util.*;
import java.util.stream.Collectors;

public class SpeciesMovesetRandomizer extends Randomizer {

    public SpeciesMovesetRandomizer(RomHandler romHandler, Settings settings, Random random) {
        super(romHandler, settings, random);
    }

    public void randomizeMovesLearnt() {
        boolean typeThemed = settings.getMovesetsMod() == Settings.MovesetsMod.RANDOM_PREFER_SAME_TYPE;
        boolean noBroken = settings.isBlockBrokenMovesetMoves();
        boolean forceStartingMoves = romHandler.supportsFourStartingMoves() && settings.isStartWithGuaranteedMoves();
        int forceStartingMoveCount = settings.getGuaranteedMoveCount();
        double goodDamagingPercentage =
                settings.isMovesetsForceGoodDamaging() ? settings.getMovesetsGoodDamagingPercent() / 100.0 : 0;
        boolean evolutionMovesForAll = settings.isEvolutionMovesForAll();
        boolean followEvolutions = settings.isMovesetsFollowEvolutions();
 
        // Get current sets
        Map<Integer, List<MoveLearnt>> movesets = romHandler.getMovesLearnt();
 
        // Build sets of moves
        List<Move> validMoves = new ArrayList<>();
        List<Move> validDamagingMoves = new ArrayList<>();
        Map<Type, List<Move>> validTypeMoves = new HashMap<>();
        Map<Type, List<Move>> validTypeDamagingMoves = new HashMap<>();
        createSetsOfMoves(noBroken, validMoves, validDamagingMoves, validTypeMoves, validTypeDamagingMoves);
 
        // Build power-tiered move lists for level-scaled picking
        List<Move> lowPowerMoves = new ArrayList<>();     // power * hitCount <= 40
        List<Move> midPowerMoves = new ArrayList<>();     // power * hitCount 41-60
        List<Move> highPowerMoves = new ArrayList<>();    // power * hitCount 61-80
        List<Move> extremePowerMoves = new ArrayList<>(); // power * hitCount > 80
        categorizeMovesByPower(validDamagingMoves, lowPowerMoves, midPowerMoves, highPowerMoves, extremePowerMoves);
 
        // Also build type-specific power-tiered lists
        Map<Type, List<Move>> lowPowerByType = new HashMap<>();
        Map<Type, List<Move>> midPowerByType = new HashMap<>();
        Map<Type, List<Move>> highPowerByType = new HashMap<>();
        Map<Type, List<Move>> extremePowerByType = new HashMap<>();
        for (Type type : validTypeDamagingMoves.keySet()) {
            lowPowerByType.put(type, new ArrayList<>());
            midPowerByType.put(type, new ArrayList<>());
            highPowerByType.put(type, new ArrayList<>());
            extremePowerByType.put(type, new ArrayList<>());
            categorizeMovesByPower(validTypeDamagingMoves.get(type),
                    lowPowerByType.get(type), midPowerByType.get(type),
                    highPowerByType.get(type), extremePowerByType.get(type));
        }
 
        if (followEvolutions) {
            // Randomize using evolution-aware logic:
            // Base forms get randomized normally, evolutions inherit and extend
            copyUpEvolutionsHelper.apply(true, true,
                    // Base species action
                    pk -> randomizeSingleSpeciesMoveset(pk, movesets, validMoves, validDamagingMoves,
                            validTypeMoves, validTypeDamagingMoves,
                            lowPowerMoves, midPowerMoves, highPowerMoves, extremePowerMoves,
                            lowPowerByType, midPowerByType, highPowerByType, extremePowerByType,
                            typeThemed, goodDamagingPercentage, forceStartingMoves, forceStartingMoveCount,
                            evolutionMovesForAll),
                    // Evolution action: inherit base form's moves, randomize only new slots
                    (evFrom, evTo, toMonIsFinalEvo) -> inheritAndExtendMoveset(evFrom, evTo, movesets,
                            validMoves, validDamagingMoves, validTypeMoves, validTypeDamagingMoves,
                            lowPowerMoves, midPowerMoves, highPowerMoves, extremePowerMoves,
                            lowPowerByType, midPowerByType, highPowerByType, extremePowerByType,
                            typeThemed, goodDamagingPercentage, forceStartingMoves, forceStartingMoveCount,
                            evolutionMovesForAll));
        } else {
            // Randomize each species independently
            for (Integer pkmnNum : movesets.keySet()) {
                Species pkmn = findSpeciesInPoolWithSpeciesID(rSpecService.getAll(true), pkmnNum);
                if (pkmn == null) continue;
                randomizeSingleSpeciesMoveset(pkmn, movesets, validMoves, validDamagingMoves,
                        validTypeMoves, validTypeDamagingMoves,
                        lowPowerMoves, midPowerMoves, highPowerMoves, extremePowerMoves,
                        lowPowerByType, midPowerByType, highPowerByType, extremePowerByType,
                        typeThemed, goodDamagingPercentage, forceStartingMoves, forceStartingMoveCount,
                        evolutionMovesForAll);
            }
        }
 
        // Done, save
        romHandler.setMovesLearnt(movesets);
        changesMade = true;
    }
    
    /**
     * Randomizes a single species' moveset with level-scaled power picking.
     * Damaging moves picked for later level slots are biased toward higher power tiers.
     */
    private void randomizeSingleSpeciesMoveset(Species pkmn, Map<Integer, List<MoveLearnt>> movesets,
                                                List<Move> validMoves, List<Move> validDamagingMoves,
                                                Map<Type, List<Move>> validTypeMoves,
                                                Map<Type, List<Move>> validTypeDamagingMoves,
                                                List<Move> lowPowerMoves, List<Move> midPowerMoves,
                                                List<Move> highPowerMoves, List<Move> extremePowerMoves,
                                                Map<Type, List<Move>> lowPowerByType, Map<Type, List<Move>> midPowerByType,
                                                Map<Type, List<Move>> highPowerByType, Map<Type, List<Move>> extremePowerByType,
                                                boolean typeThemed, double goodDamagingPercentage,
                                                boolean forceStartingMoves, int forceStartingMoveCount,
                                                boolean evolutionMovesForAll) {

        List<MoveLearnt> moves = movesets.get(pkmn.getNumber());
        if (moves == null) return;

        // Pad starting moves if needed
        if (forceStartingMoves) {
            int lv1count = 0;
            for (MoveLearnt ml : moves) {
                if (ml.level == 1) lv1count++;
            }
            if (lv1count < forceStartingMoveCount) {
                for (int i = 0; i < forceStartingMoveCount - lv1count; i++) {
                    moves.add(0, new MoveLearnt(0, 1));
                }
            }
        }

        if (evolutionMovesForAll) {
            if (moves.get(0).level != 0) {
                moves.add(0, new MoveLearnt(0, 0));
            }
        }

        if (pkmn.isActuallyCosmetic()) {
            for (int i = 0; i < moves.size(); i++) {
                moves.get(i).move = movesets.get(pkmn.getBaseForme().getNumber()).get(i).move;
            }
            return;
        }

        double atkSpAtkRatio = pkmn.getAttackSpecialAttackRatio();

        // Find last lv1 move
        int lv1index = moves.get(0).level == 1 ? 0 : 1;
        while (lv1index < moves.size() && moves.get(lv1index).level == 1) {
            lv1index++;
        }
        if (lv1index != 0) lv1index--;

        int goodDamagingLeft = (int) Math.round(goodDamagingPercentage * moves.size());

        List<Integer> learnt = new ArrayList<>();
        int lv1AttackingMove = 0;

        for (int i = 0; i < moves.size(); i++) {
            int level = moves.get(i).level;
            boolean attemptDamaging = i == lv1index || goodDamagingLeft > 0;

            Type typeOfMove = pickTypeForMove(pkmn, typeThemed);

            List<Move> pickList = validMoves;
            if (attemptDamaging) {
                // Use level-scaled power picking for damaging moves
                pickList = getLevelScaledDamagingMoves(level, typeOfMove, atkSpAtkRatio,
                        validDamagingMoves, validTypeDamagingMoves,
                        lowPowerMoves, midPowerMoves, highPowerMoves, extremePowerMoves,
                        lowPowerByType, midPowerByType, highPowerByType, extremePowerByType,
                        learnt);

                // If level-scaled picking returned nothing usable, fall back
                if (pickList.isEmpty() || !checkForUnusedMove(pickList, learnt)) {
                    pickList = validDamagingMoves;
                }

                // Apply physical/special category filter
                MoveCategory forcedCategory = random.nextDouble() < atkSpAtkRatio ?
                        MoveCategory.PHYSICAL : MoveCategory.SPECIAL;
                List<Move> filteredList = pickList.stream()
                        .filter(mv -> mv.category == forcedCategory)
                        .collect(Collectors.toList());
                if (!filteredList.isEmpty() && checkForUnusedMove(filteredList, learnt)) {
                    pickList = filteredList;
                }
            } else if (typeOfMove != null) {
                if (validTypeMoves.containsKey(typeOfMove)
                        && checkForUnusedMove(validTypeMoves.get(typeOfMove), learnt)) {
                    pickList = validTypeMoves.get(typeOfMove);
                }
            }

            Move mv = pickList.get(random.nextInt(pickList.size()));
            while (learnt.contains(mv.number)) {
                mv = pickList.get(random.nextInt(pickList.size()));
            }

            if (i == lv1index) {
                lv1AttackingMove = mv.number;
            } else {
                goodDamagingLeft--;
            }
            learnt.add(mv.number);
        }

        // Shuffle but keep lv1 attacking move in place
        Collections.shuffle(learnt, random);
        if (learnt.get(lv1index) != lv1AttackingMove) {
            for (int i = 0; i < learnt.size(); i++) {
                if (learnt.get(i) == lv1AttackingMove) {
                    learnt.set(i, learnt.get(lv1index));
                    learnt.set(lv1index, lv1AttackingMove);
                    break;
                }
            }
        }

        // Write moves
        for (int i = 0; i < learnt.size(); i++) {
            moves.get(i).move = learnt.get(i);
            if (i == lv1index) {
                moves.get(i).level = 1;
            }
        }
    }

    /**
     * For an evolved species, inherits the pre-evolution's moves for shared level slots,
     * then randomizes only the new level slots that the evolution adds.
     * This ensures evolutionary consistency while still giving evolutions new moves.
     */
    private void inheritAndExtendMoveset(Species evFrom, Species evTo,
                                          Map<Integer, List<MoveLearnt>> movesets,
                                          List<Move> validMoves, List<Move> validDamagingMoves,
                                          Map<Type, List<Move>> validTypeMoves,
                                          Map<Type, List<Move>> validTypeDamagingMoves,
                                          List<Move> lowPowerMoves, List<Move> midPowerMoves,
                                          List<Move> highPowerMoves, List<Move> extremePowerMoves,
                                          Map<Type, List<Move>> lowPowerByType, Map<Type, List<Move>> midPowerByType,
                                          Map<Type, List<Move>> highPowerByType, Map<Type, List<Move>> extremePowerByType,
                                          boolean typeThemed, double goodDamagingPercentage,
                                          boolean forceStartingMoves, int forceStartingMoveCount,
                                          boolean evolutionMovesForAll) {

        List<MoveLearnt> fromMoves = movesets.get(evFrom.getNumber());
        List<MoveLearnt> toMoves = movesets.get(evTo.getNumber());
        if (fromMoves == null || toMoves == null) return;

        if (evTo.isActuallyCosmetic()) {
            for (int i = 0; i < toMoves.size(); i++) {
                toMoves.get(i).move = movesets.get(evTo.getBaseForme().getNumber()).get(i).move;
            }
            return;
        }

        // Pad starting moves if needed
        if (forceStartingMoves) {
            int lv1count = 0;
            for (MoveLearnt ml : toMoves) {
                if (ml.level == 1) lv1count++;
            }
            if (lv1count < forceStartingMoveCount) {
                for (int i = 0; i < forceStartingMoveCount - lv1count; i++) {
                    toMoves.add(0, new MoveLearnt(0, 1));
                }
            }
        }

        if (evolutionMovesForAll) {
            if (toMoves.get(0).level != 0) {
                toMoves.add(0, new MoveLearnt(0, 0));
            }
        }

        double atkSpAtkRatio = evTo.getAttackSpecialAttackRatio();

        // Inherit moves by position (slot index), not by level.
        // Evolutions typically have the same number of slots or more than their pre-evo,
        // with levels shifted upward. We match slot-by-slot: slot 0 of the evo inherits
        // the move from slot 0 of the pre-evo, slot 1 from slot 1, etc.
        // Any extra slots the evolution has beyond the pre-evo's count get randomized fresh.
        List<Integer> inheritedMoves = new ArrayList<>();
        Set<Integer> newSlotIndices = new LinkedHashSet<>();

        int inheritableSlots = Math.min(fromMoves.size(), toMoves.size());

        for (int i = 0; i < toMoves.size(); i++) {
            if (i < inheritableSlots) {
                int inheritedMove = fromMoves.get(i).move;
                if (inheritedMove != 0 && !inheritedMoves.contains(inheritedMove)) {
                    toMoves.get(i).move = inheritedMove;
                    inheritedMoves.add(inheritedMove);
                } else {
                    // Pre-evo slot was empty or duplicate - randomize this slot
                    newSlotIndices.add(i);
                }
            } else {
                // Evolution has more slots than pre-evo - these are new
                newSlotIndices.add(i);
            }
        }

        // Now randomize the new slots with level-scaled power picking
        int goodDamagingLeft = (int) Math.round(goodDamagingPercentage * toMoves.size());
        // Subtract already-inherited good damaging moves from the count
        List<Move> allMoves = romHandler.getMoves();
        for (int moveNum : inheritedMoves) {
            if (moveNum > 0 && moveNum < allMoves.size()) {
                Move mv = allMoves.get(moveNum);
                if (mv != null && mv.isGoodDamaging(romHandler.getPerfectAccuracy())) {
                    goodDamagingLeft--;
                }
            }
        }
        goodDamagingLeft = Math.max(0, goodDamagingLeft);

        // Find lv1index for the evolution
        int lv1index = toMoves.get(0).level == 1 ? 0 : 1;
        while (lv1index < toMoves.size() && toMoves.get(lv1index).level == 1) {
            lv1index++;
        }
        if (lv1index != 0) lv1index--;

        // Check if lv1 already has an attacking move from inheritance
        boolean lv1HasAttack = false;
        if (!newSlotIndices.contains(lv1index)) {
            int lv1Move = toMoves.get(lv1index).move;
            if (lv1Move > 0 && lv1Move < allMoves.size()) {
                Move mv = allMoves.get(lv1Move);
                if (mv != null && mv.power > 0) {
                    lv1HasAttack = true;
                }
            }
        }

        List<Integer> alreadyUsed = new ArrayList<>(inheritedMoves);

        for (int i : newSlotIndices) {
            int level = toMoves.get(i).level;
            boolean attemptDamaging = (!lv1HasAttack && i == lv1index) || goodDamagingLeft > 0;

            Type typeOfMove = pickTypeForMove(evTo, typeThemed);

            List<Move> pickList = validMoves;
            if (attemptDamaging) {
                pickList = getLevelScaledDamagingMoves(level, typeOfMove, atkSpAtkRatio,
                        validDamagingMoves, validTypeDamagingMoves,
                        lowPowerMoves, midPowerMoves, highPowerMoves, extremePowerMoves,
                        lowPowerByType, midPowerByType, highPowerByType, extremePowerByType,
                        alreadyUsed);

                if (pickList.isEmpty() || !checkForUnusedMove(pickList, alreadyUsed)) {
                    pickList = validDamagingMoves;
                }

                MoveCategory forcedCategory = random.nextDouble() < atkSpAtkRatio ?
                        MoveCategory.PHYSICAL : MoveCategory.SPECIAL;
                List<Move> filteredList = pickList.stream()
                        .filter(mv -> mv.category == forcedCategory)
                        .collect(Collectors.toList());
                if (!filteredList.isEmpty() && checkForUnusedMove(filteredList, alreadyUsed)) {
                    pickList = filteredList;
                }
            } else if (typeOfMove != null) {
                if (validTypeMoves.containsKey(typeOfMove)
                        && checkForUnusedMove(validTypeMoves.get(typeOfMove), alreadyUsed)) {
                    pickList = validTypeMoves.get(typeOfMove);
                }
            }

            Move mv = pickList.get(random.nextInt(pickList.size()));
            while (alreadyUsed.contains(mv.number)) {
                mv = pickList.get(random.nextInt(pickList.size()));
            }

            toMoves.get(i).move = mv.number;
            alreadyUsed.add(mv.number);

            if (attemptDamaging && i != lv1index) {
                goodDamagingLeft--;
            }
            if (i == lv1index) {
                lv1HasAttack = true;
            }
        }
    }

    /**
     * Returns a pick list of damaging moves appropriate for the given level.
     *
     * Level scaling strategy:
     *   Lv  1-14: 70% low, 25% mid, 5% high
     *   Lv 15-29: 30% low, 45% mid, 20% high, 5% extreme
     *   Lv 30-44: 10% low, 30% mid, 40% high, 20% extreme
     *   Lv 45+  :  5% low, 15% mid, 40% high, 40% extreme
     *
     * The method builds a weighted pool by adding moves from each tier multiple
     * times proportional to its weight. Falls back gracefully if a tier is empty.
     */
    private List<Move> getLevelScaledDamagingMoves(int level, Type typeOfMove, double atkSpAtkRatio,
                                                    List<Move> validDamagingMoves,
                                                    Map<Type, List<Move>> validTypeDamagingMoves,
                                                    List<Move> lowPowerMoves, List<Move> midPowerMoves,
                                                    List<Move> highPowerMoves, List<Move> extremePowerMoves,
                                                    Map<Type, List<Move>> lowPowerByType,
                                                    Map<Type, List<Move>> midPowerByType,
                                                    Map<Type, List<Move>> highPowerByType,
                                                    Map<Type, List<Move>> extremePowerByType,
                                                    List<Integer> alreadyUsed) {

        // Determine which source lists to use (type-specific or general)
        List<Move> low, mid, high, extreme;
        if (typeOfMove != null && lowPowerByType.containsKey(typeOfMove)) {
            low = lowPowerByType.get(typeOfMove);
            mid = midPowerByType.get(typeOfMove);
            high = highPowerByType.get(typeOfMove);
            extreme = extremePowerByType.get(typeOfMove);
            // If the type-specific lists are too sparse, fall back to general
            if (low.isEmpty() && mid.isEmpty() && high.isEmpty() && extreme.isEmpty()) {
                low = lowPowerMoves;
                mid = midPowerMoves;
                high = highPowerMoves;
                extreme = extremePowerMoves;
            }
        } else {
            low = lowPowerMoves;
            mid = midPowerMoves;
            high = highPowerMoves;
            extreme = extremePowerMoves;
        }

        // Determine weights based on level
        int wLow, wMid, wHigh, wExtreme;
        if (level < 15) {
            wLow = 70; wMid = 30; wHigh = 0; wExtreme = 0;
        } else if (level < 30) {
            wLow = 30; wMid = 45; wHigh = 25; wExtreme = 0;
        } else if (level < 45) {
            wLow = 0; wMid = 35; wHigh = 45; wExtreme = 20;
        } else {
            wLow = 0; wMid = 15; wHigh = 40; wExtreme = 45;
        }

        // Hard cap: prevent extreme moves from appearing before level 30.
        // This protects small movesets (Beldum, Weedle, Magikarp etc.) where
        // the highest level slot might only be Lv. 15-20, which would otherwise
        // get an extreme move simply because it's the "latest" slot.
        if (level < 30) {
            wExtreme = 0;
        }
        // Similarly, prevent high-tier moves before level 10
        if (level < 10) {
            wHigh = 0;
        }

        // Build weighted pool
        List<Move> pool = new ArrayList<>();
        addWeightedMoves(pool, low, wLow);
        addWeightedMoves(pool, mid, wMid);
        addWeightedMoves(pool, high, wHigh);
        addWeightedMoves(pool, extreme, wExtreme);

        if (pool.isEmpty()) {
            // Absolute fallback
            pool.addAll(validDamagingMoves);
        }

        return pool;
    }

    /**
     * Adds moves from the source list to the pool, repeated proportional to weight.
     * Each unique move is added ceil(weight / 20) times to create the desired distribution.
     */
    private void addWeightedMoves(List<Move> pool, List<Move> source, int weight) {
        if (source.isEmpty() || weight <= 0) return;
        int copies = Math.max(1, (weight + 19) / 20); // ceil(weight/20), at least 1
        for (int c = 0; c < copies; c++) {
            pool.addAll(source);
        }
    }

    /**
     * Picks a type for the next move based on the Pokemon's types and the type-themed setting.
     * Extracted from the original inline logic for reuse.
     */
    private Type pickTypeForMove(Species pkmn, boolean typeThemed) {
        if (!typeThemed) return null;

        double picked = random.nextDouble();
        if ((pkmn.getPrimaryType(false) == Type.NORMAL && pkmn.getSecondaryType(false) != null) ||
                (pkmn.getSecondaryType(false) == Type.NORMAL)) {

            Type otherType = pkmn.getPrimaryType(false) == Type.NORMAL ?
                    pkmn.getSecondaryType(false) : pkmn.getPrimaryType(false);

            if (picked < 0.1) return Type.NORMAL;
            else if (picked < 0.4) return otherType;
        } else if (pkmn.getSecondaryType(false) != null) {
            if (picked < 0.2) return pkmn.getPrimaryType(false);
            else if (picked < 0.4) return pkmn.getSecondaryType(false);
        } else {
            if (picked < 0.4) return pkmn.getPrimaryType(false);
        }
        return null;
    }

    /**
     * Splits damaging moves into power tiers based on effective power (power * hitCount).
     * Excludes variable-damage moves (power = 1) since their actual damage is calculated
     * dynamically and doesn't reflect their listed power.
     */
    private void categorizeMovesByPower(List<Move> source,
                                        List<Move> low, List<Move> mid,
                                        List<Move> high, List<Move> extreme) {
        for (Move mv : source) {
            if (mv.power <= 1) continue; // Skip fixed/variable damage moves (OHKO, Return, etc.)
            double effectivePower = mv.power * mv.hitCount;
            if (effectivePower <= 40) {
                low.add(mv);
            } else if (effectivePower <= 60) {
                mid.add(mv);
            } else if (effectivePower <= 80) {
                high.add(mv);
            } else {
                extreme.add(mv);
            }
        }
    }

    public void randomizeEggMoves() {
        boolean typeThemed = settings.getMovesetsMod() == Settings.MovesetsMod.RANDOM_PREFER_SAME_TYPE;
        boolean noBroken = settings.isBlockBrokenMovesetMoves();
        double goodDamagingPercentage =
                settings.isMovesetsForceGoodDamaging() ? settings.getMovesetsGoodDamagingPercent() / 100.0 : 0;

        // Get current sets
        Map<Integer, List<Integer>> movesets = romHandler.getEggMoves();

        // Build sets of moves
        List<Move> validMoves = new ArrayList<>();
        List<Move> validDamagingMoves = new ArrayList<>();
        Map<Type, List<Move>> validTypeMoves = new HashMap<>();
        Map<Type, List<Move>> validTypeDamagingMoves = new HashMap<>();
        createSetsOfMoves(noBroken, validMoves, validDamagingMoves, validTypeMoves, validTypeDamagingMoves);

        for (Integer pkmnNum : movesets.keySet()) {
            List<Integer> learnt = new ArrayList<>();
            List<Integer> moves = movesets.get(pkmnNum);
            Species pkmn = findSpeciesInPoolWithSpeciesID(rSpecService.getAll(true), pkmnNum);
            if (pkmn == null) {
                continue;
            }

            double atkSpAtkRatio = pkmn.getAttackSpecialAttackRatio();

            if (pkmn.isActuallyCosmetic()) {
                for (int i = 0; i < moves.size(); i++) {
                    moves.set(i, movesets.get(pkmn.getBaseForme().getNumber()).get(i));
                }
                continue;
            }

            // Force a certain amount of good damaging moves depending on the percentage
            int goodDamagingLeft = (int) Math.round(goodDamagingPercentage * moves.size());

            // Replace moves as needed
            for (int i = 0; i < moves.size(); i++) {
                // should this move be forced damaging?
                boolean attemptDamaging = goodDamagingLeft > 0;

                Type typeOfMove = pickTypeForMove(pkmn, typeThemed);

                // select a list to pick a move from that has at least one free
                List<Move> pickList = validMoves;
                if (attemptDamaging) {
                    if (typeOfMove != null) {
                        if (validTypeDamagingMoves.containsKey(typeOfMove)
                                && checkForUnusedMove(validTypeDamagingMoves.get(typeOfMove), learnt)) {
                            pickList = validTypeDamagingMoves.get(typeOfMove);
                        } else if (checkForUnusedMove(validDamagingMoves, learnt)) {
                            pickList = validDamagingMoves;
                        }
                    } else if (checkForUnusedMove(validDamagingMoves, learnt)) {
                        pickList = validDamagingMoves;
                    }
                    MoveCategory forcedCategory = random.nextDouble() < atkSpAtkRatio ?
                            MoveCategory.PHYSICAL : MoveCategory.SPECIAL;
                    List<Move> filteredList = pickList.stream()
                            .filter(mv -> mv.category == forcedCategory)
                            .collect(Collectors.toList());
                    if (!filteredList.isEmpty() && checkForUnusedMove(filteredList, learnt)) {
                        pickList = filteredList;
                    }
                } else if (typeOfMove != null) {
                    if (validTypeMoves.containsKey(typeOfMove)
                            && checkForUnusedMove(validTypeMoves.get(typeOfMove), learnt)) {
                        pickList = validTypeMoves.get(typeOfMove);
                    }
                }

                // now pick a move until we get a valid one
                Move mv = pickList.get(random.nextInt(pickList.size()));
                while (learnt.contains(mv.number)) {
                    mv = pickList.get(random.nextInt(pickList.size()));
                }

                goodDamagingLeft--;
                learnt.add(mv.number);
            }

            // write all moves for the pokemon
            Collections.shuffle(learnt, random);
            for (int i = 0; i < learnt.size(); i++) {
                moves.set(i, learnt.get(i));
            }
        }
        // Done, save
        romHandler.setEggMoves(movesets);
        changesMade = true;
    }

    private boolean checkForUnusedMove(List<Move> potentialList, List<Integer> alreadyUsed) {
        for (Move mv : potentialList) {
            if (!alreadyUsed.contains(mv.number)) {
                return true;
            }
        }
        return false;
    }

    private void createSetsOfMoves(boolean noBroken, List<Move> validMoves, List<Move> validDamagingMoves,
                                   Map<Type, List<Move>> validTypeMoves, Map<Type, List<Move>> validTypeDamagingMoves) {
        List<Move> allMoves = romHandler.getMoves();
        List<Integer> hms = romHandler.getHMMoves();
        Set<Integer> allBanned = new HashSet<>(noBroken ?
                romHandler.getGameBreakingMoves() : Collections.emptySet());
        allBanned.addAll(hms);
        allBanned.addAll(romHandler.getMovesBannedFromLevelup());
        allBanned.addAll(GlobalConstants.zMoves);
        allBanned.addAll(romHandler.getIllegalMoves());

        for (Move mv : allMoves) {
            if (mv != null && !GlobalConstants.bannedRandomMoves[mv.number] && !allBanned.contains(mv.number)) {
                validMoves.add(mv);
                if (mv.type != null) {
                    if (!validTypeMoves.containsKey(mv.type)) {
                        validTypeMoves.put(mv.type, new ArrayList<>());
                    }
                    validTypeMoves.get(mv.type).add(mv);
                }

                if (!GlobalConstants.bannedForDamagingMove[mv.number]) {
                    if (mv.isGoodDamaging(romHandler.getPerfectAccuracy())) {
                        validDamagingMoves.add(mv);
                        if (mv.type != null) {
                            if (!validTypeDamagingMoves.containsKey(mv.type)) {
                                validTypeDamagingMoves.put(mv.type, new ArrayList<>());
                            }
                            validTypeDamagingMoves.get(mv.type).add(mv);
                        }
                    }
                }
            }
        }

        Map<Type, Double> avgTypePowers = new TreeMap<>();
        double totalAvgPower = 0;

        for (Type type : validTypeMoves.keySet()) {
            List<Move> typeMoves = validTypeMoves.get(type);
            int attackingSum = 0;
            for (Move typeMove : typeMoves) {
                if (typeMove.power > 0) {
                    attackingSum += (typeMove.power * typeMove.hitCount);
                }
            }
            double avgTypePower = (double) attackingSum / (double) typeMoves.size();
            avgTypePowers.put(type, avgTypePower);
            totalAvgPower += (avgTypePower);
        }

        totalAvgPower /= validTypeMoves.size();

        // Want the average power of each type to be within 25% both directions
        double minAvg = totalAvgPower * 0.75;
        double maxAvg = totalAvgPower * 1.25;

        for (Type type : avgTypePowers.keySet()) {
            double avgPowerForType = avgTypePowers.get(type);
            List<Move> typeMoves = validTypeMoves.get(type);
            List<Move> alreadyPicked = new ArrayList<>();
            int iterLoops = 0;
            while (avgPowerForType < minAvg && iterLoops < 10000) {
                final double finalAvgPowerForType = avgPowerForType;
                List<Move> strongerThanAvgTypeMoves = typeMoves
                        .stream()
                        .filter(mv -> mv.power * mv.hitCount > finalAvgPowerForType)
                        .collect(Collectors.toList());
                if (strongerThanAvgTypeMoves.isEmpty()) break;
                if (alreadyPicked.containsAll(strongerThanAvgTypeMoves)) {
                    alreadyPicked = new ArrayList<>();
                } else {
                    strongerThanAvgTypeMoves.removeAll(alreadyPicked);
                }
                Move extraMove = strongerThanAvgTypeMoves.get(random.nextInt(strongerThanAvgTypeMoves.size()));
                avgPowerForType = (avgPowerForType * typeMoves.size() + extraMove.power * extraMove.hitCount)
                        / (typeMoves.size() + 1);
                typeMoves.add(extraMove);
                alreadyPicked.add(extraMove);
                iterLoops++;
            }
            iterLoops = 0;
            while (avgPowerForType > maxAvg && iterLoops < 10000) {
                final double finalAvgPowerForType = avgPowerForType;
                List<Move> weakerThanAvgTypeMoves = typeMoves
                        .stream()
                        .filter(mv -> mv.power * mv.hitCount < finalAvgPowerForType)
                        .collect(Collectors.toList());
                if (weakerThanAvgTypeMoves.isEmpty()) break;
                if (alreadyPicked.containsAll(weakerThanAvgTypeMoves)) {
                    alreadyPicked = new ArrayList<>();
                } else {
                    weakerThanAvgTypeMoves.removeAll(alreadyPicked);
                }
                Move extraMove = weakerThanAvgTypeMoves.get(random.nextInt(weakerThanAvgTypeMoves.size()));
                avgPowerForType = (avgPowerForType * typeMoves.size() + extraMove.power * extraMove.hitCount)
                        / (typeMoves.size() + 1);
                typeMoves.add(extraMove);
                alreadyPicked.add(extraMove);
                iterLoops++;
            }
        }
    }

    // Note that this is slow and somewhat hacky.
    // TODO: add to SpeciesSet, hopefully in a less hacky way.
    // (The non-hacky way might be to make it a TreeSet.)
    private Species findSpeciesInPoolWithSpeciesID(Collection<Species> speciesPool, int speciesID) {
        for (Species sp : speciesPool) {
            if (sp.getNumber() == speciesID) {
                return sp;
            }
        }
        return null;
    }

    public void orderDamagingMovesByDamage() {
        Map<Integer, List<MoveLearnt>> movesets = romHandler.getMovesLearnt();
        List<Move> allMoves = romHandler.getMoves();
        for (Integer pkmn : movesets.keySet()) {
            List<MoveLearnt> moves = movesets.get(pkmn);

            // Build up a list of damaging moves and their positions
            List<Integer> damagingMoveIndices = new ArrayList<>();
            List<Move> damagingMoves = new ArrayList<>();
            for (int i = 0; i < moves.size(); i++) {
                if (moves.get(i).level == 0) continue; // Don't reorder evolution move
                Move mv = allMoves.get(moves.get(i).move);
                if (mv.power > 1) {
                    // considered a damaging move for this purpose
                    damagingMoveIndices.add(i);
                    damagingMoves.add(mv);
                }
            }

            // Ties should be sorted randomly, so shuffle the list first.
            Collections.shuffle(damagingMoves, random);

            // Sort the damaging moves by power
            damagingMoves.sort(Comparator.comparingDouble(m -> m.power * m.hitCount));

            // Reassign damaging moves in the ordered positions
            for (int i = 0; i < damagingMoves.size(); i++) {
                moves.get(damagingMoveIndices.get(i)).move = damagingMoves.get(i).number;
            }
        }

        // Done, save
        romHandler.setMovesLearnt(movesets);
        changesMade = true;
    }

    public void metronomeOnlyMode() {
        // TODO: kind of weird place for this to be in, since it affects more than just the Pokemon movesets

        // movesets
        Map<Integer, List<MoveLearnt>> movesets = romHandler.getMovesLearnt();

        MoveLearnt metronomeML = new MoveLearnt(MoveIDs.metronome, 1);

        for (List<MoveLearnt> ms : movesets.values()) {
            if (ms != null && !ms.isEmpty()) {
                ms.clear();
                ms.add(metronomeML);
            }
        }

        romHandler.setMovesLearnt(movesets);

        // trainers
        // run this to remove all custom non-Metronome moves
        List<Trainer> trainers = romHandler.getTrainers();

        for (Trainer t : trainers) {
            for (TrainerPokemon tpk : t.getPokemon()) {
                tpk.setResetMoves(true);
            }
        }

        // tms
        List<Integer> tmMoves = romHandler.getTMMoves();

        Collections.fill(tmMoves, MoveIDs.metronome);

        romHandler.setTMMoves(tmMoves);

        // movetutors
        if (romHandler.hasMoveTutors()) {
            List<Integer> mtMoves = romHandler.getMoveTutorMoves();

            Collections.fill(mtMoves, MoveIDs.metronome);

            romHandler.setMoveTutorMoves(mtMoves);
        }

        // move tweaks
        List<Move> moveData = romHandler.getMoves();

        Move metronome = moveData.get(MoveIDs.metronome);

        metronome.pp = 40;

        List<Integer> hms = romHandler.getHMMoves();

        for (int hm : hms) {
            Move thisHM = moveData.get(hm);
            thisHM.pp = 0;
        }
    }
}
