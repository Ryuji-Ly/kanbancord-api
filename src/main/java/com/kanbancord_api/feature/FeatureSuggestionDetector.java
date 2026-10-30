package com.kanbancord_api.feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notices a board doing an optional feature by hand: tags like {@code [M]} or {@code [Frontend]} at the start of
 * titles (labels), {@code [E]}, {@code P1} or "high prio" (priorities), deadlines written into titles (due dates),
 * {@code @name} or a member's name on tasks (assignees), and columns used as categories or named after people.
 * Works only on what the board already holds, when asked. Errs on the side of saying nothing: a pattern counts only
 * on several tasks, never because of one title.
 */
public final class FeatureSuggestionDetector {

    /** A task's own text. */
    public record TaskText(String title, String description, Long columnId) {
    }

    /** A column, by its name. */
    public record ColumnText(Long columnId, String name) {
    }

    /**
     * What was noticed for one feature.
     *
     * @param signal   TAGS or COLUMNS for labels, MARKS for priorities, DATES for due dates, NAMES or COLUMNS for
     *                 assignees
     * @param examples up to three examples as they are written on the board, to quote back
     * @param count    the tasks (or, for COLUMNS, the columns) it was seen on
     */
    public record Finding(Feature feature, String signal, List<String> examples, int count) {
    }

    /** Fewer tasks than this never make a suggestion, whatever share of the board they are. */
    static final int MIN_TASKS = 3;
    /** On bigger boards, the share of tasks a pattern must be on. */
    static final double MIN_SHARE = 0.15;
    private static final int MAX_EXAMPLES = 3;

    /** One tag at the start of a title: [x], (x), {x}, #x, or a code like US1-T3 or SH-10. */
    private static final Pattern LEADING_TAG = Pattern.compile(
            "^\\s*(?:\\[([^\\[\\]]{1,24})]|\\(([^()]{1,24})\\)|\\{([^{}]{1,24})}|#([\\p{L}\\p{N}_-]{1,24})(?=\\s)"
                    + "|([A-Za-z]{1,6}\\d{0,3}-[A-Za-z]{0,3}\\d{1,4})(?=[\\s:|]))\\s*[:|\\-–]?");
    /** "Bug: ...", "Feature: ...": a kind of task written as a prefix, which is a label. */
    private static final Pattern TYPE_PREFIX = Pattern.compile("^\\s*([\\p{L}]{2,12})\\s*:\\s+\\S");
    private static final Set<String> TYPE_WORDS = Set.of("bug", "bugs", "fix", "feat", "feature", "docs", "doc",
            "test", "tests", "chore", "refactor", "ui", "ux", "api", "design", "research", "idea", "epic", "story",
            "spike", "hotfix", "frontend", "backend", "infra", "ops", "content", "art", "audio", "music", "marketing",
            "event", "meeting", "admin");
    /** A code tag: the part before the dash names the area, as in US1-T3 or SH-10. */
    private static final Pattern CODE = Pattern.compile("^([A-Za-z]{1,6}\\d{0,3})-[A-Za-z]{0,3}\\d{1,4}$");

    /** Whole tags that mean a priority. */
    private static final Set<String> PRIORITY_TAGS = Set.of("P0", "P1", "P2", "P3", "P4", "HIGH", "LOW", "MEDIUM",
            "MED", "URGENT", "CRITICAL", "CRIT", "BLOCKER", "ASAP", "MUST", "SHOULD", "COULD", "WONT", "WON'T",
            "MUST HAVE", "SHOULD HAVE", "COULD HAVE", "NICE TO HAVE", "ESSENTIAL", "DESIRABLE", "HIGH PRIO",
            "HIGH PRIORITY", "LOW PRIO", "LOW PRIORITY", "MEDIUM PRIORITY", "TOP PRIORITY", "!", "!!", "!!!");
    /** Sizes, which are labels: S and M in "[S] [M]" are sizes, not MoSCoW, unless C or W are there too. */
    private static final Set<String> SIZES = Set.of("XS", "S", "M", "L", "XL", "XXL");
    /** Priorities written into a title rather than as a tag. */
    private static final Pattern PRIORITY_WORDS = Pattern.compile(
            "(?i)\\b(?:(?:high|higher|highest|low|lower|lowest|top|med|medium)[ -]?prio(?:rity)?|prio(?:rity)?\\s*[:=]\\s*"
                    + "(?:high|low|med|medium|urgent|\\d)|urgent|asap)\\b|(?:^|\\s)!{2,3}(?:\\s|$)");

