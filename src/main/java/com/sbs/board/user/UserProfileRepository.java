package com.sbs.board.user;

import com.sbs.board.global.entity.User;
import com.sbs.board.global.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    boolean existsByUser(User user);
}
