package com.kanbancord_api.security;

import com.kanbancord_api.sync.InternalBotToken;
import com.kanbancord_api.sync.InternalSyncProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets the Discord bot call the public API as the user who ran a slash command. The bot sends its
 * internal token, the user's id and the id of the server the command ran in; the request then goes
 * through exactly the same permission checks as the website would, as that user.
 *
 * <p>Deliberately narrow: only paths under {@code /api/servers/{id}} for the server the command ran
 * in, never with a user's own token as well, and never for anything tied to a sign-in session.
 * Created by SecurityConfig rather than as a bean, so it runs only inside the security chain.
 */
public class BotActingUserFilter extends OncePerRequestFilter {

    public static final String BOT_TOKEN_HEADER = "X-Internal-Bot-Token";
    public static final String ACTING_USER_HEADER = "X-Acting-User-Id";
    public static final String ACTING_GUILD_HEADER = "X-Acting-Guild-Id";

    private static final Pattern SERVER_PATH = Pattern.compile("^/api/servers/(\\d+)(/.*)?$");
    private static final Pattern SNOWFLAKE = Pattern.compile("^\\d{1,20}$");

    private final InternalSyncProperties internalSyncProperties;

    public BotActingUserFilter(InternalSyncProperties internalSyncProperties) {
        this.internalSyncProperties = internalSyncProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(ACTING_USER_HEADER) == null && request.getHeader(ACTING_GUILD_HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!InternalBotToken.matches(internalSyncProperties.getBotToken(), request.getHeader(BOT_TOKEN_HEADER))) {
            reject(response, HttpStatus.UNAUTHORIZED, "Invalid bot token");
            return;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            reject(response, HttpStatus.BAD_REQUEST, "Send either a user token or acting-user headers, not both");
            return;
        }
        String userId = request.getHeader(ACTING_USER_HEADER);
        String guildId = request.getHeader(ACTING_GUILD_HEADER);
        if (userId == null || guildId == null || !SNOWFLAKE.matcher(userId).matches()
                || !SNOWFLAKE.matcher(guildId).matches()) {
            reject(response, HttpStatus.BAD_REQUEST, "Acting-user requests need a user id and a server id");
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        Matcher matcher = SERVER_PATH.matcher(path);
        if (!matcher.matches() || !matcher.group(1).equals(guildId)) {
            reject(response, HttpStatus.FORBIDDEN, "The bot can only act within the server the command ran in");
            return;
        }

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(Long.valueOf(userId), null, List.of());
        auth.setDetails(new BotActingUser(Long.valueOf(guildId)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status.value() + ",\"message\":\"" + message
                + "\",\"timestamp\":\"" + LocalDateTime.now() + "\"}");
    }
}
