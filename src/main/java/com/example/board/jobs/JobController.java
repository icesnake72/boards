package com.example.board.jobs;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 단계 19: 로그인 필요(SecurityConfig의 anyRequest().authenticated()). 202 = "받았고, 나중에 처리한다".
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

  private final JobProducer producer;
  private final JobStore store;

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public JobView submit(@Valid @RequestBody JobSubmitRequest request) {
    return producer.submit(request);
  }

  @GetMapping("/{jobId}")
  public JobView find(@PathVariable String jobId) {
    return store.find(jobId).orElseThrow(() -> new NotFoundException(ErrorCode.JOB_NOT_FOUND));
  }
}
