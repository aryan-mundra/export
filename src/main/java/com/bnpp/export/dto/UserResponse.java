package com.bnpp.export.dto;

import com.bnpp.export.security.ExportPrincipal;

public record UserResponse(String username, String displayName, String role) {

    public static UserResponse from(ExportPrincipal principal) {
        return new UserResponse(principal.getUsername(), principal.getDisplayName(), principal.getRole());
    }
}
