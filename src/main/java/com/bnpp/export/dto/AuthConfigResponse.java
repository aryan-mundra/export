package com.bnpp.export.dto;

import java.util.List;

/** What the sign-in page shows: on demo databases, the demo accounts. */
public record AuthConfigResponse(boolean demo, List<DemoAccount> accounts) {

    public record DemoAccount(String role, String username, String password) {
    }
}
