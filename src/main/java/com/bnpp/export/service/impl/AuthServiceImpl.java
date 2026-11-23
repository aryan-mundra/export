package com.bnpp.export.service.impl;

import com.bnpp.export.dto.AuthConfigResponse;
import com.bnpp.export.dto.LoginRequest;
import com.bnpp.export.dto.UserResponse;
import com.bnpp.export.exception.UnauthorizedException;
import com.bnpp.export.security.ExportPrincipal;
import com.bnpp.export.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Implementation of {@link AuthService}. */
@Service
public class AuthServiceImpl implements AuthService {

    private static final List<AuthConfigResponse.DemoAccount> DEMO_ACCOUNTS = List.of(
            new AuthConfigResponse.DemoAccount("Maker", "maker", "Regliss-maker"),
            new AuthConfigResponse.DemoAccount("Approver", "approver", "Regliss-approver"));

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final boolean demoData;

    public AuthServiceImpl(AuthenticationManager authenticationManager,
                           SecurityContextRepository securityContextRepository,
                           @Value("${export.demo-data:false}") boolean demoData) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.demoData = demoData;
    }

    @Override
    public AuthConfigResponse config() {
        return demoData ? new AuthConfigResponse(true, DEMO_ACCOUNTS) : new AuthConfigResponse(false, List.of());
    }

    @Override
    public UserResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username().trim(), request.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);
            return UserResponse.from((ExportPrincipal) authentication.getPrincipal());
        } catch (AuthenticationException ex) {
            throw new UnauthorizedException("Unknown username or password");
        }
    }

    @Override
    public void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @Override
    public UserResponse currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ExportPrincipal principal)) {
            throw new UnauthorizedException("Sign in required");
        }
        return UserResponse.from(principal);
    }
}
