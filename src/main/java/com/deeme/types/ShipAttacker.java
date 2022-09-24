package com.deeme.types;

import com.deeme.types.config.Defense;
import com.deeme.types.config.ExtraKeyConditions;
import com.deeme.types.suppliers.AbilitySupplier;
import com.deeme.types.suppliers.DefenseLaserSupplier;
import com.deeme.types.suppliers.FormationSupplier;
import com.github.manolo8.darkbot.Main;
import com.github.manolo8.darkbot.config.Config.Loot.Sab;
import com.github.manolo8.darkbot.core.api.DarkBoatAdapter;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.types.PercentRange;
import eu.darkbot.api.config.types.ShipMode;
import eu.darkbot.api.config.types.ShipMode.ShipModeImpl;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.game.enums.EntityEffect;
import eu.darkbot.api.game.group.GroupMember;
import eu.darkbot.api.game.items.ItemFlag;
import eu.darkbot.api.game.items.SelectableItem;
import eu.darkbot.api.game.items.SelectableItem.Formation;
import eu.darkbot.api.game.items.SelectableItem.Laser;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.managers.ConfigAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.GroupAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.HeroItemsAPI;
import eu.darkbot.api.managers.MovementAPI;

import java.util.Collection;
import java.util.Comparator;
import java.util.Random;

import static com.github.manolo8.darkbot.Main.API;

public class ShipAttacker {
    protected final PluginAPI api;
    protected final HeroAPI heroapi;
    protected final HeroItemsAPI items;
    protected final MovementAPI movement;
    protected final ConfigAPI configAPI;
    protected final GroupAPI group;
    protected final ConfigSetting<PercentRange> repairHpRange;
    protected final ConfigSetting<Character> ammoKey;
    protected final Collection<? extends Player> allShips;
    protected final Collection<? extends Portal> allPortals;
    private DefenseLaserSupplier laserSupplier;
    private FormationSupplier formationSupplier;
    private AbilitySupplier abilitySupplier;

    public Ship target;

    protected long laserTime;
    protected long fixTimes;
    protected long clickDelay;
    protected long keyDelay;
    protected boolean sab;
    private Defense defense = null;
    private Random rnd;

    protected boolean firstAttack;
    protected long isAttacking;
    protected int fixedTimes;
    protected Character lastShot;

    public ShipAttacker(Main main) {
        this(main.pluginAPI.getAPI(PluginAPI.class), main.config.LOOT.SAB, main.config.LOOT.RSB.ENABLED);
    }

    public ShipAttacker(PluginAPI api, Sab sab, boolean rsbEnabled) {
        this.api = api;
        this.heroapi = api.getAPI(HeroAPI.class);
        this.movement = api.getAPI(MovementAPI.class);
        this.configAPI = api.getAPI(ConfigAPI.class);
        this.group = api.getAPI(GroupAPI.class);
        EntitiesAPI entities = api.getAPI(EntitiesAPI.class);
        this.allShips = entities.getPlayers();
        this.allPortals = entities.getPortals();
        this.rnd = new Random();
        this.items = api.getAPI(HeroItemsAPI.class);
        this.laserSupplier = new DefenseLaserSupplier(api, heroapi, items, sab, rsbEnabled);
        this.formationSupplier = new FormationSupplier(heroapi, items);

        this.repairHpRange = configAPI.requireConfig("general.safety.repair_hp_range");
        this.ammoKey = configAPI.requireConfig("loot.ammo_key");

        this.abilitySupplier = new AbilitySupplier(api);
    }

    public ShipAttacker(PluginAPI api, Defense defense) {
        this(api, defense.SAB, defense.useRSB);
        this.defense = defense;
    }

    public String getStatus() {
        return getTarget() != null ? "Killing " + getTarget().getEntityInfo().getUsername() : "Idle";
    }

    public Ship getTarget() {
        if (target != null && target.isValid()) {
            return target;
        } else {
            return null;
        }
    }

