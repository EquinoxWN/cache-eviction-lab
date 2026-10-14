package portfolio.cacheevictionlab.cli;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal command-line parser: {@code --name value} options only, each at most once. */
final class Args {
    /** Most items accepted in one comma-separated list option. */
    static final int MAX_LIST = 20;

    private final Map<String, String> options = new HashMap<>();

    /** Parses arguments, accepting only the named options. */
    Args(List<String> args, Set<String> allowedOptions) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!a.startsWith("--")) {
                throw new UsageException("unexpected argument '" + a + "'");
            }
            String name = a.substring(2);
            if (!allowedOptions.contains(name)) {
                throw new UsageException("unknown option --" + name);
            }
            if (i + 1 >= args.size()) {
                throw new UsageException("--" + name + " needs a value");
            }
            if (options.put(name, args.get(++i)) != null) {
                throw new UsageException("--" + name + " given twice");
            }
        }
    }

    /** Option value or null. */
    String option(String name) {
        return options.get(name);
    }

    /** Option value or the default. */
    String option(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    /** Option value that must be present. */
    String required(String name) {
        String value = options.get(name);
        if (value == null) {
            throw new UsageException("--" + name + " is required");
        }
        return value;
    }

    /** Comma-separated non-empty items, at most {@link #MAX_LIST}, without duplicates. */
    List<String> list(String name, String fallback) {
        List<String> items = new ArrayList<>();
        for (String item : option(name, fallback).split(",", -1)) {
            String trimmed = item.strip();
            if (trimmed.isEmpty()) {
                throw new UsageException("--" + name + " has an empty item");
            }
            if (items.contains(trimmed)) {
                throw new UsageException("--" + name + " lists '" + trimmed + "' twice");
            }
            items.add(trimmed);
        }
        if (items.size() > MAX_LIST) {
            throw new UsageException("--" + name + " accepts at most " + MAX_LIST + " items");
        }
        return items;
    }

    /** Comma-separated percentages above 0 and at most 100. */
    List<BigDecimal> percentages(String name, String fallback) {
        List<BigDecimal> values = new ArrayList<>();
        for (String item : list(name, fallback)) {
            BigDecimal value;
            try {
                value = new BigDecimal(item);
            } catch (NumberFormatException e) {
                throw new UsageException("--" + name + " must list numbers, got '" + item + "'");
            }
            if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0 || value.scale() > 6) {
                throw new UsageException("--" + name + " values must be above 0 and at most 100, got " + item);
            }
            values.add(value);
        }
        return values;
    }

    /** Bad command-line input; Main prints it with the usage text and exits with 2. */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception with a message for the user. */
        UsageException(String message) {
            super(message);
        }
    }
}
