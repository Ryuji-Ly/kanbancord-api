package com.kanbancord_api.service;

import com.kanbancord_api.model.Role;
import com.kanbancord_api.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class RoleService {

    private final RoleRepository roleRepository;

    public RoleService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    public Role create(Role role) {
        return roleRepository.save(role);
    }

    @Transactional(readOnly = true)
    public Optional<Role> findById(Long id) {
        return roleRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Role> findAll() {
        return roleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Role> findByServerId(Long serverId) {
        return roleRepository.findByServer_ServerId(serverId);
    }

    @Transactional(readOnly = true)
    public List<Role> findByServerIdOrderedByPosition(Long serverId) {
        return roleRepository.findByServer_ServerIdOrderByPositionAsc(serverId);
    }

    public Role update(Role role) {
        return roleRepository.save(role);
    }

    public void deleteById(Long id) {
        roleRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return roleRepository.existsById(id);
    }
}
