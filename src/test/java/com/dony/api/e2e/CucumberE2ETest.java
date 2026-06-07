package com.dony.api.e2e;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

import java.io.PrintWriter;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import org.assertj.core.api.Assertions;

/**
 * Runs every Cucumber scenario under {@code src/test/resources/features} and gates
 * the Maven build on the result.
 *
 * <p>Why not a plain {@code @Suite}? Surefire does not report individual tests that
 * are nested under the JUnit Platform <em>Suite</em> engine — it records
 * {@code Tests run: 0}, so a failing scenario silently leaves the build green. Here
 * we instead drive the Cucumber engine through the JUnit Platform {@link Launcher}
 * inside a normal {@code @Test}: Surefire counts this method, the scenarios still run
 * (glue + reporting come from {@code junit-platform.properties}), and the assertions
 * below fail the build if any scenario fails or if none are discovered.
 */
class CucumberE2ETest {

    static {
        Locale.setDefault(Locale.ENGLISH);
    }

    @Test
    void allCucumberScenariosPass() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClasspathResource("features"))
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        summary.printFailuresTo(new PrintWriter(System.out), 50);

        Assertions.assertThat(summary.getTestsFoundCount())
                .as("Cucumber scenarios discovered")
                .isGreaterThan(0);
        Assertions.assertThat(summary.getTotalFailureCount())
                .as("Failed Cucumber scenarios/steps:\n%s", renderFailures(summary))
                .isZero();
    }

    private String renderFailures(TestExecutionSummary summary) {
        StringBuilder sb = new StringBuilder();
        for (TestExecutionSummary.Failure f : summary.getFailures()) {
            sb.append("  - ").append(f.getTestIdentifier().getDisplayName())
              .append(" :: ").append(f.getException()).append('\n');
        }
        return sb.toString();
    }
}
