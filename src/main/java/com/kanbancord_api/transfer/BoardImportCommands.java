package com.kanbancord_api.transfer;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardColumn;
import com.kanbancord_api.board.BoardColumnRepository;
import com.kanbancord_api.board.BoardRepository;
import com.kanbancord_api.board.BoardResponse;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import com.kanbancord_api.label.Label;
import com.kanbancord_api.label.LabelRepository;
import com.kanbancord_api.label.TaskLabel;
import com.kanbancord_api.label.TaskLabelRepository;
import com.kanbancord_api.permission.KanbanPermission;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.permission.KanbanPermissionService;
import com.kanbancord_api.permission.Permission;
import com.kanbancord_api.permission.PermissionEscalationGuardService;
import com.kanbancord_api.permission.PermissionResponse;
import com.kanbancord_api.permission.PermissionService;
import com.kanbancord_api.priority.BoardPriority;
import com.kanbancord_api.priority.BoardPriorityRepository;
import com.kanbancord_api.priority.BoardPriorityService;
import com.kanbancord_api.server.Role;
import com.kanbancord_api.server.RoleService;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerMemberService;
import com.kanbancord_api.server.ServerService;
import com.kanbancord_api.task.Task;
import com.kanbancord_api.task.TaskAssignment;
import com.kanbancord_api.task.TaskAssignmentRepository;
import com.kanbancord_api.task.TaskComment;
import com.kanbancord_api.task.TaskCommentRepository;
import com.kanbancord_api.task.TaskRepository;
import com.kanbancord_api.task.TaskRoleAssignment;
import com.kanbancord_api.task.TaskRoleAssignmentRepository;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Creates a new board from a {@link BoardExport}, in one transaction: either all of it is created or
 * nothing is. It is recorded as one board creation, not as a change per task, so update feeds see a
 * new board rather than hundreds of new tasks.
 *
 * <p>The file comes from anywhere, so nothing in it is trusted: people are only assigned and rules
 * only kept for members and roles of this server, and every rule must be one the importer could set
 * by hand. Everything else in the file is brought in as it is.
 */
@Service
@Transactional
public class BoardImportCommands {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final String NEUTRAL = "#6b7280";
    private static final List<String> DEFAULT_COLUMNS = List.of("To Do", "In Progress", "Done");

    private final Authorizer authorizer;
    private final ServerService serverService;
    private final UserService userService;
    private final ServerMemberService serverMemberService;
    private final RoleService roleService;
    private final ServerFeatureService features;
    private final BoardRepository boardRepository;
    private final BoardColumnRepository columnRepository;
    private final BoardPriorityRepository priorityRepository;
    private final BoardPriorityService priorityService;
    private final LabelRepository labelRepository;
    private final TaskRepository taskRepository;
    private final TaskLabelRepository taskLabelRepository;
    private final TaskAssignmentRepository assignmentRepository;
    private final TaskRoleAssignmentRepository roleAssignmentRepository;
    private final TaskCommentRepository commentRepository;
    private final KanbanPermissionService kanbanPermissionService;
    private final PermissionService permissionService;
    private final PermissionEscalationGuardService escalationGuard;
    private final ApplicationEventPublisher events;

    public BoardImportCommands(
            Authorizer authorizer,
            ServerService serverService,
            UserService userService,
            ServerMemberService serverMemberService,
            RoleService roleService,
            ServerFeatureService features,
            BoardRepository boardRepository,
            BoardColumnRepository columnRepository,
            BoardPriorityRepository priorityRepository,
            BoardPriorityService priorityService,
            LabelRepository labelRepository,
            TaskRepository taskRepository,
            TaskLabelRepository taskLabelRepository,
            TaskAssignmentRepository assignmentRepository,
            TaskRoleAssignmentRepository roleAssignmentRepository,
            TaskCommentRepository commentRepository,
            KanbanPermissionService kanbanPermissionService,
            PermissionService permissionService,
            PermissionEscalationGuardService escalationGuard,
            ApplicationEventPublisher events) {
        this.authorizer = authorizer;
        this.serverService = serverService;
        this.userService = userService;
        this.serverMemberService = serverMemberService;
        this.roleService = roleService;
        this.features = features;
        this.boardRepository = boardRepository;
        this.columnRepository = columnRepository;
        this.priorityRepository = priorityRepository;
        this.priorityService = priorityService;
        this.labelRepository = labelRepository;
        this.taskRepository = taskRepository;
        this.taskLabelRepository = taskLabelRepository;
        this.assignmentRepository = assignmentRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.commentRepository = commentRepository;
        this.kanbanPermissionService = kanbanPermissionService;
        this.permissionService = permissionService;
        this.escalationGuard = escalationGuard;
        this.events = events;
    }

