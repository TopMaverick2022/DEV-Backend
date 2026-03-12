package com.developerev.security;

import com.developerev.entity.AuthProvider;
import com.developerev.entity.Role;
import com.developerev.entity.User;
import com.developerev.repository.UserRepository;
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

    public OAuth2LoginSuccessHandler(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
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
        if (authentication instanceof OAuth2AuthenticationToken) {
            String registrationId = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
            if ("google".equalsIgnoreCase(registrationId)) {
                provider = AuthProvider.GOOGLE;
            } else if ("github".equalsIgnoreCase(registrationId)) {
                provider = AuthProvider.GITHUB;
            }
        }

        Optional<User> userOptional = userRepository.findByEmail(email);
        User user;

        if (userOptional.isPresent()) {
            user = userOptional.get();
            if (user.getProvider() == AuthProvider.LOCAL) {
                user.setProvider(provider);
                user.setVerified(true);
                userRepository.save(user);
            }
        } else {
            user = new User();
            // Generate a unique username
            user.setUsername(name.replaceAll("\\s+", "") + "_" + System.currentTimeMillis());
            user.setEmail(email);
            user.setRole(Role.USER);
            user.setProvider(provider);
            user.setVerified(true);
            userRepository.save(user);
        }

        String accessToken = jwtUtil.generateToken(user.getUsername());
        
        response.setContentType("application/json");
        response.getWriter().write("{\n" +
                "  \"message\": \"OAuth2 Login Successful\",\n" +
                "  \"accessToken\": \"" + accessToken + "\"\n" +
                "}");
    }
}
