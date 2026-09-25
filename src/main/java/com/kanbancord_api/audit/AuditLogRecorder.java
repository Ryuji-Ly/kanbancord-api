package com.kanbancord_api.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardColumn;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.user.User;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes every change made in KanbanCord to the audit log in the same transaction as the change
 * itself, so a change is never stored without its audit entry (or the other way round). Changes
 * synced from Discord are not recorded.
 */
@Component
public class AuditLogRecorder {

    static final String SOURCE_API = "API";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    /** Keys of a task update entry naming the column the task is in, and the one it left if it changed column. */
    static final String COLUMN_KEY = "_column";
    static final String FROM_COLUMN_KEY = "_fromColumn";
    /** Key of an update entry that names the changed entity, so the log can say what was changed. */
    static final String SUBJECT_KEY = "_subject";
    /** Fields that name an entity, in order of preference. */
    private static final List<String> NAME_FIELDS = List.of("title", "name");
    /** Bookkeeping fields that change on every update and say nothing about what the user did. */
    private static final Set<String> IGNORED_FIELDS = Set.of("updatedAt");

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public AuditLogRecorder(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onDomainEvent(DomainEvent event) {
        if (event.type().fromDiscordSync()) {
            return;
        }
        AuditLog log = new AuditLog();
        log.setServer(entityManager.getReference(Server.class, event.serverId()));
        // A deleted board cannot be referenced; its id stays in the recorded changes.
        if (event.boardId() != null && event.type() != EventType.BOARD_DELETED) {
            log.setBoard(entityManager.getReference(Board.class, event.boardId()));
        }
        if (event.actorUserId() != null) {
            log.setUser(entityManager.getReference(User.class, event.actorUserId()));
        }
        log.setAction(event.type().name());
        log.setEntityType(event.type().entityType().name());
        log.setEntityId(event.entityId());
        log.setSource(SOURCE_API);
        Map<String, Object> changes = changes(event);
        if (event.type().entityType() == EventType.EntityType.TASK && event.before() != null && event.after() != null) {
            addColumnNames(changes, event);
        }
        log.setChanges(changes);
        entityManager.persist(log);
    }

    /**
     * Columns are recorded by id, which means nothing to someone reading the log later, and a
     * column can be renamed or deleted since. Keeps their names as they were at the time.
     */
    private void addColumnNames(Map<String, Object> changes, DomainEvent event) {
        Map<String, Object> before = toMap(event.before());
        Map<String, Object> after = toMap(event.after());
        columnName(after.get("columnId")).ifPresent(name -> changes.put(COLUMN_KEY, name));
        if (changes.containsKey("columnId")) {
            columnName(before.get("columnId")).ifPresent(name -> changes.put(FROM_COLUMN_KEY, name));
        }
    }

    private Optional<String> columnName(Object columnId) {
        if (!(columnId instanceof Number id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(entityManager.find(BoardColumn.class, id.longValue())).map(BoardColumn::getName);
    }

    /**
     * {@code {"created": {...}}}, {@code {"deleted": {...}}}, or for updates only the fields that
     * changed, {@code {"title": {"from": "a", "to": "b"}}}, plus the entity's current name under
     * {@link #SUBJECT_KEY} when it has one.
     */
    Map<String, Object> changes(DomainEvent event) {
        Map<String, Object> before = toMap(event.before());
        Map<String, Object> after = toMap(event.after());
        if (before == null) {
            return Map.of("created", after == null ? Map.of() : after);
        }
        if (after == null) {
            return Map.of("deleted", before);
        }

        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String field : fields) {
            Object from = before.get(field);
            Object to = after.get(field);
            if (!IGNORED_FIELDS.contains(field) && !Objects.equals(from, to)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", from);
                change.put("to", to);
                diff.put(field, change);
            }
        }
        NAME_FIELDS.stream()
                .map(after::get)
                .filter(name -> name instanceof String text && !text.isBlank())
                .findFirst()
                .ifPresent(name -> diff.put(SUBJECT_KEY, name));
        return diff;
    }

    private Map<String, Object> toMap(Object value) {
        return value == null ? null : objectMapper.convertValue(value, MAP);
    }
}
