package com.jtac.identity.internal;

import com.jtac.identity.CurrentUser;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets any controller declare a {@link CurrentUser} parameter. Works for session and
 * API-token authentication; unauthenticated access yields a 401 problem.
 */
@Configuration(proxyBeanMethods = false)
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return current(SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication());
    }

    static CurrentUser current(Authentication authentication) {
        if (authentication != null) {
            if (authentication.getPrincipal() instanceof JtacUserDetails user) {
                return user.toCurrentUser();
            }
            if (authentication.getPrincipal() instanceof CurrentUser user) {
                return user;
            }
        }
        throw new AuthenticationCredentialsNotFoundException("Not signed in");
    }
}
