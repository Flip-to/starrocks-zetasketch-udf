package to.flip.udf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cross-engine validation against real BigQuery HLL++ sketches.
 *
 * Fixtures are produced by {@code scripts/dump_bq_fixtures.py} and live under
 * {@code test-fixtures/bq_sketches/}. They are gitignored — this repo is
 * public and the sketches contain org data. Run the dumper locally before
 * invoking the test.
 *
 * Assertions are exact (not within tolerance). ZetaSketch is deterministic
 * for a given precision, so Java's HLL_COUNT.EXTRACT / MERGE on the same
 * bytes must produce the same cardinality as BigQuery's.
 */
class BqCompatibilityTest {

    private static final Path EXTRACT_CSV =
            Path.of("test-fixtures", "bq_sketches", "extract_fixtures.csv");
    private static final Path MERGE_CSV =
            Path.of("test-fixtures", "bq_sketches", "merge_fixtures.csv");

    static boolean fixturesPresent() {
        return Files.exists(EXTRACT_CSV) || Files.exists(MERGE_CSV);
    }

    @Test
    @EnabledIf("fixturesPresent")
    void extract_matches_bigquery_exactly() throws IOException {
        if (!Files.exists(EXTRACT_CSV)) {
            fail("Expected fixture " + EXTRACT_CSV + " — run scripts/dump_bq_fixtures.py");
        }
        HllppExtract extract = new HllppExtract();
        List<String> lines = Files.readAllLines(EXTRACT_CSV);
        int checked = 0;
        for (int i = 1; i < lines.size(); i++) {            // skip header
            String[] parts = lines.get(i).split(",", 2);
            if (parts.length != 2 || parts[1].isEmpty()) continue;
            long expected = Long.parseLong(parts[0]);
            long actual = extract.evaluate(parts[1]);
            assertEquals(expected, actual,
                    "row " + i + ": HLL_COUNT.EXTRACT mismatch");
            checked++;
        }
        System.out.printf("[extract] checked %d sketches against BigQuery%n", checked);
    }

    @Test
    @EnabledIf("fixturesPresent")
    void merge_matches_bigquery_exactly() throws IOException {
        if (!Files.exists(MERGE_CSV)) {
            fail("Expected fixture " + MERGE_CSV + " — run scripts/dump_bq_fixtures.py");
        }
        long expected = -1;
        List<String> sketches = new ArrayList<>();
        for (String line : Files.readAllLines(MERGE_CSV)) {
            String[] parts = line.split(",", 2);
            if (parts.length != 2) continue;
            switch (parts[0]) {
                case "expected_merged_count" -> expected = Long.parseLong(parts[1]);
                case "sketch" -> sketches.add(parts[1]);
                default -> { /* header or noise */ }
            }
        }
        if (expected < 0 || sketches.isEmpty()) {
            fail("Malformed " + MERGE_CSV + " — re-run scripts/dump_bq_fixtures.py");
        }

        HllppMerge merge = new HllppMerge();
        HllppMerge.State state = merge.create();
        for (String sketch : sketches) {
            merge.update(state, sketch);
        }
        long actual = merge.finalize(state);

        assertEquals(expected, actual,
                "HLL_COUNT.MERGE over " + sketches.size() + " sketches mismatched BigQuery");
        System.out.printf("[merge] merged %d sketches → %d (matches BQ)%n", sketches.size(), actual);
    }
}
