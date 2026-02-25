package hu.fmdev.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .authorizeRequests()
                .antMatchers("/api/**").permitAll() // Minden /api/** végpont engedélyezett (authentikáció nélkül is elérhető)
                .anyRequest().permitAll() // Minden egyéb végpont engedélyezett (authentikáció nélkül is elérhető)
                .and()
                .httpBasic().disable(); // HTTP Basic authentikáció kikapcsolása
    }
}
