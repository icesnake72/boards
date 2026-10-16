package com.example.board.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JobPropertiesBindingTest {

  @Autowired
  JobProperties props;

  @Test
  void should_bindJobSettings_andDisableWorkersInTests() {
    assertThat(props.enabled()).isFalse();
    assertThat(props.stream()).isEqualTo("jobs:stream");
    assertThat(props.group()).isEqualTo("job-workers");
    assertThat(props.workers()).isEqualTo(2);
    assertThat(props.maxAttempts()).isEqualTo(3);
    assertThat(props.minIdleMs()).isGreaterThan(10_000);
  }
}
