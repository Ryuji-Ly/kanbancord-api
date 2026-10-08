package com.kanbancord_api.unit.notify;

import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.notify.LanguageRequestController;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LanguageRequestCheckTest {

    /** Every language the website offers to request (frontend LanguageSettings.tsx). */
    private static final List<String> OFFERED = List.of(
            "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "es", "es-419", "et", "fa", "fi", "fil", "fr", "he", "hi", "hr",
            "hu", "id", "it", "ja", "ko", "lt", "lv", "ms", "nb", "nl", "pl", "pt-BR", "pt-PT", "ro", "ru", "sk", "sl", "sr",
            "sv", "sw", "ta", "th", "tr", "uk", "ur", "vi", "zh-Hans", "zh-Hant");

    @Test
    void everyOfferedLanguageIsAccepted_asSent() {
        for (String tag : OFFERED) {
            assertEquals(tag, LanguageRequestController.languageTag(tag));
        }
        assertEquals("pt-BR", LanguageRequestController.languageTag(" pt-br "));
    }

    @Test
    void madeUpOrMalformedLanguagesAreRefused() {
        for (String tag : List.of("zz", "", "english", "<b>", "fr--x", "a")) {
            assertThrows(BadRequestException.class, () -> LanguageRequestController.languageTag(tag), tag);
        }
        assertThrows(BadRequestException.class, () -> LanguageRequestController.languageTag(null));
    }

    @Test
    void notesArePlainAndShort() {
        assertNull(LanguageRequestController.note("   "));
        assertEquals("one\ntwo", LanguageRequestController.note(" one\n\u0000two\t"));
        assertThrows(BadRequestException.class, () -> LanguageRequestController.note("x".repeat(301)));
    }
}
