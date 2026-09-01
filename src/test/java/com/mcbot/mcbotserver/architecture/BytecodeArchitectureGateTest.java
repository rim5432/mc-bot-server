package com.mcbot.mcbotserver.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Bytecode-level architecture conformance for the layer split of
 * ADR-0002 §6 (boundary A).
 *
 * <p>Complements, does not replace, the source-text gates beside it:
 * {@code ZeroMcImportGateTest} proves the textual rule ("no such import
 * line may exist") with zero dependencies; this class re-proves purity at
 * the class-reference level and adds the dependency-direction rule the
 * text scan cannot see — {@code core/} reaching into the MC-side packages
 * ({@code adapter/}, {@code client/}) would invert boundary A even
 * without a single MC import.
 *
 * <p>Because the analyzed classes carry no MC references by contract,
 * ArchUnit needs no MC classes on the analysis classpath and runs as a
 * plain offline JUnit test.
 */
@AnalyzeClasses(packages = "com.mcbot.mcbotserver", importOptions = ImportOption.DoNotIncludeTests.class)
class BytecodeArchitectureGateTest {

    /** The layer a non-JVM harness could bind to stays pure Java. */
    @ArchTest
    static final ArchRule API_LAYER_IS_ENGINE_FREE = noClasses()
            .that()
            .resideInAPackage("com.mcbot.mcbotserver.api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "net.minecraftforge..", "com.mojang..");

    /** Offline-testable engine: same engine-type ban as api/. */
    @ArchTest
    static final ArchRule CORE_LAYER_IS_ENGINE_FREE = noClasses()
            .that()
            .resideInAPackage("com.mcbot.mcbotserver.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "net.minecraftforge..", "com.mojang..");

    /** Dependency direction: core/ is below both MC-facing packages. */
    @ArchTest
    static final ArchRule CORE_NEVER_REACHES_MC_SIDE_PACKAGES = noClasses()
            .that()
            .resideInAPackage("com.mcbot.mcbotserver.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.mcbot.mcbotserver.adapter..", "com.mcbot.mcbotserver.client..");

    /**
     * Top-level modules are acyclic: api, core, adapter, client,
     * gametest form a strict layering. A cycle at this granularity means
     * a boundary has been inverted.
     */
    @ArchTest
    static final ArchRule NO_MODULE_CYCLES =
            slices().matching("com.mcbot.mcbotserver.(*)..").should().beFreeOfCycles();

    /**
     * Subpackages inside every module are acyclic too. This rule
     * replaced the two documented exceptions below it once their last
     * real edges were removed: api.behavior <-> api.process (dissolved by
     * moving ExecutionReport to api.process) and
     * adapter <-> adapter.entity <-> adapter.rescue (dissolved by moving
     * BindingInventory to adapter.inventory). A new subpackage cycle is
     * a design regression, not a documented exception.
     */
    @ArchTest
    static final ArchRule NO_SUBPACKAGE_CYCLES =
            slices().matching("com.mcbot.mcbotserver.(*).(*)..").should().beFreeOfCycles();
}
