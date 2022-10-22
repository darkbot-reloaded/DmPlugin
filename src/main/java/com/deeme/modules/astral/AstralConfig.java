package com.deeme.modules.astral;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;

@Configuration("astral")
public class AstralConfig {
    @Option(value = "astral.min_radius")
    @Number(min = 500, max = 2000, step = 10)
    public int radioMin = 560;

    @Option(value = "general.default_ammo")
    public Character ammoKey;

    @Option(value = "astral.attack_closest")
    public boolean alwaysTheClosestNPC = false;

    @Option(value = "astral.best_ammo")
    public boolean useBestAmmo = false;

    @Option(value = "astral.choose_portal")
    public boolean autoChoosePortal = false;

    @Option(value = "astral.choose_item")
    public boolean autoChooseItem = false;

    @Option(value = "astral.cpu_key")
    public Character astralCPUKey;
}