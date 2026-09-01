package com.mcbot.mcbotserver.boundaryd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.command.SubmitResult;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventBatch;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.state.BotState;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.state.BotStateJson;
import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate over the boundary-D wire vocabulary: every
 * {@link EventKind} constant is registered here with the
 * channel-owned attr keys its producers may stamp, every attr-key
 * literal in a producer file stays inside that vocabulary, and the
 * wire-carrying record component names stay frozen in order.
 *
 * <p>Why a pinned inventory: a renamed or removed wire key breaks
 * every harness replay and every verifier silently - the event
 * stream still flows, only the consumer's mental model is wrong.
 * Updating the maps below is deliberate friction; it is the review
 * checkpoint for every intentional vocabulary change.
 *
 * <p>Scope note: TASK_COMPLETED / TASK_FAILED may carry
 * mission-owned verdict attrs ON TOP of the channel-owned keys -
 * that open set belongs to each process's own tests, not to this
 * inventory; the scan excludes process sources for exactly that
 * reason.
 *
 * <p>Rule: doc/architecture/code-health.md H-R4.
 */
class WireVocabularyGateTest {

    /**
     * The channel-owned attr contract per event kind. Keys are the
     * string values the wire carries (identical to the constant
     * names in {@link EventKind}).
     */
    private static final Map<String, Set<String>> KIND_ATTRS = Map.ofEntries(
            Map.entry("EVENT_DROPPED", Set.of("count")),
            Map.entry("EVENT_GAP", Set.of("count", "since", "oldest")),
            Map.entry("STATE_PUSH", Set.of("posX", "posY", "posZ", "dimension", "task")),
            Map.entry("TASK_REJECTED", Set.of("taskId")),
            Map.entry("TASK_COMPLETED", Set.of("task", "taskId")),
            Map.entry("TASK_FAILED", Set.of("task", "taskId", "reason")),
            Map.entry("TASK_PAUSED", Set.of("task", "taskId")),
            Map.entry("TASK_RESUMED", Set.of("task", "taskId")),
            Map.entry("TASK_DROPPED", Set.of("task", "taskId")),
            Map.entry("TASK_CANCELLED", Set.of("task", "taskId")),
            Map.entry("BOT_CRASHED", Set.of("cause")),
            Map.entry("BLOCK_BROKEN", Set.of("taskId", "posX", "posY", "posZ", "blockId")),
            Map.entry(
                    "KEEPALIVE",
                    Set.of(
                            "pose",
                            "waypointIndex",
                            "waypointsTotal",
                            "ticksSincePlanProgress",
                            "ticksSincePlan",
                            "planAge",
                            "noPathWitnesses",
                            "goalCell")),
            Map.entry("EAT_STARTED", Set.of("foodLevel", "slot", "ruleName", "source")),
            Map.entry(
                    "EAT_COMPLETED",
                    Set.of(
                            "itemId",
                            "slot",
                            "nutrition",
                            "saturationGained",
                            "foodLevelBefore",
                            "foodLevelAfter",
                            "saturationBefore",
                            "saturationAfter",
                            "containerType",
                            "source")),
            Map.entry("EAT_FAILED", Set.of("reason", "slot", "itemId", "source")),
            Map.entry("DRINK_STARTED", Set.of("potionId", "slot", "health", "source")),
            Map.entry("DRINK_COMPLETED", Set.of("potionId", "slot", "effects", "containerType", "containerDropped", "source")),
            Map.entry("DRINK_FAILED", Set.of("reason", "slot", "itemId", "source")),
            Map.entry("POTION_THROWN", Set.of("potionId", "slot", "throwType", "effects", "source")));

    /**
     * Every main-source file that constructs a {@link BotEvent} or
     * builds its attrs. A new producer file must be listed here, or
     * its attr literals fall outside the scan - listing it is the
     * registration.
     */
    private static final List<String> PRODUCER_FILES = List.of(
            "core/event/InMemoryEventQueue.java",
            "core/command/CommandBus.java",
            "core/command/GotoCommandHandler.java",
            "core/command/DigCommandHandler.java",
            "core/command/MineCommandHandler.java",
            "core/state/ChangeDetectingStateChannel.java",
            "core/tick/BotController.java",
            "core/tick/CrashLatch.java",
            "core/tick/MissionReporter.java",
            "core/behavior/PathingBehavior.java",
            "adapter/BindingActor.java");

    /** Attr-key literal shapes used at push sites. */
    private static final Pattern[] ATTR_LITERAL = {
        Pattern.compile("\\.put\\(\"([^\"]+)\""), Pattern.compile("Map\\.of\\(\"([^\"]+)\"")
    };

