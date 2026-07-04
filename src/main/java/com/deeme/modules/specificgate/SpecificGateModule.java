package com.deeme.modules.specificgate;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.InstructionProvider;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.galaxy.GalaxyGate;
import eu.darkbot.api.game.galaxy.GateInfo;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Lockable;
import eu.darkbot.api.managers.GalaxySpinnerAPI;
import eu.darkbot.shared.modules.CollectorModule;
import eu.darkbot.shared.modules.LootModule;
import eu.darkbot.shared.modules.MapModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Free, self-authored galaxy-gate module with per-gate profiles, an optional gate builder,
 * and an ordered multi-gate cycle.
 *
 * Two run modes:
 *  - Single: pick one gate in "Gate to run".
 *  - Cycle: enable gates individually; the module builds+runs each enabled gate in enum order
 *    (Alpha -> Beta -> ... -> Kuiper) and advances to the next once the current one is finished.
 *
 * When "Build the gate" is on it spins gg-energy into the active gate and places it once complete,
 * then travels there, clears the NPC waves using the shared {@link LootModule} combat, optionally
 * collects the materials, and advances through the wave portals. Each gate has its own profile.
 *
 * No advertising link, no donor check — original code using only the public DarkBot API.
 */
@Feature(name = "Specific Gate Runner",
        description = "Free per-gate GG module: build (spin + place) and run one gate, or cycle a list of enabled gates.")
