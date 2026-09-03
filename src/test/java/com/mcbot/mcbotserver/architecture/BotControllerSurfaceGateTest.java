package com.mcbot.mcbotserver.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Offline gate over {@code core/tick/BotController}'s public surface:
 * the tick orchestrator must never grow capability setters. Every
 * survival capability that once arrived as {@code setXxx(...)} pushed
 * wiring through the controller (six setters at peak, three pure
 * pass-throughs); since ledger-era refactor they are constructor-wired
 * through {@code BotController.SurvivalInputs} instead, assembled in
 * BotAssembly. A new {@code public void set...} on the controller is a
 * composition-root defect: extend SurvivalInputs (or a sibling wiring
 * record) and let BotAssembly hold the handle.
 *
 * <p>Pattern of record: {@code public} + {@code set} + uppercase name.
 * Legitimate non-setter methods (isCrashed, emergencyLatch, reset,
 * onTick, onRespawned) never match. The scan also pins that the
 * {@code SurvivalInputs} record exists, so deleting the injection shape
 * without a replacement re-registration fails here.
 *
 * <p>Task path: runs in the default {@code test} task, alongside
 * ConcurrencyPrimitiveGateTest and GetDerefGateTest.
 */
class BotControllerSurfaceGateTest {

    /** Public setter shape: "public void set" + capitalized name + "(". */
    private static final Pattern PUBLIC_SETTER = Pattern.compile("\\bpublic\\s+void\\s+set[A-Z][A-Za-z0-9]*\\s*\\(");

    /** The constructor-wired vitals record that replaced the setters. */
    private static final String SURVIVAL_INPUTS_RECORD = "public record SurvivalInputs(";

    /** Fails when a public setter appears on the controller surface. */
    @Test
    void botControllerHasZeroPublicSetters() throws IOException {
        List<String> lines = readController();
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = PUBLIC_SETTER.matcher(lines.get(i));
            if (m.find()) {
                violations.add("line " + (i + 1) + ": " + lines.get(i).trim());
            }
        }
        assertEquals(
                List.of(),
                violations,
                "BotController grew a public setter - capability wiring belongs in the"
                        + " composition root (BotAssembly) through SurvivalInputs or a sibling"
                        + " wiring record, not through per-capability setter pushes. If a setter"
                        + " is genuinely unavoidable, re-register this gate with a written reason.");
    }

    /** Fails when the SurvivalInputs injection shape disappears. */
    @Test
    void survivalInputsRecordIsTheWiringShape() throws IOException {
        List<String> lines = readController();
        assertTrue(
                lines.stream().anyMatch(l -> l.contains(SURVIVAL_INPUTS_RECORD)),
                "BotController.SurvivalInputs missing - the constructor-wired vitals shape"
                        + " was removed without re-registering this gate.");
    }

    /**
     * Fails when the orchestrator grows mission-type knowledge again:
     * the per-tick dig-claim duty lives in {@code DigClaimBehavior} (a
     * plain behaviors-list member); a {@code DigMission} reference back
     * in BotController means the mid-pipeline instanceof block has
     * regrown.
     */
    @Test
    void controllerStaysMissionTypeAgnostic() throws IOException {
        List<String> lines = readController();
        assertTrue(
                lines.stream().noneMatch(l -> l.contains("DigMission")),
                "BotController references DigMission again - the dig-claim protocol belongs"
                        + " in DigClaimBehavior; the orchestrator must not learn mission types.");
    }

    private List<String> readController() throws IOException {
        Path root = RepoRoot.find();
        Path controller = root.resolve("src/main/java/com/mcbot/mcbotserver/core/tick/BotController.java");
        return Files.readAllLines(controller);
    }
}
