package com.playdata.calen.common.schema;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.asm.*;

/** Literal inventory, not a claim to prove arbitrary dynamically generated SQL safe. */
class StartupSchemaGovernanceTest {
    private static final Pattern DDL = Pattern.compile("\\b(?:CREATE\\s+(?:UNIQUE\\s+)?(?:TABLE|INDEX)|ALTER\\s+(?:TABLE|INDEX)|DROP\\s+(?:TABLE|INDEX))\\b",Pattern.CASE_INSENSITIVE);
    private static final Set<String> EXISTING = Set.of(
            "account.config.HouseholdGoalSchemaUpdater","account.security.SecurityConfig","account.service.AdminOpsControlService",
            "account.service.RegistrationPolicyService","ledger.config.LedgerAiAnalysisSchemaUpdater","ledger.config.LedgerEntryChangeHistorySchemaUpdater",
            "ledger.config.LedgerEntrySchemaUpdater","ledger.config.LedgerImageAnalysisRequestSchemaUpdater","travel.config.TravelMapShareLinkSchemaUpdater",
            "travel.config.TravelMediaAssetSchemaUpdater","travel.config.TravelPhotoClusterSchemaUpdater","travel.config.TravelRouteSchemaUpdater",
            "travel.service.TravelDriveLinkBackfillService","travel.service.TravelLoginMapPreviewService");
    @Test void applicationDdlLiteralsRemainInsideTheAuditedFourteenSourcesIncludingNonRunnerBootstraps() throws Exception {
        Path classes = Path.of(LegacySchemaCompatibility.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("com/playdata/calen");
        Set<String> found = new TreeSet<>();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                if (hasDdl(Files.readAllBytes(file))) found.add(classes.relativize(file).toString().replace('\\','.').replace('/','.').replaceAll("\\.class$",""));
            }
        }
        assertThat(found).as("New Java schema writers require a Flyway design/rehearsal, not a silent allowlist expansion")
                .containsExactlyInAnyOrderElementsOf(EXISTING);
    }
    @Test void scannerFindsConcatenatedUniqueIndexAndDdlInHelpersWithoutRunnerNames() throws IOException {
        for (Class<?> fixture : List.of(NonRunnerFixture.class,ConcatFixture.class)) {
            try (var bytes = fixture.getResourceAsStream("/"+fixture.getName().replace('.','/')+".class")) {
                assertThat(bytes).isNotNull(); assertThat(hasDdl(bytes.readAllBytes())).isTrue();
            }
        }
    }
    private static boolean hasDdl(byte[] bytes) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            void inspect(Object value) { if (value instanceof String text && DDL.matcher(text).find()) found[0] = true; }
            @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value) { inspect(value); return null; }
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object value) { inspect(value); }
                    @Override public void visitInvokeDynamicInsn(String name,String descriptor,Handle bootstrap,Object... arguments) {
                        for (Object value : arguments) inspect(value);
                    }
                };
            }
        },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        return found[0];
    }
    static class NonRunnerFixture { String sql() { return "CREATE UNIQUE INDEX fixture ON fixture_table(id)"; } }
    static class ConcatFixture { String sql(String table) { return "ALTER TABLE "+table+" ADD COLUMN flag INTEGER"; } }
}