    public void tryLockTarget() {
        if (heroapi.getTarget() == target && firstAttack) {
            clickDelay = System.currentTimeMillis();
        }

        fixedTimes = 0;
        laserTime = 0;
        firstAttack = false;
        if (heroapi.getLocationInfo().distanceTo(target) < 800) {
            if (System.currentTimeMillis() - clickDelay > 500) {
                heroapi.setLocalTarget(target);
                target.trySelect(false);
                clickDelay = System.currentTimeMillis();
            }
        } else {
            movement.moveTo(target);
        }
    }

    public void setTarget(Ship target) {
        this.target = target;
    }

    public void tryLockAndAttack() {
        if (target == null) {
            return;
        }
        if (heroapi.isAttacking(target)) {
            tryAttackOrFix();
            return;
        } else {
            tryLockTarget();
        }
    }

    public void tryAttackOrFix() {
        if (System.currentTimeMillis() < laserTime) {
            return;
        }

        if (!firstAttack) {
            firstAttack = true;
            sendAttack(1500, 5000, true);
        } else if (lastShot != getAttackKey()) {
            sendAttack(250, 5000, true);
        } else if (!heroapi.isAttacking(target) || !heroapi.isAiming(target)) {
            sendAttack(1500, 5000, false);
        } else if (target.getHealth().hpDecreasedIn(1500) || target.hasEffect(EntityEffect.NPC_ISH)
                || target.hasEffect(EntityEffect.ISH)
                || heroapi.getLocationInfo().distanceTo(target) > 700) {
            isAttacking = Math.max(isAttacking, System.currentTimeMillis() + 2000);
        } else if (System.currentTimeMillis() > isAttacking) {
            sendAttack(1500, ++fixedTimes * 3000L, false);
        }
    }

    private void sendAttack(long minWait, long bugTime, boolean normal) {
        laserTime = System.currentTimeMillis() + minWait;
        isAttacking = Math.max(isAttacking, laserTime + bugTime);
        if (normal) {
            API.keyboardClick(lastShot = getAttackKey());
        } else if (API instanceof DarkBoatAdapter) {
            heroapi.triggerLaserAttack();
        } else if (target != null && target.isValid()) {
            target.trySelect(true);
        }
    }

    protected Laser getBestLaserAmmo() {
        return laserSupplier.get();
    }

    private Character getAttackKey() {
        return getAttackKey(ammoKey.getValue());
    }

    private Character getAttackKey(Character defaultAmmo) {
        Laser laser = getBestLaserAmmo();
        if (laser != null) {
            Character key = items.getKeyBind(laser);
            if (key != null) {
                return key;
            }
        }

        if (defense != null) {
            return defense.ammoKey;
        }

        return defaultAmmo;
    }

    public Formation getBestFormation() {
        return formationSupplier.get();
    }

    public void useHability() {
        useSelectableReadyWhenReady(abilitySupplier.get());
    }

    public void vsMove() {
        if (target != null) {
            double distance = heroapi.getLocationInfo().distanceTo(target);
            Location targetLoc = target.getLocationInfo().destinationInTime(400);
            if (distance > 600) {
                if (movement.canMove(targetLoc)) {
                    movement.moveTo(targetLoc);
                    if (target.getSpeed() > heroapi.getSpeed()) {
                        heroapi.setRunMode();
                    }
                } else {
                    resetDefenseData();
                }
            } else {
                movement.moveTo(Location.of(targetLoc, rnd.nextInt(360), distance));
            }
        }
    }

    public boolean useKeyWithConditions(ExtraKeyConditions extra, SelectableItem selectableItem) {
        if (extra.enable) {
            if (selectableItem == null && extra.Key != null) {
                selectableItem = items.getItem(extra.Key);
            }

            if (selectableItem != null && heroapi.getHealth().hpPercent() < extra.HEALTH_RANGE.max
                    && heroapi.getHealth().hpPercent() > extra.HEALTH_RANGE.min
                    && heroapi.getLocalTarget() != null
                    && heroapi.getLocalTarget().getHealth().hpPercent() < extra.HEALTH_ENEMY_RANGE.max
                    && heroapi.getLocalTarget().getHealth().hpPercent() > extra.HEALTH_ENEMY_RANGE.min
                    && (extra.CONDITION == null || extra.CONDITION.get(api).allows())) {
                return useSelectableReadyWhenReady(selectableItem);
            }
        }
        return false;
    }

