package com.deeme.modules;

import com.deeme.types.SharedFunctions;
import com.deeme.types.ShipAttacker;
import com.deeme.types.VerifierChecker;
import com.deeme.types.backpage.Utils;
import com.deeme.types.config.SentinelConfig;
import com.deeme.shared.configchanger.ExtraCChangerLogic;
import com.deeme.shared.movement.ExtraMovementLogic;
import com.github.manolo8.darkbot.Main;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.types.NpcFlag;
import eu.darkbot.api.config.types.ShipMode;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.FeatureInfo;
import eu.darkbot.api.extensions.Module;
import eu.darkbot.api.extensions.InstructionProvider;
import eu.darkbot.api.game.entities.Entity;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.game.items.SelectableItem.Formation;
import eu.darkbot.api.game.items.SelectableItem.Special;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Locatable;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.game.other.Lockable;
import eu.darkbot.api.game.other.Movable;
import eu.darkbot.api.managers.AttackAPI;
import eu.darkbot.api.managers.AuthAPI;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.ConfigAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.ExtensionsAPI;
import eu.darkbot.api.managers.GroupAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.MovementAPI;
import eu.darkbot.api.managers.PetAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.api.utils.Inject;
import eu.darkbot.shared.modules.CollectorModule;
import eu.darkbot.shared.modules.MapModule;
import eu.darkbot.shared.utils.SafetyFinder;

import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

import javax.swing.JComponent;
import javax.swing.JLabel;

@Feature(name = "Sentinel", description = "Follow the main ship or the group leader and do the same")
public class SentinelModule implements Module, Configurable<SentinelConfig>, InstructionProvider {
    private PluginAPI api;
    private HeroAPI heroapi;
    private BotAPI bot;
    private MovementAPI movement;
    private AttackAPI attacker;
    private StarSystemAPI starSystem;
    private PetAPI pet;
    private GroupAPI group;
    private ConfigSetting<Integer> workingMap;
    private ConfigSetting<Integer> maxCircleIterations;
    private ConfigSetting<ShipMode> configRun;
    private ConfigSetting<ShipMode> configRoam;
    private ConfigSetting<Boolean> runConfigInCircle;

    private Collection<? extends Portal> portals;
    private Collection<? extends Player> players;
    private Collection<? extends Npc> npcs;

    private SentinelConfig sConfig;
    private ExtraMovementLogic extraMovementLogic;
    private ExtraCChangerLogic extraConfigChangerLogic;
    private Player sentinel;
    private Main main;
    private SafetyFinder safety;
    private State currentStatus;
    private ShipAttacker shipAttacker;
    private CollectorModule collectorModule;
    private boolean isNpc = false;
    private boolean backwards = false;
    private int masterID = 0;
    private long maximumWaitingTime = 0;
    private int lastMap = 0;
    private int groupLeaderID = 0;

    private long randomWaitTime = 0;
    private Random rnd;
    private Entity oldTarget;

    private Location lastSentinelLocation = null;

    private static final double MAX_DISTANCE_LIMIT = 10000;
    private static final int MIN_SENTINEL_HEALTH = 1000;
    private static final double SPEED_MULTIPLIER = 0.625;
    private static final double ANGLE_ADJUSTMENT = 0.3;
    private static final double DISTANCE_INCREMENT = 2.0;

    private JLabel label = new JLabel("<html><b>Sentinel Module</b> <br>" +
            "It's important that the main ship is in a group <br>" +
            "Following priority: Master ID > Tag > Group Leader <br> " +
            "If a \"Sentinel Tag\" is not defined, it will follow the group leader </html>");

    private enum State {
        INIT("Init"),
        WAIT("Waiting for group invitation"),
        WAIT_GROUP_LOADING("Waiting while loading the group"),
        TRAVELLING_TO_MASTER("Travelling to the master's map"),
        FOLLOWING_MASTER("Following the master"),
        HELPING_MASTER("Helping the master"),
        TRAVELING_TO_WORKING_MAP("Travelling to the working map to wait");

        private final String message;

        State(String message) {
            this.message = message;
        }
    }

    @Override
    public void setConfig(ConfigSetting<SentinelConfig> arg0) {
        this.sConfig = arg0.getValue();
        setup();
    }

    public SentinelModule(Main main, PluginAPI api) {
        this(main, api, api.requireAPI(AuthAPI.class), api.requireInstance(SafetyFinder.class));
    }

