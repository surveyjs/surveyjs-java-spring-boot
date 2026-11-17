package io.surveyjs.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed-in user, as Spring Security's principal: handlers take {@code @AuthenticationPrincipal User user},
 * which is null when nobody is signed in. {@code plan} is a variable the forms read as {user.plan}. Editors hold the
 * {@code edit-forms} authority, which {@code @PreAuthorize("hasAuthority('edit-forms')")} checks before a definition
 * or variable presets are saved.
 */
public record User(int id, String key, String name, String email, String plan, boolean editor) implements UserDetails {

    public static final String EDIT_FORMS = "edit-forms";

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return editor ? List.of(new SimpleGrantedAuthority(EDIT_FORMS)) : List.of();
    }

    @Override
    public String getUsername() {
        return key;
    }

    /** The demo users have no password: demo/DemoUserFilter signs them in from a cookie. */
    @Override
    public String getPassword() {
        return null;
    }
}
