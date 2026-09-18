package com.builtbyjuls.arat.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditMetadataTest {

    @Test
    void acceptsOnlyNamedUuidReferences() {
        var subjectId = UUID.fromString("20000000-0000-4000-8000-000000000001");

        var metadata = AuditMetadata.references(Map.of("previousSubjectId", subjectId));

        assertThat(metadata.references()).containsExactly(Map.entry("previousSubjectId", subjectId));
    }

    @Test
    void rejectsForbiddenMetadataFields() {
        var value = UUID.fromString("20000000-0000-4000-8000-000000000001");

        assertThatIllegalArgumentException().isThrownBy(() -> AuditMetadata.references(Map.of("invitationToken", value)));
        assertThatIllegalArgumentException().isThrownBy(() -> AuditMetadata.references(Map.of("idempotencyKey", value)));
        assertThatIllegalArgumentException().isThrownBy(() -> AuditMetadata.references(Map.of("privateNote", value)));
        assertThatIllegalArgumentException().isThrownBy(() -> AuditMetadata.references(Map.of("requestBody", value)));
    }

    @Test
    void rejectsMoreThanEighteenReferences() {
        var references = new java.util.HashMap<String, UUID>();
        for (var index = 0; index < 19; index++) {
            references.put("reference" + "x".repeat(53) + index, UUID.randomUUID());
        }

        assertThatIllegalArgumentException().isThrownBy(() -> AuditMetadata.references(references));
    }
}
