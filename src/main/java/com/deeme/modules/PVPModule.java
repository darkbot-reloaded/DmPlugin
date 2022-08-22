package com.deeme.modules;

import com.deeme.types.SharedFunctions;
import com.deeme.types.ShipAttacker;
import com.deeme.types.VerifierChecker;
import com.deeme.types.backpage.Utils;
import com.deeme.types.config.PVPConfig;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.types.ShipMode;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Module;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.game.entities.Entity;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.game.items.SelectableItem.Special;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.managers.AuthAPI;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.ConfigAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.MovementAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.api.utils.Inject;
import eu.darkbot.shared.modules.CollectorModule;
import eu.darkbot.shared.modules.MapModule;
import eu.darkbot.shared.utils.SafetyFinder;

import java.util.Arrays;
import java.util.Collection;

@Feature(name = "PVP Module", description = "It is limited so as not to spoil the game")
public class PVPModule implements Module, Configurable<PVPConfig> {
    private PVPConfig pvpConfig;
    public Ship target;
    private ShipAttacker shipAttacker;

    protected final PluginAPI api;
    protected final HeroAPI heroapi;
    protected final MovementAPI movement;
    protected final StarSystemAPI starSystem;
    protected final BotAPI bot;

    protected final Collection<? extends Portal> portals;
    protected final Collection<? extends Player> players;

    protected final ConfigSetting<Integer> workingMap;
    protected final ConfigSetting<ShipMode> configOffensive;
    protected final ConfigSetting<ShipMode> configRun;

    private boolean attackConfigLost = false;
    protected boolean firstAttack;
    protected long isAttacking;
    protected int fixedTimes;
    protected Character lastShot;
    protected long laserTime;
    protected long fixTimes;
    protected long clickDelay;

    private SafetyFinder safety;
    private double lastDistanceTarget = 1000;
    protected CollectorModule collectorModule;

    public PVPModule(PluginAPI api) {
        this(api, api.requireAPI(HeroAPI.class),
                api.requireAPI(AuthAPI.class),
                api.requireAPI(ConfigAPI.class),
                api.requireAPI(MovementAPI.class),
                api.requireInstance(SafetyFinder.class));
    }

    @Inject
    public PVPModule(PluginAPI api, HeroAPI hero, AuthAPI auth, ConfigAPI configApi, MovementAPI movement,
            SafetyFinder safety) {
        if (!Arrays.equals(VerifierChecker.class.getSigners(), getClass().getSigners()))
            throw new SecurityException();
        VerifierChecker.checkAuthenticity(auth);

        if (!Utils.discordCheck(auth.getAuthId())) {
            Utils.showDiscordDialog();
            throw new UnsupportedOperationException("To use this option you need to be on my discord");
        }

        this.api = api;
        this.heroapi = hero;
        this.safety = safety;
        this.movement = movement;
        this.starSystem = api.getAPI(StarSystemAPI.class);
        this.bot = api.getAPI(BotAPI.class);
        this.workingMap = configApi.requireConfig("general.working_map");
        this.configOffensive = configApi.requireConfig("general.offensive");
        this.configRun = configApi.requireConfig("general.run");

        EntitiesAPI entities = api.getAPI(EntitiesAPI.class);
        this.portals = entities.getPortals();
        this.players = entities.getPlayers();

        this.collectorModule = new CollectorModule(api);

        setup();
    }

    @Override
    public String getStatus() {
        return safety.state() != SafetyFinder.Escaping.NONE ? safety.status()
                : target != null ? shipAttacker.getStatus() : collectorModule.getStatus();
    }

    @Override
    public void setConfig(ConfigSetting<PVPConfig> arg0) {
        this.pvpConfig = arg0.getValue();
        setup();
    }

    @Override
    public boolean canRefresh() {
        if (pvpConfig.move && target == null && collectorModule.canRefresh()) {
            return safety.tick();
        }
        return false;
    }

    private void setup() {
        if (api == null || pvpConfig == null)
            return;

        this.shipAttacker = new ShipAttacker(api, pvpConfig.SAB, pvpConfig.useRSB);
    }

    @Override
    public void onTickModule() {
        if (!pvpConfig.move || safety.tick()) {
            if (getTarget()) {
                if (pvpConfig.changeConfig) {
                    setConfigToUse();
                }

                shipAttacker.doKillTargetTick();

                if (pvpConfig.useBestRocket) {
                    shipAttacker.changeRocket();
                }

                if (pvpConfig.useAbility) {
                    shipAttacker.useHability();
                }

                if (heroapi.getLocationInfo().distanceTo(target) < 575) {
                    shipAttacker.useKeyWithConditions(pvpConfig.ability, null);
                }

                shipAttacker.useKeyWithConditions(pvpConfig.ISH, Special.ISH_01);
                shipAttacker.useKeyWithConditions(pvpConfig.SMB, Special.SMB_01);
                shipAttacker.useKeyWithConditions(pvpConfig.PEM, Special.EMP_01);
                shipAttacker.useKeyWithConditions(pvpConfig.otherKey, null);

                shipAttacker.tryAttackOrFix();

                if (pvpConfig.move) {
                    shipAttacker.vsMove();
                }
            } else {
                attackConfigLost = false;
                target = null;
                shipAttacker.resetDefenseData();
                if (pvpConfig.move) {
                    if (pvpConfig.changeConfig) {
                        heroapi.setRoamMode();
                    }
                    if (checkMap()) {
                        if (pvpConfig.collectorActive) {
                            collectorModule.onTickModule();
                        } else if (!movement.isMoving() || movement.isOutOfMap()) {
                            movement.moveRandom();
                        }
                    }
                }
            }
        }
    }

    private boolean checkMap() {
        GameMap map = starSystem.getOrCreateMapById(workingMap.getValue());
        if (!portals.isEmpty() && map != starSystem.getCurrentMap()) {
            this.bot.setModule(api.requireInstance(MapModule.class)).setTarget(map);
            return false;
        }
        return true;
    }

    private boolean getTarget() {
        if ((target != null && target.isValid() && target.getLocationInfo().distanceTo(heroapi) < 2000)
                || isUnderAttack()) {
            return true;
        }

        target = shipAttacker.getEnemy(pvpConfig.rangeForEnemies);

        shipAttacker.setTarget(target);

        return target != null;
    }

    private void setConfigToUse() {
        if (attackConfigLost || heroapi.getHealth().shieldPercent() < 0.1 && heroapi.getHealth().hpPercent() < 0.3) {
            attackConfigLost = true;
            shipAttacker.setMode(configRun.getValue(), pvpConfig.useBestFormation);
        } else if (pvpConfig.useRunConfig && target != null) {
            double distance = heroapi.getLocationInfo().distanceTo(target);
            if (distance > 400 && distance > lastDistanceTarget && target.getSpeed() > heroapi.getSpeed()) {
                shipAttacker.setMode(configRun.getValue(), pvpConfig.useBestFormation);
                lastDistanceTarget = distance;
            } else {
                shipAttacker.setMode(configOffensive.getValue(), pvpConfig.useBestFormation);
            }
        } else {
            shipAttacker.setMode(configOffensive.getValue(), pvpConfig.useBestFormation);
        }
    }

    private boolean isUnderAttack() {
        Entity targetAttacker = SharedFunctions.getAttacker(heroapi, players, heroapi);
        if (targetAttacker != null) {
            shipAttacker.setTarget((Ship) targetAttacker);
            return true;
        }
        shipAttacker.resetDefenseData();
        attackConfigLost = false;
        target = null;

        return false;
    }
}
