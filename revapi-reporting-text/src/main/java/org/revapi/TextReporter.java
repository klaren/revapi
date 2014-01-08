/*
 * Copyright 2014 Lukas Krejci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package org.revapi;

import java.io.PrintStream;
import java.util.Iterator;
import java.util.Map;

/**
 * @author Lukas Krejci
 * @since 0.1
 */
public class TextReporter implements Reporter {

    private boolean reportMatches;

    @Override
    public void initialize(Configuration config) {
        String reportMatches = config.getProperties().get("TextReporter.reportMatches");

        this.reportMatches = reportMatches != null && Boolean.valueOf(reportMatches);
    }

    @Override
    public void report(MatchReport matchReport, PrintStream output) {
        if (!reportMatches && matchReport.getProblems().isEmpty()) {
            return;
        }

        if (matchReport.getProblems().isEmpty()) {
            output.print("MATCH: ");
        }

        output.print(matchReport.getOldElement());
        output.print(" with ");
        output.println(matchReport.getNewElement());
        if (!matchReport.getProblems().isEmpty()) {
            output.append(":");
            for (MatchReport.Problem p : matchReport.getProblems()) {
                output.append(p.name).append(" (").append(p.code).append(")");
                reportClassification(output, p);
                output.append("): ").append(p.description).append("\n");
            }
        }
    }

    private void reportClassification(PrintStream output, MatchReport.Problem problem) {
        Iterator<Map.Entry<CompatibilityType, MismatchSeverity>> it = problem.classification.entrySet().iterator();

        if (it.hasNext()) {
            Map.Entry<CompatibilityType, MismatchSeverity> e = it.next();
            output.append(" ").append(e.getKey().toString()).append(": ").append(e.getValue().toString());
        }

        while (it.hasNext()) {
            Map.Entry<CompatibilityType, MismatchSeverity> e = it.next();
            output.append(", ").append(e.getKey().toString()).append(": ").append(e.getValue().toString());
        }
    }
}
