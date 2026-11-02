package com.rbac_platform.lib.security;

import com.rbac_platform.lib.core.error.ProblemWriter;
import com.rbac_platform.lib.core.http.HttpClientProperties;
import com.rbac_platform.lib.core.http.ServiceRestClients;
import com.rbac_platform.lib.security.access.AccessChecker;
import com.rbac_platform.lib.security.access.HttpAccessChecker;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The security chain every service gets by having lib-security on the classpath: stateless bearer-token
 * authentication against the tenant issuers, problem+json 401/403, and {@link Caller} controller parameters.
 * Ordered before Boot's security auto-configurations so their default chain (form login, HTTP basic) and
 * generated in-memory user back off: our {@link SecurityFilterChain} and issuer resolver beans exist by then.
 * A service's own bean of any of these types makes ours back off.
 *
 * <p>There are two chains. {@code /internal/**} has its own, ordered first, guarded only by the
 * {@link InternalTokenFilter}. It stays when a service defines its own {@link SecurityFilterChain}: that replaces
 * the main chain only, so a service cannot lose the internal guard by customising user-facing security.
 */
@AutoConfiguration(before = {SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
public class RbacSecurityAutoConfiguration {

    static final String INTERNAL_PATHS = "/internal/**";

    @Bean
    @ConditionalOnMissingBean
    TenantStatusCache tenantStatusCache(JdbcClient jdbc, SecurityProperties properties) {
        return new TenantStatusCache(jdbc, properties.tenantCacheTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    TenantJwtDecoders tenantJwtDecoders(SecurityProperties properties, TenantStatusCache tenants,
            HttpClientProperties http) {
        return new TenantJwtDecoders(properties.issuerPrefix(), properties.audience(), tenants, http);
    }

    /** A bean, not just chain configuration, so that Boot's UserDetailsServiceAutoConfiguration backs off. */
    @Bean
    @ConditionalOnMissingBean
    JwtIssuerAuthenticationManagerResolver jwtIssuerAuthenticationManagerResolver(TenantJwtDecoders decoders) {
        return new JwtIssuerAuthenticationManagerResolver(issuer -> {
            JwtDecoder decoder = decoders.decoderFor(issuer);
            if (decoder == null) {
                return null;
            }
            AuthenticationManager manager = new JwtAuthenticationProvider(decoder)::authenticate;
            return manager;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(ProblemWriter problems) {
        return new ProblemAuthenticationEntryPoint(problems);
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemAccessDeniedHandler problemAccessDeniedHandler(ProblemWriter problems) {
        return new ProblemAccessDeniedHandler(problems);
    }

    /**
     * Service-to-service calls: the shared secret is the only credential, a bearer token is neither needed nor
     * looked at. Highest precedence, so no other chain sees these paths.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    InternalSecurityFilterChain rbacInternalSecurityFilterChain(HttpSecurity http, SecurityProperties properties,
            ProblemWriter problems) {
        http.securityMatcher(INTERNAL_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(properties.internalToken(), problems),
                        AnonymousAuthenticationFilter.class)
                // Only requests that passed the filter get this far.
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return new InternalSecurityFilterChain(http.build());
    }

    /** Backs off for any service-defined chain, but not for {@link #rbacInternalSecurityFilterChain}. */
    @Bean
    @ConditionalOnMissingBean(value = SecurityFilterChain.class, ignored = InternalSecurityFilterChain.class)
    SecurityFilterChain rbacSecurityFilterChain(HttpSecurity http, SecurityProperties properties,
            JwtIssuerAuthenticationManagerResolver issuers, ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler accessDenied) {
        List<String> publicPaths = properties.publicPaths();
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
                    // No tenant token may read the other actuator endpoints (env, configprops, ...).
                    requests.requestMatchers("/actuator/**").denyAll();
                    requests.requestMatchers("/error").permitAll();
                    // These requests belong to the internal chain, which runs first and requires the internal
                    // token. Denied here, before the public paths, so that no public pattern opens them should
                    // one ever reach this chain.
                    requests.requestMatchers(INTERNAL_PATHS).denyAll();
                    if (!publicPaths.isEmpty()) {
                        requests.requestMatchers(publicPaths.toArray(String[]::new)).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationManagerResolver(issuers)
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied));
        return http.build();
    }

    /** Only for services configured with an authz-service URL; authz-service itself supplies an in-process one. */
    @Bean
    @ConditionalOnMissingBean(AccessChecker.class)
    @ConditionalOnProperty("rbac.security.authz-url")
    HttpAccessChecker httpAccessChecker(ServiceRestClients clients, SecurityProperties properties) {
        return new HttpAccessChecker(clients.create(properties.authzUrl()));
    }

    @Bean
    @ConditionalOnMissingBean
    CallerArgumentResolver callerArgumentResolver() {
        return new CallerArgumentResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    CallerWebMvcConfigurer callerWebMvcConfigurer(CallerArgumentResolver callerArgumentResolver) {
        return new CallerWebMvcConfigurer(callerArgumentResolver);
    }

    /**
     * The {@code /internal/**} chain. Its own type so that the main chain's back-off condition can ignore it
     * while still backing off for any chain a service defines.
     */
    public static final class InternalSecurityFilterChain implements SecurityFilterChain {

        private final SecurityFilterChain delegate;

        public InternalSecurityFilterChain(SecurityFilterChain delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean matches(HttpServletRequest request) {
            return delegate.matches(request);
        }

        @Override
        public List<Filter> getFilters() {
            return delegate.getFilters();
        }
    }

    /** Registers the {@link CallerArgumentResolver} with Spring MVC; its own type so a service can replace it. */
    public static class CallerWebMvcConfigurer implements WebMvcConfigurer {

        private final CallerArgumentResolver callerArgumentResolver;

        public CallerWebMvcConfigurer(CallerArgumentResolver callerArgumentResolver) {
            this.callerArgumentResolver = callerArgumentResolver;
        }

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(callerArgumentResolver);
        }
    }
}
