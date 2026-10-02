package com.example.board.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LiveRepositoryTest {

  @Autowired
  LiveEventRepository eventRepository;

  @Autowired
  QuestionRepository questionRepository;

  @Autowired
  UserRepository userRepository;

  @Test
  void should_findHostIdAndOwnership_withoutLoadingEntities() {
    User host = userRepository.save(new User("host1", "host1@example.com", "encoded", Role.USER));
    User author = userRepository.save(new User("author1", "author1@example.com", "encoded", Role.USER));
    LiveEvent event = eventRepository.save(new LiveEvent(host, "주간 회의", "ABC234"));
    Question question = questionRepository.save(new Question(event, author, "질문"));

    assertThat(eventRepository.findByCode("ABC234")).isPresent();
    assertThat(eventRepository.existsByCode("ABC234")).isTrue();
    assertThat(eventRepository.findHostIdByCode("ABC234")).contains(host.getId());
    assertThat(questionRepository.findOwnershipById(question.getId()))
        .contains(new QuestionOwnership(author.getId(), host.getId()));
    assertThat(questionRepository.findByEventId(event.getId())).hasSize(1);
  }
}
