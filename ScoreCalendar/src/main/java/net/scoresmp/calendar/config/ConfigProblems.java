package net.scoresmp.calendar.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects configuration warnings and errors so that broken configuration is never silently ignored.
 * Every entry names the file and the exact setting path.
 */
public final class ConfigProblems {

    public enum Severity { WARNING, ERROR }

    public record Problem(Severity severity, String source, String path, String message) {
        @Override
        public String toString() {
            return "[" + source + "] " + (path == null || path.isEmpty() ? "" : path + ": ") + message;
        }
    }

    private final String source;
    private final List<Problem> problems;

    public ConfigProblems(String source) {
        this(source, new ArrayList<>());
    }

    private ConfigProblems(String source, List<Problem> shared) {
        this.source = source;
        this.problems = shared;
    }

    /** Returns a reporter sharing the same problem list but labelled with another source (file). */
    public ConfigProblems forSource(String otherSource) {
        return new ConfigProblems(otherSource, problems);
    }

    public void warn(String path, String message) {
        problems.add(new Problem(Severity.WARNING, source, path, message));
    }

    public void error(String path, String message) {
        problems.add(new Problem(Severity.ERROR, source, path, message));
    }

    public boolean hasErrors() {
        return problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
    }

    public int errorCount() {
        return (int) problems.stream().filter(p -> p.severity() == Severity.ERROR).count();
    }

    public int warningCount() {
        return (int) problems.stream().filter(p -> p.severity() == Severity.WARNING).count();
    }

    public List<Problem> all() {
        return Collections.unmodifiableList(problems);
    }

    public void log(Logger logger) {
        for (Problem p : problems) {
            logger.log(p.severity() == Severity.ERROR ? Level.SEVERE : Level.WARNING, p.toString());
        }
    }
}
