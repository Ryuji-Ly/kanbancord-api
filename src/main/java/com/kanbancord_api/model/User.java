package com.kanbancord_api.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "global_name")
    private String globalName;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences", columnDefinition = "jsonb")
    private Map<String, Object> preferences;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<ServerMember> serverMemberships = new HashSet<>();

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL)
    @JsonIgnore
    private Set<Server> ownedServers = new HashSet<>();

    @OneToMany(mappedBy = "createdBy", cascade = CascadeType.ALL)
    @JsonIgnore
    private Set<Board> createdBoards = new HashSet<>();

    @OneToMany(mappedBy = "createdBy", cascade = CascadeType.ALL)
    @JsonIgnore
    private Set<Task> createdTasks = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<TaskAssignment> taskAssignments = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<TaskComment> taskComments = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @JsonIgnore
    private Set<AuditLog> auditLogs = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<Notification> notifications = new HashSet<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public User() {
    }

    // Getters and Setters

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getGlobalName() {
        return globalName;
    }

    public void setGlobalName(String globalName) {
        this.globalName = globalName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Map<String, Object> getPreferences() {
        return preferences;
    }

    public void setPreferences(Map<String, Object> preferences) {
        this.preferences = preferences;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Set<ServerMember> getServerMemberships() {
        return serverMemberships;
    }

    public void setServerMemberships(Set<ServerMember> serverMemberships) {
        this.serverMemberships = serverMemberships;
    }

    public Set<Server> getOwnedServers() {
        return ownedServers;
    }

    public void setOwnedServers(Set<Server> ownedServers) {
        this.ownedServers = ownedServers;
    }

    public Set<Board> getCreatedBoards() {
        return createdBoards;
    }

    public void setCreatedBoards(Set<Board> createdBoards) {
        this.createdBoards = createdBoards;
    }

    public Set<Task> getCreatedTasks() {
        return createdTasks;
    }

    public void setCreatedTasks(Set<Task> createdTasks) {
        this.createdTasks = createdTasks;
    }

    public Set<TaskAssignment> getTaskAssignments() {
        return taskAssignments;
    }

    public void setTaskAssignments(Set<TaskAssignment> taskAssignments) {
        this.taskAssignments = taskAssignments;
    }

    public Set<TaskComment> getTaskComments() {
        return taskComments;
    }

    public void setTaskComments(Set<TaskComment> taskComments) {
        this.taskComments = taskComments;
    }

    public Set<AuditLog> getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(Set<AuditLog> auditLogs) {
        this.auditLogs = auditLogs;
    }

    public Set<Notification> getNotifications() {
        return notifications;
    }

    public void setNotifications(Set<Notification> notifications) {
        this.notifications = notifications;
    }
}
