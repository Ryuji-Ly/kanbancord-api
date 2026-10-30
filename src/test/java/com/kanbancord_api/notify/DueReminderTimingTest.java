package com.kanbancord_api.notify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DueReminderTimingTest {

    @Test
    void theLatestTimeReachedApplies() {
        List<Integer> times = List.of(1440, 180);
        assertNull(DueReminderScheduler.reached(times, 2000), "more than a day left: nothing yet");
        assertEquals(1440, DueReminderScheduler.reached(times, 600));
        assertEquals(180, DueReminderScheduler.reached(times, 120), "both passed: only the three-hour one");
        assertNull(DueReminderScheduler.reached(List.of(), 10), "no times: no reminders before the due date");
    }

    @Test
    void aDayBeforeKeepsItsOldName_soNothingIsSentTwiceAcrossTheUpdate() {
        assertEquals("DUE_SOON", DueReminderScheduler.kindFor(1440));
        assertEquals("BEFORE_180", DueReminderScheduler.kindFor(180));
        assertEquals(1440, DueReminderScheduler.minutesOf("DUE_SOON"));
        assertEquals(180, DueReminderScheduler.minutesOf("BEFORE_180"));
        assertNull(DueReminderScheduler.minutesOf("OVERDUE"));
    }
}