    /** The constant declarations scanned out of EventKind.java - a
     *  source scan, not reflection, per H-R1 (the door rule). */
    private static final Pattern STRING_CONSTANT =
            Pattern.compile("public\\s+static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"");

    /** Fails when an EventKind constant lacks a registered shape
     *  or a registered shape lacks its kind. */
    @Test
    void kindInventoryMatchesEventKindConstants() {
        Path source = RepoRoot.find()
                .resolve(Path.of(
                        "src", "main", "java", "com", "mcbot", "mcbotserver", "api", "event", "EventKind.java"));
        assertTrue(Files.isRegularFile(source), () -> "EventKind source not found at " + source);
        Set<String> constants = new TreeSet<>();
        try {
            Matcher m = STRING_CONSTANT.matcher(Files.readString(source));
            while (m.find()) {
                constants.add(m.group(2));
            }
        } catch (IOException e) {
            fail("cannot read EventKind source: " + e.getMessage());
            return;
        }
        Set<String> registered = KIND_ATTRS.keySet();
        Set<String> unregistered = new TreeSet<>(constants);
        unregistered.removeAll(registered);
        Set<String> unknown = new TreeSet<>(registered);
        unknown.removeAll(constants);
        if (!unregistered.isEmpty() || !unknown.isEmpty()) {
            fail("event-kind inventory drifted. unregisteredKinds="
                    + unregistered + " unknownKinds=" + unknown
                    + ". A new EventKind constant must register its "
                    + "channel-owned attr keys in KIND_ATTRS (and update "
                    + "boundaries.md section D); a removed constant must "
                    + "retire its row here in the same change.");
        }
    }

    /** Fails when a producer stamps an attr key outside the
     *  registered vocabulary, or a registered key is produced
     *  nowhere. */
    @Test
    void producerAttrLiteralsStayInsideTheVocabulary() {
        Set<String> stamped = new TreeSet<>();
        for (String file : PRODUCER_FILES) {
            Path path = RepoRoot.find().resolve("src/main/java/com/mcbot/mcbotserver/" + file);
            assertTrue(
                    Files.isRegularFile(path),
                    () -> "producer file listed in PRODUCER_FILES is " + "gone (renamed?): " + file);
            String source;
            try {
                source = Files.readString(path);
            } catch (IOException e) {
                fail("cannot read producer file " + path + ": " + e.getMessage());
                return;
            }
            for (Pattern p : ATTR_LITERAL) {
                Matcher m = p.matcher(source);
                while (m.find()) {
                    stamped.add(m.group(1));
                }
            }
        }
        Set<String> vocabulary = new TreeSet<>();
        KIND_ATTRS.values().forEach(vocabulary::addAll);
        Set<String> offVocabulary = new TreeSet<>(stamped);
        offVocabulary.removeAll(vocabulary);
        Set<String> stalePins = new TreeSet<>(vocabulary);
        stalePins.removeAll(stamped);
        if (!offVocabulary.isEmpty() || !stalePins.isEmpty()) {
            fail("wire attr-key vocabulary drifted. "
                    + "offVocabulary=" + offVocabulary
                    + " (a producer stamps a key nobody registered - "
                    + "register it in KIND_ATTRS or fix the typo) "
                    + "stalePins=" + stalePins
                    + " (registered keys no producer stamps - retire "
                    + "the pin or restore the producer).");
        }
    }

    /** Fails when a wire-carrying record renames or reorders a
     *  component; component order is the positional-constructor
     *  contract, so it is pinned in declaration order. */
    @Test
    void wireRecordComponentsStayFrozen() {
        assertComponents(BotEvent.class, "kind", "day", "t", "urgent", "attrs", "text");
        assertComponents(EventBatch.class, "events", "droppedCount", "latestEventId", "resetAt");
        assertComponents(SubmitResult.Ok.class, "taskId", "idempotencyReplay");
        assertComponents(SubmitResult.Rejected.class, "reason");
        assertComponents(BotCommand.class, "verb", "args");
        assertComponents(
                BotState.class,
                "pos",
                "yaw",
                "pitch",
                "dimension",
                "itemCounts",
                "selectedHotbarSlot",
                "effectAmplifiers",
                "currentTaskSummary",
                "healthHearts",
                "freeSlots",
                "foodLevel",
                "experienceLevel");
    }

    /** Pins the serialized wire keys of the state snapshot: the
     *  record-component pin above freezes the shape, this freezes
     *  the JSON dialect - five components serialize to shortened
     *  keys and nothing may rename either side independently
     *  (H-R4: the key survives field renames). */
    @Test
    void stateJsonWireKeysStayFrozen() {
        BotState sample = new BotState(
                new CellPos(1, 2, 3),
                10.0f,
                20.0f,
                "minecraft:overworld",
                Map.of("minecraft:stone", 5),
                2,
                Map.of(),
                "goto:task-1",
                8,
                12,
                17,
                0);
        var obj = BotStateJson.toJsonObject(sample);
        assertEquals(
                Set.of(
                        "pos",
                        "yaw",
                        "pitch",
                        "dim",
                        "items",
                        "slot",
                        "effects",
                        "task",
                        "healthHearts",
                        "freeSlots",
                        "food",
                        "xp"),
                obj.keySet(),
                "serialized state wire keys drifted; the mapping is " + "documented at boundaries.md (decision 32)");
    }

    private static void assertComponents(Class<?> record, String... expected) {
        var components = record.getRecordComponents();
        Set<String> actual = new LinkedHashSet<>();
        for (var c : components) {
            actual.add(c.getName());
        }
        assertEquals(
                List.of(expected),
                List.copyOf(actual),
                () -> record.getSimpleName() + " wire components "
                        + "drifted (renamed or reordered). Every dump, debug "
                        + "and harness path prints these names; a rename is "
                        + "a boundary-D breaking change - update the pin, "
                        + "boundaries.md and every consumer in one change.");
    }
}