    public BoardImportResult importBoard(Long serverId, Long actorUserId, BoardImportRequest request) {
        authorizer.requireServerPermission(actorUserId, serverId, "CREATE_BOARD");
        BoardExport file = request.document();
        if (!BoardExport.FORMAT.equals(file.format())) {
            throw new BadRequestException("This is not a KanbanCord board file");
        }
        if (file.version() > BoardExport.VERSION) {
            throw new BadRequestException("This board file is from a newer version of KanbanCord");
        }
        Set<String> parts = chosenParts(file, request.parts());
        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));
        User actor = userService.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", actorUserId));
        Import run = new Import(serverId, actor, parts);

        Board board = new Board();
        board.setServer(server);
        board.setName(freeName(serverId, request.name() != null && !request.name().isBlank()
                ? request.name().trim() : file.board().name().trim()));
        board.setDescription(file.board().description());
        board.setIsArchived(false);
        board.setCreatedBy(actor);
        board = boardRepository.save(board);
        // Announced first, so the board exists for anything recorded after it, such as its rules.
        BoardResponse created = BoardResponse.from(board);
        events.publishEvent(DomainEvent.created(EventType.BOARD_CREATED, serverId, board.getBoardId(),
                board.getBoardId(), actorUserId, created));

        Map<String, BoardColumn> columns = createColumns(board, file.columns());
        run.count("columns", columns.size());
        BoardColumn first = columns.values().iterator().next();
        Map<String, BoardPriority> priorities = parts.contains(BoardExport.PRIORITIES)
                ? createPriorities(board.getBoardId(), file.priorities(), run)
                : defaultPriorities(board.getBoardId());
        Map<String, Label> labels = parts.contains(BoardExport.LABELS) ? createLabels(board, file.labels(), run) : Map.of();

        Map<BoardColumn, Integer> positions = new HashMap<>();
        for (BoardExport.Task item : file.tasks()) {
            BoardColumn column = item.column() != null && columns.containsKey(item.column()) ? columns.get(item.column()) : first;
            int position = positions.merge(column, 1, Integer::sum);
            Task task = createTask(board, column, position, item, parts.contains(BoardExport.PRIORITIES) ? priorities : Map.of(), actor);
            run.count("tasks", 1);
            if (parts.contains(BoardExport.LABELS)) {
                attachLabels(task, item.labels(), labels);
            }
            if (parts.contains(BoardExport.ASSIGNEES)) {
                assign(task, item, run);
            }
            if (parts.contains(BoardExport.COMMENTS)) {
                addComments(task, item.comments(), run);
            }
        }

        if (parts.contains(BoardExport.PERMISSIONS)) {
            addRules(board.getBoardId(), file.permissions(), run);
        }

        return new BoardImportResult(created, run.imported, run.skipped);
    }

    /** The parts asked for that the file has; every part it has when none were asked for. */
    private static Set<String> chosenParts(BoardExport file, List<String> requested) {
        Set<String> inFile = new HashSet<>();
        if (file.labels() != null) inFile.add(BoardExport.LABELS);
        if (file.priorities() != null) inFile.add(BoardExport.PRIORITIES);
        if (file.tasks().stream().anyMatch(task -> task.assignees() != null || task.roles() != null)) inFile.add(BoardExport.ASSIGNEES);
        if (file.tasks().stream().anyMatch(task -> task.comments() != null)) inFile.add(BoardExport.COMMENTS);
        if (file.permissions() != null) inFile.add(BoardExport.PERMISSIONS);
        if (file.parts() != null) {
            inFile.retainAll(file.parts().stream().map(part -> part.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));
        }
        Set<String> chosen = requested == null ? new HashSet<>(BoardExport.PARTS) : BoardExportQuery.partsOf(requested);
        chosen.retainAll(inFile);
        return chosen;
    }

    /** The name, or the name with a number after it when the server already has a board by that name. */
    private String freeName(Long serverId, String name) {
        Set<String> taken = boardRepository.findByServer_ServerId(serverId).stream()
                .map(board -> board.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!taken.contains(name.toLowerCase(Locale.ROOT))) {
            return name;
        }
        for (int n = 2; ; n++) {
            String suffix = " (" + n + ")";
            String candidate = name.substring(0, Math.min(name.length(), 100 - suffix.length())) + suffix;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    private Map<String, BoardColumn> createColumns(Board board, List<BoardExport.Column> items) {
        Map<String, BoardColumn> columns = new LinkedHashMap<>();
        List<BoardExport.Column> source = items.isEmpty()
                ? DEFAULT_COLUMNS.stream().map(name -> new BoardExport.Column(name, name, null, null)).toList()
                : items;
        for (BoardExport.Column item : source) {
            if (columns.containsKey(item.id())) {
                continue;
            }
            BoardColumn column = new BoardColumn();
            column.setBoard(board);
            column.setName(item.name().trim());
            column.setPosition(BigDecimal.valueOf(columns.size() + 1L).setScale(2));
            column.setColor(item.color() != null && HEX_COLOR.matcher(item.color()).matches() ? item.color() : null);
            column.setWipLimit(item.wipLimit() != null && item.wipLimit() > 0 ? item.wipLimit() : null);
            columns.put(item.id(), columnRepository.save(column));
        }
        return columns;
    }

    private Map<String, BoardPriority> defaultPriorities(Long boardId) {
        priorityService.createDefaults(boardId);
        return Map.of();
    }

    /** Levels in the file's order, most urgent first; a name used twice is kept once. */
    private Map<String, BoardPriority> createPriorities(Long boardId, List<BoardExport.Priority> items, Import run) {
        Map<String, BoardPriority> levels = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (BoardExport.Priority item : items) {
            String name = item.name().trim();
            if (levels.containsKey(item.id()) || !names.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            BoardPriority level = new BoardPriority();
            level.setBoardId(boardId);
            level.setName(name);
            level.setColor(color(item.color()));
            level.setPosition(levels.size() + 1);
            levels.put(item.id(), priorityRepository.save(level));
        }
        run.count("priorities", levels.size());
        return levels;
    }

    private Map<String, Label> createLabels(Board board, List<BoardExport.Label> items, Import run) {
        Map<String, Label> labels = new LinkedHashMap<>();
        Map<String, Label> byName = new HashMap<>();
        for (BoardExport.Label item : items) {
            String name = item.name().trim();
            Label existing = byName.get(name.toLowerCase(Locale.ROOT));
            if (existing != null) {
                labels.put(item.id(), existing);
                continue;
            }
            Label label = new Label();
            label.setBoard(board);
            label.setName(name);
            label.setColor(color(item.color()));
            label = labelRepository.save(label);
            byName.put(name.toLowerCase(Locale.ROOT), label);
            labels.put(item.id(), label);
        }
        run.count("labels", byName.size());
        return labels;
    }

    private Task createTask(Board board, BoardColumn column, int position, BoardExport.Task item,
                            Map<String, BoardPriority> priorities, User actor) {
        Task task = new Task();
        task.setBoard(board);
        task.setColumn(column);
        task.setTitle(item.title().trim());
        task.setDescription(item.description());
        task.setPosition(BigDecimal.valueOf(position).setScale(2));
        task.setDueDate(time(item.dueDate(), "due date"));
        task.setIsArchived(Boolean.TRUE.equals(item.archived()));
        task.setCompletedAt(time(item.completedAt(), "completion time"));
        BoardPriority priority = item.priority() != null ? priorities.get(item.priority()) : null;
        task.setPriorityId(priority != null ? priority.getPriorityId() : null);
        task.setCreatedBy(actor);
        return taskRepository.save(task);
    }

    private void attachLabels(Task task, List<String> ids, Map<String, Label> labels) {
        if (ids == null) {
            return;
        }
        Set<Long> attached = new HashSet<>();
        for (String id : ids) {
            Label label = labels.get(id);
            if (label != null && attached.add(label.getLabelId())) {
                TaskLabel link = new TaskLabel();
                link.setTask(task);
                link.setLabel(label);
                taskLabelRepository.save(link);
            }
        }
    }

    /** People who are members here, and roles of this server; anyone else is left out. */
    private void assign(Task task, BoardExport.Task item, Import run) {
        Set<Long> people = new HashSet<>();
        for (BoardExport.Person person : Optional.ofNullable(item.assignees()).orElse(List.of())) {
            Long userId = snowflake(person.userId());
            if (userId == null || !people.add(userId)) {
                continue;
            }
            Optional<User> user = run.member(userId);
            if (user.isEmpty()) {
                run.skip("assignees");
                continue;
            }
            TaskAssignment assignment = new TaskAssignment();
            assignment.setTask(task);
            assignment.setUser(user.get());
            assignment.setAssignedBy(run.actor);
            assignmentRepository.save(assignment);
            run.count("assignees", 1);
        }
        Set<Long> roles = new HashSet<>();
        for (BoardExport.Role role : Optional.ofNullable(item.roles()).orElse(List.of())) {
            Long roleId = snowflake(role.roleId());
            if (roleId == null || !roles.add(roleId)) {
                continue;
            }
            if (!run.roleHere(roleId)) {
                run.skip("roles");
                continue;
            }
            TaskRoleAssignment assignment = new TaskRoleAssignment();
            assignment.setTaskId(task.getTaskId());
            assignment.setRoleId(roleId);
            assignment.setAssignedBy(run.actor.getUserId());
            roleAssignmentRepository.save(assignment);
            run.count("roles", 1);
        }
    }

    /**
     * Comments in the file's order, replies kept with what they answer. A comment by someone who is not
     * a member here becomes the importer's, starting with who wrote it, so nobody appears in a server
     * they are not in.
     */
    private void addComments(Task task, List<BoardExport.Comment> items, Import run) {
        if (items == null) {
            return;
        }
        Map<String, TaskComment> saved = new HashMap<>();
        for (BoardExport.Comment item : items) {
            Long authorId = item.author() != null ? snowflake(item.author().userId()) : null;
            Optional<User> author = authorId != null ? run.member(authorId) : Optional.empty();
            String content = item.content();
            if (author.isEmpty()) {
                String name = item.author() != null && item.author().name() != null && !item.author().name().isBlank()
                        ? item.author().name().trim() : "someone";
                content = clip("*" + name + " wrote:*\n" + content, 2000);
                run.skip("commentAuthors");
            }
            TaskComment comment = new TaskComment();
            comment.setTask(task);
            comment.setUser(author.orElse(run.actor));
            comment.setContent(content);
            comment.setReplyTo(item.replyTo() != null ? saved.get(item.replyTo()) : null);
            saved.put(item.id(), commentRepository.save(comment));
            run.count("comments", 1);
        }
    }

    /**
     * The board's rules, for subjects that exist here: Discord permissions always, members of this
     * server, and roles of this server (so a file from another server keeps none of its roles). Each
     * rule must be one the importer could set by hand; any other is left out, never the whole import.
     */
    private void addRules(Long boardId, List<BoardExport.Rule> items, Import run) {
        if (!features.isEnabled(run.serverId, Feature.PERMISSIONS)) {
            run.skip("permissions", items.size());
            return;
        }
        int priority = 1000;
        for (BoardExport.Rule item : items) {
            Long subjectId = snowflake(item.subjectId());
            String type = item.subjectType().toUpperCase(Locale.ROOT);
            String state = item.state().toUpperCase(Locale.ROOT);
            Optional<KanbanPermission> key = kanbanPermissionService.findByKey(item.permission());
            boolean subjectHere = subjectId != null && switch (type) {
                case "DISCORD_PERMISSION" -> true;
                case "USER" -> run.member(subjectId).isPresent();
                case "ROLE" -> run.roleHere(subjectId);
                default -> false;
            };
            boolean valid = subjectHere && key.isPresent() && (state.equals("ALLOW") || state.equals("DENY"))
                    && KanbanPermissionCatalog.fromKey(item.permission()).map(KanbanPermissionCatalog::isBoardScopeAllowed).orElse(false)
                    && !("ADMIN".equals(item.permission()) && state.equals("DENY"));
            if (!valid) {
                run.skip("permissions");
                continue;
            }
            Permission rule = new Permission();
            rule.setScopeType("BOARD");
            rule.setScopeId(boardId);
            rule.setSubjectType(type);
            rule.setSubjectId(subjectId);
            rule.setKanbanPermission(key.get());
            rule.setState(state);
            int wanted = item.priority() != null ? item.priority() : priority;
            rule.setPriority(Math.max(0, Math.min(1000, wanted)));
            rule.setIsImmutable(false);
            priority = Math.max(0, priority - 1);
            try {
                escalationGuard.validateChange(run.actor.getUserId(), run.serverId, null, rule);
            } catch (RuntimeException refused) {
                run.skip("permissions");
                continue;
            }
            PermissionResponse created = PermissionResponse.from(permissionService.create(rule));
            events.publishEvent(DomainEvent.created(EventType.PERMISSION_CREATED, run.serverId, boardId,
                    created.id(), run.actor.getUserId(), created));
            run.count("permissions", 1);
        }
    }

    private static String color(String value) {
        return value != null && HEX_COLOR.matcher(value).matches() ? value : NEUTRAL;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static Long snowflake(String id) {
        if (id == null || !id.matches("\\d{1,20}")) {
            return null;
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    private static LocalDateTime time(String value, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.endsWith("Z") ? value.substring(0, value.length() - 1) : value);
        } catch (DateTimeParseException invalid) {
            throw new BadRequestException("Invalid " + what + " in the file: " + value);
        }
    }

    /** One import's state: who is importing, what to bring in, what was created and what was left out. */
    private final class Import {
        final Long serverId;
        final User actor;
        final Set<String> parts;
        final Map<String, Integer> imported = new LinkedHashMap<>();
        final Map<String, Integer> skipped = new LinkedHashMap<>();
        private final Map<Long, Optional<User>> members = new HashMap<>();
        private Set<Long> roles;

        Import(Long serverId, User actor, Set<String> parts) {
            this.serverId = serverId;
            this.actor = actor;
            this.parts = parts;
            parts.forEach(part -> imported.put(part, 0));
        }

        void count(String kind, int n) {
            imported.merge(kind, n, Integer::sum);
        }

        void skip(String kind) {
            skip(kind, 1);
        }

        void skip(String kind, int n) {
            skipped.merge(kind, n, Integer::sum);
        }

        /** The user, if they are a member of this server. */
        Optional<User> member(Long userId) {
            return members.computeIfAbsent(userId, id -> serverMemberService.existsByServerIdAndUserId(serverId, id)
                    ? userService.findById(id) : Optional.empty());
        }

        boolean roleHere(Long roleId) {
            if (roles == null) {
                roles = roleService.findByServerId(serverId).stream().map(Role::getRoleId).collect(Collectors.toSet());
            }
            return roles.contains(roleId);
        }
    }
}
