package com.developerev.security;

import com.developerev.entity.AuthProvider;
import com.developerev.entity.RefreshToken;
import com.developerev.entity.Role;
import com.developerev.entity.User;
import com.developerev.repository.RefreshTokenRepository;
import com.developerev.repository.UserRepository;
import com.developerev.service.EmailService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final RefreshTokenRepository refreshTokenRepository;

    public OAuth2LoginSuccessHandler(UserRepository userRepository, JwtUtil jwtUtil,
            EmailService emailService, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");

        if (email == null) {
            email = oAuth2User.getAttribute("login") + "@github.com";
        }
        if (name == null) {
            name = oAuth2User.getAttribute("login");
            if (name == null) {
                name = email.split("@")[0];
            }
        }

        AuthProvider provider = AuthProvider.LOCAL;
        String providerId = null;
        if (authentication instanceof OAuth2AuthenticationToken) {
            String registrationId = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
            if ("google".equalsIgnoreCase(registrationId)) {
                provider = AuthProvider.GOOGLE;
                providerId = oAuth2User.getAttribute("sub");
            } else if ("github".equalsIgnoreCase(registrationId)) {
                provider = AuthProvider.GITHUB;
                Object idObj = oAuth2User.getAttribute("id");
                providerId = idObj != null ? String.valueOf(idObj) : null;
            }
        }

        Optional<User> userOptional = Optional.empty();
        if (provider != AuthProvider.LOCAL && providerId != null) {
            userOptional = userRepository.findByProviderAndProviderId(provider, providerId);
        }
        if (userOptional.isEmpty()) {
            userOptional = userRepository.findByEmail(email);
        }

        User user;
        if (userOptional.isPresent()) {
            user = userOptional.get();
            if (user.getProvider() == AuthProvider.LOCAL || (user.getProviderId() == null && providerId != null)) {
                user.setProvider(provider);
                user.setProviderId(providerId);
                user.setVerified(true);
                userRepository.save(user);
            }
        } else {
            user = new User();
            String baseUsername = (name != null ? name : email.split("@")[0]).replaceAll("\\s+", "");
            String finalUsername = baseUsername;
            int count = 1;
            while (userRepository.findByUsername(finalUsername).isPresent()) {
                finalUsername = baseUsername + "_" + (System.currentTimeMillis() % 10000) + "_" + count++;
            }
            user.setUsername(finalUsername);
            user.setEmail(email);
            user.setRole(Role.USER);
            user.setProvider(provider);
            user.setProviderId(providerId);
            user.setVerified(true);
            userRepository.save(user);

            emailService.sendEmail(user.getEmail(), "Welcome to DeveloperEV!",
                    "Hello " + (name != null ? name : user.getUsername())
                            + ",\n\nWelcome to DeveloperEV! Your account has been successfully created via "
                            + provider + ". We're excited to have you on board!");
        }

        String accessToken = jwtUtil.generateToken(user.getUsername());
        String refreshToken = createRefreshToken(user);

        // Set refresh token as httpOnly cookie
        Cookie cookie = new Cookie("refreshToken", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // set true in production (HTTPS)
        cookie.setPath("/api/auth");
        cookie.setMaxAge(7 * 24 * 60 * 60);
        response.addCookie(cookie);

        response.sendRedirect("http://localhost:5173/oauth/callback?token=" + accessToken);
    }

    private String createRefreshToken(User user) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken(UUID.randomUUID().toString());
        refreshToken.setExpiryDate(LocalDateTime.now().plusDays(7));
        return refreshTokenRepository.save(refreshToken).getToken();
    }
}
