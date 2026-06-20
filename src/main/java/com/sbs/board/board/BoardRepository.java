package com.sbs.board.board;

import com.sbs.board.global.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {


    boolean existsByName(String name);
}
