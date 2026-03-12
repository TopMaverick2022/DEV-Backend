package com.developerev.service;

import com.developerev.dto.*;
import com.developerev.entity.*;
import com.developerev.repository.RefreshTokenRepository;
import com.developerev.repository.UserRepository;
import com.developerev.repository.VerificationTokenRepository;
import com.developerev.security.JwtUtil;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final VerificationTokenRepository verificationTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager, JwtUtil jwtUtil,
            EmailService emailService, VerificationTokenRepository verificationTokenRepository,
            RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
        this.verificationTokenRepository = verificationTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public void register(RegisterRequest request) {
        User user = null;

        // Check if username exists
        var existingByUsername = userRepository.findByUsername(request.getUsername());
        if (existingByUsername.isPresent()) {
            if (existingByUsername.get().isVerified()) {
                throw new RuntimeException("Username is already taken");
            } else {
                user = existingByUsername.get();
            }
        }

        // Check if email exists
        var existingByEmail = userRepository.findByEmail(request.getEmail());
        if (existingByEmail.isPresent()) {
            if (existingByEmail.get().isVerified()) {
                throw new RuntimeException("Email is already taken");
            } else {
                if (user != null && !user.getId().equals(existingByEmail.get().getId())) {
                    throw new RuntimeException("Email is associated with a different unverified account");
                }
                user = existingByEmail.get();
            }
        }

        // If user is null, it's a completely new registration
        if (user == null) {
            user = new User();
            user.setUsername(request.getUsername());
            user.setEmail(request.getEmail());
            user.setRole(Role.USER);
            user.setProvider(AuthProvider.LOCAL);
            user.setVerified(false);
        } else {
            // If user exists and is unverified, delete old verification tokens
            verificationTokenRepository.deleteByUser(user);
        }

        // Set or update the password
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);

        // Generate OTP
        String otp = generateOtp();
        createVerificationToken(user, otp, VerificationToken.TokenType.EMAIL_VERIFICATION);

        emailService.sendEmail(user.getEmail(), "DeveloperEV - Verify your email",
                "Your verification code is: " + otp);
    }

    @Transactional
    public AuthResponse login(AuthRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.isVerified()) {
            throw new RuntimeException("Email not verified. Please verify your email first.");
        }

        String accessToken = jwtUtil.generateToken(user.getUsername());
        String refreshToken = createRefreshToken(user).getToken();

        // Send login notification asynchronously or synchronously
        emailService.sendEmail(user.getEmail(), "DeveloperEV - New Login Detected",
                "Hello " + user.getUsername() + ",\n\nWe noticed a new login to your DeveloperEV account. If this was you, you can safely ignore this email.");

        return new AuthResponse(accessToken, refreshToken);
    }

    @Transactional
    public void verifyEmail(VerificationRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isVerified()) {
            throw new RuntimeException("User is already verified");
        }

        VerificationToken token = verificationTokenRepository.findByToken(request.getCode())
                .orElseThrow(() -> new RuntimeException("Invalid verification code"));

        if (!token.getUser().getId().equals(user.getId()) || token.getType() != VerificationToken.TokenType.EMAIL_VERIFICATION) {
            throw new RuntimeException("Invalid verification code for this user");
        }

        if (token.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Verification code has expired");
        }

        user.setVerified(true);
        userRepository.save(user);
        verificationTokenRepository.delete(token);
    }

    @Transactional
    public void resendVerificationToken(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isVerified()) {
            throw new RuntimeException("User is already verified");
        }

        String otp = generateOtp();
        createVerificationToken(user, otp, VerificationToken.TokenType.EMAIL_VERIFICATION);

        emailService.sendEmail(user.getEmail(), "DeveloperEV - Verify your email",
                "Your new verification code is: " + otp);
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        if (refreshToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new RuntimeException("Refresh token has expired. Please sign in again.");
        }

        User user = refreshToken.getUser();
        String accessToken = jwtUtil.generateToken(user.getUsername());
        
        return new AuthResponse(accessToken, request.getToken());
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String otp = generateOtp();
        createVerificationToken(user, otp, VerificationToken.TokenType.PASSWORD_RESET);

        emailService.sendEmail(user.getEmail(), "DeveloperEV - Password Reset",
                "Your password reset code is: " + otp);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        VerificationToken token = verificationTokenRepository.findByToken(request.getCode())
                .orElseThrow(() -> new RuntimeException("Invalid reset code"));

        if (!token.getUser().getId().equals(user.getId()) || token.getType() != VerificationToken.TokenType.PASSWORD_RESET) {
            throw new RuntimeException("Invalid reset code for this user");
        }

        if (token.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Reset code has expired");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setVerified(true);
        userRepository.save(user);
        verificationTokenRepository.delete(token);
    }

    private void createVerificationToken(User user, String token, VerificationToken.TokenType type) {
        VerificationToken verificationToken = new VerificationToken();
        verificationToken.setToken(token);
        verificationToken.setUser(user);
        verificationToken.setType(type);
        verificationToken.setExpiryDate(LocalDateTime.now().plusMinutes(15));
        verificationTokenRepository.save(verificationToken);
    }

    private RefreshToken createRefreshToken(User user) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken(UUID.randomUUID().toString());
        refreshToken.setExpiryDate(LocalDateTime.now().plusDays(7));
        return refreshTokenRepository.save(refreshToken);
    }

    private String generateOtp() {
        Random random = new Random();
        int otp = 100000 + random.nextInt(900000);
        return String.valueOf(otp);
    }
}
