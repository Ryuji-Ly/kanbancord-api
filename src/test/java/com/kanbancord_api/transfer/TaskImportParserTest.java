package com.kanbancord_api.transfer;

import com.kanbancord_api.transfer.TaskImportParser.Field;
import com.kanbancord_api.transfer.TaskImportParser.Format;
import com.kanbancord_api.transfer.TaskImportParser.Item;
import com.kanbancord_api.transfer.TaskImportParser.Parsed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskImportParserTest {

    private static List<String> titles(Parsed parsed) {
        return parsed.items().stream().map(Item::title).toList();
    }

    @Test
    void plainLines_bulletsAndNumbers_areOneTaskEach() {
        Parsed parsed = TaskImportParser.parse("""
                Buy milk, eggs
                - Fix the login
                * Write docs
                + Ship it
                1. First
                2) Second
                • Dot
                """, Format.AUTO, null);
        assertEquals(Format.LIST, parsed.format(), "a comma in a line does not make it CSV");
        assertEquals(List.of("Buy milk, eggs", "Fix the login", "Write docs", "Ship it", "First", "Second", "Dot"), titles(parsed));
    }

    @Test
    void checkboxes_tickedOnesAreDone_andHeadingsStartColumns() {
        Parsed parsed = TaskImportParser.parse("""
                # Watching
                - [ ] Frieren
                - [x] Dandadan
                [x] Without a bullet

                ## Plan to read
                - Dungeon Meshi
                """, Format.AUTO, null);
        List<Item> items = parsed.items();
        assertEquals(List.of("Frieren", "Dandadan", "Without a bullet", "Dungeon Meshi"), titles(parsed));
        assertEquals("Watching", items.get(0).column());
        assertFalse(items.get(0).done());
        assertTrue(items.get(1).done());
        assertTrue(items.get(2).done());
        assertEquals("Plan to read", items.get(3).column());
    }

    @Test
    void indentedItems_becomeAChecklist_andIndentedText_theDescription() {
        Parsed parsed = TaskImportParser.parse("""
                - Launch
                  - [x] Write the post
                  - Record the video
                  Due before the jam ends.
                - Next
                """, Format.AUTO, null);
        assertEquals(List.of("Launch", "Next"), titles(parsed));
        assertEquals("- [x] Write the post\n- [ ] Record the video\nDue before the jam ends.", parsed.items().get(0).description());
        assertNull(parsed.items().get(1).description());
    }

    @Test
    void csv_readsHeaders_quotes_andLineBreaksInCells() {
        String csv = "Title,Notes,Status,Due date,Priority,Tags\n"
                + "\"Fix \"\"login\"\"\",\"Line one\nline two\",Doing,2026-10-20,High,\"Bug, Web\"\n"
                + "Plain,,,,,\n"
                + ",no title here,,,,\n";
        Parsed parsed = TaskImportParser.parse(csv, Format.AUTO, null);
        assertEquals(Format.CSV, parsed.format());
        assertEquals(List.of(Field.TITLE, Field.DESCRIPTION, Field.COLUMN, Field.DUE, Field.PRIORITY, Field.LABELS), parsed.fields());
        assertEquals(List.of("Fix \"login\"", "Plain"), titles(parsed), "rows without a title are left out");
        Item first = parsed.items().get(0);
        assertEquals("Line one\nline two", first.description());
        assertEquals("Doing", first.column());
        assertEquals("2026-10-20", first.due());
        assertEquals("High", first.priority());
        assertEquals(List.of("Bug", "Web"), first.labels());
    }

    @Test
    void tsv_asPastedFromASpreadsheet_andAChosenMapping() {
        String pasted = "Task\tOwner\tColumn\nDesign\tMia\tTo Do\nBuild\tTheo\tDoing\n";
        Parsed parsed = TaskImportParser.parse(pasted, Format.AUTO, null);
        assertEquals(Format.TSV, parsed.format());
        assertEquals(List.of("Task", "Owner", "Column"), parsed.headers());
        assertEquals(List.of(Field.TITLE, Field.IGNORE, Field.COLUMN), parsed.fields(), "unknown headers are ignored");

        Parsed remapped = TaskImportParser.parse(pasted, Format.AUTO, List.of(Field.TITLE, Field.DESCRIPTION, Field.IGNORE));
        assertEquals("Mia", remapped.items().get(0).description());
        assertNull(remapped.items().get(0).column());
    }

    @Test
    void markdownTable_withEscapedBars() {
        Parsed parsed = TaskImportParser.parse("""
                | Name | Description | Status |
                |------|:-----------:|--------|
                | One  | a \\| b      | Done   |
                | Two  |             |        |
                """, Format.AUTO, null);
        assertEquals(Format.MARKDOWN, parsed.format());
        assertEquals(List.of("One", "Two"), titles(parsed));
        assertEquals("a | b", parsed.items().get(0).description());
        assertEquals("Done", parsed.items().get(0).column());
    }

    @Test
    void aTableWithoutKnownHeaders_isAllRows_titleThenDescription() {
        Parsed parsed = TaskImportParser.parse("Alpha\tfirst\nBeta\tsecond\n", Format.TSV, null);
        assertEquals(List.of("Alpha", "Beta"), titles(parsed));
        assertEquals("first", parsed.items().get(0).description());
        assertEquals(List.of("Column 1", "Column 2"), parsed.headers());
    }

    @Test
    void boardFile_tasksWithTheirColumnPriorityAndLabelNames_withoutArchivedOnes() {
        String file = """
                {"format":"kanbancord-board","version":1,"board":{"name":"Old"},
                 "columns":[{"id":"c1","name":"Backlog"},{"id":"c2","name":"Done"}],
                 "labels":[{"id":"l1","name":"Bug","color":"#ff0000"}],
                 "priorities":[{"id":"p1","name":"High"}],
                 "tasks":[{"id":"t1","column":"c2","title":"Shipped","priority":"p1","labels":["l1"],"dueDate":"2026-10-20T18:00:00"},
                          {"id":"t2","column":"c1","title":"Old idea","archived":true}]}
                """;
        Parsed parsed = TaskImportParser.parse(file, Format.AUTO, null);
        assertEquals(Format.KANBANCORD, parsed.format());
        assertEquals(List.of("Shipped"), titles(parsed));
        Item item = parsed.items().get(0);
        assertEquals("Done", item.column());
        assertEquals("High", item.priority());
        assertEquals(List.of("Bug"), item.labels());
        assertEquals("2026-10-20T18:00:00", item.due());
    }

    @Test
    void windowsLineEndings_andAByteOrderMark_areHandled() {
        Parsed parsed = TaskImportParser.parse("﻿Title,Status\r\nOne,Doing\r\n", Format.AUTO, null);
        assertEquals(Format.CSV, parsed.format());
        assertEquals(List.of("One"), titles(parsed));
        assertEquals("Doing", parsed.items().get(0).column());
    }
}