    private static final String MONTHS = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
            + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b\\.?";
    private static final String WEEKDAYS = "(?:mon|tues?|wed|thu|thur|thurs|fri|sat|sun)(?:day|nesday|rsday|urday)?";
    /** 12/11, 12/11/26 or 12.11.2026; with dots only with a year, so 2.5 is not a date. */
    private static final String NUMERIC_DATE = "\\d{1,2}(?:/\\d{1,2}(?:/\\d{2,4})?|\\.\\d{1,2}\\.\\d{2,4})";
    /** A date or deadline written into a title or description. "Due to" (because of) is not a deadline. */
    private static final Pattern DATE = Pattern.compile("(?i)"
            + "\\b(?:19|20)\\d{2}-\\d{1,2}-\\d{1,2}\\b"
            + "|\\b(?:over)?due\\b(?!\\s+to\\b)|\\bdeadlines?\\b"
            + "|\\b(?:by|before|until|till)\\s+(?:tomorrow|tonight|today|eod|eow|eom|end of (?:the )?(?:day|week|month|sprint)"
            + "|next (?:week|month)|" + WEEKDAYS + ")\\b"
            + "|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+" + MONTHS
            + "|\\b" + MONTHS + "\\s+\\d{1,2}(?:st|nd|rd|th)?\\b"
            + "|\\b(?:by|before|until|till|due|on)\\s+" + NUMERIC_DATE + "\\b"
            + "|\\[" + NUMERIC_DATE + "]");

    /** A mention: @name (not an e-mail address), or Discord's own mention markup. */
    private static final Pattern MENTION = Pattern.compile("(?<![\\w.])@([\\p{L}\\p{N}_.]{2,32})|<@!?\\d+>");
    private static final Set<String> NOT_PEOPLE = Set.of("everyone", "here", "media", "import", "keyframes", "apply",
            "font-face", "param", "return", "returns", "see", "since", "throws", "todo", "deprecated");
    /** Code in a description (`...` and ```...```), left out when looking for mentions. */
    private static final Pattern CODE_SPANS = Pattern.compile("```[\\s\\S]*?```|`[^`\\n]*`");
    private static final Pattern ASSIGNED_WORDS = Pattern.compile(
            "(?i)\\b(?:assigned to|assignee|owner|responsible)\\s*:?\\s+@?[\\p{L}\\p{N}_.]{2,32}");
    /** Where a name usually goes: "Alex: ...", "... (Sam)", "... - Sam", "... for Rudra". */
    private static final Pattern NAME_PLACES = Pattern.compile(
            "^\\s*([\\p{L}][\\p{L}\\p{N}_.]{1,31})\\s*:\\s|\\(([\\p{L}][\\p{L}\\p{N}_.]{1,31})\\)\\s*$"
                    + "|\\s[-–]\\s*([\\p{L}][\\p{L}\\p{N}_.]{1,31})\\s*$|(?i:\\bfor)\\s+([\\p{L}][\\p{L}\\p{N}_.]{1,31})\\s*$");

    /** Words that make a column a workflow stage rather than a category. */
    private static final Pattern WORKFLOW_COLUMN = Pattern.compile("(?i)\\b(?:to ?-?do|todo|backlog|doing|in progress|"
            + "progress|wip|working|review|reviewing|testing|tested|qa|done|complete|completed|finished|closed|blocked|"
            + "on hold|waiting|ideas?|next|later|ready|icebox|archived?|shipped|released|deployed|planned|planning|"
            + "open|new|inbox|started|pending|approved|cancell?ed|rejected)\\b");
    /** Columns that are planning buckets (weeks, sprints, days), neither stages nor categories. */
    private static final Pattern TIME_COLUMN = Pattern.compile("(?i)\\b(?:week|sprint|phase|milestone|month|day|"
            + "q[1-4]|" + WEEKDAYS + "|" + MONTHS + ")\\b|\\d{4}");

    private FeatureSuggestionDetector() {
    }

