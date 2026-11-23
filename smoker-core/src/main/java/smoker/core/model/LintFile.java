package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The lint of one record session: {@code <scenario>.lint.json} beside the scenario (WP-09).
 * The recorder writes it at each save. {@code lint --run} reads it together with the {@code run-result.json} files.
 */
public class LintFile {
    public String scenarioName;
    /** Components that need a real name, with proposals. The same entries as {@link RunResult#lint}. */
    public List<LintEntry> lint = new ArrayList<LintEntry>();
}
