package smoker.cli;

import java.util.ArrayList;
import java.util.List;
import smoker.core.model.RunResult;

/** {@code suite-result.json}: what a pipeline or SelfTestIT reads after {@code run --suite} (WP-05, design section 10). */
final class SuiteResult {

    static final class Entry {
        String name;
        RunResult.Status status;
        int attempts;
        String note;
    }

    int applicationStarts;
    List<Entry> scenarios = new ArrayList<Entry>();
    List<String> commandErrors = new ArrayList<String>();
}
