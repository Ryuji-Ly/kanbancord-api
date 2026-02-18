package com.kanbancord_api.service;

import com.kanbancord_api.model.TaskLabel;
import com.kanbancord_api.repository.TaskLabelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TaskLabelService {

    private final TaskLabelRepository taskLabelRepository;

    public TaskLabelService(TaskLabelRepository taskLabelRepository) {
        this.taskLabelRepository = taskLabelRepository;
    }

    public TaskLabel create(TaskLabel taskLabel) {
        return taskLabelRepository.save(taskLabel);
    }

    @Transactional(readOnly = true)
    public Optional<TaskLabel> findById(Long id) {
        return taskLabelRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<TaskLabel> findByIdAndServerId(Long taskLabelId, Long serverId) {
        return taskLabelRepository.findByIdAndServerId(taskLabelId, serverId);
    }

    @Transactional(readOnly = true)
    public List<TaskLabel> findAll() {
        return taskLabelRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<TaskLabel> findByTaskId(Long taskId) {
        return taskLabelRepository.findByTask_TaskId(taskId);
    }

    @Transactional(readOnly = true)
    public List<TaskLabel> findByLabelId(Long labelId) {
        return taskLabelRepository.findByLabel_LabelId(labelId);
    }

    @Transactional(readOnly = true)
    public Optional<TaskLabel> findByTaskIdAndLabelId(Long taskId, Long labelId) {
        return taskLabelRepository.findByTask_TaskIdAndLabel_LabelId(taskId, labelId);
    }

    public TaskLabel update(TaskLabel taskLabel) {
        return taskLabelRepository.save(taskLabel);
    }

    public void deleteById(Long id) {
        taskLabelRepository.deleteById(id);
    }

    public void deleteByTaskIdAndLabelId(Long taskId, Long labelId) {
        taskLabelRepository.deleteByTask_TaskIdAndLabel_LabelId(taskId, labelId);
    }
}
