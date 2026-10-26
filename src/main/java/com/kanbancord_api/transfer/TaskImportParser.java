package com.kanbancord_api.transfer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.exception.BadRequestException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads tasks out of pasted text or a file, whatever shape it has: a plain list (one task per line,
 * bullets, numbers or checkboxes, with indented sub-items and headings for columns), a table (CSV,
 * TSV as pasted from a spreadsheet, or Markdown) or a KanbanCord board file. It only reads: which
 * board, column, label or priority each task ends up with is decided by {@link TaskImportCommands}.
 */
public final class TaskImportParser {

    public enum Format { AUTO, LIST, CSV, TSV, MARKDOWN, KANBANCORD }

    /** What a table column holds. */
    public enum Field { TITLE, DESCRIPTION, COLUMN, DUE, PRIORITY, LABELS, IGNORE }

    /**
     * One task as read.
     *
     * @param column the column it names (a heading, or a table's column cell), or null
     * @param done   a ticked checkbox: it goes to the board's last column
     */
    public record Item(String title, String description, String column, boolean done, String due, String priority,
                       List<String> labels) {
    }

    /**
     * @param headers a table's headers (made up when it has none), for choosing what each one holds
     * @param fields  what each header was read as, in the same order
     */
    public record Parsed(Format format, List<String> headers, List<Field> fields, List<Item> items) {
    }

