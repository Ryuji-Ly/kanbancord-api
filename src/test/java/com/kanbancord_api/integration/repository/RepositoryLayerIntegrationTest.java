package com.kanbancord_api.integration.repository;

import com.kanbancord_api.audit.AuditLog;
import com.kanbancord_api.audit.AuditLogRepository;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardColumn;
import com.kanbancord_api.board.BoardColumnRepository;
import com.kanbancord_api.board.BoardRepository;
import com.kanbancord_api.integration.config.TestcontainersConfiguration;
import com.kanbancord_api.label.Label;
import com.kanbancord_api.label.LabelRepository;
import com.kanbancord_api.label.TaskLabel;
import com.kanbancord_api.label.TaskLabelRepository;
import com.kanbancord_api.notification.Notification;
import com.kanbancord_api.notification.NotificationRepository;
import com.kanbancord_api.permission.KanbanPermission;
import com.kanbancord_api.permission.KanbanPermissionRepository;
import com.kanbancord_api.permission.Permission;
import com.kanbancord_api.permission.PermissionRepository;
import com.kanbancord_api.server.MemberRole;
import com.kanbancord_api.server.MemberRoleRepository;
import com.kanbancord_api.server.Role;
import com.kanbancord_api.server.RoleRepository;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerMember;
import com.kanbancord_api.server.ServerMemberRepository;
import com.kanbancord_api.server.ServerRepository;
import com.kanbancord_api.task.Task;
import com.kanbancord_api.task.TaskAssignment;
import com.kanbancord_api.task.TaskAssignmentRepository;
import com.kanbancord_api.task.TaskComment;
import com.kanbancord_api.task.TaskCommentRepository;
import com.kanbancord_api.task.TaskRepository;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryLayerIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ServerRepository serverRepository;
    @Autowired
    private ServerMemberRepository serverMemberRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private MemberRoleRepository memberRoleRepository;
    @Autowired
    private BoardRepository boardRepository;
    @Autowired
    private BoardColumnRepository boardColumnRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;
    @Autowired
    private TaskCommentRepository taskCommentRepository;
    @Autowired
    private LabelRepository labelRepository;
    @Autowired
    private TaskLabelRepository taskLabelRepository;
    @Autowired
    private KanbanPermissionRepository kanbanPermissionRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private NotificationRepository notificationRepository;

    private User owner;
    private User memberUser;
    private User outsider;
    private Server server;
    private Server otherServer;
    private ServerMember serverMember;
    private Role role;
    private Board board;
    private BoardColumn column;
    private Task task;
    private Label label;
    private TaskAssignment taskAssignment;
    private TaskComment taskComment;
    private TaskLabel taskLabel;
    private MemberRole memberRole;
    private KanbanPermission kanbanPermission;
    private Permission permission;
    private AuditLog auditLog;
    private Notification notification;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setUserId(100L);
        owner.setUsername("owner-user");
        owner = userRepository.save(owner);

        memberUser = new User();
        memberUser.setUserId(101L);
        memberUser.setUsername("member-user");
        memberUser = userRepository.save(memberUser);

        outsider = new User();
        outsider.setUserId(102L);
        outsider.setUsername("outsider-user");
        outsider = userRepository.save(outsider);

        server = new Server();
        server.setServerId(500L);
        server.setName("Main Server");
        server.setOwner(owner);
        server = serverRepository.save(server);

        otherServer = new Server();
        otherServer.setServerId(600L);
        otherServer.setName("Other Server");
        otherServer.setOwner(owner);
        otherServer = serverRepository.save(otherServer);

        serverMember = new ServerMember();
        serverMember.setServer(server);
        serverMember.setUser(memberUser);
        serverMember.setNickname("nick");
        serverMember = serverMemberRepository.save(serverMember);

        role = new Role();
        role.setRoleId(700L);
        role.setServer(server);
        role.setName("Admin");
        role.setPosition(1);
        role = roleRepository.save(role);

        memberRole = new MemberRole();
        memberRole.setServerMember(serverMember);
        memberRole.setRole(role);
        memberRole = memberRoleRepository.save(memberRole);

        board = new Board();
        board.setServer(server);
        board.setName("Engineering");
        board.setDescription("Board");
        board.setCreatedBy(owner);
        board = boardRepository.save(board);

        column = new BoardColumn();
        column.setBoard(board);
        column.setName("Todo");
        column.setPosition(new BigDecimal("1.00"));
        column = boardColumnRepository.save(column);

        task = new Task();
        task.setBoard(board);
        task.setColumn(column);
        task.setTitle("Ship tests");
        task.setDescription("Task desc");
        task.setPosition(new BigDecimal("1.00"));
        task.setPriority("HIGH");
        task.setCreatedBy(owner);
        task.setMetadata(Map.of("k", "v"));
        task = taskRepository.save(task);

        label = new Label();
        label.setBoard(board);
        label.setName("Backend");
        label.setColor("#112233");
        label = labelRepository.save(label);

        taskLabel = new TaskLabel();
        taskLabel.setTask(task);
        taskLabel.setLabel(label);
        taskLabel = taskLabelRepository.save(taskLabel);

        taskAssignment = new TaskAssignment();
        taskAssignment.setTask(task);
        taskAssignment.setUser(memberUser);
        taskAssignment.setAssignedBy(owner);
        taskAssignment = taskAssignmentRepository.save(taskAssignment);

        taskComment = new TaskComment();
        taskComment.setTask(task);
        taskComment.setUser(memberUser);
        taskComment.setContent("Looks good");
        taskComment = taskCommentRepository.save(taskComment);

        kanbanPermission = new KanbanPermission();
        kanbanPermission.setKey("TASK_EDIT");
        kanbanPermission.setName("Edit task");
        kanbanPermission.setCategory("TASK");
        kanbanPermission = kanbanPermissionRepository.save(kanbanPermission);

        permission = new Permission();
        permission.setScopeType("BOARD");
        permission.setScopeId(board.getBoardId());
        permission.setSubjectType("ROLE");
        permission.setSubjectId(role.getRoleId());
        permission.setKanbanPermission(kanbanPermission);
        permission.setState("ALLOW");
        permission.setPriority(100);
        permission = permissionRepository.save(permission);

        auditLog = new AuditLog();
        auditLog.setServer(server);
        auditLog.setBoard(board);
        auditLog.setUser(memberUser);
        auditLog.setAction("TASK_UPDATED");
        auditLog.setEntityType("TASK");
        auditLog.setEntityId(task.getTaskId());
        auditLog = auditLogRepository.save(auditLog);

        notification = new Notification();
        notification.setUser(memberUser);
        notification.setType("TASK");
        notification.setEntityType("TASK");
        notification.setEntityId(task.getTaskId());
        notification.setMessage("Task updated");
        notification.setIsRead(false);
        notification = notificationRepository.save(notification);
    }

    @Test
    void boardRepository_supportsScopedAndPagedQueries_happyAndUnhappy() {
        assertEquals(1, boardRepository.findByServer_ServerId(server.getServerId()).size());
        assertEquals(1,
                boardRepository.findByServer_ServerId(server.getServerId(), PageRequest.of(0, 10)).getTotalElements());
        assertTrue(
                boardRepository.findByBoardIdAndServer_ServerId(board.getBoardId(), server.getServerId()).isPresent());
        assertTrue(boardRepository.findByBoardIdAndServer_ServerId(board.getBoardId(), otherServer.getServerId())
                .isEmpty());
    }

    @Test
    void boardColumnRepository_returnsOrderedAndScopedResults_happyAndUnhappy() {
        assertEquals(1, boardColumnRepository.findByBoard_BoardId(board.getBoardId()).size());
        assertEquals(1, boardColumnRepository.findByBoard_BoardIdOrderByPositionAsc(board.getBoardId()).size());
        assertTrue(boardColumnRepository.findByColumnIdAndServerId(column.getColumnId(), server.getServerId())
                .isPresent());
        assertTrue(boardColumnRepository.findByColumnIdAndServerId(column.getColumnId(), otherServer.getServerId())
                .isEmpty());
    }

    @Test
    void taskRepository_returnsBoardColumnArchiveAndScopedResults_happyAndUnhappy() {
        assertEquals(1, taskRepository.findByBoard_BoardId(board.getBoardId()).size());
        assertEquals(1, taskRepository.findByColumn_ColumnIdOrderByPositionAsc(column.getColumnId()).size());
        assertTrue(taskRepository.findByTaskIdAndServerId(task.getTaskId(), server.getServerId()).isPresent());
        assertTrue(taskRepository.findByTaskIdAndServerId(task.getTaskId(), otherServer.getServerId()).isEmpty());
        assertEquals(1, taskRepository.findByBoard_BoardIdAndIsArchived(board.getBoardId(), false).size());
    }

    @Test
    void labelAndTaskLabelRepositories_supportScopedAndLinkQueries_happyAndUnhappy() {
        assertEquals(1, labelRepository.findByBoard_BoardId(board.getBoardId()).size());
        assertTrue(labelRepository.findByLabelIdAndServerId(label.getLabelId(), server.getServerId()).isPresent());
        assertTrue(labelRepository.findByLabelIdAndServerId(label.getLabelId(), otherServer.getServerId()).isEmpty());

        assertEquals(1, taskLabelRepository.findByTask_TaskId(task.getTaskId()).size());
        assertTrue(taskLabelRepository.findByIdAndServerId(taskLabel.getId(), server.getServerId()).isPresent());
        assertTrue(taskLabelRepository.findByIdAndServerId(taskLabel.getId(), otherServer.getServerId()).isEmpty());
    }

    @Test
    void serverMemberRoleAndRoleRepositories_supportMembershipQueries_happyAndUnhappy() {
        assertTrue(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(server.getServerId(),
                memberUser.getUserId()));
        assertFalse(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(server.getServerId(),
                outsider.getUserId()));
        assertTrue(serverMemberRepository
                .findByServer_ServerIdAndUser_UserId(server.getServerId(), memberUser.getUserId()).isPresent());

        assertEquals(1, roleRepository.findByServer_ServerId(server.getServerId()).size());
        assertEquals(1, roleRepository.findByServer_ServerIdOrderByPositionAsc(server.getServerId()).size());

        assertTrue(memberRoleRepository.findByServerMember_IdAndRole_RoleId(serverMember.getId(), role.getRoleId())
                .isPresent());

        // Permission-snapshot queries
        assertEquals(List.of(role.getRoleId()), memberRoleRepository.findRolesByServerMemberId(serverMember.getId())
                .stream().map(Role::getRoleId).toList());
        assertEquals(owner.getUserId(), serverRepository.findOwnerIdByServerId(server.getServerId()).orElseThrow());
        assertTrue(serverRepository.findOwnerIdByServerId(9999L).isEmpty());
        assertTrue(boardRepository.existsByBoardIdAndServer_ServerId(board.getBoardId(), server.getServerId()));
        assertFalse(boardRepository.existsByBoardIdAndServer_ServerId(board.getBoardId(), otherServer.getServerId()));
    }

    @Test
    void taskAssignmentAndCommentRepositories_supportTaskAndScopedQueries_happyAndUnhappy() {
        assertEquals(1, taskAssignmentRepository.findByTask_TaskId(task.getTaskId()).size());
        assertTrue(
                taskAssignmentRepository.findByIdAndServerId(taskAssignment.getId(), server.getServerId()).isPresent());
        assertTrue(taskAssignmentRepository.findByIdAndServerId(taskAssignment.getId(), otherServer.getServerId())
                .isEmpty());

        assertEquals(1, taskCommentRepository.findByTask_TaskId(task.getTaskId()).size());
        assertEquals(1, taskCommentRepository.findByTask_TaskIdAndDeletedAtIsNull(task.getTaskId()).size());
        assertEquals(1,
                taskCommentRepository.findByTask_TaskId(task.getTaskId(), PageRequest.of(0, 10)).getTotalElements());
        assertTrue(taskCommentRepository.findByCommentIdAndServerId(taskComment.getCommentId(), server.getServerId())
                .isPresent());
        assertTrue(taskCommentRepository
                .findByCommentIdAndServerId(taskComment.getCommentId(), otherServer.getServerId()).isEmpty());
    }

    @Test
    void permissionAndKanbanPermissionRepositories_supportLookupQueries_happyAndUnhappy() {
        assertTrue(kanbanPermissionRepository.findByKey("TASK_EDIT").isPresent());
        assertTrue(kanbanPermissionRepository.findByKey("MISSING").isEmpty());
        assertFalse(kanbanPermissionRepository.findByCategory("TASK").isEmpty());

        assertEquals(1, permissionRepository.findByScopeTypeAndScopeId("BOARD", board.getBoardId()).size());
        assertEquals(1, permissionRepository.findBySubjectTypeAndSubjectId("ROLE", role.getRoleId()).size());
        assertEquals(1, permissionRepository.findByScopeTypeAndScopeIdIn("BOARD", List.of(board.getBoardId(), 9999L))
                .size());
        assertEquals(0, permissionRepository.findByScopeTypeAndScopeId("SERVER", 9999L).size());
    }

    @Test
    void auditLogNotificationUserAndServerRepositories_supportReportingQueries_happyAndUnhappy() {
        assertEquals(1, auditLogRepository.findByServer_ServerId(server.getServerId()).size());
        assertEquals(1, auditLogRepository.findByBoard_BoardId(board.getBoardId()).size());
        assertEquals(1, auditLogRepository.findByUser_UserId(memberUser.getUserId()).size());
        assertEquals(1, auditLogRepository.findByServer_ServerIdOrderByCreatedAtDesc(server.getServerId()).size());

        assertEquals(1, notificationRepository.findByUser_UserId(memberUser.getUserId()).size());
        assertEquals(1, notificationRepository.findByUser_UserIdAndIsRead(memberUser.getUserId(), false).size());
        assertEquals(1L, notificationRepository.countByUser_UserIdAndIsRead(memberUser.getUserId(), false));
        assertEquals(0L, notificationRepository.countByUser_UserIdAndIsRead(memberUser.getUserId(), true));
        assertEquals(1, notificationRepository.findByUser_UserIdOrderByCreatedAtDesc(memberUser.getUserId()).size());

        assertTrue(serverRepository.existsByServerId(server.getServerId()));
        assertFalse(serverRepository.existsByServerId(9999L));
    }
}
