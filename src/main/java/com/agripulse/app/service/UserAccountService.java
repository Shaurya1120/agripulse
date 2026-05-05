package com.agripulse.app.service;

import com.agripulse.app.dto.RegistrationRequest;
import com.agripulse.app.model.UserAccount;
import com.agripulse.app.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserAccountService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAccount register(RegistrationRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userAccountRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("An account with that email already exists.");
        }

        UserAccount userAccount = new UserAccount();
        userAccount.setFullName(request.getFullName().trim());
        userAccount.setEmail(email);
        userAccount.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        return userAccountRepository.save(userAccount);
    }

    public UserAccount getRequiredUser(String email) {
        return userAccountRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount userAccount = getRequiredUser(username);
        return User.withUsername(userAccount.getEmail())
                .password(userAccount.getPasswordHash())
                .roles("USER")
                .build();
    }
}
