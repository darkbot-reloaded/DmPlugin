
package com.deeme.types.config.ProfileChanger;

import com.github.manolo8.darkbot.config.types.Option;
import com.github.manolo8.darkbot.config.types.Num;

@Option("NPC Counter Condition")
public class NpcCounterCondition {
    @Option("Active condition")
    public boolean active = false;

    @Option("Npc Name")
    public String npcName = "";

    @Option("Number of NPCs to kill")
    @Num(min = 0, max = 100000, step = 1)
    public int npcsToKill = 1;

    public transient int npcCounter = 0;
}