package com.bnpp.export.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static String username() {
        Authentication authentication = authentication();
        if (authentication == null) {
            return "unknown";
        }
        return authentication.getName();
    }

    public static String displayName() {
        Authentication authentication = authentication();
        if (authentication != null && authentication.getPrincipal() instanceof ExportPrincipal principal) {
            return principal.getDisplayName();
        }
        return username();
    }

    public static boolean approver() {
        Authentication authentication = authentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_APPROVER".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
