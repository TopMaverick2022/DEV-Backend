package com.developerev.service;

import com.developerev.dto.*;
import com.developerev.entity.*;
import com.developerev.exception.auth.*;
import com.developerev.repository.RefreshTokenRepository;
import com.developerev.repository.UserRepository;
import com.developerev.repository.VerificationTokenRepository;
import com.developerev.security.JwtUtil;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
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
                throw new UserAlreadyExistsException("Username is already taken");
            } else {
                user = existingByUsername.get();
            }
        }

        // Check if email exists
        var existingByEmail = userRepository.findByEmail(request.getEmail());
        if (existingByEmail.isPresent()) {
            if (existingByEmail.get().isVerified()) {
                throw new UserAlreadyExistsException("Email is already taken");
            } else {
                if (user != null && !user.getId().equals(existingByEmail.get().getId())) {
                    throw new UserAlreadyExistsException("Email is associated with a different unverified account");
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
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        } else {
            // If user exists and is unverified, check password history, then update password
            for (String oldPassword : user.getPasswordHistory()) {
                if (passwordEncoder.matches(request.getPassword(), oldPassword)) {
                    throw new PasswordAlreadyUsedException("New password cannot be one of the last 12 passwords used.");
                }
            }
            // Add old password to history and trim
            if(user.getPassword() != null && !user.getPassword().isEmpty()){
                user.getPasswordHistory().add(user.getPassword());
                if (user.getPasswordHistory().size() > 12) {
                    user.getPasswordHistory().remove(0);
                }
            }
            user.setPassword(passwordEncoder.encode(request.getPassword()));

            // Delete old verification tokens
            verificationTokenRepository.deleteByUser(user);
        }

        userRepository.save(user);

        // Generate OTP
        String otp = generateOtp();
        createVerificationToken(user, otp, VerificationToken.TokenType.EMAIL_VERIFICATION);

        emailService.sendOtpEmail(user.getEmail(), "DeveloperEV - Verify your email",
                "Your verification code is: " + otp);
    }

    @Transactional
    public AuthResponse login(AuthRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (!user.isVerified()) {
            throw new EmailNotVerifiedException("Email not verified. Please verify your email first.");
        }

        String accessToken = jwtUtil.generateToken(user.getUsername());
        String refreshToken = createRefreshToken(user).getToken();

        return new AuthResponse(accessToken, refreshToken);
    }

    @Transactional
    public void verifyEmail(VerificationRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (user.isVerified()) {
            // This is not an error, just idempotent
            return;
        }

        VerificationToken token = verificationTokenRepository.findByToken(request.getCode())
                .orElseThrow(() -> new InvalidVerificationTokenException("Invalid verification code"));

        if (!token.getUser().getId().equals(user.getId())
                || token.getType() != VerificationToken.TokenType.EMAIL_VERIFICATION) {
            throw new InvalidVerificationTokenException("Invalid verification code for this user");
        }

        if (token.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new VerificationTokenExpiredException("Verification code has expired");
        }

        user.setVerified(true);
        userRepository.save(user);
        verificationTokenRepository.delete(token);

        // Send welcome email
        emailService.sendNotificationEmail(user.getEmail(), "Welcome to DeveloperEV!",
                "Hello " + (user.getUsername() != null ? user.getUsername() : "there")
                        + ",\n\nWelcome to DeveloperEV! Your account has been successfully verified. We're excited to have you on board!");
    }

    @Transactional
    public void resendVerificationToken(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (user.isVerified()) {
            throw new UserAlreadyExistsException("User is already verified");
        }

        verificationTokenRepository.deleteByUser(user);
        String otp = generateOtp();
        createVerificationToken(user, otp, VerificationToken.TokenType.EMAIL_VERIFICATION);

        emailService.sendOtpEmail(user.getEmail(), "DeveloperEV - Verify your email",
                "Your new verification code is: " + otp);
    }

    @Transactional
    public AuthResponse refreshToken(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidVerificationTokenException("Invalid refresh token"));

        if (refreshToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new VerificationTokenExpiredException("Refresh token has expired. Please sign in again.");
        }

        // Rotate: delete old, issue new
        User user = refreshToken.getUser();
        refreshTokenRepository.delete(refreshToken);
        String newRefreshToken = createRefreshToken(user).getToken();
        String accessToken = jwtUtil.generateToken(user.getUsername());

        return new AuthResponse(accessToken, newRefreshToken);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UserNotFoundException("User not found with email: " + request.getEmail()));

        String otp = generateOtp();
        createVerificationToken(user, otp, VerificationToken.TokenType.PASSWORD_RESET);

        emailService.sendOtpEmail(user.getEmail(), "DeveloperEV - Password Reset",
                "Your password reset code is: " + otp);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UserNotFoundException("User not found with email: " + request.getEmail()));

        // Check if the new password is the same as the old one
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new SamePasswordException("New password cannot be the same as the current password");
        }
        
        // Check password history
        for (String oldPassword : user.getPasswordHistory()) {
            if (passwordEncoder.matches(request.getNewPassword(), oldPassword)) {
                throw new PasswordAlreadyUsedException("New password cannot be one of the last 12 passwords used.");
            }
        }

        VerificationToken token = verificationTokenRepository.findByToken(request.getCode())
                .orElseThrow(() -> new InvalidVerificationTokenException("Invalid reset code"));

        if (!token.getUser().getId().equals(user.getId()) || token.getType() != VerificationToken.TokenType.PASSWORD_RESET) {
            throw new InvalidVerificationTokenException("Invalid reset code for this user");
        }

        if (token.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new VerificationTokenExpiredException("Reset code has expired");
        }
        
        // Add old password to history and trim
        user.getPasswordHistory().add(user.getPassword());
        if (user.getPasswordHistory().size() > 12) {
            user.getPasswordHistory().remove(0);
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setVerified(true); // Also verify the user on password reset
        userRepository.save(user);
        verificationTokenRepository.delete(token);

        // Send password change success email
        emailService.sendNotificationEmail(user.getEmail(), "DeveloperEV - Password Changed Successfully",
                "Hello " + user.getUsername()
                        + ",\n\nYour password has been successfully changed. If you did not perform this action, please contact support immediately.");
    }

    private void createVerificationToken(User user, String token, VerificationToken.TokenType type) {
        VerificationToken verificationToken = new VerificationToken();
        verificationToken.setToken(token);
        verificationToken.setUser(user);
        verificationToken.setType(type);
        // OTP valid for 2 minutes as requested by user
        verificationToken.setExpiryDate(LocalDateTime.now().plusMinutes(2)); 
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