    /**
     * @param memberNames the server's members' names, lower case, for recognising names on tasks and columns; may
     *                    be empty
     */
    public static List<Finding> detect(List<TaskText> tasks, List<ColumnText> columns, Set<String> memberNames) {
        List<Finding> findings = new ArrayList<>();
        if (tasks.size() < MIN_TASKS) {
            return findings;
        }
        int needed = Math.max(MIN_TASKS, (int) Math.ceil(tasks.size() * MIN_SHARE));
        List<List<Tag>> tags = tasks.stream().map(task -> leadingTags(task.title())).toList();
        Map<Integer, String> slotKinds = classifyShortTagSlots(tags);

        labels(tasks, tags, slotKinds, columns, memberNames, needed).ifPresent(findings::add);
        priorities(tasks, tags, slotKinds, needed).ifPresent(findings::add);
        dueDates(tasks, needed).ifPresent(findings::add);
        assignees(tasks, columns, memberNames, needed).ifPresent(findings::add);
        return findings;
    }

    // ── Tags ────────────────────────────────────────────────────────────────

    /**
     * A tag at the start of a title, as written ({@code [M]}) and as compared ({@code M}). {@code shortSlot} is its
     * place among the title's one- and two-letter tags, or -1.
     */
    record Tag(String written, String value, boolean code, int shortSlot) {
    }

    static List<Tag> leadingTags(String title) {
        List<Tag> tags = new ArrayList<>();
        String rest = title == null ? "" : title;
        int shortSlot = 0;
        while (tags.size() < 5) {
            Matcher matcher = LEADING_TAG.matcher(rest);
            if (!matcher.find()) {
                break;
            }
            String value = null;
            for (int group = 1; group <= 5 && value == null; group++) {
                value = matcher.group(group);
            }
            String written = matcher.group().strip().replaceAll("\\s*[:|\\-–]$", "");
            rest = rest.substring(matcher.end());
            value = value.strip().toUpperCase(Locale.ROOT);
            if (value.isEmpty() || value.chars().allMatch(Character::isDigit) || value.equals("X")) {
                // A number ([1], #12) or a checkbox ([x], [ ]): not a tag.
                continue;
            }
            boolean code = CODE.matcher(value).matches();
            boolean isShort = !code && value.length() <= 2 && value.chars().allMatch(Character::isLetter);
            tags.add(new Tag(written, value, code, isShort ? shortSlot++ : -1));
        }
        if (tags.isEmpty()) {
            Matcher type = TYPE_PREFIX.matcher(rest);
            if (type.find() && TYPE_WORDS.contains(type.group(1).toLowerCase(Locale.ROOT))) {
                tags.add(new Tag(type.group(1) + ":", type.group(1).toUpperCase(Locale.ROOT), false, -1));
            }
        }
        return tags;
    }

    /**
     * Decides what each place of one- and two-letter tags means across the board: "[M] [E]" on one task and
     * "[S] [D]" on another make the first place sizes (labels) and the second Essential/Desirable (priorities).
     */
    static Map<Integer, String> classifyShortTagSlots(List<List<Tag>> tags) {
        Map<Integer, Set<String>> values = new HashMap<>();
        for (List<Tag> taskTags : tags) {
            for (Tag tag : taskTags) {
                if (tag.shortSlot() >= 0) {
                    values.computeIfAbsent(tag.shortSlot(), slot -> new HashSet<>()).add(tag.value());
                }
            }
        }
        Map<Integer, String> kinds = new HashMap<>();
        values.forEach((slot, seen) -> kinds.put(slot, shortTagKind(seen)));
        return kinds;
    }

    private static String shortTagKind(Set<String> seen) {
        boolean essentialDesirable = Set.of("E", "D", "O", "N").containsAll(seen)
                && (seen.contains("E") || seen.contains("D"));
        boolean highLow = Set.of("H", "M", "L").containsAll(seen) && seen.contains("H");
        boolean moscow = Set.of("M", "S", "C", "W").containsAll(seen) && (seen.contains("C") || seen.contains("W"));
        return essentialDesirable || highLow || moscow ? "PRIORITY" : "LABEL";
    }

    private static boolean isPriorityTag(Tag tag, Map<Integer, String> slotKinds) {
        if (tag.shortSlot() >= 0) {
            return "PRIORITY".equals(slotKinds.get(tag.shortSlot()));
        }
        return PRIORITY_TAGS.contains(tag.value());
    }

    // ── Labels ──────────────────────────────────────────────────────────────

