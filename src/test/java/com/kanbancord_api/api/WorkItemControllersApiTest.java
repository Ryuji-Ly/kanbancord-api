package com.kanbancord_api.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardColumn;
import com.kanbancord_api.board.BoardColumnController;
import com.kanbancord_api.board.BoardColumnRequest;
import com.kanbancord_api.board.BoardColumnService;
import com.kanbancord_api.board.BoardCommands;
import com.kanbancord_api.board.BoardController;
import com.kanbancord_api.board.BoardRequest;
import com.kanbancord_api.board.BoardService;
import com.kanbancord_api.board.BoardSnapshotQuery;
import com.kanbancord_api.board.ColumnCommands;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.exception.GlobalExceptionHandler;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.label.Label;
import com.kanbancord_api.label.LabelController;
import com.kanbancord_api.label.LabelRequest;
import com.kanbancord_api.label.LabelCommands;
import com.kanbancord_api.label.LabelService;
import com.kanbancord_api.label.TaskLabelCommands;
import com.kanbancord_api.label.TaskLabel;
import com.kanbancord_api.label.TaskLabelController;
import com.kanbancord_api.label.TaskLabelRequest;
import com.kanbancord_api.label.TaskLabelService;
import com.kanbancord_api.priority.BoardPriorityService;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerService;
import com.kanbancord_api.task.Task;
import com.kanbancord_api.task.TaskAssignment;
import com.kanbancord_api.task.TaskAssignmentCommands;
import com.kanbancord_api.task.TaskAssignmentController;
import com.kanbancord_api.task.TaskAssignmentRequest;
import com.kanbancord_api.task.TaskAssignmentService;
import com.kanbancord_api.task.TaskCommands;
import com.kanbancord_api.task.TaskComment;
import com.kanbancord_api.task.TaskCommentCommands;
import com.kanbancord_api.task.TaskCommentController;
import com.kanbancord_api.task.TaskCommentEditRepository;
import com.kanbancord_api.task.TaskCommentRequest;
import com.kanbancord_api.task.TaskCommentService;
import com.kanbancord_api.task.TaskController;
import com.kanbancord_api.task.TaskRequest;
import com.kanbancord_api.task.TaskService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import static com.kanbancord_api.api.ApiTestAuth.asUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        BoardController.class,
        BoardColumnController.class,
        LabelController.class,
        TaskController.class,
        TaskAssignmentController.class,
        TaskCommentController.class,
        TaskLabelController.class
})
@AutoConfigureMockMvc(addFilters = false)
// The real command services run against the mocked services below, so these tests also cover the
// authorization and validation rules the commands apply.
@Import({
        GlobalExceptionHandler.class,
        BoardCommands.class,
        ColumnCommands.class,
        TaskCommands.class,
        TaskAssignmentCommands.class,
        TaskCommentCommands.class,
        LabelCommands.class,
        TaskLabelCommands.class,
        BoardSnapshotQuery.class
})
@RecordApplicationEvents
class WorkItemControllersApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApplicationEvents applicationEvents;

        @MockitoBean
    private BoardService boardService;
        @MockitoBean
    private BoardColumnService boardColumnService;
        @MockitoBean
    private LabelService labelService;
        @MockitoBean
    private TaskService taskService;
        @MockitoBean
    private TaskAssignmentService taskAssignmentService;
        @MockitoBean
    private TaskCommentService taskCommentService;
        @MockitoBean
    private TaskLabelService taskLabelService;
    @MockitoBean
    private BoardPriorityService boardPriorityService;
        @MockitoBean
    private ServerService serverService;
        @MockitoBean
    private UserService userService;
        @MockitoBean
    private Authorizer authorizer;
        @MockitoBean
    private ResourceValidator resourceValidator;
        @MockitoBean
    private TaskCommentEditRepository taskCommentEditRepository;
        @MockitoBean
    private PermissionEvaluationService permissionEvaluationService;

    @Test
    void boardEndpoints_coverHappyAndUnhappy() throws Exception {
        BoardRequest create = new BoardRequest();
        create.setName("Board");
        create.setServerId(1L);
        create.setCreatedBy(10L);

        Board board = board(100L);
        when(serverService.findById(1L)).thenReturn(Optional.of(server(1L)));
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        when(boardService.create(any(Board.class), any())).thenReturn(board);
        Board hiddenBoard = board(101L);
        when(boardService.findByServerId(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(board, hiddenBoard)));
        when(permissionEvaluationService.filterAllowedBoards(eq(1L), any(), eq(10L), eq("VIEW_BOARD")))
                .thenReturn(Set.of(100L));
        when(resourceValidator.requireBoardInServer(100L, 1L)).thenReturn(board);
        when(boardService.update(any(Board.class))).thenReturn(board);

        mockMvc.perform(post("/api/servers/1/boards").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.boardId").value(100));

        mockMvc.perform(get("/api/servers/1/boards").with(asUser(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].boardId").value(100))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(get("/api/servers/1/boards/100").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100").with(asUser(10L)))
                .andExpect(status().isNoContent());

        when(serverService.findById(999L)).thenReturn(Optional.empty());
        create.setServerId(999L);
        mockMvc.perform(post("/api/servers/999/boards").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isNotFound());
    }

    @Test
    void boardColumnEndpoints_coverHappyAndUnhappy() throws Exception {
        BoardColumnRequest request = new BoardColumnRequest();
        request.setBoardId(100L);
        request.setName("Todo");
        request.setPosition(new BigDecimal("1.00"));
        request.setColor("#111111");
        request.setWipLimit(5);

        Board board = board(100L);
        BoardColumn column = column(200L, board);

        when(resourceValidator.requireBoardInServer(100L, 1L)).thenReturn(board);
        when(boardColumnService.create(any(BoardColumn.class))).thenReturn(column);
        when(boardColumnService.findByBoardIdOrdered(100L)).thenReturn(List.of(column));
        when(resourceValidator.requireColumnInServer(200L, 1L)).thenReturn(column);
        when(boardColumnService.update(any(BoardColumn.class))).thenReturn(column);

        mockMvc.perform(post("/api/servers/1/boards/100/columns").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/columns").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/columns/200").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100/columns/200").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/columns/200").with(asUser(10L)))
                .andExpect(status().isNoContent());

        request.setName("");
        mockMvc.perform(post("/api/servers/1/boards/100/columns").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void labelEndpoints_coverHappyAndUnhappy() throws Exception {
        LabelRequest request = new LabelRequest();
        request.setBoardId(100L);
        request.setName("Bug");
        request.setColor("#123ABC");

        Board board = board(100L);
        Label label = label(300L, board);

        when(resourceValidator.requireBoardInServer(100L, 1L)).thenReturn(board);
        when(labelService.create(any(Label.class))).thenReturn(label);
        when(labelService.findByBoardId(100L)).thenReturn(List.of(label));
        when(resourceValidator.requireLabelInServer(300L, 1L)).thenReturn(label);
        when(labelService.update(any(Label.class))).thenReturn(label);

        mockMvc.perform(post("/api/servers/1/boards/100/labels").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/labels").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/labels/300").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100/labels/300").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/labels/300").with(asUser(10L)))
                .andExpect(status().isNoContent());

        request.setColor("bad");
        mockMvc.perform(post("/api/servers/1/boards/100/labels").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taskEndpoints_coverHappyAndUnhappy() throws Exception {
        TaskRequest request = new TaskRequest();
        request.setBoardId(100L);
        request.setColumnId(200L);
        request.setTitle("T1");
        request.setCreatedBy(10L);
        request.setMetadata(Map.of("a", "b"));

        Board board = board(100L);
        Task task = task(400L, board, column(200L, board));

        when(resourceValidator.requireBoardInServer(100L, 1L)).thenReturn(board);
        when(boardColumnService.findById(200L)).thenReturn(Optional.of(column(200L, board)));
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        when(taskService.create(any(Task.class))).thenReturn(task);
        when(taskService.findByBoardId(100L, PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(task)));
        when(resourceValidator.requireTaskInServer(400L, 1L)).thenReturn(task);
        when(taskService.update(any(Task.class))).thenReturn(task);

        mockMvc.perform(post("/api/servers/1/boards/100/tasks").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400").with(asUser(10L)))
                .andExpect(status().isNoContent());

        when(boardColumnService.findById(999L)).thenReturn(Optional.empty());
        request.setColumnId(999L);
        mockMvc.perform(post("/api/servers/1/boards/100/tasks").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void taskAssignmentEndpoints_coverHappyAndUnhappy() throws Exception {
        TaskAssignmentRequest request = new TaskAssignmentRequest();
        request.setTaskId(400L);
        request.setUserId(11L);
        request.setAssignedBy(10L);

        Task task = task(400L, board(100L), column(200L, board(100L)));
        TaskAssignment assignment = assignment(500L, task, user(11L), user(10L));

        when(resourceValidator.requireTaskInServer(400L, 1L)).thenReturn(task);
        when(userService.findById(11L)).thenReturn(Optional.of(user(11L)));
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        when(taskAssignmentService.create(any(TaskAssignment.class))).thenReturn(assignment);
        when(taskAssignmentService.findByTaskId(400L)).thenReturn(List.of(assignment));
        when(resourceValidator.requireAssignmentInServer(500L, 1L)).thenReturn(assignment);

        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/assignments").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/assignments").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/assignments/500").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400/assignments/500").with(asUser(10L)))
                .andExpect(status().isNoContent());

        when(userService.findById(99L)).thenReturn(Optional.empty());
        request.setUserId(99L);
        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/assignments").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void taskCommentEndpoints_coverHappyAndUnhappy() throws Exception {
        TaskCommentRequest request = new TaskCommentRequest();
        request.setTaskId(400L);
        request.setUserId(11L);
        request.setContent("hello");

        Task task = task(400L, board(100L), column(200L, board(100L)));
        TaskComment comment = comment(600L, task, user(11L));

        when(resourceValidator.requireTaskInServer(400L, 1L)).thenReturn(task);
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        when(taskCommentService.create(any(TaskComment.class))).thenReturn(comment);
        when(taskCommentService.findByTaskId(400L, PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(comment)));
        when(resourceValidator.requireCommentInServer(600L, 1L)).thenReturn(comment);
        when(taskCommentService.update(any(TaskComment.class))).thenReturn(comment);

        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/comments").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/comments").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/comments/600").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400/comments/600").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400/comments/600").with(asUser(10L)))
                .andExpect(status().isNoContent());

        request.setContent("");
        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/comments").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taskLabelEndpoints_coverHappyAndUnhappy() throws Exception {
        TaskLabelRequest request = new TaskLabelRequest();
        request.setTaskId(400L);
        request.setLabelId(300L);

        Task task = task(400L, board(100L), column(200L, board(100L)));
        Label label = label(300L, board(100L));
        TaskLabel taskLabel = taskLabel(700L, task, label);

        when(resourceValidator.requireTaskInServer(400L, 1L)).thenReturn(task);
        when(resourceValidator.requireLabelInServer(300L, 1L)).thenReturn(label);
        when(taskLabelService.create(any(TaskLabel.class))).thenReturn(taskLabel);
        when(taskLabelService.findByTaskId(400L)).thenReturn(List.of(taskLabel));
        when(resourceValidator.requireTaskLabelInServer(700L, 1L)).thenReturn(taskLabel);

        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/labels").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/labels").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/labels/700").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400/labels/700").with(asUser(10L)))
                .andExpect(status().isNoContent());

        when(resourceValidator.requireLabelInServer(999L, 1L))
                .thenThrow(new ResourceNotFoundException("Label", "labelId", 999L));
        request.setLabelId(999L);
        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/labels").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // ── Authorization rules ────────────────────────────────────────────────────

    @Test
    void unauthenticatedRequest_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/servers/1/boards/100"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authorizer);
    }

    @Test
    void createTask_ignoresClientSuppliedCreator() throws Exception {
        Board board = board(100L);
        BoardColumn column = column(200L, board);
        TaskRequest request = new TaskRequest();
        request.setTitle("New task");
        request.setBoardId(100L);
        request.setColumnId(200L);
        request.setCreatedBy(99L);

        when(resourceValidator.requireBoardInServer(100L, 1L)).thenReturn(board);
        when(boardColumnService.findById(200L)).thenReturn(Optional.of(column));
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        when(taskService.create(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/servers/1/boards/100/tasks").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("10"));

        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "CREATE_TASK");
        verify(userService, never()).findById(99L);
    }

    @Test
    void updateTask_moveOnly_requiresMoveTaskButNotEditTask() throws Exception {
        Board board = board(100L);
        Task task = task(400L, board, column(200L, board));
        stubTaskUpdate(board, task);

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(taskUpdate("Task", 201L))))
                .andExpect(status().isOk());

        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "MOVE_TASK");
        verify(authorizer, never()).requireBoardPermission(10L, 1L, 100L, "EDIT_TASK");
    }

    @Test
    void updateTask_editInPlace_requiresEditTaskButNotMoveTask() throws Exception {
        Board board = board(100L);
        Task task = task(400L, board, column(200L, board));
        stubTaskUpdate(board, task);

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(taskUpdate("Renamed", 200L))))
                .andExpect(status().isOk());

        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "EDIT_TASK");
        verify(authorizer, never()).requireBoardPermission(10L, 1L, 100L, "MOVE_TASK");
    }

    @Test
    void updateTask_withoutChanges_onlyRequiresViewAndDoesNotWrite() throws Exception {
        Board board = board(100L);
        Task task = task(400L, board, column(200L, board));
        stubTaskUpdate(board, task);

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(taskUpdate("Task", 200L))))
                .andExpect(status().isOk());

        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "VIEW_TASK");
        verify(taskService, never()).update(any(Task.class));
        assertEquals(0, applicationEvents.stream(DomainEvent.class).count());
    }

    @Test
    void assignTask_toSelf_requiresAssignSelf_andRecordsActorAsAssigner() throws Exception {
        Board board = board(100L);
        Task task = task(400L, board, column(200L, board));
        stubAssignment(task);
        TaskAssignmentRequest request = new TaskAssignmentRequest();
        request.setTaskId(400L);
        request.setUserId(10L);
        request.setAssignedBy(77L);

        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/assignments").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedBy").value("10"));

        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "ASSIGN_TASK_SELF");
        verify(userService, never()).findById(77L);
    }

    @Test
    void assignTask_toSomeoneElse_requiresAssignOthers_andServerMembership() throws Exception {
        Board board = board(100L);
        Task task = task(400L, board, column(200L, board));
        stubAssignment(task);
        TaskAssignmentRequest request = new TaskAssignmentRequest();
        request.setTaskId(400L);
        request.setUserId(11L);

        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/assignments").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "ASSIGN_TASK_OTHERS");
        verify(resourceValidator).validatePermissionSubjectBelongsToServer("USER", 11L, 1L);
    }

    @Test
    void editingOwnComment_requiresCommentPermission_othersRequireModeration() throws Exception {
        Board board = board(100L);
        Task task = task(400L, board, column(200L, board));
        TaskComment own = comment(600L, task, user(10L));
        TaskComment others = comment(601L, task, user(11L));
        when(resourceValidator.requireCommentInServer(600L, 1L)).thenReturn(own);
        when(resourceValidator.requireCommentInServer(601L, 1L)).thenReturn(others);
        when(taskCommentService.update(any(TaskComment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        TaskCommentRequest request = new TaskCommentRequest();
        request.setTaskId(400L);
        request.setContent("edited");

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400/comments/600").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "CREATE_TASK_COMMENT");

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400/comments/601").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "EDIT_TASK_COMMENT");

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400/comments/601").with(asUser(10L)))
                .andExpect(status().isNoContent());
        verify(authorizer).requireBoardPermission(10L, 1L, 100L, "DELETE_TASK_COMMENT");
    }

    private void stubTaskUpdate(Board board, Task task) {
        when(resourceValidator.requireBoardInServer(100L, 1L)).thenReturn(board);
        when(resourceValidator.requireTaskInServer(400L, 1L)).thenReturn(task);
        when(boardColumnService.findById(200L)).thenReturn(Optional.of(column(200L, board)));
        when(boardColumnService.findById(201L)).thenReturn(Optional.of(column(201L, board)));
        when(taskService.update(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static TaskRequest taskUpdate(String title, Long columnId) {
        TaskRequest request = new TaskRequest();
        request.setTitle(title);
        request.setBoardId(100L);
        request.setColumnId(columnId);
        return request;
    }

    private void stubAssignment(Task task) {
        when(resourceValidator.requireTaskInServer(400L, 1L)).thenReturn(task);
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        when(userService.findById(11L)).thenReturn(Optional.of(user(11L)));
        when(taskAssignmentService.create(any(TaskAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static User user(Long id) {
        User user = new User();
        user.setUserId(id);
        user.setUsername("u" + id);
        return user;
    }

    private static Server server(Long id) {
        Server server = new Server();
        server.setServerId(id);
        server.setName("server-" + id);
        server.setOwner(user(1L));
        return server;
    }

    private static Board board(Long id) {
        Board board = new Board();
        board.setBoardId(id);
        board.setName("board-" + id);
        board.setServer(server(1L));
        board.setCreatedBy(user(10L));
        return board;
    }

    private static BoardColumn column(Long id, Board board) {
        BoardColumn column = new BoardColumn();
        column.setColumnId(id);
        column.setBoard(board);
        column.setName("Todo");
        return column;
    }

    private static Label label(Long id, Board board) {
        Label label = new Label();
        label.setLabelId(id);
        label.setBoard(board);
        label.setName("Label");
        label.setColor("#123ABC");
        return label;
    }

    private static Task task(Long id, Board board, BoardColumn column) {
        Task task = new Task();
        task.setTaskId(id);
        task.setBoard(board);
        task.setColumn(column);
        task.setTitle("Task");
        task.setCreatedBy(user(10L));
        return task;
    }

    private static TaskAssignment assignment(Long id, Task task, User user, User assignedBy) {
        TaskAssignment assignment = new TaskAssignment();
        assignment.setId(id);
        assignment.setTask(task);
        assignment.setUser(user);
        assignment.setAssignedBy(assignedBy);
        return assignment;
    }

    private static TaskComment comment(Long id, Task task, User user) {
        TaskComment comment = new TaskComment();
        comment.setCommentId(id);
        comment.setTask(task);
        comment.setUser(user);
        comment.setContent("content");
        return comment;
    }

    private static TaskLabel taskLabel(Long id, Task task, Label label) {
        TaskLabel taskLabel = new TaskLabel();
        taskLabel.setId(id);
        taskLabel.setTask(task);
        taskLabel.setLabel(label);
        return taskLabel;
    }
}