    @Inject
    public SentinelModule(Main main, PluginAPI api, AuthAPI auth, SafetyFinder safety) {
        if (!Arrays.equals(VerifierChecker.class.getSigners(), getClass().getSigners())) {
            throw new SecurityException();
        }

        VerifierChecker.requireAuthenticity(auth);

        ExtensionsAPI extensionsAPI = api.requireAPI(ExtensionsAPI.class);
        FeatureInfo<?> feature = extensionsAPI.getFeatureInfo(this.getClass());
        Utils.discordCheck(feature, auth.getAuthId());
        Utils.showDonateDialog(feature, auth.getAuthId());

        this.main = main;
        this.currentStatus = State.INIT;

        this.api = api;
        this.bot = api.requireAPI(BotAPI.class);
        this.heroapi = api.requireAPI(HeroAPI.class);
        this.movement = api.requireAPI(MovementAPI.class);
        this.attacker = api.requireAPI(AttackAPI.class);
        this.starSystem = api.requireAPI(StarSystemAPI.class);
        this.pet = api.requireAPI(PetAPI.class);
        this.group = api.requireAPI(GroupAPI.class);
        this.safety = safety;

        EntitiesAPI entities = api.requireAPI(EntitiesAPI.class);
        this.portals = entities.getPortals();
        this.players = entities.getPlayers();
        this.npcs = entities.getNpcs();

        ConfigAPI configApi = api.requireAPI(ConfigAPI.class);
        this.collectorModule = new CollectorModule(api);

        this.workingMap = configApi.requireConfig("general.working_map");
        this.maxCircleIterations = configApi.requireConfig("loot.max_circle_iterations");
        this.runConfigInCircle = configApi.requireConfig("loot.run_config_in_circle");
        this.configRun = configApi.requireConfig("general.run");
        this.configRoam = configApi.requireConfig("general.roam");
        this.rnd = new Random();
        this.oldTarget = null;

        setup();
    }

    private void setup() {
        if (api == null || sConfig == null) {
            return;
        }

        this.shipAttacker = new ShipAttacker(api, sConfig.ammoConfig, sConfig.humanizer);
        this.extraMovementLogic = new ExtraMovementLogic(api, sConfig.movementConfig);
        this.extraConfigChangerLogic = new ExtraCChangerLogic(api, sConfig.extraConfigChangerConfig);
    }

    @Override
    public boolean canRefresh() {
        if (!sConfig.collectorActive || collectorModule.canRefresh()) {
            return safety.tick();
        }
        return false;
    }

    @Override
    public String getStatus() {
        return safety.state() != SafetyFinder.Escaping.NONE ? safety.status()
                : currentStatus.message + " | " + getAttackStatus();
    }

    private String getAttackStatus() {
        return isNpc ? getNpcStatus() : getPlayerStatus();
    }

    private String getNpcStatus() {
        return "NPC | " + attacker.getStatus();
    }

    private String getPlayerStatus() {
        return "Player | " + shipAttacker.getStatus();
    }

    @Override
    public JComponent instructionsComponent() {
        return label;
    }

    @Override
    public void onTickModule() {
        pet.setEnabled(true);
        if ((sConfig.ignoreSecurity || safety.tick()) && (!sConfig.collectorActive || collectorModule.canRefresh())) {
            if (hasSentinel()) {
                updateLastMap();
                if (isAttacking()) {
                    currentStatus = State.HELPING_MASTER;
                    attackLogic();
                } else if (sentinel.isValid()) {
                    currentStatus = State.FOLLOWING_MASTER;
                    followSameMapLogic();
                } else {
                    sentinel = null;
                }
            } else if (!followByPortals()) {
                noSentinelLogic();
            }
        }
    }

    private void attackLogic() {
        if (isNpc) {
            npcMove();
            if (sConfig.specialItems.npcEnabled) {
                useSpecialItems();
            }
        } else {
            shipAttacker.tryAttackOrFix();
            extraMovementLogic.tick();
            useSpecialItems();
        }

        if (sConfig.aggressiveFollow
                && heroapi.distanceTo(sentinel.getLocationInfo().getCurrent()) > sConfig.rangeToLider) {
            moveToMaster();
        }
    }

    private void followSameMapLogic() {
        setMode(configRoam.getValue());
        if (heroapi.distanceTo(sentinel.getLocationInfo().getCurrent()) > sConfig.rangeToLider) {
            moveToMaster();
        } else if (sConfig.collectorActive) {
            collectorModule.findBox();
            if (collectorModule.currentBox != null && sentinel.getLocationInfo()
                    .distanceTo(collectorModule.currentBox) < sConfig.rangeToLider) {
                collectorModule.tryCollectNearestBox();
            }
        }
    }

