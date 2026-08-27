package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;

/**
 * Mission stub counting ticks for skip assertions: always active,
 * constant ROUTINE priority, aims at a fixed far cell every tick so
 * a healthy pipeline keeps it current and a preempted pipeline stops
 * advancing it.
 *
 * <p>Shared test infrastructure for the tick gate family; not part
 * of any boundary.
 */
class CountingMission implements BotProcess {

    int tickCalls;

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public Directive onTick(WorldView world) {
        tickCalls++;
        return Directive.of(new GoalBlock(new CellPos(9, 64, 9)));
    }

    @Override
    public void onLostControl(InterruptionContext c) {}

    @Override
    public boolean resume(InterruptionContext c) {
        return true;
    }

    @Override
    public void onContextInvalidated() {}

    @Override
    public String displayName() {
        return "counting-mission";
    }
}
