package com.mcbot.mcbotserver.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.mcbot.mcbotserver.testsupport.RepoRoot;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Layer-1 gate over the package structure (AGENTS.md 1.1 as the tree
 * actually lives it): main-source modules stay single-level with at
 * most one subject segment, no module name is ever reused as another
 * module's subpackage (a {@code core.api} would be a namespace
 * defect), and test packages either mirror a main package or belong
 * to the sanctioned meta set that tests the repo rather than a
 * module.
 *
 * <p>Why a structure gate: flat test pseudo-packages
 * ({@code tickpipeline}, {@code corepathing}, ...) forced every
 * test-to-code hop through a mental rename; the mirroring rule makes
 * navigation mechanical, and this gate keeps it true.
 *
 * <p>Rule: doc/architecture/code-health.md H-R7.
 */
class PackageStructureGateTest {

    /** Legal first segments under com.mcbot.mcbotserver in src/main. */
    private static final Set<String> MODULES = Set.of(
        "api", "core", "adapter", "mixin", "gametest", "client");

    /**
     * Test packages that test the repository rather than one main
     * package: the boundary-contract gates (boundarya = boundary A,
     * boundaryd = boundary D), the hygiene/architecture gates, and
     * testsupport (shared test infrastructure - RepoRoot et al).
     */
    private static final Set<String> TEST_METAS = Set.of(
        "architecture", "boundarya", "boundaryd", "hygiene",
        "testsupport");

    /** Anchors proving the main scan actually saw the tree. */
    private static final List<String> MAIN_ANCHORS = List.of(
        "api/actor", "core/tick", "adapter", "gametest");

    /** Anchors proving the test scan actually saw the tree. */
    private static final List<String> TEST_ANCHORS = List.of(
        "core/tick", "boundarya", "boundaryd", "hygiene");

    /** Fails when a main package breaks the module grammar. */
    @Test
    void mainPackagesStaySingleLevelAndCrossHalfFree() {
        Set<String> packages = scanPackages(RepoRoot.find().resolve(
            Path.of("src", "main", "java", "com", "mcbot", "mcbotserver")));
        List<String> violations = packages.stream()
            .filter(p -> !isLegalMainPackage(p))
            .toList();
        assertTrue(violations.isEmpty(),
            () -> "main packages break the module grammar: "
                + violations + ". Legal shapes: the root, <module>, "
                + "or <module>.<subject> with module in " + MODULES
                + " and subject never a module name; deeper nesting "
                + "is a namespace defect (AGENTS.md 1.1).");
        for (String anchor : MAIN_ANCHORS) {
            assertTrue(packages.contains(anchor),
                () -> "main package anchor missing: " + anchor
                    + " - the scan came back incomplete.");
        }
    }

    /** Fails when a test package neither mirrors a main package nor
     *  belongs to the sanctioned meta set. */
    @Test
    void testPackagesMirrorMainOrAreSanctionedMetas() {
        Set<String> main = scanPackages(RepoRoot.find().resolve(
            Path.of("src", "main", "java", "com", "mcbot", "mcbotserver")));
        Set<String> test = scanPackages(RepoRoot.find().resolve(
            Path.of("src", "test", "java", "com", "mcbot", "mcbotserver")));
        Set<String> violations = new TreeSet<>(test);
        violations.removeAll(main);
        violations.removeAll(TEST_METAS);
        assertTrue(violations.isEmpty(),
            () -> "test packages with no main counterpart and no meta "
                + "sanction: " + violations + ". Either mirror the "
                + "package under test or add the package to TEST_METAS "
                + "as a deliberate, reviewed decision (a theme name is "
                + "not a mirror).");
        for (String anchor : TEST_ANCHORS) {
            assertTrue(test.contains(anchor),
                () -> "test package anchor missing: " + anchor
                    + " - the scan came back incomplete.");
        }
    }

    private static boolean isLegalMainPackage(String path) {
        if (path.isEmpty()) {
            return true;
        }
        String[] segments = path.split("/");
        if (segments.length > 2) {
            return false;
        }
        if (!MODULES.contains(segments[0])) {
            return false;
        }
        return segments.length == 1 || !MODULES.contains(segments[1]);
    }

    /** Collects every directory under root that directly holds at
     *  least one .java file, as slash-separated package paths.
     *
     * @param root the mcbotserver package directory; never null
     * @return package paths, never null
     */
    private static Set<String> scanPackages(Path root) {
        Set<String> packages = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                .filter(PackageStructureGateTest::holdsJavaFile)
                .forEach(dir -> packages.add(root.relativize(dir)
                    .toString().replace('\\', '/')));
        } catch (IOException e) {
            fail("cannot scan package tree under " + root + ": "
                + e.getMessage());
        }
        return packages;
    }

    private static boolean holdsJavaFile(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.anyMatch(p -> p.getFileName().toString()
                .endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }
}
