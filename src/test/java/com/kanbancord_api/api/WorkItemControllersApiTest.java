package com.kanbancord_api.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.controller.BoardColumnController;
import com.kanbancord_api.controller.BoardController;
import com.kanbancord_api.controller.LabelController;
import com.kanbancord_api.controller.TaskAssignmentController;
import com.kanbancord_api.controller.TaskCommentController;
import com.kanbancord_api.controller.TaskController;
import com.kanbancord_api.controller.TaskLabelController;
import com.kanbancord_api.dto.BoardColumnRequest;
import com.kanbancord_api.dto.BoardRequest;
import com.kanbancord_api.dto.LabelRequest;
import com.kanbancord_api.dto.TaskAssignmentRequest;
import com.kanbancord_api.dto.TaskCommentRequest;
import com.kanbancord_api.dto.TaskLabelRequest;
import com.kanbancord_api.dto.TaskRequest;
import com.kanbancord_api.exception.GlobalExceptionHandler;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.model.Label;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskAssignment;
import com.kanbancord_api.model.TaskComment;
import com.kanbancord_api.model.TaskLabel;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.BoardColumnService;
import com.kanbancord_api.service.BoardService;
import com.kanbancord_api.service.LabelService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.ServerService;
import com.kanbancord_api.service.TaskAssignmentService;
import com.kanbancord_api.service.TaskCommentService;
import com.kanbancord_api.service.TaskLabelService;
import com.kanbancord_api.service.TaskService;
import com.kanbancord_api.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
@Import(GlobalExceptionHandler.class)
class WorkItemControllersApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

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
    private ServerService serverService;
        @MockitoBean
    private UserService userService;
        @MockitoBean
    private AccessValidator accessValidator;
        @MockitoBean
    private ResourceValidator resourceValidator;

    @Test
    void boardEndpoints_coverHappyAndUnhappy() throws Exception {
        BoardRequest create = new BoardRequest();
        create.setName("Board");
        create.setServerId(1L);
        create.setCreatedBy(10L);

        Board board = board(100L);
        when(serverService.findById(1L)).thenReturn(Optional.of(server(1L)));
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        when(boardService.create(any(Board.class))).thenReturn(board);
        when(boardService.findByServerId(1L, PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(board)));
        when(resourceValidator.requireBoardInServer(100L, 1L)).thenReturn(board);
        when(boardService.update(any(Board.class))).thenReturn(board);

        mockMvc.perform(post("/api/servers/1/boards").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.boardId").value(100));

        mockMvc.perform(get("/api/servers/1/boards").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100").param("userId", "10"))
                .andExpect(status().isNoContent());

        when(serverService.findById(999L)).thenReturn(Optional.empty());
        create.setServerId(999L);
        mockMvc.perform(post("/api/servers/999/boards").param("userId", "10")
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

        mockMvc.perform(post("/api/servers/1/boards/100/columns").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/columns").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/columns/200").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100/columns/200").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/columns/200").param("userId", "10"))
                .andExpect(status().isNoContent());

        request.setName("");
        mockMvc.perform(post("/api/servers/1/boards/100/columns").param("userId", "10")
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

        mockMvc.perform(post("/api/servers/1/boards/100/labels").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/labels").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/labels/300").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100/labels/300").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/labels/300").param("userId", "10"))
                .andExpect(status().isNoContent());

        request.setColor("bad");
        mockMvc.perform(post("/api/servers/1/boards/100/labels").param("userId", "10")
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
        request.setPriority("HIGH");
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

        mockMvc.perform(post("/api/servers/1/boards/100/tasks").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400").param("userId", "10"))
                .andExpect(status().isNoContent());

        when(boardColumnService.findById(999L)).thenReturn(Optional.empty());
        request.setColumnId(999L);
        mockMvc.perform(post("/api/servers/1/boards/100/tasks").param("userId", "10")
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

        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/assignments").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/assignments").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/assignments/500").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400/assignments/500").param("userId", "10"))
                .andExpect(status().isNoContent());

        when(userService.findById(99L)).thenReturn(Optional.empty());
        request.setUserId(99L);
        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/assignments").param("userId", "10")
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
        when(userService.findById(11L)).thenReturn(Optional.of(user(11L)));
        when(taskCommentService.create(any(TaskComment.class))).thenReturn(comment);
        when(taskCommentService.findByTaskId(400L, PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(comment)));
        when(resourceValidator.requireCommentInServer(600L, 1L)).thenReturn(comment);
        when(taskCommentService.update(any(TaskComment.class))).thenReturn(comment);

        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/comments").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/comments").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/comments/600").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/boards/100/tasks/400/comments/600").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400/comments/600").param("userId", "10"))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/servers/1/boards/100/tasks/400/comments/600/soft-delete").param("userId", "10"))
                .andExpect(status().isNoContent());

        request.setContent("");
        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/comments").param("userId", "10")
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

        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/labels").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/labels").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/boards/100/tasks/400/labels/700").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/boards/100/tasks/400/labels/700").param("userId", "10"))
                .andExpect(status().isNoContent());

        when(resourceValidator.requireLabelInServer(999L, 1L))
                .thenThrow(new ResourceNotFoundException("Label", "labelId", 999L));
        request.setLabelId(999L);
        mockMvc.perform(post("/api/servers/1/boards/100/tasks/400/labels").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
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