    private void noSentinelLogic() {
        groupLeaderID = 0;
        if (group.hasGroup()) {
            goToGroup();
        } else {
            if (lastMap != heroapi.getMap().getId()) {
                maximumWaitingTime = System.currentTimeMillis() + 60000;
            }
            acceptGroupSentinel();
            if (lastMap != heroapi.getMap().getId() && currentStatus != State.WAIT_GROUP_LOADING
                    && currentStatus != State.WAIT) {
                currentStatus = State.WAIT_GROUP_LOADING;
                maximumWaitingTime = System.currentTimeMillis() + 60000;
            } else if (maximumWaitingTime <= System.currentTimeMillis()) {
                currentStatus = State.WAIT;
                GameMap map = getWorkingMap();
                if (map != null && !portals.isEmpty() && map != starSystem.getCurrentMap()) {
                    currentStatus = State.TRAVELING_TO_WORKING_MAP;
                    this.bot.setModule(new MapModule(api, true))
                            .setTarget(map);
                } else if (sConfig.collectorActive) {
                    collectorModule.onTickModule();
                }
            }
        }
    }

    private void updateLastMap() {
        lastMap = heroapi.getMap() != null ? heroapi.getMap().getId() : 0;
    }

    private void useSpecialItems() {
        shipAttacker.useKeyWithConditions(sConfig.specialItems.ish, Special.ISH_01);
        shipAttacker.useKeyWithConditions(sConfig.specialItems.smb, Special.SMB_01);
        shipAttacker.useKeyWithConditions(sConfig.specialItems.pem, Special.EMP_01);
    }

    private boolean followByPortals() {
        if (!sConfig.followByPortals || lastSentinelLocation == null) {
            return false;
        }

        Portal portal = getNearestPortal(lastSentinelLocation);
        if (portal != null) {
            if (group.hasGroup() && masterID != 0) {
                eu.darkbot.api.game.group.GroupMember member = group.getMember(masterID);
                if (member != null && !member.isDead()) {
                    return false;
                }
            }
            portal.getTargetMap().ifPresentOrElse(
                    m -> this.bot.setModule(api.requireInstance(MapModule.class)).setTarget(m),
                    () -> lastSentinelLocation = null);
            return true;
        } else {
            lastSentinelLocation = null;
        }
        return false;
    }

    private void moveToMaster() {
        if (sentinel != null) {
            if (sConfig.goToMasterDestination) {
                sentinel.getDestination().ifPresentOrElse(d -> movement.moveTo(d),
                        () -> movement.moveTo(sentinel.getLocationInfo().getCurrent()));
            } else {
                movement.moveTo(sentinel.getLocationInfo().getCurrent());
            }
        }
    }

    private GameMap getWorkingMap() {
        return starSystem.findMap(workingMap.getValue()).orElse(null);
    }

    private boolean isAttacking() {
        if (this.randomWaitTime > System.currentTimeMillis()) {
            return this.oldTarget != null;
        }

        if (sConfig.humanizer.addRandomTime) {
            this.randomWaitTime = System.currentTimeMillis() + (rnd.nextInt(sConfig.humanizer.maxRandomTime) * 1000);
        } else {
            this.randomWaitTime = System.currentTimeMillis() + 1000;
        }

        Entity target = getSentinelTarget();

        if (target == null) {
            target = getTargetFromAttackerModules();
        }

        if (target != null && (!target.isValid() || target.getId() == heroapi.getId() || isOurPet(target))) {
            target = null;
        }

        if (target == null && sConfig.autoAttack.autoAttackEnemies) {
            target = this.shipAttacker.getEnemy(sConfig.autoAttack.rangeForEnemies);
        }
        if (target == null && sConfig.autoAttack.defendFromNPCs) {
            target = SharedFunctions.getAttacker(heroapi, npcs, heroapi);
        }

        changeTarget(target);

        this.oldTarget = target;

        return target != null;
    }

    private void changeTarget(Entity target) {
        if (target == null) {
            return;
        }

        setMode(extraConfigChangerLogic.getShipMode());
        if (target instanceof Npc) {
            this.isNpc = true;
            attacker.setTarget((Npc) target);
            attacker.tryLockAndAttack();
        } else {
            this.isNpc = false;
            shipAttacker.setTarget((Ship) target);
            shipAttacker.tryLockAndAttack();
        }
    }

    private boolean isOurPet(Entity target) {
        if (target == null) {
            return false;
        }

        return heroapi.getPet().isPresent() && heroapi.getPet().get().getId() == target.getId();
    }

