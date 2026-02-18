package com.kanbancord_api.service;

import com.kanbancord_api.model.Label;
import com.kanbancord_api.repository.LabelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class LabelService {

    private final LabelRepository labelRepository;

    public LabelService(LabelRepository labelRepository) {
        this.labelRepository = labelRepository;
    }

    public Label create(Label label) {
        return labelRepository.save(label);
    }

    @Transactional(readOnly = true)
    public Optional<Label> findById(Long id) {
        return labelRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Label> findAll() {
        return labelRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Label> findByBoardId(Long boardId) {
        return labelRepository.findByBoard_BoardId(boardId);
    }

    @Transactional(readOnly = true)
    public Optional<Label> findByIdAndServerId(Long labelId, Long serverId) {
        return labelRepository.findByLabelIdAndServerId(labelId, serverId);
    }

    public Label update(Label label) {
        return labelRepository.save(label);
    }

    public void deleteById(Long id) {
        labelRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return labelRepository.existsById(id);
    }
}
