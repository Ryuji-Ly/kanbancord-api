package com.kanbancord_api.notify;

import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.TooManyRequestsException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Asking for the website in another language. The request is queued for the bot, which passes it on
 * to the developers by direct message; nothing here says who they are. Who asked is the signed-in
 * user, never something in the request.
 */
@RestController
public class LanguageRequestController {

    static final int NOTE_MAX = 300;
    /** A language tag: a language, then optional parts such as a script or region (pt-BR, zh-Hant). */
    private static final Pattern TAG = Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}");

    private final NotificationQueue queue;

    public LanguageRequestController(NotificationQueue queue) {
        this.queue = queue;
    }

    public record Request(String language, String note) {
    }

    @PostMapping("/api/me/language-requests")
    public ResponseEntity<Void> request(@CurrentUser Long userId, @RequestBody Request request) {
        String language = languageTag(request.language());
        String note = note(request.note());
        if (!queue.enqueueLanguageRequest(userId, language, note)) {
            throw new TooManyRequestsException(
                    "You can request up to " + NotificationQueue.LANGUAGE_REQUESTS_PER_DAY + " languages a day.", 3600);
        }
        return ResponseEntity.accepted().build();
    }

    /** The tag in its usual form (pt-BR), for a language that exists; anything else is refused. */
    static String languageTag(String value) {
        String tag = value == null ? "" : value.trim();
        if (tag.length() > 35 || !TAG.matcher(tag).matches()) {
            throw new BadRequestException("Choose a language from the list.");
        }
        Locale locale = Locale.forLanguageTag(tag);
        String language = locale.getLanguage();
        // Java names every language it knows; for an unknown code it gives the code back.
        if (language.isEmpty() || locale.getDisplayLanguage(Locale.ENGLISH).equalsIgnoreCase(language)) {
            throw new BadRequestException("Choose a language from the list.");
        }
        return locale.toLanguageTag();
    }

    /** What the person added, as plain text on at most a few lines; null when nothing was. */
    static String note(String value) {
        if (value == null) {
            return null;
        }
        // Control characters other than line breaks have no place in a note.
        String note = value.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").trim();
        if (note.length() > NOTE_MAX) {
            throw new BadRequestException("The note can be at most " + NOTE_MAX + " characters.");
        }
        return note.isEmpty() ? null : note;
    }
}
