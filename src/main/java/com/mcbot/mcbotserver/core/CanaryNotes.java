package com.mcbot.mcbotserver.core;

/**
 * Gate canaries — synthetic violations that prove each text-scan gate is
 * live and that its semantic boundary is pinned. Every canary lives in a
 * comment: zero runtime effect, zero compile effect, zero real import.
 * The gate assertion shape is "violation set equals canary set": a missing
 * canary means the gate went blind (regex changed, scope shrank, walk root
 * moved); an extra violation means a real defect appeared.
 *
 * <p>Must-match (gate MUST flag):
 * <ul>
 *   <li>zeromc — commented MC import; proves ZeroMcImport catches
 *       commented-out imports (commented code is banned, AGENTS.md 1.4)</li>
 *   <li>getderef — simple-arg chained get-deref; proves the basic form
 *       matches</li>
 * </ul>
 * Must-not-match (gate must NOT flag — pinned blind spots; a change that
 * starts matching them forces boundary re-registration):
 * <ul>
 *   <li>getderef-nested — nested function argument inside get(); the regex
 *       [^)]* stops at the first close paren, so this form is invisible.
 *       Registered as a blind spot in GetDerefGateTest Javadoc.</li>
 * </ul>
 */
public final class CanaryNotes {
    // canary(zeromc): import net.minecraft.world.level.Level;
    // canary(getderef): state.get(ruleName).lastPriority
    // canary(getderef-nested): cache.get(keyOf(msg)).size()
    private CanaryNotes() {}
}
