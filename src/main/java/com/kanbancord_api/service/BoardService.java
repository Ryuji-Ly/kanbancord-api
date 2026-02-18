package com.kanbancord_api.service;

import com.kanbancord_api.model.Board;
import com.kanbancord_api.repository.BoardRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class BoardService {

    private final BoardRepository boardRepository;

    public BoardService(BoardRepository boardRepository) {
        this.boardRepository = boardRepository;
    }

    public Board create(Board board) {
        return boardRepository.save(board);
    }

    @Transactional(readOnly = true)
    public Optional<Board> findById(Long id) {
        return boardRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Board> findAll() {
        return boardRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Board> findByServerId(Long serverId) {
        return boardRepository.findByServer_ServerId(serverId);
    }

    @Transactional(readOnly = true)
    public Page<Board> findByServerId(Long serverId, Pageable pageable) {
        return boardRepository.findByServer_ServerId(serverId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Board> findByServerIdAndArchived(Long serverId, Boolean isArchived) {
        return boardRepository.findByServer_ServerIdAndIsArchived(serverId, isArchived);
    }

    @Transactional(readOnly = true)
    public Page<Board> findByServerIdAndArchived(Long serverId, Boolean isArchived, Pageable pageable) {
        return boardRepository.findByServer_ServerIdAndIsArchived(serverId, isArchived, pageable);
    }

    @Transactional(readOnly = true)
    public List<Board> findByCreatedBy(Long userId) {
        return boardRepository.findByCreatedBy_UserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<Board> findByIdAndServerId(Long boardId, Long serverId) {
        return boardRepository.findByBoardIdAndServer_ServerId(boardId, serverId);
    }

    public Board update(Board board) {
        return boardRepository.save(board);
    }

    public void deleteById(Long id) {
        boardRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return boardRepository.existsById(id);
    }
}
