package com.dony.api.e2e;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import java.util.Locale;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
public class CucumberE2ETest {
    static {
        Locale.setDefault(Locale.ENGLISH);
    }
}
