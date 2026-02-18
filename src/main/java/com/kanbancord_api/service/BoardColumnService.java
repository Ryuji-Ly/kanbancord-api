package com.kanbancord_api.service;

import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.repository.BoardColumnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class BoardColumnService {

    private final BoardColumnRepository boardColumnRepository;

    public BoardColumnService(BoardColumnRepository boardColumnRepository) {
        this.boardColumnRepository = boardColumnRepository;
    }

    public BoardColumn create(BoardColumn column) {
        return boardColumnRepository.save(column);
    }

    @Transactional(readOnly = true)
    public Optional<BoardColumn> findById(Long id) {
        return boardColumnRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<BoardColumn> findAll() {
        return boardColumnRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<BoardColumn> findByBoardId(Long boardId) {
        return boardColumnRepository.findByBoard_BoardId(boardId);
    }

    @Transactional(readOnly = true)
    public List<BoardColumn> findByBoardIdOrdered(Long boardId) {
        return boardColumnRepository.findByBoard_BoardIdOrderByPositionAsc(boardId);
    }

    @Transactional(readOnly = true)
    public Optional<BoardColumn> findByIdAndServerId(Long columnId, Long serverId) {
        return boardColumnRepository.findByColumnIdAndServerId(columnId, serverId);
    }

    public BoardColumn update(BoardColumn column) {
        return boardColumnRepository.save(column);
    }

    public void deleteById(Long id) {
        boardColumnRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return boardColumnRepository.existsById(id);
    }
}
