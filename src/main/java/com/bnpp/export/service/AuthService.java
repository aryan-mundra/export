package com.bnpp.export.service;

import com.bnpp.export.dto.AuthConfigResponse;
import com.bnpp.export.dto.LoginRequest;
import com.bnpp.export.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

/** Signing in and out with a session, and who is signed in. */
public interface AuthService {

    /** What the sign-in page shows (the demo accounts on demo databases). */
    AuthConfigResponse config();

    /** Checks the username and password and starts a session; refused with 401 when they do not match. */
    UserResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse);

    /** Ends the session. */
    void logout(HttpServletRequest request);

    /** The signed-in user; 401 when nobody is signed in. */
    UserResponse currentUser(Authentication authentication);
}