    /** Header names understood without being told, in lower case. */
    private static final Map<String, Field> HEADER_NAMES = headerNames();

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)(?:[-*+•]|\\d{1,4}[.)])\\s+(?:\\[([ xX])]\\s*)?(.*)$");
    private static final Pattern CHECKBOX = Pattern.compile("^(\\s*)\\[([ xX])]\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private TaskImportParser() {
    }

    /**
     * @param fields what each table column holds, as chosen in the preview; null to work it out
     *               from the headers
     */
    public static Parsed parse(String text, Format format, List<Field> fields) {
        String source = text == null ? "" : text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        Format chosen = format == null || format == Format.AUTO ? detect(source) : format;
        return switch (chosen) {
            case KANBANCORD -> new Parsed(chosen, List.of(), List.of(), boardFile(source));
            case CSV -> table(chosen, rows(source, ','), fields);
            case TSV -> table(chosen, rows(source, '\t'), fields);
            case MARKDOWN -> table(chosen, markdownRows(source), fields);
            default -> new Parsed(Format.LIST, List.of(), List.of(), list(source));
        };
    }

    /** Which format text is in, when nobody said. A plain list is the fallback. */
    static Format detect(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("{") && trimmed.contains(BoardExport.FORMAT)) {
            return Format.KANBANCORD;
        }
        List<String> lines = trimmed.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() >= 2 && lines.get(0).strip().startsWith("|") && TABLE_SEPARATOR.matcher(lines.get(1).strip()).matches()) {
            return Format.MARKDOWN;
        }
        long withTabs = lines.stream().filter(line -> line.contains("\t")).count();
        if (lines.size() >= 2 && lines.get(0).contains("\t") && withTabs * 2 >= lines.size()) {
            return Format.TSV;
        }
        // Commas are common in plain lists ("Buy milk, eggs"), so CSV needs a header that names a field.
        if (lines.size() >= 2 && lines.get(0).contains(",")
                && splitRow(lines.get(0), ',').stream().anyMatch(cell -> HEADER_NAMES.containsKey(normalise(cell)))) {
            return Format.CSV;
        }
        return Format.LIST;
    }

    // ── Lists ────────────────────────────────────────────────────────────────

    /** A task being read, until the next one starts. */
    private static final class Draft {
        final String title;
        final boolean done;
        final String column;
        final int indent;
        final List<String> details = new ArrayList<>();

        Draft(String title, boolean done, String column, int indent) {
            this.title = title;
            this.done = done;
            this.column = column;
            this.indent = indent;
        }

        Item item() {
            return new Item(title, details.isEmpty() ? null : String.join("\n", details), column, done, null, null, List.of());
        }
    }

    /**
     * One task per item. A heading starts a column; an item indented under another is a checklist
     * item in that task's description, and an indented line without a bullet is part of its
     * description.
     */
    static List<Item> list(String text) {
        List<Item> items = new ArrayList<>();
        String column = null;
        Draft current = null;
        for (String raw : text.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (current != null) {
                    items.add(current.item());
                    current = null;
                }
                column = heading.group(1).strip();
                continue;
            }
            int indent = indentOf(line);
            Matcher item = ITEM.matcher(line);
            Matcher checkbox = item.matches() ? null : CHECKBOX.matcher(line);
            boolean isItem = item.matches() || (checkbox != null && checkbox.matches());
            String content = item.matches() ? item.group(3) : isItem ? checkbox.group(3) : line.strip();
            String tick = item.matches() ? item.group(2) : isItem ? checkbox.group(2) : null;
            boolean ticked = tick != null && tick.equalsIgnoreCase("x");
            content = content.strip();

            if (current != null && indent > current.indent) {
                if (!content.isEmpty()) {
                    current.details.add(isItem ? (ticked ? "- [x] " : "- [ ] ") + content : content);
                }
                continue;
            }
            if (current != null) {
                items.add(current.item());
            }
            current = content.isEmpty() ? null : new Draft(content, ticked, column, indent);
        }
        if (current != null) {
            items.add(current.item());
        }
        return items;
    }

    private static int indentOf(String line) {
        int width = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += 4;
            } else {
                break;
            }
        }
        return width;
    }

    // ── Tables ───────────────────────────────────────────────────────────────

    private static Parsed table(Format format, List<List<String>> rows, List<Field> chosen) {
        if (rows.isEmpty()) {
            return new Parsed(format, List.of(), List.of(), List.of());
        }
        List<String> first = rows.get(0);
        List<Field> named = first.stream().map(cell -> HEADER_NAMES.getOrDefault(normalise(cell), Field.IGNORE)).toList();
        boolean hasHeader = named.stream().anyMatch(field -> field != Field.IGNORE);
        int width = rows.stream().mapToInt(List::size).max().orElse(0);

        List<String> headers = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            headers.add(hasHeader && i < first.size() && !first.get(i).isBlank() ? first.get(i).strip() : "Column " + (i + 1));
        }
        List<Field> fields = new ArrayList<>();
        if (chosen != null && !chosen.isEmpty()) {
            for (int i = 0; i < width; i++) {
                fields.add(i < chosen.size() && chosen.get(i) != null ? chosen.get(i) : Field.IGNORE);
            }
        } else if (hasHeader) {
            fields.addAll(named);
            while (fields.size() < width) {
                fields.add(Field.IGNORE);
            }
            // Headers that name no title: the first column not used for anything else is the title.
            if (!fields.contains(Field.TITLE)) {
                int free = fields.indexOf(Field.IGNORE);
                if (free >= 0) {
                    fields.set(free, Field.TITLE);
                }
            }
        } else {
            for (int i = 0; i < width; i++) {
                fields.add(i == 0 ? Field.TITLE : i == 1 ? Field.DESCRIPTION : Field.IGNORE);
            }
        }

        List<Item> items = new ArrayList<>();
        for (List<String> row : hasHeader ? rows.subList(1, rows.size()) : rows) {
            Map<Field, String> cells = new HashMap<>();
            for (int i = 0; i < row.size() && i < fields.size(); i++) {
                String cell = row.get(i).strip();
                if (!cell.isEmpty() && fields.get(i) != Field.IGNORE) {
                    cells.merge(fields.get(i), cell, (a, b) -> a + "\n" + b);
                }
            }
            String title = cells.get(Field.TITLE);
            if (title == null) {
                continue;
            }
            String labels = cells.get(Field.LABELS);
            items.add(new Item(title, cells.get(Field.DESCRIPTION), cells.get(Field.COLUMN), false, cells.get(Field.DUE),
                    cells.get(Field.PRIORITY), labels == null ? List.of() : List.of(labels.split("\\s*[,;]\\s*")).stream()
                    .map(String::strip).filter(name -> !name.isEmpty()).toList()));
        }
        return new Parsed(format, headers, fields, items);
    }

    /** Rows of a CSV or TSV, with quoted cells that may hold the delimiter, quotes or line breaks. */
    static List<List<String>> rows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                addRow(rows, row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        row.add(cell.toString());
        addRow(rows, row);
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(cell -> !cell.isBlank())) {
            rows.add(row);
        }
    }

    private static List<String> splitRow(String line, char delimiter) {
        List<List<String>> rows = rows(line, delimiter);
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    /** A Markdown table's rows, without the line under the headers; \| is a bar inside a cell. */
    static List<List<String>> markdownRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (!line.startsWith("|") || TABLE_SEPARATOR.matcher(line).matches()) {
                continue;
            }
            String inner = line.substring(1, line.endsWith("|") && line.length() > 1 ? line.length() - 1 : line.length());
            List<String> cells = new ArrayList<>();
            for (String cell : inner.split("(?<!\\\\)\\|", -1)) {
                cells.add(cell.replace("\\|", "|").strip());
            }
            addRow(rows, cells);
        }
        return rows;
    }

    private static String normalise(String header) {
        return header.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
    }

    private static Map<String, Field> headerNames() {
        Map<String, Field> names = new LinkedHashMap<>();
        for (String name : List.of("title", "name", "task", "tasks", "summary", "item", "todo", "to do")) {
            names.put(name, Field.TITLE);
        }
        for (String name : List.of("description", "notes", "note", "details", "body", "content")) {
            names.put(name, Field.DESCRIPTION);
        }
        for (String name : List.of("column", "status", "list", "stage", "state", "section")) {
            names.put(name, Field.COLUMN);
        }
        for (String name : List.of("due", "due date", "deadline", "date")) {
            names.put(name, Field.DUE);
        }
        names.put("priority", Field.PRIORITY);
        for (String name : List.of("labels", "label", "tags", "tag")) {
            names.put(name, Field.LABELS);
        }
        return names;
    }

    // ── KanbanCord board files ───────────────────────────────────────────────

    /** The file's tasks, with their column, priority and labels by name; archived tasks stay out. */
    private static List<Item> boardFile(String text) {
        BoardExport file;
        try {
            file = JSON.readValue(text, BoardExport.class);
        } catch (Exception ex) {
            throw new BadRequestException("This is not a KanbanCord board file");
        }
        if (!BoardExport.FORMAT.equals(file.format()) || file.tasks() == null) {
            throw new BadRequestException("This is not a KanbanCord board file");
        }
        if (file.version() != null && file.version() > BoardExport.VERSION) {
            throw new BadRequestException("This board file is from a newer version of KanbanCord");
        }
        Map<String, String> columns = new HashMap<>();
        Optional.ofNullable(file.columns()).orElse(List.of()).forEach(column -> columns.put(column.id(), column.name()));
        Map<String, String> priorities = new HashMap<>();
        Optional.ofNullable(file.priorities()).orElse(List.of()).forEach(level -> priorities.put(level.id(), level.name()));
        Map<String, String> labels = new HashMap<>();
        Optional.ofNullable(file.labels()).orElse(List.of()).forEach(label -> labels.put(label.id(), label.name()));

        List<Item> items = new ArrayList<>();
        for (BoardExport.Task task : file.tasks()) {
            if (Boolean.TRUE.equals(task.archived()) || task.title() == null || task.title().isBlank()) {
                continue;
            }
            items.add(new Item(task.title().strip(), task.description(), columns.get(task.column()), false, task.dueDate(),
                    priorities.get(task.priority()),
                    Optional.ofNullable(task.labels()).orElse(List.of()).stream().map(labels::get).filter(name -> name != null).toList()));
        }
        return items;
    }
}