    public boolean useSelectableReadyWhenReady(SelectableItem selectableItem) {
        if (System.currentTimeMillis() - keyDelay < 500)
            return false;
        if (selectableItem == null)
            return false;

        boolean isReady = items.getItem(selectableItem, ItemFlag.USABLE, ItemFlag.READY).isPresent();

        if (isReady && items.useItem(selectableItem).isSuccessful()) {
            keyDelay = System.currentTimeMillis();
            return true;
        }

        return false;
    }

    public boolean inGroupAttacked(int id) {
        if (group.hasGroup()) {
            for (GroupMember member : group.getMembers()) {
                if (!member.isDead() && member.getId() == id && member.isAttacked()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean inGroup(int id) {
        if (group.hasGroup()) {
            for (GroupMember member : group.getMembers()) {
                if (member.getId() == id) {
                    return true;
                }
            }
        }
        return false;
    }

    public void goToMemberAttacked() {
        GroupMember member = getMemberGroupAttacked();
        if (member != null) {
            movement.moveTo(member.getLocation());
        }
    }

    private GroupMember getMemberGroupAttacked() {
        if (group.hasGroup()) {
            for (GroupMember member : group.getMembers()) {
                if (!member.isDead() && member.getMapId() == heroapi.getMap().getId() && member.isAttacked()
                        && member.getTargetInfo() != null
                        && member.getTargetInfo().getShipType() != 0 && !member.getTargetInfo().getUsername().isEmpty()
                        && !SharedFunctions.isNpc(configAPI, member.getTargetInfo().getUsername())) {
                    return member;

                }
            }
        }
        return null;
    }

    public GroupMember getClosestMember() {
        if (group.hasGroup()) {
            return group.getMembers().stream()
                    .filter(member -> !member.isDead() && member.getMapId() == heroapi.getMap().getId())
                    .min(Comparator.<GroupMember>comparingDouble(m -> m.getLocation().distanceTo(heroapi)))
                    .orElse(null);
        }
        return null;
    }

    public void setMode(ShipMode config) {
        if (defense != null) {
            setMode(config, defense.useBestFormation);
        } else {
            setMode(config, true);
        }
    }

    public void setMode(ShipMode config, boolean useBestFormation) {
        if (useBestFormation) {
            Formation formation = getBestFormation();
            if (formation != null) {
                setMode(config, formation);
            } else {
                heroapi.setMode(config);
            }
        } else {
            heroapi.setMode(config);
        }
    }

    public void setMode(ShipMode config, Formation formation) {
        if (formation != null && !heroapi.isInFormation(formation)) {
            if (items.getItem(formation, ItemFlag.USABLE, ItemFlag.READY).isPresent()) {
                ShipModeImpl mode = new ShipModeImpl(config.getConfiguration(), formation);
                heroapi.setMode(mode);
            } else {
                heroapi.setMode(config);
            }
        } else {
            heroapi.setMode(config);
        }
    }

    public Ship getEnemy(int maxDistance) {
        if (heroapi.getMap().isPvp() || allPortals.stream().filter(p -> heroapi.distanceTo(p) < maxDistance).findFirst()
                .orElse(null) == null) {
            return allShips.stream()
                    .filter(s -> (s.getEntityInfo().isEnemy() && !s.getEffects().toString().contains("290")
                            && s.getLocationInfo().distanceTo(heroapi) <= maxDistance)
                            && !SharedFunctions.isPet(s.getEntityInfo().getUsername())
                            && !inGroup(s.getId()))
                    .sorted(Comparator.comparingDouble(s -> s.getLocationInfo().distanceTo(heroapi))).findAny()
                    .orElse(null);
        }
        return null;
    }

    public void resetDefenseData() {
        target = null;
        fixedTimes = 0;
        firstAttack = true;
    }
}
