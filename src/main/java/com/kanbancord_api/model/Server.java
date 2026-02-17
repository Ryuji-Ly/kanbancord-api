package com.kanbancord_api.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "servers")
public class Server {

    @Id
    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "icon_url", columnDefinition = "TEXT")
    private String iconUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<ServerMember> members = new HashSet<>();

    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<Role> roles = new HashSet<>();

    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<Board> boards = new HashSet<>();

    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL)
    @JsonIgnore
    private Set<AuditLog> auditLogs = new HashSet<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Server() {
    }

    // Getters and Setters

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
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

    public Set<ServerMember> getMembers() {
        return members;
    }

    public void setMembers(Set<ServerMember> members) {
        this.members = members;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public Set<Board> getBoards() {
        return boards;
    }

    public void setBoards(Set<Board> boards) {
        this.boards = boards;
    }

    public Set<AuditLog> getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(Set<AuditLog> auditLogs) {
        this.auditLogs = auditLogs;
    }
}
