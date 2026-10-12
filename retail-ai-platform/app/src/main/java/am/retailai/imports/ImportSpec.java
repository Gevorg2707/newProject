package am.retailai.imports;

import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.TargetEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How one file is stored: which columns are kept (only mapped and id columns, so unmapped personal data never reaches
 * the database), how a row's identity is built, and a suffix that separates identities across snapshots.
 */
public record ImportSpec(SourceSystem source, List<String> idColumns, Set<String> keepHeaders, String identitySuffix) {

    public static ImportSpec forMapping(ColumnMapping m) {
        Set<String> keep = new LinkedHashSet<>(m.fieldToHeader().values());
        List<String> ids = m.settings() == null || m.settings().idColumns() == null ? List.of() : m.settings().idColumns();
        keep.addAll(ids);
        String suffix = "";
        if (m.targetEntity() == TargetEntity.INVENTORY_SNAPSHOT && m.settings() != null && m.settings().asOfDate() != null) {
            suffix = "@" + m.settings().asOfDate();   // weekly snapshots with equal quantities are still distinct records
        }
        return new ImportSpec(m.sourceSystem(), List.copyOf(ids), Set.copyOf(keep), suffix);
    }

    /** Keeps every column. For diagnostics and tests only; client files go through {@link #forMapping}. */
    public static ImportSpec allColumns(SourceSystem source, List<String> idColumns) {
        return new ImportSpec(source, idColumns == null ? List.of() : List.copyOf(idColumns), null, "");
    }

    /** Stored on the batch; a source must keep the same scheme, or identities of the same rows would differ. */
    public String identityScheme() {
        return idColumns.isEmpty() ? "content" : "cols:" + String.join("|", idColumns);
    }
}
