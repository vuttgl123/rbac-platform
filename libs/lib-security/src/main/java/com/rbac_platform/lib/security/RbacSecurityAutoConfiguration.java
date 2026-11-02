package com.rbac_platform.lib.security;

import com.rbac_platform.lib.core.error.ProblemWriter;
import com.rbac_platform.lib.core.http.HttpClientProperties;
import com.rbac_platform.lib.core.http.ServiceRestClients;
import com.rbac_platform.lib.security.access.AccessChecker;
import com.rbac_platform.lib.security.access.HttpAccessChecker;
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
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The security chain every service gets by having lib-security on the classpath: stateless bearer-token
 * authentication against the tenant issuers, problem+json 401/403, and {@link Caller} controller parameters.
 * Ordered before Boot's security auto-configurations so their default chain (form login, HTTP basic) and
 * generated in-memory user back off: our {@link SecurityFilterChain} and issuer resolver beans exist by then.
 * A service's own bean of any of these types makes ours back off.
 */
@AutoConfiguration(before = {SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
public class RbacSecurityAutoConfiguration {

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

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain rbacSecurityFilterChain(HttpSecurity http, SecurityProperties properties,
            JwtIssuerAuthenticationManagerResolver issuers, ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler accessDenied, ProblemWriter problems) {
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
                    // Ordered before the public paths so no public pattern can open /internal/**; the
                    // InternalTokenFilter, not this rule, is what authenticates those calls.
                    requests.requestMatchers("/internal/**").permitAll();
                    if (!publicPaths.isEmpty()) {
                        requests.requestMatchers(publicPaths.toArray(String[]::new)).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .addFilterBefore(new InternalTokenFilter(properties.internalToken(), problems),
                        BearerTokenAuthenticationFilter.class)
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