    private Entity getTargetFromAttackerModules() {
        if (shipAttacker.getTarget() != null) {
            return shipAttacker.getTarget();
        } else if (attacker.getTarget() != null) {
            return attacker.getTarget();
        }

        return null;
    }

    private Entity getSentinelTarget() {
        Entity target = null;
        if (sentinel.getTarget() != null) {
            if (this.oldTarget != null && this.oldTarget.getId() == sentinel.getTarget().getId()) {
                return this.oldTarget;
            }

            if (sConfig.autoAttack.helpAttackPlayers || sConfig.autoAttack.helpAttackEnemyPlayers) {
                target = players.stream()
                        .filter(s -> (sentinel.getTarget().getId() == s.getId())
                                && (sConfig.autoAttack.helpAttackPlayers || s.getEntityInfo().isEnemy()))
                        .findAny().orElse(null);
            }
            if (target == null && sConfig.autoAttack.helpAttackNPCs) {
                target = npcs.stream()
                        .filter(s -> sentinel.getTarget().getId() == s.getId())
                        .findAny().orElse(null);
                if (target != null) {
                    isNpc = true;
                }
            }
        }
        return target;
    }

    private boolean isSentinelValid() {
        return sentinel != null && sentinel.isValid()
                && sentinel.getHealth() != null && sentinel.getHealth().getHp() > MIN_SENTINEL_HEALTH;
    }

    private boolean hasSentinel() {
        if (players == null || players.isEmpty()) {
            return false;
        }

        if (isSentinelValid()) {
            sentinel = players.stream().filter(ship -> ship.getId() == sentinel.getId()).findFirst().orElse(null);
        }

        if (sentinel == null) {
            sentinel = players.stream().filter(this::isPotentialSentinel).findFirst().orElse(null);
        }

        if (sentinel != null) {
            lastSentinelLocation = sentinel.getLocationInfo().getCurrent();
        }

        return sentinel != null;
    }

    private boolean isPotentialSentinel(Player ship) {
        return ship != null && ship.isValid() && ship.getId() != heroapi.getId() &&
                (ship.getId() == masterID || ship.getId() == sConfig.MASTER_ID ||
                        isTaggedSentinel(ship) || (sConfig.followGroupLeader && ship.getId() == groupLeaderID));
    }

    private boolean isTaggedSentinel(Player ship) {
        return ship != null && sConfig.SENTINEL_TAG != null &&
                main.config.PLAYER_INFOS != null &&
                sConfig.SENTINEL_TAG.hasTag(main.config.PLAYER_INFOS.get(ship.getId()));
    }

    private void goToGroup() {
        group.getMembers().stream()
                .filter(this::isGroupMemberValid)
                .forEach(m -> {
                    if (m.isLeader()) {
                        groupLeaderID = m.getId();
                    }
                    masterID = m.getId();

                    if (heroapi.getMap() != null && m.getMapId() == heroapi.getMap().getId()) {
                        lastSentinelLocation = m.getLocation();
                    }
                    if (m.getMapId() == heroapi.getMap().getId()) {
                        movement.moveTo(m.getLocation());
                        currentStatus = State.FOLLOWING_MASTER;
                    } else {
                        GameMap map = starSystem.findMap(m.getMapId()).orElse(null);
                        if (map != null) {
                            this.bot.setModule(api.requireInstance(MapModule.class))
                                    .setTarget(map);
                            currentStatus = State.TRAVELLING_TO_MASTER;
                        }
                    }
                });
    }

    private boolean isGroupMemberValid(eu.darkbot.api.game.group.GroupMember m) {
        return !m.isDead() && m.getId() != heroapi.getId()
                && ((sConfig.MASTER_ID != 0 && m.getId() == sConfig.MASTER_ID) ||
                        (sConfig.SENTINEL_TAG != null
                                && sConfig.SENTINEL_TAG.has(main.config.PLAYER_INFOS.get(m.getId())))
                        ||
                        (sConfig.followGroupLeader && m.isLeader()));
    }

    private void acceptGroupSentinel() {
        if (group.getInvites().isEmpty()) {
            return;
        }

        main.guiManager.group.invites.stream()
                .filter(in -> in.isIncoming() && (sConfig.SENTINEL_TAG == null ||
                        sConfig.SENTINEL_TAG.has(main.config.PLAYER_INFOS.get(in.getInviter().getId()))))
                .findFirst()
                .ifPresent(main.guiManager.group::acceptInvite);
    }