    private static Optional<Finding> labels(List<TaskText> tasks, List<List<Tag>> tags,
            Map<Integer, String> slotKinds, List<ColumnText> columns, Set<String> memberNames, int needed) {
        // A tag counts when it is on more than one task; codes count by area, and only when there are several
        // areas (KC-1, KC-2 is numbering, US1-T3 and SH-10 are two user stories). One- and two-letter tags count
        // by their place, so [S], [M] and [L] once each are one size tag on three tasks.
        Map<String, Integer> uses = new HashMap<>();
        Map<Integer, Integer> slotUses = new HashMap<>();
        Map<String, String> written = new LinkedHashMap<>();
        Set<String> codeAreas = new HashSet<>();
        for (List<Tag> taskTags : tags) {
            Set<String> onTask = new HashSet<>();
            for (Tag tag : taskTags) {
                String key = labelKey(tag, slotKinds);
                if (key != null && onTask.add(key)) {
                    uses.merge(key, 1, Integer::sum);
                    written.putIfAbsent(key, tag.written());
                    if (tag.code()) {
                        codeAreas.add(key);
                    }
                    if (tag.shortSlot() >= 0) {
                        slotUses.merge(tag.shortSlot(), 1, Integer::sum);
                    }
                }
            }
        }
        Set<String> counted = new HashSet<>();
        for (List<Tag> taskTags : tags) {
            for (Tag tag : taskTags) {
                String key = labelKey(tag, slotKinds);
                boolean repeated = tag.shortSlot() >= 0
                        ? slotUses.getOrDefault(tag.shortSlot(), 0) >= 2
                        : key != null && uses.get(key) >= 2 && (!tag.code() || codeAreas.size() >= 2);
                if (key != null && repeated) {
                    counted.add(key);
                }
            }
        }
        int tagged = 0;
        for (List<Tag> taskTags : tags) {
            if (taskTags.stream().map(tag -> labelKey(tag, slotKinds)).anyMatch(counted::contains)) {
                tagged++;
            }
        }
        if (tagged >= needed) {
            List<String> examples = counted.stream()
                    .sorted((a, b) -> uses.get(b) - uses.get(a))
                    .map(written::get)
                    .limit(MAX_EXAMPLES)
                    .toList();
            return Optional.of(new Finding(Feature.LABELS, "TAGS", examples, tagged));
        }

        // Columns used as categories ("User Story #1", "Shared") next to workflow stages ("To-Do", "Done").
        List<ColumnText> stages = new ArrayList<>();
        List<ColumnText> categories = new ArrayList<>();
        for (ColumnText column : columns) {
            String name = column.name() == null ? "" : column.name();
            if (WORKFLOW_COLUMN.matcher(name).find()) {
                stages.add(column);
            } else if (!TIME_COLUMN.matcher(name).find() && !isMemberName(name, memberNames)) {
                categories.add(column);
            }
        }
        Set<Long> categoryIds = new HashSet<>();
        categories.forEach(column -> categoryIds.add(column.columnId()));
        long inCategories = tasks.stream().filter(task -> categoryIds.contains(task.columnId())).count();
        if (stages.size() >= 2 && categories.size() >= 2 && inCategories >= MIN_TASKS) {
            return Optional.of(new Finding(Feature.LABELS, "COLUMNS",
                    categories.stream().map(ColumnText::name).limit(MAX_EXAMPLES).toList(), categories.size()));
        }
        return Optional.empty();
    }

    /** What the tag is compared by as a label, or null when it is not one. */
    private static String labelKey(Tag tag, Map<Integer, String> slotKinds) {
        if (isPriorityTag(tag, slotKinds) || DATE.matcher(tag.written()).find()) {
            return null;
        }
        if (tag.code()) {
            Matcher code = CODE.matcher(tag.value());
            return code.matches() ? code.group(1) : null;
        }
        return tag.value();
    }

    // ── Priorities ──────────────────────────────────────────────────────────

