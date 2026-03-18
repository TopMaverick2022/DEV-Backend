package com.developerev.security;

import com.developerev.entity.AuthProvider;
import com.developerev.entity.Role;
import com.developerev.entity.User;
import com.developerev.repository.UserRepository;
import com.developerev.service.EmailService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    public OAuth2LoginSuccessHandler(UserRepository userRepository, JwtUtil jwtUtil, EmailService emailService) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        
        // GitHub might not return email or name in the same way, best effort fallback
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

        // 1. Try to find by provider + providerId
        Optional<User> userOptional = Optional.empty();
        if (provider != AuthProvider.LOCAL && providerId != null) {
            userOptional = userRepository.findByProviderAndProviderId(provider, providerId);
        }

        // 2. Fallback to email if not found by providerId
        if (userOptional.isEmpty()) {
            userOptional = userRepository.findByEmail(email);
        }

        User user;
        if (userOptional.isPresent()) {
            user = userOptional.get();
            // Link provider if it was local or update existing link
            if (user.getProvider() == AuthProvider.LOCAL || (user.getProviderId() == null && providerId != null)) {
                user.setProvider(provider);
                user.setProviderId(providerId);
                user.setVerified(true);
                userRepository.save(user);
            }
        } else {
            user = new User();
            // Generate a unique username based on provider and name
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

            // Send welcome email for new user
            emailService.sendEmail(user.getEmail(), "Welcome to DeveloperEV!",
                    "Hello " + (name != null ? name : user.getUsername()) + ",\n\nWelcome to DeveloperEV! Your account has been successfully created via " + provider + ". We're excited to have you on board!");
        }

        String accessToken = jwtUtil.generateToken(user.getUsername());
        
        String targetUrl = "http://localhost:5173/oauth/callback?token=" + accessToken;
        response.sendRedirect(targetUrl);
    }
}
