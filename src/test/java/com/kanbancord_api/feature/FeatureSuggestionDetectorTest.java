package com.kanbancord_api.feature;

import com.kanbancord_api.feature.FeatureSuggestionDetector.ColumnText;
import com.kanbancord_api.feature.FeatureSuggestionDetector.Finding;
import com.kanbancord_api.feature.FeatureSuggestionDetector.TaskText;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureSuggestionDetectorTest {

    private static final List<ColumnText> WORKFLOW = List.of(new ColumnText(1L, "To Do"),
            new ColumnText(2L, "In Progress"), new ColumnText(3L, "Done"));

    private static List<TaskText> titles(String... titles) {
        return java.util.Arrays.stream(titles).map(title -> new TaskText(title, null, 1L)).toList();
    }

    private static Map<Feature, Finding> detect(List<TaskText> tasks, List<ColumnText> columns, Set<String> members) {
        return FeatureSuggestionDetector.detect(tasks, columns, members).stream()
                .collect(Collectors.toMap(Finding::feature, finding -> finding));
    }

    private static Map<Feature, Finding> detect(String... titles) {
        return detect(titles(titles), WORKFLOW, Set.of());
    }

    @Test
    void tagsRebuiltByHand_suggestLabelsAndPriorities() {
        // As seen on a real board: story codes, a size and Essential/Desirable in every title.
        Map<Feature, Finding> found = detect(
                "[US1-T3] [M] [E] Implement randomized event-space generation and activation",
                "[US2-T8] [S] [D] Implement point-earned notifications",
                "[SH-10] [M] [E] Perform end-to-end testing across gameplay and reward systems",
                "[US1-T4] [S] [E] Add the event board",
                "[US2-T9] [M] [D] Show the score");
        assertEquals(Set.of(Feature.LABELS, Feature.PRIORITIES), found.keySet());
        assertEquals("TAGS", found.get(Feature.LABELS).signal());
        assertEquals(5, found.get(Feature.LABELS).count());
        assertTrue(found.get(Feature.LABELS).examples().contains("[M]"), found.get(Feature.LABELS).examples().toString());
        assertEquals(Set.of("[E]", "[D]"), Set.copyOf(found.get(Feature.PRIORITIES).examples()),
                "E and D are priorities, while S and M are sizes");
    }

    @Test
    void commonPriorityMarks_suggestPriorities() {
        Map<Feature, Finding> found = detect("[P1] Fix login", "Fix crash (high prio)", "URGENT: payment fails",
                "Write docs");
        assertEquals(Set.of(Feature.PRIORITIES), found.keySet());
        assertEquals(3, found.get(Feature.PRIORITIES).count());

        assertEquals(Set.of(Feature.PRIORITIES), detect("[H] One", "[L] Two", "[M] Three", "[H] Four").keySet(),
                "H, M and L are high, medium and low");
        assertEquals(Set.of(Feature.PRIORITIES), detect("[M] One", "[S] Two", "[C] Three", "[W] Four").keySet(),
                "with C or W, M and S are MoSCoW");
        assertEquals(Set.of(Feature.LABELS), detect("[M] One", "[S] Two", "[L] Three", "[M] Four").keySet(),
                "S, M and L alone are sizes");
    }

    @Test
    void deadlinesInTitlesOrDescriptions_suggestDueDates() {
        List<TaskText> tasks = List.of(
                new TaskText("Hand in report by Friday", null, 1L),
                new TaskText("Slides", "Deadline: 12 Nov", 1L),
                new TaskText("Book the room [12/11]", null, 1L),
                new TaskText("Order shirts 2026-11-20", null, 1L),
                new TaskText("Refactor the parser", "Slow due to regex backtracking", 1L));
        Finding found = detect(tasks, WORKFLOW, Set.of()).get(Feature.DUE_DATES);
        assertEquals(4, found.count(), "\"due to\" is not a deadline");
    }

    @Test
    void mentionsAndMembersNames_suggestAssignees_butCodeDoesNot() {
        Set<String> members = new HashSet<>();
        FeatureSuggestionDetector.addMemberNames(List.of("rudra_k", "Rudra Kumar", "samlee", "Sam"), members);
        List<TaskText> tasks = List.of(
                new TaskText("Draw the logo (Sam)", null, 1L),
                new TaskText("Write the intro for Rudra", null, 1L),
                new TaskText("Fix the menu", "@samlee can you look?", 1L),
                new TaskText("Plan the launch", null, 1L));
        assertEquals(3, detect(tasks, WORKFLOW, members).get(Feature.ASSIGNEES).count());

        List<TaskText> code = List.of(
                new TaskText("Add a service", "Annotate it with `@Service` and @Transactional", 1L),
                new TaskText("Add tests", "Use @Test and @BeforeEach", 1L),
                new TaskText("Style it", "Inside @media (max-width: 600px)", 1L),
                new TaskText("Support Linux", "Mail help@example.com", 1L));
        assertEquals(Map.of(), detect(code, WORKFLOW, Set.of()), "annotations, CSS and e-mail are not people");

        List<ColumnText> people = List.of(new ColumnText(1L, "Sam"), new ColumnText(2L, "Rudra"),
                new ColumnText(3L, "Done"));
        Finding byColumn = detect(titles("A", "B", "C"), people, members).get(Feature.ASSIGNEES);
        assertEquals("COLUMNS", byColumn.signal());
    }

    @Test
    void categoryColumnsNextToStages_suggestLabels() {
        List<ColumnText> columns = List.of(new ColumnText(1L, "User Story #1"), new ColumnText(2L, "User Story #2"),
                new ColumnText(3L, "Shared"), new ColumnText(4L, "To-Do"), new ColumnText(5L, "In Progress"),
                new ColumnText(6L, "Done"));
        List<TaskText> tasks = List.of(new TaskText("A", null, 1L), new TaskText("B", null, 2L),
                new TaskText("C", null, 3L), new TaskText("D", null, 4L));
        Finding found = detect(tasks, columns, Set.of()).get(Feature.LABELS);
        assertEquals("COLUMNS", found.signal());
        assertEquals(List.of("User Story #1", "User Story #2", "Shared"), found.examples());

        List<ColumnText> weeks = List.of(new ColumnText(1L, "Week 1"), new ColumnText(2L, "Week 2"),
                new ColumnText(4L, "To-Do"), new ColumnText(6L, "Done"));
        assertTrue(detect(tasks, weeks, Set.of()).isEmpty(), "weeks are not categories");
    }

    @Test
    void ordinaryBoards_suggestNothing() {
        assertTrue(detect("Buy milk", "Fix the login", "Write docs", "Ship it", "Call the venue").isEmpty());
        assertTrue(detect("[WIP] Fix login", "Write docs", "Ship it", "Plan").isEmpty(), "one tag is not a pattern");
        assertTrue(detect("KC-1 Fix login", "KC-2 Write docs", "KC-3 Ship it", "KC-4 Plan").isEmpty(),
                "one code prefix is numbering, not categories");
        assertTrue(detect("[1] First", "[2] Second", "[3] Third", "#4 Fourth").isEmpty(), "numbers are not tags");
        assertTrue(detect("[x] Done thing", "[x] Other", "[ ] Open", "[x] Last").isEmpty(), "checkboxes");
        assertTrue(detect("Fix it", "Ship 2.5 version", "Read 3 decks", "Draw on 2.5").isEmpty(),
                "numbers and words that merely look like dates");
        assertTrue(detect("[Bug] Crash", "Write docs").isEmpty(), "fewer than three tasks never suggest anything");
    }

    @Test
    void aFewTagsOnABigBoard_areNotEnough() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add("Task " + i);
        }
        many.addAll(List.of("[Bug] One", "[Bug] Two", "[Bug] Three"));
        assertTrue(detect(many.toArray(String[]::new)).isEmpty(), "three of 43 is not a clear share");
    }
}
