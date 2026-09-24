package com.kanbancord_api.service;

import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.repository.BoardColumnRepository;
import com.kanbancord_api.repository.BoardRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class BoardService {

    private final BoardRepository boardRepository;
    private final BoardColumnRepository boardColumnRepository;

    public BoardService(
            BoardRepository boardRepository,
            BoardColumnRepository boardColumnRepository) {
        this.boardRepository = boardRepository;
        this.boardColumnRepository = boardColumnRepository;
    }

    public Board create(Board board) {
        return create(board, null);
    }

    public Board create(Board board, List<String> columnNames) {
        Board created = boardRepository.save(board);

        createDefaultColumns(created, columnNames);

        // No permission rules are created: a new board inherits the server's rules until someone
        // overrides them at board scope.
        return created;
    }

    private void createDefaultColumns(Board board, List<String> columnNames) {
        if (board == null || board.getBoardId() == null) {
            return;
        }

        List<String> effectiveNames = sanitizeColumnNames(columnNames);

        List<BoardColumn> defaults = new ArrayList<>();
        for (int i = 0; i < effectiveNames.size(); i++) {
            String position = String.format("%d.00", i + 1);
            defaults.add(newDefaultColumn(board, effectiveNames.get(i), position));
        }

        boardColumnRepository.saveAll(defaults);
    }

    private List<String> sanitizeColumnNames(List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            return List.of("To Do", "In Progress", "Done");
        }

        Set<String> deduped = new LinkedHashSet<>();
        for (String raw : columnNames) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                deduped.add(trimmed);
            }
        }

        if (deduped.isEmpty()) {
            return List.of("To Do", "In Progress", "Done");
        }

        return new ArrayList<>(deduped);
    }

    private BoardColumn newDefaultColumn(Board board, String name, String position) {
        BoardColumn column = new BoardColumn();
        column.setBoard(board);
        column.setName(name);
        column.setPosition(new BigDecimal(position));
        return column;
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
    public Optional<Board> findByIdAndServerId(Long boardId, Long serverId) {
        return boardRepository.findByBoardIdAndServer_ServerId(boardId, serverId);
    }

    public Board update(Board board) {
        return boardRepository.saveAndFlush(board);
    }

    public void deleteById(Long id) {
        boardRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return boardRepository.existsById(id);
    }
}
