package com.kanbancord_api.permission;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional
public class PermissionBootstrapService {

    private static final String SCOPE_SERVER = "SERVER";
    private static final String SUBJECT_DISCORD_PERMISSION = "DISCORD_PERMISSION";
    private static final String STATE_ALLOW = "ALLOW";

    private final KanbanPermissionRepository kanbanPermissionRepository;
    private final PermissionRepository permissionRepository;

    public PermissionBootstrapService(
            KanbanPermissionRepository kanbanPermissionRepository,
            PermissionRepository permissionRepository) {
        this.kanbanPermissionRepository = kanbanPermissionRepository;
        this.permissionRepository = permissionRepository;
    }

    public void ensureCatalogSeeded() {
        for (KanbanPermissionCatalog item : KanbanPermissionCatalog.values()) {
            KanbanPermission existing = kanbanPermissionRepository.findByKey(item.getKey())
                    .orElseGet(KanbanPermission::new);

            existing.setKey(item.getKey());
            existing.setName(item.getName());
            existing.setDescription(item.getDescription());
            existing.setCategory(item.getCategory());
            existing.setIsSystem(true);

            kanbanPermissionRepository.save(existing);
        }
    }

    /**
     * Gives a new server the default rules ({@link DefaultPermissionRules}). Runs on every sync, so a
     * server that already has rules keeps them as they are: they are the server's to change once custom
     * permissions are on, and while those are off the defaults apply anyway. Only the rules no one may
     * change are put back.
     */
    public void initializeDefaultServerConfiguration(Long serverId) {
        ensureCatalogSeeded();
        boolean fresh = !permissionRepository.existsByScopeTypeAndScopeId(SCOPE_SERVER, serverId);
        for (DefaultPermissionRules.Grant grant : DefaultPermissionRules.grants()) {
            if (fresh || grant.immutable()) {
                for (String key : grant.keys()) {
                    upsertPermission(SCOPE_SERVER, serverId, SUBJECT_DISCORD_PERMISSION, grant.flag().getBit(), key,
                            STATE_ALLOW, grant.priority(), grant.immutable());
                }
            }
        }
    }

    private void upsertPermission(
            String scopeType,
            Long scopeId,
            String subjectType,
            Long subjectId,
            String kanbanPermissionKey,
            String state,
            Integer priority,
            boolean immutable) {

        KanbanPermission kanbanPermission = kanbanPermissionRepository.findByKey(kanbanPermissionKey)
                .orElseThrow(() -> new IllegalStateException("Unknown kanban permission key: " + kanbanPermissionKey));

        Permission permission = permissionRepository
                .findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdAndKanbanPermission_PermissionId(
                        scopeType,
                        scopeId,
                        subjectType,
                        subjectId,
                        kanbanPermission.getPermissionId())
                .orElseGet(Permission::new);

        permission.setScopeType(scopeType);
        permission.setScopeId(scopeId);
        permission.setSubjectType(subjectType);
        permission.setSubjectId(subjectId);
        permission.setKanbanPermission(kanbanPermission);
        permission.setState(state);
        permission.setPriority(priority);

        boolean existingImmutable = permission.getIsImmutable() != null && permission.getIsImmutable();
        permission.setIsImmutable(existingImmutable || immutable);

        permissionRepository.save(permission);
    }
}
