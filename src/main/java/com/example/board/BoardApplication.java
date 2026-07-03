package com.example.board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// 단계 7: @ConfigurationPropertiesScan — KakaoOAuthProperties 같은 record 기반 프로퍼티 바인딩을 활성화한다
@SpringBootApplication
@ConfigurationPropertiesScan
public class BoardApplication {

	public static void main(String[] args) {
		SpringApplication.run(BoardApplication.class, args);
	}

}
