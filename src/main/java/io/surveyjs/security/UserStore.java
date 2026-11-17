package io.surveyjs.security;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * The users table as Spring Security's {@link UserDetailsService}. Defining one also stops Boot from creating its
 * default in-memory user with a generated password. Replace it with your own user store.
 */
@Service
public class UserStore implements UserDetailsService {

    private final JdbcClient jdbc;

    public UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public User loadUserByUsername(String key) {
        return find(key).orElseThrow(() -> new UsernameNotFoundException(key));
    }

    public Optional<User> find(String key) {
        return jdbc.sql("SELECT id, key, name, email, plan, is_editor FROM users WHERE key = ?")
                .param(key)
                .query((row, n) -> new User(row.getInt("id"), row.getString("key"), row.getString("name"),
                        row.getString("email"), row.getString("plan"), row.getBoolean("is_editor")))
                .optional();
    }
}