    private static Optional<Finding> priorities(List<TaskText> tasks, List<List<Tag>> tags,
            Map<Integer, String> slotKinds, int needed) {
        Map<String, Integer> uses = new LinkedHashMap<>();
        int marked = 0;
        for (int i = 0; i < tasks.size(); i++) {
            String mark = null;
            for (Tag tag : tags.get(i)) {
                if (isPriorityTag(tag, slotKinds)) {
                    mark = tag.written();
                    break;
                }
            }
            if (mark == null) {
                Matcher words = PRIORITY_WORDS.matcher(text(tasks.get(i).title()));
                if (words.find()) {
                    mark = words.group().strip();
                }
            }
            if (mark != null) {
                marked++;
                uses.merge(mark, 1, Integer::sum);
            }
        }
        if (marked < needed) {
            return Optional.empty();
        }
        return Optional.of(new Finding(Feature.PRIORITIES, "MARKS", mostUsed(uses), marked));
    }

    // ── Due dates ───────────────────────────────────────────────────────────

    private static Optional<Finding> dueDates(List<TaskText> tasks, int needed) {
        Map<String, Integer> uses = new LinkedHashMap<>();
        int dated = 0;
        for (TaskText task : tasks) {
            Matcher date = DATE.matcher(text(task.title()) + "\n" + text(task.description()));
            if (date.find()) {
                dated++;
                uses.merge(date.group().strip(), 1, Integer::sum);
            }
        }
        if (dated < needed) {
            return Optional.empty();
        }
        return Optional.of(new Finding(Feature.DUE_DATES, "DATES", mostUsed(uses), dated));
    }

    // ── Assignees ───────────────────────────────────────────────────────────

    private static Optional<Finding> assignees(List<TaskText> tasks, List<ColumnText> columns,
            Set<String> memberNames, int needed) {
        Map<String, Integer> uses = new LinkedHashMap<>();
        int named = 0;
        for (TaskText task : tasks) {
            String name = nameOn(task, memberNames);
            if (name != null) {
                named++;
                uses.merge(name, 1, Integer::sum);
            }
        }
        if (named >= needed) {
            return Optional.of(new Finding(Feature.ASSIGNEES, "NAMES", mostUsed(uses), named));
        }

        List<String> people = columns.stream()
                .map(ColumnText::name)
                .filter(name -> name != null && isMemberName(name, memberNames))
                .toList();
        if (people.size() >= 2) {
            return Optional.of(new Finding(Feature.ASSIGNEES, "COLUMNS",
                    people.stream().limit(MAX_EXAMPLES).toList(), people.size()));
        }
        return Optional.empty();
    }

    /** The person a task is marked for, as written, or null. A bare name counts only if it is a member's. */
    private static String nameOn(TaskText task, Set<String> memberNames) {
        String all = text(task.title()) + "\n" + CODE_SPANS.matcher(text(task.description())).replaceAll(" ");
        Matcher mention = MENTION.matcher(all);
        while (mention.find()) {
            String handle = mention.group(1);
            // Discord usernames are lower case; @Override or @Test in a developer's notes is code, not a person.
            boolean person = handle == null || isMemberName(handle, memberNames)
                    || (handle.equals(handle.toLowerCase(Locale.ROOT)) && !NOT_PEOPLE.contains(handle));
            if (person) {
                return mention.group();
            }
        }
        Matcher assigned = ASSIGNED_WORDS.matcher(all);
        if (assigned.find()) {
            return assigned.group();
        }
        Matcher place = NAME_PLACES.matcher(text(task.title()));
        while (place.find()) {
            for (int group = 1; group <= 4; group++) {
                String name = place.group(group);
                if (name != null && isMemberName(name, memberNames)) {
                    return name;
                }
            }
        }
        return null;
    }

    private static boolean isMemberName(String name, Set<String> memberNames) {
        String key = name.strip().toLowerCase(Locale.ROOT);
        return key.length() >= 3 && memberNames.contains(key) && !WORKFLOW_COLUMN.matcher(key).find()
                && !TYPE_WORDS.contains(key);
    }

    /**
     * The names a member goes by, lower case, for {@link #detect}: username, display name, nickname, and the first
     * word of the last two ("Sam" for "Sam Lee").
     */
    public static void addMemberNames(Collection<String> names, Set<String> into) {
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String lower = name.strip().toLowerCase(Locale.ROOT);
            into.add(lower);
            String first = lower.split("\\s+")[0];
            if (first.length() >= 3) {
                into.add(first);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static List<String> mostUsed(Map<String, Integer> uses) {
        return uses.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(Map.Entry::getKey)
                .limit(MAX_EXAMPLES)
                .toList();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
