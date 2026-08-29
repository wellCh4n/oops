package com.github.wellch4n.oops.application.dto;

public record ChangePasswordCommand(String oldPassword, String newPassword) {
}
