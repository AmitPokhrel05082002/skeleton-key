package dev.skeletonkey.record;

import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.Option;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure validator for record payloads against an {@link Entity}. Collects every missing required field,
 * every unknown key and every failing field in a single pass.
 */
public final class RecordValidator {

    public enum Mode { CREATE, UPDATE }

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 ()\\-.]{5,18}[0-9]$");

    /** Validates {@code payload}; an empty list means it is acceptable. */
    public List<FieldViolation> validate(Entity entity, Map<String, Object> payload, Mode mode) {
        List<FieldViolation> out = new ArrayList<>();

        // Unknown keys (7.5)
        for (String key : payload.keySet()) {
            if (entity.field(key).isEmpty()) {
                out.add(new FieldViolation(key, "UNKNOWN_FIELD", "Unknown field '" + key + "'"));
            }
        }

        for (Field f : entity.fields()) {
            boolean present = payload.containsKey(f.code());
            Object value = payload.get(f.code());
            if (value == null) {
                // Missing or null required field (7.4). On update only an explicit null is a violation.
                if (f.required() && (mode == Mode.CREATE || present)) {
                    out.add(new FieldViolation(f.code(), "REQUIRED", "Field '" + f.code() + "' is required"));
                }
                continue;
            }
            String problem = check(f, value);
            if (problem != null) {
                out.add(new FieldViolation(f.code(), constraintOf(problem), message(f, problem)));
            }
        }
        return out;
    }

    // A problem is encoded as "CONSTRAINT" or "CONSTRAINT:detail".
    private static String constraintOf(String problem) {
        int i = problem.indexOf(':');
        return i < 0 ? problem : problem.substring(0, i);
    }

    private static String message(Field f, String problem) {
        int i = problem.indexOf(':');
        String detail = i < 0 ? "" : " (" + problem.substring(i + 1) + ")";
        return "Field '" + f.code() + "' violates " + constraintOf(problem) + detail;
    }

    private String check(Field f, Object v) {
        return switch (f.type()) {
            case TEXT, TEXTAREA -> checkText(f, v);
            case NUMBER -> checkNumber(f, v, true);
            case DECIMAL -> checkNumber(f, v, false);
            case EMAIL -> v instanceof String s ? (EMAIL.matcher(s).matches() ? null : "EMAIL") : "TYPE:string";
            case PHONE -> v instanceof String s ? (PHONE.matcher(s).matches() ? null : "PHONE") : "TYPE:string";
            case DATE -> checkDate(v);
            case DATETIME -> checkDateTime(v);
            case BOOLEAN -> v instanceof Boolean ? null : "TYPE:boolean";
            case SELECT -> checkSelect(f, v);
            case MULTISELECT -> checkMultiSelect(f, v);
            case USER, REFERENCE -> checkUuid(v);
            case FILE -> checkFile(f, v);
            case LOCATION -> checkLocation(v);
        };
    }

    private String checkText(Field f, Object v) {
        if (!(v instanceof String s)) return "TYPE:string";
        if (f.maxLength() != null && s.codePointCount(0, s.length()) > f.maxLength()) {
            return "MAX_LENGTH:" + f.maxLength();
        }
        if (f.pattern() != null) {
            try {
                if (!Pattern.compile(f.pattern()).matcher(s).find()) return "PATTERN:" + f.pattern();
            } catch (PatternSyntaxException e) {
                return "PATTERN:" + f.pattern();
            }
        }
        return null;
    }

    private String checkNumber(Field f, Object v, boolean integerOnly) {
        if (!(v instanceof Number n)) return "TYPE:number";
        BigDecimal d;
        try {
            if (n instanceof BigDecimal bd) d = bd;
            else if (n instanceof BigInteger bi) d = new BigDecimal(bi);
            else if (n instanceof Double || n instanceof Float) {
                double x = n.doubleValue();
                if (Double.isNaN(x) || Double.isInfinite(x)) return "TYPE:number";
                d = BigDecimal.valueOf(x);
            } else d = BigDecimal.valueOf(n.longValue());
        } catch (NumberFormatException e) {
            return "TYPE:number";
        }
        if (integerOnly && d.stripTrailingZeros().scale() > 0) return "TYPE:integer";
        if (f.min() != null && d.compareTo(BigDecimal.valueOf(f.min())) < 0) return "MIN:" + f.min();
        if (f.max() != null && d.compareTo(BigDecimal.valueOf(f.max())) > 0) return "MAX:" + f.max();
        return null;
    }

    private String checkDate(Object v) {
        if (!(v instanceof String s)) return "TYPE:string";
        try {
            LocalDate.parse(s);
            return null;
        } catch (DateTimeParseException e) {
            return "DATE:YYYY-MM-DD";
        }
    }

    private String checkDateTime(Object v) {
        if (!(v instanceof String s)) return "TYPE:string";
        try {
            OffsetDateTime.parse(s);
            return null;
        } catch (DateTimeParseException e) {
            return "DATETIME:ISO-8601 with offset";
        }
    }

    private String checkSelect(Field f, Object v) {
        if (!(v instanceof String s)) return "TYPE:string";
        return hasOption(f, s) ? null : "OPTION";
    }

    private String checkMultiSelect(Field f, Object v) {
        if (!(v instanceof List<?> list)) return "TYPE:array";
        Set<Object> seen = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s)) return "TYPE:array of strings";
            if (!hasOption(f, s)) return "OPTION";
            if (!seen.add(s)) return "DUPLICATE";
        }
        return null;
    }

    private boolean hasOption(Field f, String value) {
        for (Option o : f.options()) {
            if (o.value().equals(value)) return true;
        }
        return false;
    }

    private String checkUuid(Object v) {
        if (!(v instanceof String s)) return "TYPE:string";
        try {
            UUID.fromString(s);
            return s.length() == 36 ? null : "UUID";
        } catch (IllegalArgumentException e) {
            return "UUID";
        }
    }

    private String checkFile(Field f, Object v) {
        if (!(v instanceof List<?> list)) return "TYPE:array";
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) return "TYPE:array of file references";
        }
        if (f.maxFiles() != null && list.size() > f.maxFiles()) return "MAX_FILES:" + f.maxFiles();
        return null;
    }

    private String checkLocation(Object v) {
        if (!(v instanceof Map<?, ?> m)) return "TYPE:object";
        Object lat = m.get("lat");
        Object lng = m.get("lng");
        if (!(lat instanceof Number la) || !(lng instanceof Number lo)) return "LOCATION:lat and lng numbers";
        double a = la.doubleValue();
        double b = lo.doubleValue();
        if (Double.isNaN(a) || Double.isNaN(b) || a < -90 || a > 90 || b < -180 || b > 180) {
            return "LOCATION:lat in [-90,90], lng in [-180,180]";
        }
        return null;
    }
}
