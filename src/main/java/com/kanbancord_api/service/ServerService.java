package com.kanbancord_api.service;

import com.kanbancord_api.model.Server;
import com.kanbancord_api.repository.ServerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ServerService {

    private final ServerRepository serverRepository;

    public ServerService(ServerRepository serverRepository) {
        this.serverRepository = serverRepository;
    }

    public Server create(Server server) {
        return serverRepository.save(server);
    }

    @Transactional(readOnly = true)
    public Optional<Server> findById(Long id) {
        return serverRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Server> findAll() {
        return serverRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Server> findByOwnerId(Long ownerId) {
        return serverRepository.findByOwner_UserId(ownerId);
    }

    public Server update(Server server) {
        return serverRepository.save(server);
    }

    public void deleteById(Long id) {
        serverRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return serverRepository.existsByServerId(id);
    }
}