public class SpecificGateModule extends LootModule
        implements Configurable<SpecificGateModule.Config>, InstructionProvider {

    private final CollectorModule collector;
    private final GalaxySpinnerAPI spinner;
    private Config config = new Config();

    private String buildStatus = "";
    private GalaxyGate cyclingGate; // current gate when cycling
    private boolean enteredCurrent; // have we been inside the current cycle gate yet

    public SpecificGateModule(PluginAPI api) {
        super(api);
        this.collector = api.requireInstance(CollectorModule.class);
        this.spinner = api.requireAPI(GalaxySpinnerAPI.class);
    }

    @Override
    public void setConfig(ConfigSetting<Config> config) {
        this.config = config.getValue();
    }

    @Override
    public String instructions() {
        return "Specific Gate Runner\n"
                + "Single mode: pick the gate in 'Gate to run'.\n"
                + "Cycle mode: turn on 'Cycle enabled gates' and tick 'Enable in cycle' on each gate you want;\n"
                + "it runs them in order (Alpha -> ... -> Kuiper) and moves on when each is finished.\n"
                + "(Optional) 'Build the gate' spins energy into the active gate and places it automatically.";
    }

    /** All gates flagged 'Enable in cycle', in enum order. */
    private List<GalaxyGate> enabledGates() {
        List<GalaxyGate> list = new ArrayList<>();
        for (GalaxyGate g : GalaxyGate.values())
            if (config.forGate(g).enabled) list.add(g);
        return list;
    }

    /** The gate we should be working on right now (single selection, or current cycle gate). */
    private GalaxyGate selectedGate() {
        if (!config.cycleGates) return config.gate;
        List<GalaxyGate> enabled = enabledGates();
        if (enabled.isEmpty()) return config.gate;
        if (cyclingGate == null || !enabled.contains(cyclingGate)) {
            cyclingGate = enabled.get(0);
            enteredCurrent = false;
        }
        return cyclingGate;
    }

    /** Detect when the active cycle gate is finished and advance to the next enabled one. */
    private void updateCycle() {
        if (!config.cycleGates) return;
        GalaxyGate g = selectedGate();
        GameMap current = starSystem.getCurrentMap();
        if (g.isInGate(current)) {
            enteredCurrent = true; // we're running it now
            return;
        }
        // Not inside the gate: if we already ran it and it's no longer placed, move on.
        GateInfo info = spinner.getGalaxyInfo().getGateInfo(g);
        boolean onMap = info != null && info.isOnMap();
        if (enteredCurrent && !onMap) {
            List<GalaxyGate> enabled = enabledGates();
            if (!enabled.isEmpty()) {
                int idx = enabled.indexOf(g);
                cyclingGate = enabled.get((idx + 1) % enabled.size());
                enteredCurrent = false;
            }
        }
    }

    private GateProfile profile() {
        return config.forGate(selectedGate());
    }

    /** The in-game map for the active gate, if it's currently placed/available. */
    private GameMap resolveGateMap() {
        return selectedGate().getMaps(starSystem).stream().findFirst().orElse(null);
    }

    /**
     * Spin/place the active gate. Returns true once the gate is on the map (ready to enter),
     * false while it still needs building. Only does anything when 'Build the gate' is enabled.
     */
    private boolean ensureGateBuilt() {
        GalaxyGate gate = selectedGate();
        spinner.updateGalaxyInfos(60_000);
        GateInfo info = spinner.getGalaxyInfo().getGateInfo(gate);
        if (info == null) {
            buildStatus = "waiting for galaxy info";
            return false;
        }
        if (info.isOnMap()) {
            buildStatus = "";
            if (config.buyLives && info.getLivesLeft() <= 0) spinner.buyLife(gate, 1500);
            return true;
        }
        if (info.canBePlaced()) {
            buildStatus = "placing gate";
            spinner.placeGate(gate, 1500);
            return false;
        }
        if (spinner.getGalaxyInfo().getFreeEnergy() > 0) {
            buildStatus = "spinning " + info.getCurrentParts() + "/" + info.getTotalParts();
            spinner.spinGate(gate, config.useMultiplierAt, config.spinAmount, 1500);
        } else {
            buildStatus = "no energy (" + info.getCurrentParts() + "/" + info.getTotalParts() + ")";
        }
        return false;
    }

    /**
     * Route to the active gate. Returns true (let combat run) when we're inside a gate,
     * false while building or travelling.
     */
    @Override
    protected boolean checkMap() {
        GameMap current = starSystem.getCurrentMap();
        if (current.isGG()) return true; // inside a gate: fight

        if (config.buildGate && !ensureGateBuilt()) return false; // build first if requested

        GameMap target = resolveGateMap();
        if (target != null && !portals.isEmpty()) {
            bot.setModule(new MapModule(api, true)).setTarget(target);
            return false;
        }
        return false; // gate not available yet: hold position
    }

    /** Per-gate kiting distance on top of the NPC's normal radius. */
    @Override
    protected double getRadius(Lockable target) {
        return super.getRadius(target) + Math.max(0, profile().extraRange);
    }

    @Override
    public void onTickModule() {
        updateCycle();

        if (!checkDangerousAndCurrentMap()) return;
        pet.setEnabled(true);

        if (findTarget()) {
            moveToAnSafePosition();
            ignoreInvalidTarget();
            attack.tryLockAndAttack();
            return;
        }

        if (profile().collectBoxes && collector.isNotWaiting()) {
            collector.findBox();
            if (collector.tryCollectNearestBox()) return;
        }

        if (npcs.isEmpty() && !portals.isEmpty()) {
            Portal portal = portals.iterator().next();
            movement.moveTo(portal.getLocationInfo().destinationInTime(0));
            return;
        }

        hero.setRoamMode();
        if (hero.distanceTo(movement.getDestination()) < 20 || movement.isOutOfMap()) movement.moveRandom();
    }

    @Override
    public String getStatus() {
        String prefix = config.cycleGates ? "[cycle] " : "";
        if (config.buildGate && !buildStatus.isEmpty()) return prefix + "Building " + selectedGate() + ": " + buildStatus;
        return prefix + selectedGate() + " | " + super.getStatus();
    }

    /** Per-gate tuning. */
    public static class GateProfile {
        @Option("Enable in cycle")
        public boolean enabled = false;

        @Option("Collect materials")
        public boolean collectBoxes = true;

        @Option("Extra attack range")
        @Number(min = 0, max = 2000, step = 50)
        public int extraRange = 0;
    }

    @Configuration("specific_gate")
    public static class Config {
        @Option("Gate to run (single mode)")
        @Dropdown
        public GalaxyGate gate = GalaxyGate.GAMMA;

        @Option("Cycle enabled gates in order")
        public boolean cycleGates = false;

        @Option("Build the gate (spin + place) before running")
        public boolean buildGate = false;

        @Option("Spin amount")
        @Number(min = 1, max = 100, step = 1)
        public int spinAmount = 100;

        @Option("Use multiplier at (x)")
        @Number(min = 2, max = 6, step = 1)
        public int useMultiplierAt = 3;

        @Option("Auto-buy lives when empty")
        public boolean buyLives = false;

        @Option("Alpha profile")   public GateProfile alpha   = new GateProfile();
        @Option("Beta profile")    public GateProfile beta    = new GateProfile();
        @Option("Gamma profile")   public GateProfile gamma   = new GateProfile();
        @Option("Delta profile")   public GateProfile delta   = new GateProfile();
        @Option("Epsilon profile") public GateProfile epsilon = new GateProfile();
        @Option("Zeta profile")    public GateProfile zeta    = new GateProfile();
        @Option("Kappa profile")   public GateProfile kappa   = new GateProfile();
        @Option("Lambda profile")  public GateProfile lambda  = new GateProfile();
        @Option("Kronos profile")  public GateProfile kronos  = new GateProfile();
        @Option("Hades profile")   public GateProfile hades   = new GateProfile();
        @Option("Kuiper profile")  public GateProfile kuiper  = new GateProfile();

        public GateProfile forGate(GalaxyGate g) {
            switch (g) {
                case ALPHA:   return alpha;
                case BETA:    return beta;
                case GAMMA:   return gamma;
                case DELTA:   return delta;
                case EPSILON: return epsilon;
                case ZETA:    return zeta;
                case KAPPA:   return kappa;
                case LAMBDA:  return lambda;
                case KRONOS:  return kronos;
                case HADES:   return hades;
                case KUIPER:  return kuiper;
                default:      return gamma;
            }
        }
    }
}