    private void setMode(ShipMode config) {
        if (sConfig.copyMasterFormation && sentinel != null) {
            Formation sentinelFormation = sentinel.getFormation();

            if (!heroapi.isInFormation(sentinelFormation)) {
                if (sentinelFormation != null) {
                    shipAttacker.setMode(config, sentinelFormation);
                } else {
                    heroapi.setMode(config);
                }
            }
        } else {
            heroapi.setMode(config);
        }
    }

    private double getRadius(Lockable target) {
        if (!(target instanceof Npc)) {
            return 550.0 + this.rnd.nextInt(60);
        }
        return attacker.modifyRadius(((Npc) target).getInfo().getRadius());
    }

    private void npcMove() {
        if (!attacker.hasTarget()) {
            return;
        }
        Lockable target = attacker.getTarget();

        Location direction = movement.getDestination();
        Location targetLoc = target.getLocationInfo().destinationInTime(400);

        double distance = heroapi.distanceTo(attacker.getTarget());
        double angle = targetLoc.angleTo(heroapi);
        double radius = getRadius(target);
        double speed = target instanceof Movable ? ((Movable) target).getSpeed() : 0;
        boolean noCircle = attacker.hasExtraFlag(NpcFlag.NO_CIRCLE);

        double angleDiff;
        if (noCircle) {
            double dist = targetLoc.distanceTo(direction);
            double minRad = Math.max(0, Math.min(radius - 200, radius * 0.5));
            if (dist <= radius && dist >= minRad) {
                setNPCConfig(direction);
                return;
            }
            distance = minRad + Math.random() * (radius - minRad - 10);
            angleDiff = (Math.random() * 0.1) - 0.05;
        } else {
            double maxRadFix = radius / 2;
            double radiusFix = (int) Math.max(Math.min(radius - distance, maxRadFix), -maxRadFix);
            distance = (radius += radiusFix);
            angleDiff = Math.max((heroapi.getSpeed() * SPEED_MULTIPLIER) + (Math.max(200, speed) * SPEED_MULTIPLIER)
                    - heroapi.distanceTo(Location.of(targetLoc, angle, radius)), 0) / radius;
        }
        direction = getBestDir(targetLoc, angle, angleDiff, distance);

        while (!movement.canMove(direction) && distance < MAX_DISTANCE_LIMIT) {
            direction.toAngle(targetLoc, angle += backwards ? -ANGLE_ADJUSTMENT : ANGLE_ADJUSTMENT,
                    distance += DISTANCE_INCREMENT);
        }

        if (distance >= MAX_DISTANCE_LIMIT) {
            direction.toAngle(targetLoc, angle, 500);
        }

        setNPCConfig(direction);

        movement.moveTo(direction);
    }

    private Location getBestDir(Locatable targetLoc, double angle, double angleDiff, double distance) {
        int maxCircleIterationsValue = this.maxCircleIterations.getValue();
        int iteration = 1;
        double forwardScore = 0;
        double backScore = 0;
        do {
            forwardScore += score(Locatable.of(targetLoc, angle + (angleDiff * iteration), distance));
            backScore += score(Locatable.of(targetLoc, angle - (angleDiff * iteration), distance));
            if (forwardScore < 0 != backScore < 0 || Math.abs(forwardScore - backScore) > 300)
                break;
        } while (iteration++ < maxCircleIterationsValue);

        if (iteration <= maxCircleIterationsValue)
            backwards = backScore > forwardScore;
        return Location.of(targetLoc, angle + angleDiff * (backwards ? -1 : 1), distance);
    }

    private double score(Locatable loc) {
        return (movement.canMove(loc) ? 0 : -1000) - npcs.stream()
                .filter(n -> attacker.getTarget() != n)
                .mapToDouble(n -> Math.max(0, n.getInfo().getRadius() - n.distanceTo(loc)))
                .sum();
    }

    private Portal getNearestPortal(Location loc) {
        if (loc == null) {
            return null;
        }
        return portals.stream().filter(p -> p.distanceTo(loc) < 500).findFirst().orElse(null);
    }

    private void setNPCConfig(Location direction) {
        Npc target = attacker.getTargetAs(Npc.class);
        if (target == null || !target.isValid()) {
            setMode(configRoam.getValue());
        } else if (Boolean.TRUE.equals(runConfigInCircle.getValue()
                && target.getHealth().hpPercent() < 0.25)
                && heroapi.getLocationInfo().getCurrent().distanceTo(direction) > target.getInfo().getRadius() * 2) {
            setMode(configRun.getValue());
        } else if (heroapi.getLocationInfo().getCurrent().distanceTo(direction) > target.getInfo().getRadius() * 3) {
            setMode(configRoam.getValue());
        } else {
            setMode(extraConfigChangerLogic.getShipMode());
        }
    }
}
