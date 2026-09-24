package com.kanbancord_api.priority;

import com.kanbancord_api.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Reading a board's priority levels, and giving new boards the default ones. */
@Service
@Transactional
public class BoardPriorityService {

    /** The levels every new board starts with, most urgent first. They can be renamed, reordered or deleted. */
    static final List<Default> DEFAULTS = List.of(
            new Default("Critical", "#dc2626"),
            new Default("High", "#ea580c"),
            new Default("Medium", "#ca8a04"),
            new Default("Low", "#2563eb"),
            new Default("Ignorable", "#6b7280"));

    private final BoardPriorityRepository boardPriorityRepository;

    public BoardPriorityService(BoardPriorityRepository boardPriorityRepository) {
        this.boardPriorityRepository = boardPriorityRepository;
    }

    record Default(String name, String color) {
    }

    public void createDefaults(Long boardId) {
        for (int i = 0; i < DEFAULTS.size(); i++) {
            BoardPriority priority = new BoardPriority();
            priority.setBoardId(boardId);
            priority.setName(DEFAULTS.get(i).name());
            priority.setColor(DEFAULTS.get(i).color());
            priority.setPosition(i + 1);
            boardPriorityRepository.save(priority);
        }
    }

    @Transactional(readOnly = true)
    public List<BoardPriority> findByBoardId(Long boardId) {
        return boardPriorityRepository.findByBoardIdOrderByPositionAsc(boardId);
    }

    /** The level, which must belong to the board; a task can only use its own board's levels. */
    @Transactional(readOnly = true)
    public BoardPriority requireInBoard(Long priorityId, Long boardId) {
        return boardPriorityRepository.findById(priorityId)
                .filter(priority -> priority.getBoardId().equals(boardId))
                .orElseThrow(() -> new BadRequestException("Priority " + priorityId + " does not belong to this board"));
    }
}
