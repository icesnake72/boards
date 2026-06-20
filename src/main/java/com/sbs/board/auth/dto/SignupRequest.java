package com.sbs.board.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SignupRequest {
    @NotBlank
    private String email;

    @NotBlank
    @Size(min = 8, max = 30)  // 8자이상, 30자미만
    private String password;

    @NotBlank
    @JsonProperty("nick_name")
    private String nickName;

    @NotBlank
    private String role;
}
